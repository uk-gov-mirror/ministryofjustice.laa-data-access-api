#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# run-infrastructure-smoke-tests.sh
#
# Builds the application JAR, starts the full local infrastructure via
# docker-compose.smoke-test.yml, runs the Cucumber @smoke scenarios against
# the live Axon service, then collects diagnostics and tears everything down.
#
# Usage:
#   ./scripts/run-infrastructure-smoke-tests.sh
#
# BDD configuration defaults match the local stack. See
# data-access-service-axon/docs/testing/bdd-testing-framework.md.
# ---------------------------------------------------------------------------

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.smoke-test.yml"
COMPOSE_PROJECT="laa-data-access-smoke-test"
DIAGNOSTICS_DIR="${ROOT_DIR}/data-access-service-axon/build/reports/smoke-stack"
mkdir -p "${DIAGNOSTICS_DIR}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log()  { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { echo "[$(date '+%H:%M:%S')] ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Tear-down: always runs on EXIT
# ---------------------------------------------------------------------------
teardown() {
  local exit_code=$?
  trap - EXIT
  log "Collecting container diagnostics..."
  if ! docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT}" ps --all \
    > "${DIAGNOSTICS_DIR}/status.txt" 2>&1; then
    log "ERROR: Could not collect container status."
    [ "${exit_code}" -ne 0 ] || exit_code=1
  fi
  if ! docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT}" logs --no-color \
    > "${DIAGNOSTICS_DIR}/containers.log" 2>&1; then
    log "ERROR: Could not collect container logs."
    [ "${exit_code}" -ne 0 ] || exit_code=1
  fi
  log "Tearing down local infrastructure..."
  if docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT}" down --volumes --remove-orphans; then
    log "Infrastructure stopped."
  else
    log "ERROR: Infrastructure teardown failed."
    [ "${exit_code}" -ne 0 ] || exit_code=1
  fi
  exit "${exit_code}"
}
trap teardown EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------------------
# 1. Build the application JAR (required by the Dockerfile COPY step)
# ---------------------------------------------------------------------------
log "Building application JAR..."
cd "${ROOT_DIR}"
./gradlew :data-access-service-axon:bootJar --quiet \
  || fail "Gradle bootJar build failed."
log "JAR built successfully."

# ---------------------------------------------------------------------------
# 2. Start full infrastructure and wait until all services are healthy
# ---------------------------------------------------------------------------
log "Starting local infrastructure (docker-compose.smoke-test.yml)..."
docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT}" up --build --detach --wait --wait-timeout 180 \
  || fail "docker compose failed to start all services in a healthy state."
log "All services are healthy."

# Also verify the published host port used by the BDD client.
log "Waiting for app health endpoint..."
READINESS_URL="${LAA_BDD_BASE_URL:-http://localhost:9000}/actuator/health"
for i in $(seq 1 30); do
  if curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
    "${READINESS_URL}" > "${DIAGNOSTICS_DIR}/health.json"; then
    log "App ready."
    break
  fi
  [ "${i}" -eq 30 ] && fail "App health endpoint did not become ready after 30 attempts."
  sleep 2
done

# ---------------------------------------------------------------------------
# 3. Run Cucumber @smoke scenarios. Playwright only needs its HTTP client.
# ---------------------------------------------------------------------------
log "Running infrastructure smoke tests..."
TEST_EXIT_CODE=0
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
./gradlew :data-access-service-axon:bddTest \
  -Dcucumber.filter.tags='@smoke' \
  --rerun-tasks \
  --console=plain \
  || TEST_EXIT_CODE=$?

if [ "${TEST_EXIT_CODE}" -eq 0 ]; then
  log "Infrastructure smoke tests PASSED."
else
  log "Infrastructure smoke tests FAILED (exit code ${TEST_EXIT_CODE})."
  log "Full report: ${ROOT_DIR}/data-access-service-axon/build/reports/cucumber/index.html"
fi

# Exit with the test exit code; the trap will handle teardown.
exit "${TEST_EXIT_CODE}"
