#!/usr/bin/env bash
#
# Publishes Maven errors and failing test reports as GitHub Actions error
# annotations so that failures are visible on the check run summary.
#
# Usage: scripts/ci/annotate-failures.sh <maven-log> <surefire-reports-dir>

set -uo pipefail

MAVEN_LOG="${1:?maven log path required}"
REPORTS_DIR="${2:?surefire reports directory required}"
MAX_LINES=60

escape() {
  local text="$1"
  text="${text//'%'/'%25'}"
  text="${text//$'\r'/'%0D'}"
  text="${text//$'\n'/'%0A'}"
  printf '%s' "${text}"
}

annotate() {
  local title="$1" body="$2"
  printf '::error title=%s::%s\n' "$(escape "${title}")" "$(escape "${body}")"
}

if [[ -f "${MAVEN_LOG}" ]]; then
  errors="$(grep -E '^\[ERROR\]' "${MAVEN_LOG}" | head -n "${MAX_LINES}")"
  if [[ -n "${errors}" ]]; then
    annotate "Maven errors" "${errors}"
  fi
fi

if [[ -d "${REPORTS_DIR}" ]]; then
  for report in "${REPORTS_DIR}"/*.txt; do
    [[ -e "${report}" ]] || continue
    if grep -qE 'FAILURE|ERROR' "${report}"; then
      summary="$(head -n 20 "${report}")"
      causes="$(grep -E '^Caused by:' "${report}" | sort -u | head -n 20)"
      annotate "$(basename "${report}" .txt)" "${summary}"$'\n'"${causes}"
    fi
  done
fi
