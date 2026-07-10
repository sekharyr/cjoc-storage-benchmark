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
    // BUILD_TAG inherits the job name, which can contain spaces/other
    // shell-hostile chars (e.g. a job named "FSX HA Pipeline" yields
    // "jenkins-FSX HA Pipeline-6"). Unquoted in an sh cp that made the shell
    // word-split the destination and cp fail. Sanitize it to a safe filename
    // AND quote the sh args so it's robust regardless of the job name.
    def safeTag = (env.BUILD_TAG ?: 'build').replaceAll(/[^A-Za-z0-9._-]/, '_')
    sh "mkdir -p '${destDir}'"
    sh "cp '${metricsFile}' '${destDir}/${safeTag}.csv'"
    echo "[bench] published metrics to ${destDir}/${safeTag}.csv"
}

// Turns collect-cloudwatch.sh's raw per-metric JSON into actual IOPS/MB-per-sec
// numbers and writes dir/cloudwatch-summary.csv — same math as
// scripts/summarize-cloudwatch.py, reimplemented in pure Groovy (writeFile/
// readFile + groovy.json.JsonSlurper, no plugin beyond core pipeline steps)
// specifically because python3 isn't installed on the maven:* agent image, so
// the Python version can't run inline here. That script still exists for
// manual, standalone re-analysis of downloaded JSON on a machine that does
// have python3 — this is the automatic, in-pipeline equivalent.
//
// Uses a plain for-loop over the file list, not `.each{}`, for the same
// reason vars/benchStages.groovy's Build matrix stage does: readFile (a real
// pipeline step) runs inside this loop, and CPS checkpointing has bitten
// range/closure iteration wrapping pipeline steps before. The `.findAll{}`/
// `.collect{}` calls further down are safe as `.each{}`-style closures since
// they only do pure in-memory arithmetic — no pipeline step calls inside them.
def summarizeCloudWatch(String dir) {
    def periodSeconds = 60
    def bytesPerMb = 1000000
    def fileList = sh(script: "ls ${dir}/*.json 2>/dev/null || true", returnStdout: true).trim()
    if (!fileList) {
        echo "[bench] no CloudWatch JSON found under ${dir}, skipping summary"
        return
    }
    def jsonSlurper = new groovy.json.JsonSlurper()
    def rows = []
    def paths = fileList.split('\n')
    for (int idx = 0; idx < paths.size(); idx++) {
        def path = paths[idx]
        def filename = path.tokenize('/').last()
        def metricName = filename.replaceAll(/\.json$/, '').split('-', 2)[1]
        def kind = (metricName.endsWith('Ops') || metricName.endsWith('Operations')) ? 'iops' :
            metricName.endsWith('Bytes') ? 'throughput' : 'other'
        def data = jsonSlurper.parseText(readFile(path))
        def datapoints = data.Datapoints ?: []
        if (datapoints.isEmpty()) {
            continue
        }
        def avgRates, maxRates, unit
        if (kind == 'iops') {
            avgRates = datapoints.findAll { it.Sum != null }.collect { it.Sum / periodSeconds }
            maxRates = avgRates
            unit = 'ops/sec'
        } else if (kind == 'throughput') {
            avgRates = datapoints.findAll { it.Sum != null }.collect { it.Sum / periodSeconds / bytesPerMb }
            maxRates = avgRates
            unit = 'MB/sec'
        } else {
            // Percent/Utilization/Balance metrics are already a rate — and unlike
            // iops/throughput, CloudWatch's own Maximum here is genuinely different
            // from (and more useful than) the highest per-period Average, so track
            // them as separate series rather than deriving "max" from "avg"'s data.
            avgRates = datapoints.findAll { it.Average != null }.collect { it.Average }
            maxRates = datapoints.findAll { it.Maximum != null }.collect { it.Maximum }
            unit = datapoints[0].Unit ?: ''
        }
        if (!avgRates || !maxRates) {
            continue
        }
        def avgVal = avgRates.sum() / avgRates.size()
        def maxVal = maxRates.max()
        rows << [metric: metricName, kind: kind, n: avgRates.size(), avg: avgVal, max: maxVal, unit: unit]
    }
    if (rows.isEmpty()) {
        echo "[bench] no CloudWatch datapoints to summarize (check the time window / CloudWatch's 1-2 min ingestion delay)"
        return
    }
    def csvLines = ['metric,kind,n,avg,max,unit']
    for (int idx = 0; idx < rows.size(); idx++) {
        def r = rows[idx]
        csvLines << "${r.metric},${r.kind},${r.n},${String.format('%.2f', r.avg)},${String.format('%.2f', r.max)},${r.unit}"
    }
    writeFile file: "${dir}/cloudwatch-summary.csv", text: csvLines.join('\n') + '\n'
    echo "[bench] wrote ${dir}/cloudwatch-summary.csv"
}
