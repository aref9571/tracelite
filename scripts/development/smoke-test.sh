#!/usr/bin/env bash
#
# Starts the packaged backend against a running PostgreSQL and verifies:
#   1. startup without database configuration fails with a clear message
#   2. startup with a wrong database password fails
#   3. startup with configuration succeeds and the health probes report UP
#   4. the API rejects missing and wrong API keys and accepts the configured ones
#   5. SIGTERM triggers a graceful shutdown
#
# Usage: scripts/development/smoke-test.sh [path-to-jar]
# Requires SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME,
# SPRING_DATASOURCE_PASSWORD, TRACELITE_SECURITY_MANAGEMENTKEY,
# TRACELITE_SECURITY_PROJECTS_0_APPLICATION and
# TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY to be exported,
# e.g. set -a; . ./.env; set +a
# Step 4 stores one event with the environment "smoke-test".

set -euo pipefail

JAR="${1:-backend/target/tracelite-backend.jar}"
PORT="${SERVER_PORT:-8080}"
BASE_URL="http://localhost:${PORT}"
STARTUP_TIMEOUT_SECONDS=60
LOG_DIR="$(mktemp -d)"
APP_PID=""

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill -KILL "${APP_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

require_environment() {
  [[ -f "${JAR}" ]] || fail "jar not found at ${JAR}; run ./mvnw verify first"
  local name
  for name in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD \
      TRACELITE_SECURITY_MANAGEMENTKEY TRACELITE_SECURITY_PROJECTS_0_APPLICATION \
      TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY; do
    [[ -n "${!name:-}" ]] || fail "${name} is not set"
  done
}

check_missing_configuration_fails() {
  local log="${LOG_DIR}/missing-configuration.log"
  echo "Checking that startup without database configuration fails..."
  if env -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD \
      timeout "${STARTUP_TIMEOUT_SECONDS}" java -jar "${JAR}" >"${log}" 2>&1; then
    fail "application started without database configuration"
  fi
  if ! grep -q "Failed to configure a DataSource" "${log}"; then
    cat "${log}" >&2
    fail "missing configuration did not produce the expected DataSource error"
  fi
  echo "OK: startup failed with 'Failed to configure a DataSource'"
}

check_wrong_password_fails() {
  local log="${LOG_DIR}/wrong-password.log"
  echo "Checking that startup with a wrong database password fails..."
  if SPRING_DATASOURCE_PASSWORD="wrong-${RANDOM}" \
      timeout "${STARTUP_TIMEOUT_SECONDS}" java -jar "${JAR}" >"${log}" 2>&1; then
    fail "application started with a wrong database password"
  fi
  if ! grep -q "password authentication failed" "${log}"; then
    cat "${log}" >&2
    fail "wrong password did not produce PostgreSQL's authentication error"
  fi
  echo "OK: startup failed with 'password authentication failed'"
}

wait_until_ready() {
  local deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
  until curl -fsS "${BASE_URL}/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; do
    if ! kill -0 "${APP_PID}" 2>/dev/null; then
      cat "${LOG_DIR}/app.log" >&2
      fail "application exited during startup"
    fi
    if (( SECONDS >= deadline )); then
      cat "${LOG_DIR}/app.log" >&2
      fail "application was not ready within ${STARTUP_TIMEOUT_SECONDS}s"
    fi
    sleep 1
  done
}

check_endpoint_up() {
  local path="$1"
  curl -fsS "${BASE_URL}${path}" | grep -q '"status":"UP"' || fail "${path} did not report UP"
  echo "OK: ${path} reports UP"
}

expect_status() {
  local expected="$1" method="$2" path="$3" api_key="$4" body="${5:-}"
  local args=(--silent --output /dev/null --write-out '%{http_code}' --request "${method}")
  if [[ -n "${api_key}" ]]; then
    args+=(--header "X-API-Key: ${api_key}")
  fi
  if [[ -n "${body}" ]]; then
    args+=(--header 'Content-Type: application/json' --data "${body}")
  fi
  local actual
  actual="$(curl "${args[@]}" "${BASE_URL}${path}")"
  if [[ "${actual}" != "${expected}" ]]; then
    fail "${method} ${path} returned ${actual}, expected ${expected}"
  fi
  echo "OK: ${method} ${path} returned ${expected}"
}

check_api_keys() {
  local ingestion_key="${TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY}"
  local management_key="${TRACELITE_SECURITY_MANAGEMENTKEY}"
  local event
  event="$(printf '{"application":"%s","environment":"smoke-test","exceptionType":"java.lang.IllegalStateException","occurredAt":"%s"}' \
    "${TRACELITE_SECURITY_PROJECTS_0_APPLICATION}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")"

  echo "Checking API-key protection..."
  expect_status 401 POST /api/v1/events "" "${event}"
  expect_status 401 POST /api/v1/events "wrong-key-0123456789abcdef0123456789" "${event}"
  expect_status 403 POST /api/v1/events "${management_key}" "${event}"
  expect_status 201 POST /api/v1/events "${ingestion_key}" "${event}"
  expect_status 401 GET /api/v1/issues ""
  expect_status 403 GET /api/v1/issues "${ingestion_key}"
  expect_status 200 GET /api/v1/issues "${management_key}"
}

check_startup_and_graceful_shutdown() {
  echo "Starting application..."
  java -jar "${JAR}" >"${LOG_DIR}/app.log" 2>&1 &
  APP_PID=$!

  wait_until_ready
  check_endpoint_up /actuator/health
  check_endpoint_up /actuator/health/liveness
  check_endpoint_up /actuator/health/readiness
  check_api_keys

  echo "Sending SIGTERM..."
  kill -TERM "${APP_PID}"
  local exit_code=0
  wait "${APP_PID}" || exit_code=$?
  APP_PID=""

  if [[ "${exit_code}" -ne 0 && "${exit_code}" -ne 143 ]]; then
    cat "${LOG_DIR}/app.log" >&2
    fail "application exited with code ${exit_code} after SIGTERM"
  fi
  if ! grep -q "Graceful shutdown complete" "${LOG_DIR}/app.log"; then
    cat "${LOG_DIR}/app.log" >&2
    fail "graceful shutdown was not logged"
  fi
  echo "OK: graceful shutdown completed (exit code ${exit_code})"
}

require_environment
check_missing_configuration_fails
check_wrong_password_fails
check_startup_and_graceful_shutdown
echo "Smoke test passed."
