#!/usr/bin/env bash
# Generic, dependency-free soak generator (curl loop) — fires concurrent
# requests at the deployed app for a fixed duration. This mostly exercises the
# build agent's CPU/log I/O, not the controller's JENKINS_HOME PVC directly;
# the controller-relevant effect is the log volume Jenkins streams back while
# this runs. Point SOAK_ENDPOINTS at the app's own read+write REST routes for
# a more realistic mix — /actuator/health is just a safe default.
set -euo pipefail

DURATION_SECONDS="${1:-120}"
BASE_URL="${SOAK_BASE_URL:-http://localhost:8080}"
ENDPOINTS="${SOAK_ENDPOINTS:-/actuator/health}"
CONCURRENCY="${SOAK_CONCURRENCY:-8}"

end=$((SECONDS + DURATION_SECONDS))
echo "[soak] hitting ${BASE_URL} {${ENDPOINTS}} for ${DURATION_SECONDS}s at concurrency ${CONCURRENCY}"

fire() {
    curl -s -o /dev/null -w '' --max-time 5 "${BASE_URL}$1" || true
}

loops=0
while [ "$SECONDS" -lt "$end" ]; do
    for path in ${ENDPOINTS//,/ }; do
        fire "$path" &
    done
    while [ "$(jobs -rp | wc -l)" -ge "$CONCURRENCY" ]; do
        wait -n || true
    done
    loops=$((loops + 1))
done
wait
echo "[soak] done, approx ${loops} fan-out loops"
