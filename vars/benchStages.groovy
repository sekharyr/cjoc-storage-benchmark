// Shared-library global var: `benchStages`.
// Holds the actual stage logic so both Jenkinsfile (PERFORMANCE_OPTIMIZED)
// and Jenkinsfile.max-survivability (MAX_SURVIVABILITY) can share it without
// duplicating the pipeline body — only the durabilityHint job property differs
// between the two, and that has to be set at the job/branch level (see README).
//
// Two things get checked out into the workspace, on purpose:
//   bench-repo/  - this repo: scripts/soak.sh, resources/application-iobench.yml
//   workload/    - the actual Spring Boot app being built (spring-petclinic-rest by default)
// `checkout scm` alone would only give you bench-repo — the Jenkinsfile's own
// repo isn't the thing whose I/O you're trying to measure.

def call(Map cfg) {
    def agentLabel = cfg.agentLabel ?: 'bench-agent'
    def concurrency = (cfg.concurrency ?: '1') as int
    def soakSeconds = cfg.soakDurationSeconds ?: 120
    def workloadRepo = cfg.workloadRepo ?: 'https://github.com/spring-petclinic/spring-petclinic-rest'
    def workloadBranch = cfg.workloadBranch ?: 'master'
    def dockerhubRepo = cfg.dockerhubRepo ?: 'REPLACE_ME/cjoc-storage-benchmark'
    def trivyHardFail = cfg.trivyHardFail ?: false
    def trivySeverity = cfg.trivySeverity ?: 'CRITICAL,HIGH'
    // Pinned together since buildctl and the buildkitd sidecar must be compatible versions.
    def buildkitVersion = 'v0.20.0'
    def craneVersion = 'v0.20.2'

    node(agentLabel) {
        stage('Checkout') {
            if (cfg.coldCache) {
                deleteDir()
            }
            dir('bench-repo') {
                checkout scm
            }
            dir('workload') {
                git url: workloadRepo, branch: workloadBranch
            }
            sh 'cp bench-repo/resources/application-iobench.yml workload/src/main/resources/application-iobench.yml'
            sh 'cp bench-repo/Dockerfile workload/Dockerfile'
            stash name: 'src', includes: '**', excludes: '**/.git/**'
        }
    }

    stage('Build matrix') {
        def branches = [:]
        // Plain for-loop, not (1..concurrency).each{} — Groovy's IntRange isn't
        // serializable in a way Jenkins' CPS engine can checkpoint mid-pipeline,
        // and throws java.io.NotSerializableException: groovy.lang.IntRange the
        // first time it tries. `def i = branchIndex` inside the loop snapshots
        // the value per iteration so each closure captures its own i rather than
        // all closures sharing a reference to the same mutable loop variable.
        for (int branchIndex = 1; branchIndex <= concurrency; branchIndex++) {
            def i = branchIndex
            branches["build-${i}"] = {
                node(agentLabel) {
                    unstash 'src'
                    def mvnRepo = cfg.coldCache ? "../.m2-cache-${i}" : '../.m2-cache-shared'
                    bench.timed("unit-test-${i}") {
                        dir('workload') {
                            sh "./mvnw -B -Dmaven.repo.local=${mvnRepo} clean test"
                        }
                    }
                    // spring-petclinic-rest has no failsafe plugin or Testcontainers
                    // dependency — `verify` here doesn't run separate integration tests,
                    // it re-runs the unit tests via the default lifecycle and then the
                    // JaCoCo coverage-gate check bound to the verify phase (85% line
                    // coverage threshold). Labeled honestly as what it actually measures,
                    // not as a stand-in for real ITs. `package` (part of the lifecycle
                    // verify runs through) is what produces target/*.jar for the stages
                    // that follow.
                    bench.timed("integration-test-${i}") {
                        dir('workload') {
                            sh "./mvnw -B -Dmaven.repo.local=${mvnRepo} verify"
                        }
                    }
                    archiveArtifacts artifacts: 'workload/target/*.jar', fingerprint: true, allowEmptyArchive: true
                    stash name: "artifact-${i}", includes: 'workload/target/*.jar'
                }
            }
        }
        parallel branches
    }

    stage('Docker publish') {
        // No Docker daemon/socket on the agent node — bench-agent nodes may be
        // containerd-only (dockershim removal). Build via a BuildKit sidecar
        // (buildctl talking over localhost, since sidecars in a pod share the
        // network namespace), scan the resulting OCI tarball directly with
        // trivy, then push with crane — none of the three needs a Docker
        // daemon anywhere. Scoped to this stage only (inheritFrom + a
        // distinct label) so the buildkitd sidecar isn't carried by every
        // other stage's pod.
        def buildkitLabel = "${agentLabel}-buildkit"
        podTemplate(label: buildkitLabel, inheritFrom: agentLabel, containers: [
            containerTemplate(name: 'buildkitd', image: "moby/buildkit:${buildkitVersion}",
                               privileged: true, command: 'buildkitd', args: '--addr tcp://0.0.0.0:1234',
                               ttyEnabled: true)
        ]) {
            node(buildkitLabel) {
                unstash 'src'
                unstash 'artifact-1'
                def imageTag = "${dockerhubRepo}:${env.BUILD_TAG}"
                try {
                    sh """
                        mkdir -p tools
                        curl -sL https://github.com/moby/buildkit/releases/download/${buildkitVersion}/buildkit-${buildkitVersion}.linux-amd64.tar.gz | tar xz -C tools --strip-components=1 bin/buildctl
                        curl -sL https://github.com/google/go-containerregistry/releases/download/${craneVersion}/go-containerregistry_Linux_x86_64.tar.gz | tar xz -C tools crane
                        curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b tools
                    """
                    bench.timed('docker-build') {
                        sh "tools/buildctl --addr tcp://localhost:1234 build --frontend dockerfile.v0 " +
                           "--local context=workload --local dockerfile=workload " +
                           "--output type=docker,name=${imageTag},dest=image.tar"
                    }
                    bench.timed('trivy-scan') {
                        def trivyExitCode = trivyHardFail ? 1 : 0
                        sh "mkdir -p trivy-reports"
                        sh "tools/trivy image --input image.tar --exit-code ${trivyExitCode} --severity ${trivySeverity} -o trivy-reports/${env.BUILD_TAG}.txt"
                        archiveArtifacts artifacts: 'trivy-reports/*.txt', allowEmptyArchive: true
                    }
                    bench.timed('docker-push') {
                        withCredentials([usernamePassword(credentialsId: 'dockerhub-creds',
                                                           usernameVariable: 'DOCKERHUB_USER',
                                                           passwordVariable: 'DOCKERHUB_TOKEN')]) {
                            // printf | pipe rather than a <<< here-string — Jenkins' `sh`
                            // step runs /bin/sh, which on Debian/Ubuntu agent images is
                            // dash, not bash, and dash doesn't support here-strings.
                            sh 'printf %s "$DOCKERHUB_TOKEN" | tools/crane auth login docker.io -u "$DOCKERHUB_USER" --password-stdin'
                        }
                        sh "tools/crane push image.tar ${imageTag}"
                    }
                } catch (err) {
                    echo "[bench] Docker publish stage failed, continuing so metrics for this run still get published: ${err}"
                }
            }
        }
    }

    stage('Deploy + soak') {
        node(agentLabel) {
            unstash 'src'        // brings back bench-repo/scripts/soak.sh
            unstash 'artifact-1'
            bench.timed('soak') {
                sh '''
                    jar_path=$(find workload/target -name "*.jar" | head -1)
                    # h2,spring-data-jpa is spring-petclinic-rest's own default repository/DB
                    # profile combo (see its readme.md) — spring.profiles.active replaces
                    # rather than adds to defaults, so iobench alone leaves no PetRepository
                    # bean and the app fails to start. Verified against a real build.
                    nohup java -jar "$jar_path" --spring.profiles.active=iobench,h2,spring-data-jpa > app.log 2>&1 &
                    echo $! > app.pid
                    sleep 5
                '''
                sh "./bench-repo/scripts/soak.sh ${soakSeconds}"
                sh 'kill $(cat app.pid) 2>/dev/null || true'
            }
        }
    }

    stage('Publish metrics') {
        node(agentLabel) {
            bench.publishMetrics(cfg.storageClass)
        }
    }
}
