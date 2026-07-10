// Shared-library global var: `benchJobSprawl`.
// Ensures `jobCount` target jobs exist, each pointed at Jenkinsfile.target
// and pre-configured with the durability tier under test. Called only by
// orchestrator jobs (Jenkinsfile.orchestrator) — the target jobs it creates
// never call this themselves.
//
// Uses the Job DSL plugin rather than direct Jenkins API/Groovy scripting:
// creating jobs from inside a pipeline needs Jenkins' job-management API,
// which isn't safely reachable from a normal (sandboxed) pipeline step.
// Job DSL is the standard, script-security-approved way to reach it.
//
// durabilityHint has to live on the TARGET jobs, not the orchestrator —
// the target jobs are what actually create builds/<n>/ directories on the
// controller under test, so that's where the durability setting under test
// needs to apply. See STORAGE-OPTIONS.md §12.5 for the still-open decision
// on whether this creates jobCount x 3 distinct jobs (one full set per
// durability tier) or reconfigures the same jobCount jobs between sweeps —
// this implementation takes the first approach (distinct jobs per tier,
// via jobNamePrefix already encoding the tier) since it's the one that
// doesn't contaminate a job's build history across tiers.

def call(Map cfg) {
    def jobCount = (cfg.jobCount ?: 5) as int
    def durabilityHint = cfg.durabilityHint ?: 'MAX_SURVIVABILITY'
    def jobNamePrefix = cfg.jobNamePrefix ?: 'bench-target'
    def repoUrl = cfg.repoUrl
    def repoBranch = cfg.repoBranch ?: 'main'
    def targetJenkinsfile = cfg.targetJenkinsfile ?: 'Jenkinsfile.target'

    if (!repoUrl) {
        error("benchJobSprawl: cfg.repoUrl is required (repo containing ${targetJenkinsfile})")
    }

    // scriptText, not scriptPath — the seed script itself is tiny and
    // fully parameterized from cfg, so it's simpler to inline than to
    // maintain as its own tracked file. idempotent: Job DSL's default
    // behavior updates an existing job with the same name rather than
    // erroring, so re-running this against jobs that already exist is safe.
    jobDsl scriptText: """
        (1..${jobCount}).each { i ->
            pipelineJob("${jobNamePrefix}-\${i}") {
                definition {
                    cpsScm {
                        scm {
                            git {
                                remote { url("${repoUrl}") }
                                branch("*/${repoBranch}")
                            }
                        }
                        scriptPath("${targetJenkinsfile}")
                    }
                }
                logRotator {
                    numToKeep(5)
                }
                // Job DSL has no first-class durabilityHint() method as of
                // writing — inject the raw job-config XML property directly.
                // NOT independently verified against a live controller;
                // confirm the element name/structure against an actual
                // Jenkins instance before relying on this in a real run.
                configure { node ->
                    node / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty' {
                        hint("${durabilityHint}")
                    }
                }
            }
        }
    """
}
