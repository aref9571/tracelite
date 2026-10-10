#!/usr/bin/env bash
#
# Publishes the Surefire test totals as a GitHub Actions notice annotation.
#
# Usage: scripts/ci/summarize-tests.sh <surefire-reports-dir>

set -euo pipefail

REPORTS_DIR="${1:?surefire reports directory required}"

total=0 failures=0 errors=0 skipped=0 suites=0

attribute() {
  grep -o -m1 "<testsuite [^>]*" "$2" | grep -o " $1=\"[0-9]*\"" | grep -o "[0-9]*" || echo 0
}

for report in "${REPORTS_DIR}"/TEST-*.xml; do
  [[ -e "${report}" ]] || continue
  suites=$((suites + 1))
  total=$((total + $(attribute tests "${report}")))
  failures=$((failures + $(attribute failures "${report}")))
  errors=$((errors + $(attribute errors "${report}")))
  skipped=$((skipped + $(attribute skipped "${report}")))
done

echo "::notice title=Test results::${total} tests in ${suites} classes: $((total - failures - errors - skipped)) passed, ${failures} failed, ${errors} errors, ${skipped} skipped"
