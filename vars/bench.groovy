// Shared-library global var: `bench`.
// Register this repo as a Jenkins Global Pipeline Library named
// "cjoc-storage-benchmark" so `@Library('cjoc-storage-benchmark') _`
// makes `bench.timed(...)` / `bench.publishMetrics(...)` available.

def timed(String label, Closure body) {
    def start = System.currentTimeMillis()
    def ok = true
    try {
        body()
    } catch (err) {
        ok = false
        throw err
    } finally {
        def durationMs = System.currentTimeMillis() - start
        echo "[bench] ${label}: ${durationMs} ms (ok=${ok})"
        appendRow(label, durationMs, ok)
    }
}

def appendRow(String label, long durationMs, boolean ok) {
    def metricsFile = 'bench-metrics.csv'
    def storageClass = params.STORAGE_CLASS ?: env.STORAGE_CLASS ?: 'unknown'
    def concurrency = params.CONCURRENCY ?: env.CONCURRENCY ?: '1'
    def durability = env.BENCH_DURABILITY_HINT ?: 'unspecified'
    def header = 'timestamp_ms,build_tag,storage_class,concurrency,durability,label,duration_ms,ok\n'
    def row = "${System.currentTimeMillis()},${env.BUILD_TAG},${storageClass},${concurrency},${durability},${label},${durationMs},${ok}\n"

    if (!fileExists(metricsFile)) {
        writeFile file: metricsFile, text: header
    }
    def existing = readFile(metricsFile)
    writeFile file: metricsFile, text: existing + row
}

// Copies bench-metrics.csv into results/<storageClass>/<BUILD_TAG>.csv and
// archives it on the controller so `scripts/aggregate-results.py` can find it.
def publishMetrics(String storageClass) {
    def metricsFile = 'bench-metrics.csv'
    if (!fileExists(metricsFile)) {
        echo "[bench] no metrics recorded for ${storageClass}, skipping publish"
        return
    }
    archiveArtifacts artifacts: metricsFile, fingerprint: true
    def destDir = "results/${storageClass}"
    sh "mkdir -p ${destDir}"
    sh "cp ${metricsFile} ${destDir}/${env.BUILD_TAG}.csv"
    echo "[bench] published metrics to ${destDir}/${env.BUILD_TAG}.csv"
}
