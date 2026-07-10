// Shared-library global var: `benchDriver`.
// Fans out real, separate builds across the jobCount jobs benchJobSprawl
// created, buildIterations times each — this is what actually multiplies
// jobs/*/builds/* directories on the controller under test, unlike fanning
// parallel branches out inside one build (which only ever produces one
// build directory, no matter how many branches run inside it). See
// STORAGE-OPTIONS.md §11/§12 for why this distinction is the point.
//
// Each triggered build gets a stamp unique to (this run, job index, build
// iteration, wall-clock time) — passed as UNIQUE_STAMP, which
// vars/benchStages.groovy's minimal-mode branch writes to a file and
// archives with fingerprint: true. Uniqueness matters: a byte-identical
// artifact fingerprints to the SAME existing entry rather than creating a
// new one, so a non-unique stamp would silently fail to grow the
// fingerprint database the way this is meant to.

def call(Map cfg) {
    def jobCount = (cfg.jobCount ?: 5) as int
    def buildIterations = (cfg.buildIterations ?: 2) as int
    def jobNamePrefix = cfg.jobNamePrefix ?: 'bench-target'

    def branches = [:]
    // Plain nested indexed for-loops, not (1..n).each{} — same
    // CPS-serialization reason as vars/benchStages.groovy's Build matrix
    // stage: Groovy's IntRange isn't checkpoint-safe across a pipeline step
    // boundary, and `def i = branchIndex` inside the loop snapshots the
    // value per iteration so each closure captures its own index.
    for (int j = 1; j <= jobCount; j++) {
        def jobIndex = j
        for (int b = 1; b <= buildIterations; b++) {
            def buildIndex = b
            def branchKey = "job-${jobIndex}-build-${buildIndex}"
            branches[branchKey] = {
                def stamp = "${env.BUILD_TAG}-j${jobIndex}-b${buildIndex}-${System.currentTimeMillis()}"
                bench.timed(branchKey) {
                    build job: "${jobNamePrefix}-${jobIndex}",
                          wait: true,
                          parameters: [string(name: 'UNIQUE_STAMP', value: stamp)]
                }
            }
        }
    }
    // wait: true inside each parallel branch means every triggered build is
    // individually timed via bench.timed, while different branches still
    // run concurrently against each other — real concurrent controller
    // pressure, not one build at a time. Needs enough executor capacity
    // across the target jobs' agent pool to actually run jobCount x
    // buildIterations builds concurrently; otherwise they queue, and this
    // measures queue wait time instead of storage performance.
    parallel branches
}
