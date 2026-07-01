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
    def logFloodSizeGb = (cfg.logFloodSizeGb ?: 1) as int
    def workloadRepo = cfg.workloadRepo ?: 'https://github.com/spring-petclinic/spring-petclinic-rest'
    def workloadBranch = cfg.workloadBranch ?: 'master'
    def dockerhubRepo = cfg.dockerhubRepo ?: 'sekharyr/cjoc-storage-benchmark'
    def trivyHardFail = cfg.trivyHardFail ?: false
    def trivySeverity = cfg.trivySeverity ?: 'CRITICAL,HIGH'
    def storageResourceId = cfg.storageResourceId ?: ''
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
                    // MAVEN_CONFIG="" — the maven:* base image (recommended for the
                    // bench-agent jnlp container) sets MAVEN_CONFIG=/root/.m2, which
                    // mvnw wrongly folds into its own arg list, producing "Unknown
                    // lifecycle phase /root/.m2". Same fix as the Dockerfile's build
                    // stage. Found via a real failed run against the EBS controller.
                    bench.timed("unit-test-${i}") {
                        dir('workload') {
                            sh "MAVEN_CONFIG='' ./mvnw -B -Dmaven.repo.local=${mvnRepo} clean test"
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
                            sh "MAVEN_CONFIG='' ./mvnw -B -Dmaven.repo.local=${mvnRepo} verify"
                        }
                    }
                    archiveArtifacts artifacts: 'workload/target/*.jar', fingerprint: true, allowEmptyArchive: true
                    stash name: "artifact-${i}", includes: 'workload/target/*.jar'
                    // Every node(agentLabel) call is a fresh, separate pod with its own
                    // empty workspace — bench.timed()'s bench-metrics.csv only exists in
                    // *this* pod's filesystem. Stash it under a unique name so Publish
                    // metrics (running in yet another fresh pod) can collect and merge
                    // every stage's rows instead of finding an empty workspace.
                    stash name: "metrics-build-${i}", includes: 'bench-metrics.csv', allowEmpty: true
                }
            }
        }
        parallel branches
    }

    stage('Log flood') {
        // The build/test/soak stages all run entirely on the agent pod's own local
        // disk — none of them actually touch JENKINS_HOME (see the README's "Before
        // you trust a number" note on this). Build console logs are one of the few
        // things Jenkins genuinely writes to JENKINS_HOME incrementally as steps run,
        // on the *controller*. Running CONCURRENCY branches that each write a fixed
        // logFloodSizeGb of console output means the controller receives that many
        // simultaneous large log streams at once — real, concurrent write pressure on
        // the storage class actually under test, independent of Maven/Docker/HTTP
        // load. A fixed total size (CONCURRENCY x logFloodSizeGb) rather than a
        // duration is deliberate — "how long to write N GB" is a more comparable,
        // reproducible metric across storage classes than "however much fits in N
        // seconds," which conflates the answer with the a-priori-unknown throughput.
        def branches = [:]
        for (int branchIndex = 1; branchIndex <= concurrency; branchIndex++) {
            def i = branchIndex
            branches["log-flood-${i}"] = {
                node(agentLabel) {
                    bench.timed("log-flood-${i}") {
                        // head -c <N>G reads exactly N GiB from /dev/zero (verified: 1G
                        // produces exactly 1073741824 bytes); tr converts the null bytes
                        // to a printable character since raw NUL bytes in a text log
                        // stream can confuse log viewers/downstream tooling.
                        sh "head -c ${logFloodSizeGb}G /dev/zero | tr '\\0' 'x'"
                    }
                    stash name: "metrics-log-flood-${i}", includes: 'bench-metrics.csv', allowEmpty: true
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
                // Outside the try/catch so this still runs (and captures whatever rows
                // did get written) even if the stage above failed partway through.
                stash name: 'metrics-docker-publish', includes: 'bench-metrics.csv', allowEmpty: true
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
            stash name: 'metrics-soak', includes: 'bench-metrics.csv', allowEmpty: true
        }
    }

    stage('Publish metrics') {
        node(agentLabel) {
            // Collect every stage's separately-stashed bench-metrics.csv (each written
            // in its own pod, per the comment on the build-matrix stash above) into
            // distinct subdirectories, then concatenate into one file: one header line
            // (identical across all parts, from vars/bench.groovy's appendRow) plus
            // every part's data rows.
            for (int branchIndex = 1; branchIndex <= concurrency; branchIndex++) {
                def i = branchIndex
                dir("metrics-parts/build-${i}") {
                    unstash "metrics-build-${i}"
                }
                dir("metrics-parts/log-flood-${i}") {
                    unstash "metrics-log-flood-${i}"
                }
            }
            dir('metrics-parts/docker-publish') {
                unstash 'metrics-docker-publish'
            }
            dir('metrics-parts/soak') {
                unstash 'metrics-soak'
            }
            sh '''
                : > bench-metrics.csv
                first=1
                for f in metrics-parts/*/bench-metrics.csv; do
                    [ -f "$f" ] || continue
                    if [ "$first" = 1 ]; then
                        cat "$f" > bench-metrics.csv
                        first=0
                    else
                        tail -n +2 "$f" >> bench-metrics.csv
                    fi
                done
            '''
            bench.publishMetrics(cfg.storageClass)
        }
    }

    stage('Collect CloudWatch metrics') {
        node(agentLabel) {
            if (!storageResourceId) {
                echo "[bench] storageResourceId not set, skipping CloudWatch collection"
            } else {
                unstash 'src'   // brings back bench-repo/scripts/collect-cloudwatch.sh
                def storageClassLower = (cfg.storageClass ?: '').toLowerCase()
                def idEnvVar = storageClassLower.contains('efs') ? 'EFS_FILESYSTEM_ID' :
                    (storageClassLower.contains('fsx') || storageClassLower.contains('openzfs')) ? 'FSX_FILESYSTEM_ID' :
                    'EBS_VOLUME_ID'
                // currentBuild.startTimeInMillis .. now covers the whole run, not just
                // this stage — CloudWatch's own 1-2 minute ingestion delay means the
                // last minute or so of that window may come back sparse/incomplete.
                def startEpochSeconds = (currentBuild.startTimeInMillis / 1000) as long
                def endEpochSeconds = (System.currentTimeMillis() / 1000) as long
                try {
                    withEnv(["${idEnvVar}=${storageResourceId}"]) {
                        sh """
                            mkdir -p tools
                            curl -sL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o tools/awscliv2.zip
                            # unzip isn't on the maven:* image, but it's JDK-based — jar xf
                            # extracts any zip-format archive since JAR files are zip under
                            # the hood. jar doesn't preserve the executable bit, hence chmod.
                            jar xf tools/awscliv2.zip
                            mv aws tools/aws-src
                            chmod +x tools/aws-src/install tools/aws-src/dist/aws tools/aws-src/dist/aws_completer
                            tools/aws-src/install -i \$(pwd)/tools/aws-cli -b \$(pwd)/tools/aws-bin
                            start=\$(date -u -d @${startEpochSeconds} +%Y-%m-%dT%H:%M:%SZ)
                            end=\$(date -u -d @${endEpochSeconds} +%Y-%m-%dT%H:%M:%SZ)
                            echo "[bench] collecting CloudWatch metrics for \$start .. \$end"
                            PATH="\$(pwd)/tools/aws-bin:\$PATH" ./bench-repo/scripts/collect-cloudwatch.sh "\$start" "\$end" results/cloudwatch
                        """
                    }
                    archiveArtifacts artifacts: 'results/cloudwatch/*.json', allowEmptyArchive: true
                } catch (err) {
                    echo "[bench] CloudWatch collection failed, continuing: ${err}"
                }
            }
        }
    }
}
