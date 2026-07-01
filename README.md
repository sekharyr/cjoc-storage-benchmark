# cjoc-storage-benchmark

Runnable scaffold for benchmarking `JENKINS_HOME` IOPS/throughput across EBS,
EFS, and FSx for OpenZFS on a CloudBees CJOC + 3 managed-controller fleet on
EKS. Companion to the design memo — same five-layer mental model, same test
matrix, same risk list. This repo implements Layers 1–4.

Workload app: [spring-petclinic-rest](https://github.com/spring-petclinic/spring-petclinic-rest)
(Maven, REST API, Postgres/MySQL/H2/HSQLDB profiles) by default, overridable via
the `workloadRepo`/`workloadBranch` keys `benchStages.groovy` accepts. Each
pipeline run builds it, unit-tests it, runs a real Testcontainers-backed
integration test against it, then builds/scans/pushes a container image —
approximating a real service pipeline rather than a bare synthetic build.

## Layout

```
Jenkinsfile                      Layer 2/3 harness, durabilityHint=PERFORMANCE_OPTIMIZED
Jenkinsfile.max-survivability    same harness, durabilityHint=MAX_SURVIVABILITY
vars/bench.groovy                timed{} wrapper + CSV metric emission
vars/benchStages.groovy          shared stage logic both Jenkinsfiles call
Dockerfile                       multi-stage image build for the workload app, copied into workload/ at Checkout
fio/                             Layer 1 synthetic baseline (fio jobs + kubestr runner)
resources/application-iobench.yml Spring profile dropped into the workload app
resources/BenchPostgresIT.java   injected Testcontainers integration test, copied into workload/ at Checkout
scripts/setup-workload.sh        clones the reference Spring Boot apps
scripts/inject-testcontainers-deps.py  adds Testcontainers test deps to the workload's pom.xml
scripts/soak.sh                  post-deploy read/write load generator
scripts/collect-cloudwatch.sh    Layer 4 storage-metric snapshot per run
scripts/aggregate-results.py     medians/p95 across all collected CSVs
results/                         run output lands here (gitignored contents)
```

## Setup

1. **Storage classes.** Assumed already provisioned in-cluster (managed
   outside this repo) — `fio/run-kubestr.sh` and the Jenkinsfiles'
   `STORAGE_CLASS` parameter both hardcode the names `ebs-gp3`, `efs-elastic`,
   `fsx-openzfs`. Confirm those names match what's actually applied
   (`kubectl get sc`) before running anything, or update those two spots to
   match your naming.
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
5. **Docker daemon access.** `bench-agent` nodes now build and scan container
   images and run a Testcontainers-based integration test, all of which need a
   Docker daemon — bind-mount `/var/run/docker.sock` into the bench-agent
   pod/node. One socket covers `docker build`/`docker push`, the Trivy
   container, and the Testcontainers-launched Postgres container; no separate
   DinD sidecar is required.
6. **Docker Hub credentials.** Create a Jenkins **Username with password**
   credential with ID `dockerhub-creds` — username is your Docker Hub
   namespace, password is a Docker Hub access token (Account Settings →
   Security → New Access Token), **not** your account password. Required on
   every controller that runs the pipeline, since `withDockerRegistry(credentialsId: 'dockerhub-creds')`
   resolves the credential from the running controller's own store unless a
   shared CJOC-level credential provider is configured. Also set `DOCKERHUB_REPO`
   away from its `REPLACE_ME/...` default before running a job — the pipeline
   will fail loudly at the `docker push` step if you don't.

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
- Docker build/Trivy console output adds to the per-run log volume Jenkins
  streams back to the controller (same mechanism already noted for
  `soak.sh`'s output) — runs from before/after this change to the pipeline
  aren't directly log-I/O comparable.
- At higher `CONCURRENCY` values, each branch now also starts its own
  Testcontainers Postgres container for `integration-test-${i}` — confirm the
  bench-agent node group has enough local-disk/CPU headroom for `CONCURRENCY`
  simultaneous containers, not just `CONCURRENCY` simultaneous Maven builds.
  This is agent-node-local disk, not the `JENKINS_HOME` PVC under test, but it
  is a new source of noisy-neighbor variance in the same node the existing
  dedicated-node-group setup step already exists to protect.

See the design memo for the full rationale behind each of these.
