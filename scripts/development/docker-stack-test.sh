#!/usr/bin/env bash

set -euo pipefail

IMAGE="tracelite-backend"
BASE_URL="http://localhost:${TRACELITE_BACKEND_PORT:-8080}"
RUN_ID="$(date -u +%Y%m%d%H%M%S)-${RANDOM}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

require_environment() {
  [[ -f .env ]] || fail ".env not found; run from the repository root after creating .env"
  local name
  for name in SPRING_DATASOURCE_PASSWORD TRACELITE_SECURITY_MANAGEMENTKEY \
      TRACELITE_SECURITY_PROJECTS_0_APPLICATION TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY; do
    [[ -n "${!name:-}" ]] || fail "${name} is not set"
  done
}

start_stack() {
  echo "Starting the Docker Compose stack..."
  if ! docker compose up --detach --wait; then
    docker compose logs >&2 || true
    fail "the stack did not become healthy"
  fi
  echo "OK: postgres and backend are healthy"
}

check_non_root_user() {
  local uid
  uid="$(docker compose exec -T backend id -u)"
  [[ "${uid}" != "0" ]] || fail "the backend container runs as root"
  echo "OK: the backend runs as user id ${uid}"
}

check_no_secrets_in_image() {
  local metadata secret
  metadata="$(docker image inspect "${IMAGE}"; docker history --no-trunc "${IMAGE}")"
  for secret in "${SPRING_DATASOURCE_PASSWORD}" "${TRACELITE_SECURITY_MANAGEMENTKEY}" \
      "${TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY}"; do
    if grep -qF -- "${secret}" <<<"${metadata}"; then
      fail "a configured secret appears in the image metadata or history"
    fi
  done
  if [[ -n "$(docker run --rm --user root --entrypoint find "${IMAGE}" / -xdev -name '.env*')" ]]; then
    fail "the image contains a .env file"
  fi
  echo "OK: no configured secret in the image metadata or history, no .env file in the image"
}

api() {
  local method="$1" path="$2" api_key="$3" body="${4:-}"
  local args=(--silent --show-error --fail --request "${method}" --header "X-API-Key: ${api_key}")
  if [[ -n "${body}" ]]; then
    args+=(--header 'Content-Type: application/json' --data "${body}")
  fi
  curl "${args[@]}" "${BASE_URL}${path}"
}

issue_count_for_run() {
  api GET "/api/v1/issues?environment=docker-stack-test&query=${RUN_ID}" "${TRACELITE_SECURITY_MANAGEMENTKEY}" \
    | grep -o '"totalItems":[0-9]*' | cut -d: -f2
}

check_ingestion_and_read_back() {
  local event
  event="$(printf '{"application":"%s","environment":"docker-stack-test","exceptionType":"java.lang.IllegalStateException","message":"stack test %s","occurredAt":"%s"}' \
    "${TRACELITE_SECURITY_PROJECTS_0_APPLICATION}" "${RUN_ID}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")"
  api POST /api/v1/events "${TRACELITE_SECURITY_PROJECTS_0_INGESTIONKEY}" "${event}" >/dev/null \
    || fail "ingesting an event failed"
  [[ "$(issue_count_for_run)" == "1" ]] || fail "the ingested event's issue was not found"
  echo "OK: event ingested and its issue read back"
}

check_graceful_stop() {
  echo "Stopping the backend..."
  docker compose stop backend
  if ! docker compose logs backend | grep -q "Graceful shutdown complete"; then
    docker compose logs backend >&2
    fail "graceful shutdown was not logged"
  fi
  echo "OK: the backend shut down gracefully"
}

check_data_survives_restart() {
  echo "Removing the containers (keeping the volume) and starting again..."
  docker compose down
  start_stack
  [[ "$(issue_count_for_run)" == "1" ]] || fail "the issue did not survive a restart of the stack"
  echo "OK: the issue survived a restart of the stack"
}

require_environment
start_stack
check_non_root_user
check_no_secrets_in_image
check_ingestion_and_read_back
check_graceful_stop
check_data_survives_restart
echo "Docker stack test passed."
