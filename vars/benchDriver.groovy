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
    // Hard cap on how many triggered builds are ever in flight at once,
    // regardless of jobCount x buildIterations. Each concurrent build costs
    // controller heap (its own flow graph + console + build record), so an
    // unbounded fan-out can OOM a modestly-sized managed controller. Default 5.
    // Set >= jobCount x buildIterations to effectively disable the cap.
    def maxConcurrent = (cfg.maxConcurrent ?: 5) as int
    if (maxConcurrent < 1) { maxConcurrent = 1 }
    // Passed through to every triggered target build so the orchestrator can drive
    // the full realistic sequence (not just the minimal stamp) and so CloudWatch
    // collection in realistic mode attributes to the right AWS resource. Default
    // realisticServiceMode=false keeps the fan-out controller-memory-safe.
    def realisticServiceMode = cfg.realisticServiceMode ?: false
    def storageClass = cfg.storageClass ?: ''
    def storageResourceId = cfg.storageResourceId ?: ''

    // Build the full ordered task list first. Plain nested indexed for-loops,
    // not (1..n).each{} — same CPS-serialization reason as
    // vars/benchStages.groovy's Build matrix stage: Groovy's IntRange isn't
    // checkpoint-safe across a pipeline step boundary, and snapshotting each
    // index into a local means each closure captures its own value.
    def tasks = []
    for (int j = 1; j <= jobCount; j++) {
        def jobIndex = j
        for (int b = 1; b <= buildIterations; b++) {
            def buildIndex = b
            def branchKey = "job-${jobIndex}-build-${buildIndex}"
            tasks << [key: branchKey, run: {
                def stamp = "${env.BUILD_TAG}-j${jobIndex}-b${buildIndex}-${System.currentTimeMillis()}"
                bench.timed(branchKey) {
                    build job: "${jobNamePrefix}-${jobIndex}",
                          wait: true,
                          parameters: [
                              string(name: 'UNIQUE_STAMP', value: stamp),
                              booleanParam(name: 'REALISTIC_SERVICE_MODE', value: realisticServiceMode),
                              string(name: 'STORAGE_CLASS', value: storageClass),
                              string(name: 'STORAGE_RESOURCE_ID', value: storageResourceId)
                          ]
                }
            }]
        }
    }

    // Drive the tasks in sequential WINDOWS of maxConcurrent: at most
    // maxConcurrent builds ever run against the controller at once. wait:true
    // inside each branch still times every build individually via bench.timed,
    // and builds within a window run genuinely concurrently — real, BOUNDED
    // concurrent controller pressure instead of an all-at-once flood. This is a
    // batch window, not a rolling one: a slow build delays its window finishing
    // before the next window starts. Chosen over a java.util.concurrent.Semaphore
    // because a Semaphore held across the `build` step boundary isn't
    // CPS-serialization-safe.
    def total = tasks.size()
    for (int start = 0; start < total; start += maxConcurrent) {
        def end = Math.min(start + maxConcurrent, total)
        def window = [:]
        for (int k = start; k < end; k++) {
            def t = tasks[k]
            window[t.key] = t.run
        }
        echo "[bench] driving builds ${start + 1}..${end} of ${total} (<= ${maxConcurrent} concurrent)"
        parallel window
    }
}
