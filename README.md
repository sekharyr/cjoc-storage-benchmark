# cjoc-storage-benchmark

Runnable scaffold for benchmarking `JENKINS_HOME` IOPS/throughput across EBS,
EFS, and FSx for OpenZFS on a CloudBees CJOC + 3 managed-controller fleet on
EKS. Companion to the design memo — same five-layer mental model, same test
matrix, same risk list. This repo implements Layers 1–4.

Workload app: [spring-petclinic-rest](https://github.com/spring-petclinic/spring-petclinic-rest)
(Maven, REST API, Postgres/MySQL/H2/HSQLDB profiles) by default, overridable via
the `workloadRepo`/`workloadBranch` keys `benchStages.groovy` accepts. Each
pipeline run unit-tests it, runs its Maven `verify` phase (JaCoCo coverage
gate — see the comment in `vars/benchStages.groovy`'s `integration-test-${i}`
step for why that's labeled honestly rather than claimed as real integration
tests), then builds/scans/pushes a container image — approximating a real
service pipeline rather than a bare synthetic build.

**Important:** the build/test/Docker/soak work above all runs on the
`bench-agent` pod's own local disk — none of it touches `JENKINS_HOME` at
all, since agent workspace storage is architecturally separate from whatever
StorageClass backs a given controller's `JENKINS_HOME` PVC. If you're seeing
near-identical numbers across EBS/EFS/FSx, this is why — most of what's
timed here isn't exercising the thing being compared. The **`Log flood`**
stage exists specifically to address this: it deliberately generates real,
concurrent, sustained log volume, which *is* one of the few things Jenkins
genuinely writes to `JENKINS_HOME` incrementally (build console logs) on the
controller itself. See "Before you trust a number" below.

## Layout

```
Jenkinsfile                      Layer 2/3 harness, durabilityHint=PERFORMANCE_OPTIMIZED
Jenkinsfile.max-survivability    same harness, durabilityHint=MAX_SURVIVABILITY
vars/bench.groovy                timed{} wrapper + CSV metric emission
vars/benchStages.groovy          shared stage logic both Jenkinsfiles call
Dockerfile                       multi-stage image build for the workload app, copied into workload/ at Checkout
fio/                             Layer 1 synthetic baseline (fio jobs + kubestr runner)
resources/application-iobench.yml Spring profile dropped into the workload app
scripts/setup-workload.sh        clones the reference Spring Boot apps
scripts/soak.sh                  post-deploy read/write load generator
scripts/collect-cloudwatch.sh    Layer 4 storage-metric snapshot per run
scripts/aggregate-results.py     medians/p95 across all collected CSVs
results/                         run output lands here (gitignored contents)
```

## Setup

1. **Storage classes.** Provisioned in-cluster outside this repo, named
   `ebs-gp3-sc`, `efs-sc`, `openzfs-sc` — `fio/run-kubestr.sh` and the
   Jenkinsfiles' `STORAGE_CLASS` parameter both hardcode these names. Reverify
   with `kubectl get sc` if the cluster's naming ever changes.
2. **Dedicated node group.** Put the three controllers and the `bench-agent`
   pool on tainted nodes nothing else can schedule onto — noisy neighbors on
   shared nodes will otherwise show up as a storage-class difference that
   isn't real.
3. **Shared library.** In CJOC: *Manage Jenkins → System → Global Pipeline
   Libraries* → add this repo as a library named `cjoc-storage-benchmark`.
   That's what makes `@Library('cjoc-storage-benchmark') _` resolve
   `vars/bench.groovy` and `vars/benchStages.groovy` in the Jenkinsfiles.
4. **Jobs.** On each of the three managed controllers, create two pipeline
   jobs pointed at this repo — one using `Jenkinsfile`, one using
   `Jenkinsfile.max-survivability` (Jenkins "Pipeline script from SCM" lets
   you pick the script path). That gives you 3 controllers × 2 durability
   settings = 6 jobs total, each parametrized by `CONCURRENCY`, `COLD_CACHE`,
   `DOCKERHUB_REPO`, `TRIVY_HARD_FAIL`, `TRIVY_SEVERITY`, `LOG_FLOOD_SIZE_GB`,
   and `STORAGE_RESOURCE_ID` for the rest of the matrix.
5. **BuildKit sidecar, privileged-pod check.** No Docker daemon is needed
   anywhere in this pipeline — `Docker publish` builds via a `moby/buildkit`
   sidecar container (`buildctl` talking to it over `localhost`, since
   sidecars share a pod's network namespace), scans the resulting OCI tarball
   directly with `trivy`, and pushes with `crane` — chosen specifically
   because `bench-agent` nodes may be containerd-only (no `/var/run/docker.sock`
   to bind-mount at all, post-dockershim-removal). `vars/benchStages.groovy`
   declares this sidecar inline via the Kubernetes plugin's `podTemplate` step,
   scoped to just the `Docker publish` stage (`inheritFrom` your existing
   `bench-agent` template) — nothing to configure by hand beyond one check:
   the `buildkitd` container needs `privileged: true`, and Pod Security
   Admission set to `restricted` on the `cloudbees-agents` namespace (or an
   OPA/Gatekeeper policy) will silently block that pod from scheduling.
   Confirm before running a job: `kubectl get ns cloudbees-agents -o
   jsonpath='{.metadata.labels}'` — look for
   `pod-security.kubernetes.io/enforce: restricted`; if present, either relax
   it for this namespace or exempt this specific pod template.
6. **Docker Hub credentials.** Create a Jenkins **Username with password**
   credential with ID `dockerhub-creds` — username is your Docker Hub
   namespace, password is a Docker Hub access token (Account Settings →
   Security → New Access Token), **not** your account password. Consumed via
   `withCredentials([usernamePassword(...)])` + `crane auth login` (not
   `withDockerRegistry`, since there's no `docker` CLI on the agent at all).
   Required on every controller that runs the pipeline unless a shared
   CJOC-level credential provider is configured. `DOCKERHUB_REPO` defaults to
   `sekharyr/cjoc-storage-benchmark` — override it via the job parameter if
   you're pushing to a different namespace/repo. Docker/OCI repository names
   must be all-lowercase; the build fails fast at `buildctl build` (before
   `crane` even runs) if the tag isn't.
7. **CloudWatch access (optional, for automatic Layer 4 collection).** Set
   `STORAGE_RESOURCE_ID` to the AWS resource ID backing *this specific*
   controller's `JENKINS_HOME` PVC — `vol-xxxx` for the EBS controller,
   `fs-xxxx` for the EFS/FSx ones (find it via
   `kubectl get pv $(kubectl get pvc jenkins-home -n <controller-ns> -o
   jsonpath='{.spec.volumeName}') -o yaml`, look for `volumeHandle`). Leave it
   blank to skip this stage entirely — everything else in the pipeline still
   runs and publishes metrics normally. If set, the `bench-agent` pod needs
   AWS credentials with `cloudwatch:GetMetricStatistics` — **IRSA** (an IAM
   role annotated on the pod's Kubernetes ServiceAccount) is the recommended,
   credential-free way to provide this on EKS; injecting static access keys
   via a Jenkins credential is the fallback if IRSA isn't set up. A missing
   `STORAGE_RESOURCE_ID` or failed AWS auth only skips this one stage — it
   doesn't fail the rest of the run or block metrics already published.

## Running the matrix

**Phase 1 — synthetic baseline**, before touching Jenkins at all:

```
./fio/run-kubestr.sh 20Gi results/fio-baseline
```

**Phase 2/3 — applied + concurrency**, per controller/durability job, sweep
`CONCURRENCY` across `1`, `5`, `20` and `COLD_CACHE` across `true`/`false`.
Each run archives `bench-metrics.csv` and copies it to
`results/<storage_class>/<BUILD_TAG>.csv`.

**Layer 4 — storage metrics for each run's time window.** Runs automatically
as the pipeline's last stage (`Collect CloudWatch metrics`) whenever
`STORAGE_RESOURCE_ID` is set — using `currentBuild.startTimeInMillis` through
"now" as the window, and whichever of `EBS_VOLUME_ID`/`EFS_FILESYSTEM_ID`/
`FSX_FILESYSTEM_ID` matches the job's `STORAGE_CLASS`. Results land under
`results/cloudwatch/*.json` and get archived as build artifacts. To pull
metrics manually instead (e.g. for a window spanning multiple runs, or if
`STORAGE_RESOURCE_ID` wasn't set for a given run):

```
EBS_VOLUME_ID=vol-xxxx EFS_FILESYSTEM_ID=fs-xxxx FSX_FILESYSTEM_ID=fs-yyyy \
  ./scripts/collect-cloudwatch.sh 2026-06-30T10:00:00Z 2026-06-30T10:10:00Z
```

(each of the three ID env vars is independently optional — set only the
one(s) relevant to what you're checking).

**Aggregate everything collected so far:**

```
./scripts/aggregate-results.py
```

## Before you trust a number

- State the exact EBS volume type + provisioned IOPS/throughput, the EFS
  throughput mode (and whether burst credits had time to accrue), and the FSx
  deployment type + provisioned throughput capacity alongside every result —
  each of these changes the numbers by multiples on its own.
- Report median and p95 across ≥5 runs per matrix cell, not a single run.
- Disclose cold vs warm cache state; it can dwarf the storage-class effect.
- BuildKit build/Trivy console output adds to the per-run log volume Jenkins
  streams back to the controller (same mechanism already noted for
  `soak.sh`'s output) — runs from before/after this change to the pipeline
  aren't directly log-I/O comparable.
- `integration-test-${i}`'s `mvn verify` mostly re-runs the same tests as
  `unit-test-${i}` plus a JaCoCo coverage-gate check — don't read a big gap
  between those two labels' timings as "integration tests are expensive,"
  since there aren't real integration tests here (see the Layout/intro note).
- `unit-test-${i}`, `integration-test-${i}`, `docker-build`, `trivy-scan`,
  `docker-push`, and `soak` all run on the `bench-agent` pod's local disk, not
  `JENKINS_HOME` — expect these to look similar across EBS/EFS/FSx regardless
  of the controller's actual storage class, since none of them exercise it.
  `log-flood-${i}` is the label that actually should differ across storage
  classes, since it's the one deliberately generating concurrent write
  pressure on the controller's own disk. If `log-flood-${i}` *also* looks
  identical across all three, check the Layer 1 `fio` baseline and Layer 4
  CloudWatch data for the same time window before concluding the storage
  classes perform the same — it could mean the PVCs/StorageClasses aren't as
  differentiated as expected, not that this pipeline failed to measure them.
- Total `Log flood` volume is `CONCURRENCY × LOG_FLOOD_SIZE_GB` GB (e.g.
  `CONCURRENCY=4`, `LOG_FLOOD_SIZE_GB=1` writes 4GB total, 1GB per branch,
  concurrently) — do that arithmetic against the controller's actual
  available disk before pushing either value very high, since this is a
  fixed, deliberate volume rather than a rate-limited/duration-bound one.
- CloudWatch has its own 1-2 minute ingestion delay — the `Collect CloudWatch
  metrics` stage queries up through "now," so the last minute or so of that
  window can come back sparse or empty even though the run genuinely covered
  it. Don't read a dip at the very end of a CloudWatch chart as a real
  storage-class effect.

See the design memo for the full rationale behind each of these.
