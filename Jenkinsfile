// Scripted pipeline (not declarative) — chosen deliberately so the
// durability/concurrency knobs described in the design memo are plain Groovy,
// not fighting the declarative model's restrictions.
@Library('cjoc-storage-benchmark') _

// durabilityHint is a JOB property, not a build parameter: setting it via a
// build param would only take effect starting the *next* build, and you can't
// A/B it within a single job run. To compare PERFORMANCE_OPTIMIZED against
// MAX_SURVIVABILITY, run this file and Jenkinsfile.max-survivability as two
// separate jobs/branches — they share all stage logic via vars/benchStages.groovy.
env.BENCH_DURABILITY_HINT = 'PERFORMANCE_OPTIMIZED'

properties([
    parameters([
        choice(name: 'STORAGE_CLASS', choices: ['ebs-gp3-sc', 'efs-sc', 'openzfs-sc'],
               description: 'Informational label only — this job must already be scheduled onto the controller backed by this storage class'),
        string(name: 'CONCURRENCY', defaultValue: '1', description: 'Parallel build branches to fan out (maps to Layer 3 in the design memo)'),
        booleanParam(name: 'COLD_CACHE', defaultValue: true, description: 'Wipe workspace + Maven cache before building'),
        string(name: 'DOCKERHUB_REPO', defaultValue: 'sekharyr/cjoc-storage-benchmark', description: 'Docker Hub repo (namespace/name) to tag and push the built workload image to'),
        booleanParam(name: 'TRIVY_HARD_FAIL', defaultValue: false, description: 'Fail the pipeline on Trivy-detected vulnerabilities instead of only reporting them'),
        string(name: 'TRIVY_SEVERITY', defaultValue: 'CRITICAL,HIGH', description: 'Comma-separated severity levels Trivy hard-fails on when TRIVY_HARD_FAIL is true'),
        string(name: 'LOG_FLOOD_SECONDS', defaultValue: '30', description: 'How long each of the CONCURRENCY Log flood branches floods its own console output for (~2MB/s per branch) — the one stage that deliberately generates real, concurrent JENKINS_HOME write pressure on the controller, since build/test/soak all run on agent-local disk')
    ]),
    durabilityHint(env.BENCH_DURABILITY_HINT)
])

benchStages(
    storageClass: params.STORAGE_CLASS,
    concurrency: params.CONCURRENCY,
    coldCache: params.COLD_CACHE,
    agentLabel: 'bench-agent',
    soakDurationSeconds: 120,
    dockerhubRepo: params.DOCKERHUB_REPO,
    trivyHardFail: params.TRIVY_HARD_FAIL,
    trivySeverity: params.TRIVY_SEVERITY,
    logFloodSeconds: params.LOG_FLOOD_SECONDS
)
