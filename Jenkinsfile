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
        string(name: 'LOG_FLOOD_SIZE_MB', defaultValue: '50', description: 'MB of realistic log-shaped output each of the CONCURRENCY Log flood branches writes (e.g. CONCURRENCY=4 + this=50 writes 200MB total) — the one stage that deliberately generates real, concurrent JENKINS_HOME write pressure on the controller. Start small: 1GB/branch measured impractically slow through Jenkins own console-log capture path in a real run — calibrate up from this default rather than jumping straight to large values.'),
        string(name: 'STORAGE_RESOURCE_ID', defaultValue: '', description: 'The AWS resource ID backing THIS controller\'s JENKINS_HOME PVC — vol-xxxx for EBS, fs-xxxx for EFS/FSx. Leave blank to skip Layer 4 CloudWatch collection. Requires the bench-agent pod to have AWS credentials (IRSA recommended) with cloudwatch:GetMetricStatistics.'),
        string(name: 'INTEGRATION_TEST_REPEAT', defaultValue: '1', description: 'Re-run the real mvnw verify this many times per build branch — genuine Maven/JVM console output repeated, not synthetic flood text, as a second source of concurrent JENKINS_HOME log pressure alongside Log flood. Default 1 = no change from today\'s single run.')
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
    logFloodSizeMb: params.LOG_FLOOD_SIZE_MB,
    storageResourceId: params.STORAGE_RESOURCE_ID,
    integrationTestRepeat: params.INTEGRATION_TEST_REPEAT
)
