#!/usr/bin/env bash
# Clones the two reference apps used as Layer 2's I/O generator, and drops in
# the iobench Spring profile. Run once per agent image, or as a pipeline
# "warm the cache" step.
set -euo pipefail

WORKLOAD_DIR="${1:-workload}"
mkdir -p "$WORKLOAD_DIR"

if [ ! -d "${WORKLOAD_DIR}/spring-petclinic-rest" ]; then
    git clone --depth 1 https://github.com/spring-petclinic/spring-petclinic-rest "${WORKLOAD_DIR}/spring-petclinic-rest"
fi

if [ ! -d "${WORKLOAD_DIR}/spring-petclinic-microservices" ]; then
    git clone --depth 1 https://github.com/spring-petclinic/spring-petclinic-microservices "${WORKLOAD_DIR}/spring-petclinic-microservices"
fi

cp resources/application-iobench.yml "${WORKLOAD_DIR}/spring-petclinic-rest/src/main/resources/application-iobench.yml"
python3 scripts/inject-testcontainers-deps.py "${WORKLOAD_DIR}/spring-petclinic-rest/pom.xml"
mkdir -p "${WORKLOAD_DIR}/spring-petclinic-rest/src/test/java/org/springframework/samples/petclinic"
cp resources/BenchPostgresIT.java "${WORKLOAD_DIR}/spring-petclinic-rest/src/test/java/org/springframework/samples/petclinic/BenchPostgresIT.java"
cp Dockerfile "${WORKLOAD_DIR}/spring-petclinic-rest/Dockerfile"

echo "Workload apps ready under ${WORKLOAD_DIR}/"
echo "  - single-service build profile: ${WORKLOAD_DIR}/spring-petclinic-rest"
echo "  - fan-out/parallel build profile: ${WORKLOAD_DIR}/spring-petclinic-microservices"
