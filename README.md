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
scripts/summarize-cloudwatch.py  turns that raw JSON into actual IOPS/MB-per-sec numbers
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
   `DOCKERHUB_REPO`, `TRIVY_HARD_FAIL`, `TRIVY_SEVERITY`, `LOG_FLOOD_SIZE_MB`,
   `STORAGE_RESOURCE_ID`, `INTEGRATION_TEST_REPEAT`, and `ARTIFACT_COPY_COUNT`
   for the rest of the matrix.
5. **BuildKit sidecar, Pod Security check.** No Docker daemon is needed
   anywhere in this pipeline — `Docker publish` builds via a `moby/buildkit`
   **rootless** sidecar container (`buildctl` talking to it over `localhost`,
   since sidecars share a pod's network namespace), scans the resulting OCI
   tarball directly with `trivy`, and pushes with `crane` — chosen
   specifically because `bench-agent` nodes may be containerd-only (no
   `/var/run/docker.sock` to bind-mount at all, post-dockershim-removal).
   `vars/benchStages.groovy` declares this sidecar inline via the Kubernetes
   plugin's `podTemplate` step (using its raw `yaml:` form, not
   `containerTemplate()`, since only `yaml:` can express the
   `seccompProfile`/`appArmorProfile` settings rootless mode needs), scoped to
   just the `Docker publish` stage (`inheritFrom` your existing `bench-agent`
   template).
   - **Why rootless, not `privileged: true`**: rootless buildkitd runs in a
     user namespace with `fuse-overlayfs` instead of `CAP_SYS_ADMIN` + kernel
     overlayfs, so the sidecar never gets host-level capabilities or device
     access — a materially smaller blast radius than a privileged container.
   - **This does not fully clear the Pod Security Admission risk**, though —
     rootless mode requires `seccompProfile`/`appArmorProfile: Unconfined` on
     Kubernetes (no equivalent to Docker's `--security-opt
     systempaths=unconfined`), and `Unconfined` is disallowed under
     Kubernetes' `baseline` *and* `restricted` Pod Security Standards, same as
     `privileged: true` was. Confirm before running a job: `kubectl get ns
     cloudbees-agents -o jsonpath='{.metadata.labels}'` — look for
     `pod-security.kubernetes.io/enforce: restricted` or `baseline`. If
     present, this stage still needs a policy exemption for this
     namespace/pod template — or switch this stage to Kaniko, which needs
     neither privileged nor Unconfined seccomp/AppArmor and is the actual
     PSA-`restricted`-compliant option, unlike rootless BuildKit.
   - `appArmorProfile` as a container `securityContext` field needs
     Kubernetes >= 1.30; on older clusters, drop that block and use the
     pre-1.30 pod-annotation form instead (`container.apparmor.security.beta.
     kubernetes.io/buildkitd: unconfined`).
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
7. **CloudWatch access (optional, for automatic Layer 4 collection).**
   `vars/benchStages.groovy` hardcodes this fleet's actual resource IDs in a
   map keyed by `STORAGE_CLASS`, found via `kubectl get pvc -n cloudbees-core`
   + `kubectl get pv ... -o jsonpath='{.spec.csi.volumeHandle}'` (+ `aws fsx
   describe-volumes` for FSx, since its `volumeHandle` is a volume ID, not the
   filesystem ID CloudWatch's `AWS/FSx` metrics actually key on):
   - EBS (`ebs-gp3-sc`): `vol-01d464727c847df38`
   - EFS (`efs-sc`): `fs-0246a526db0adaedf` (the CSI driver's `volumeHandle`
     is `efs:fs-xxxx::fsap-xxxx` — the filesystem ID is the 2nd colon-separated
     field, not the 1st)
   - FSx (`openzfs-sc`): `fs-01bbc0b1019aac1c4`

   Leave the job's `STORAGE_RESOURCE_ID` parameter blank to use this
   auto-selected default — it only needs to be set explicitly to override it
   (e.g. testing against a different volume). **These are specific to this
   fleet's current volumes** — if any controller's PVC is ever recreated
   (new cluster, disaster recovery, etc.), update the map in
   `vars/benchStages.groovy` to match.

   If set (explicitly or via the default), the `bench-agent` pod needs AWS
   credentials with `cloudwatch:GetMetricStatistics` — **IRSA** (an IAM role
   annotated on the pod's Kubernetes ServiceAccount) is the recommended,
   credential-free way to provide this on EKS; injecting static access keys
   via a Jenkins credential is the fallback if IRSA isn't set up. A missing
   resource ID or failed AWS auth only skips this one stage — it doesn't fail
   the rest of the run or block metrics already published.

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
`FSX_FILESYSTEM_ID` matches the job's `STORAGE_CLASS`. Raw results land under
`results/cloudwatch/*.json` — but `aws cloudwatch get-metric-statistics`
returns per-period `Sum`/`Average`/`Maximum`, not a ready ops-per-second or
MB-per-second figure, so the stage also calls `bench.summarizeCloudWatch()`
(in `vars/bench.groovy`) to compute the actual numbers and write
`results/cloudwatch/cloudwatch-summary.csv`. **This is a pure-Groovy
reimplementation of `scripts/summarize-cloudwatch.py`'s math, not a call to
that script** — `python3` isn't installed on the `maven:*` agent image, so
the Python version can't run inline in the pipeline; the Groovy version uses
only core pipeline steps (`sh`/`readFile`/`writeFile`) plus
`groovy.json.JsonSlurper`, no extra install needed. `*Ops`/`*Operations`
metrics get `Sum ÷ 60s` → IOPS; `*Bytes` metrics get `Sum ÷ 60s ÷
1,000,000` → MB/sec (decimal MB, matching AWS's own published specs like
gp3's 125 MB/s baseline); everything else (`PercentIOLimit`, `BurstBalance`,
etc.) is already a rate and gets reported as-is from each datapoint's own
`Average`/`Maximum`. Both the raw JSON and the summary CSV get archived as
build artifacts.

To pull and summarize metrics manually instead (e.g. for a window spanning
multiple runs, or if `STORAGE_RESOURCE_ID` wasn't set for a given run) —
this path needs `python3` and `aws` installed wherever you run it, unlike
the in-pipeline version:

```
EBS_VOLUME_ID=vol-xxxx EFS_FILESYSTEM_ID=fs-xxxx FSX_FILESYSTEM_ID=fs-yyyy \
  ./scripts/collect-cloudwatch.sh 2026-06-30T10:00:00Z 2026-06-30T10:10:00Z
./scripts/summarize-cloudwatch.py results/cloudwatch
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
- `INTEGRATION_TEST_REPEAT > 1` is a second, complementary way to generate
  real (not synthetic) concurrent JENKINS_HOME log pressure — it re-runs the
  actual `mvnw verify` command per branch, so the genuine Maven/JVM console
  output (not agent-local disk I/O — the *test execution* is still
  agent-local, only its console output streams to the controller) repeats
  and streams to the controller multiple times per branch. Labels become
  `integration-test-${i}-rep-${rep}` once repeat > 1; at the default
  (repeat=1) the label is unchanged from before, so existing CSVs still
  aggregate correctly.
- `ARTIFACT_COPY_COUNT > 1` is a third, distinct source of real JENKINS_HOME
  write pressure — genuine compressed binary content (the built jar, copied
  N times) via `archiveArtifacts`, which won't compress further the way
  repeated log text can. **This one is different from Log flood and
  Integration test repeat in a way that matters**: those two don't
  permanently grow `JENKINS_HOME` usage, but archived artifacts land in
  `JENKINS_HOME/jobs/<job>/builds/<n>/archive/` and would otherwise
  **accumulate forever** — both Jenkinsfiles now set
  `buildDiscarder(logRotator(numToKeepStr: '5'))`, keeping only the last 5
  builds. That's a deliberately tight number given `ARTIFACT_COPY_COUNT`
  exists specifically to multiply artifact volume — but it's in tension with
  "report median/p95 across ≥5 runs per matrix cell" above: with retention
  at 5, your 5th run can already be evicting your 1st by the time you go to
  aggregate. Either raise `numToKeepStr` if you need more history retained
  (do the disk-arithmetic below first), or aggregate promptly after each
  batch of runs rather than letting them pile up. Do the arithmetic —
  `CONCURRENCY × ARTIFACT_COPY_COUNT × jar size` — against real available
  disk before pushing this parameter high. Timed as `archive-artifacts-${i}`
  (the controller-side write); the
  local duplication step that creates the copies isn't separately timed,
  since it's agent-local disk work, same category as unit-test/soak.
- Total `Log flood` volume is `CONCURRENCY × LOG_FLOOD_SIZE_MB` MB — do that
  arithmetic against the controller's actual available disk before pushing
  either value very high, since this is a fixed, deliberate volume rather
  than a rate-limited/duration-bound one. **Start small and calibrate up**:
  1GB/branch measured impractically slow in a real run — the bottleneck
  isn't the write itself (verified locally: instant) but Jenkins' own
  console-log capture path (buffered on the agent, streamed to the
  controller, appended to the build log, plus however slowly a browser
  renders a rapidly-growing multi-hundred-MB page if you're watching it
  live). The default (50MB/branch) is a safer starting point; increase
  gradually and watch wall-clock time before jumping to GB-scale values.
  Each line is a realistic, log-shaped string (timestamp/level/context/kv
  pairs), not a wall of repeated characters — genuine log text has varied
  structure and doesn't compress the way one repeated byte does, so this is
  a closer proxy for real build-log write patterns.
- CloudWatch has its own 1-2 minute ingestion delay — the `Collect CloudWatch
  metrics` stage queries up through "now," so the last minute or so of that
  window can come back sparse or empty even though the run genuinely covered
  it. Don't read a dip at the very end of a CloudWatch chart as a real
  storage-class effect.

See the design memo for the full rationale behind each of these.
