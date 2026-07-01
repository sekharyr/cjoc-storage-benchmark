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
            sh 'python3 bench-repo/scripts/inject-testcontainers-deps.py workload/pom.xml'
            sh 'mkdir -p workload/src/test/java/org/springframework/samples/petclinic'
            sh 'cp bench-repo/resources/BenchPostgresIT.java workload/src/test/java/org/springframework/samples/petclinic/BenchPostgresIT.java'
            sh 'cp bench-repo/Dockerfile workload/Dockerfile'
            stash name: 'src', includes: '**', excludes: '**/.git/**'
        }
    }

    stage('Build matrix') {
        def branches = [:]
        (1..concurrency).each { i ->
            branches["build-${i}"] = {
                node(agentLabel) {
                    unstash 'src'
                    def mvnRepo = cfg.coldCache ? "../.m2-cache-${i}" : '../.m2-cache-shared'
                    // `package` runs the default lifecycle's `test` phase first, so this
                    // is the unit-test run *and* what produces target/*.jar for the stages
                    // that follow (archive/stash below, then Docker publish + soak).
                    bench.timed("unit-test-${i}") {
                        dir('workload') {
                            sh "./mvnw -B -Dmaven.repo.local=${mvnRepo} clean package"
                        }
                    }
                    bench.timed("integration-test-${i}") {
                        dir('workload') {
                            sh "./mvnw -B -Dmaven.repo.local=${mvnRepo} test -Dtest=BenchPostgresIT"
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
        node(agentLabel) {
            unstash 'src'
            unstash 'artifact-1'
            def imageTag = "${dockerhubRepo}:${env.BUILD_TAG}"
            try {
                bench.timed('docker-build') {
                    sh "docker build -t ${imageTag} workload"
                }
                bench.timed('trivy-scan') {
                    def trivyExitCode = trivyHardFail ? 1 : 0
                    sh "mkdir -p trivy-reports"
                    sh "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest image --exit-code ${trivyExitCode} --severity ${trivySeverity} -o trivy-reports/${env.BUILD_TAG}.txt ${imageTag}"
                    archiveArtifacts artifacts: 'trivy-reports/*.txt', allowEmptyArchive: true
                }
                bench.timed('docker-push') {
                    withDockerRegistry(credentialsId: 'dockerhub-creds') {
                        sh "docker push ${imageTag}"
                    }
                }
            } catch (err) {
                echo "[bench] Docker publish stage failed, continuing so metrics for this run still get published: ${err}"
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
