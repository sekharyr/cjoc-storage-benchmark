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
   `DOCKERHUB_REPO`, `TRIVY_HARD_FAIL`, and `TRIVY_SEVERITY` for the rest of
   the matrix.
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

## Running the matrix

**Phase 1 — synthetic baseline**, before touching Jenkins at all:

```
./fio/run-kubestr.sh 20Gi results/fio-baseline
```

**Phase 2/3 — applied + concurrency**, per controller/durability job, sweep
`CONCURRENCY` across `1`, `5`, `20` and `COLD_CACHE` across `true`/`false`.
Each run archives `bench-metrics.csv` and copies it to
`results/<storage_class>/<BUILD_TAG>.csv`.

**Layer 4 — pull the storage metrics for the same window each run covered:**

```
EBS_VOLUME_ID=vol-xxxx EFS_FILESYSTEM_ID=fs-xxxx FSX_FILESYSTEM_ID=fs-yyyy \
  ./scripts/collect-cloudwatch.sh 2026-06-30T10:00:00Z 2026-06-30T10:10:00Z
```

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

See the design memo for the full rationale behind each of these.
