#!/usr/bin/env bash

set -euo pipefail

REPORT="${1:?JaCoCo CSV report required}"

if [[ ! -f "${REPORT}" ]]; then
  echo "::warning title=Coverage::No JaCoCo report found at ${REPORT}"
  exit 0
fi

awk -F, '
  NR > 1 {
    branch_missed += $6; branch_covered += $7
    line_missed += $8; line_covered += $9
  }
  END {
    lines = line_missed + line_covered
    branches = branch_missed + branch_covered
    printf "::notice title=Coverage::Lines %.1f%% (%d of %d), branches %.1f%% (%d of %d)\n",
      lines ? 100 * line_covered / lines : 0, line_covered, lines,
      branches ? 100 * branch_covered / branches : 0, branch_covered, branches
  }
' "${REPORT}"
