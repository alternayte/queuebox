#!/usr/bin/env bash
# Run the unit tests under Bun, one file at a time.
#
# `bun test <directory>` loads all four files and then runs the tests of ONE of them. The other
# three register and never run, and the summary still says that everything passed. A green run
# that silently skips twenty tests is worse than no run at all, so each file runs on its own.
set -euo pipefail

status=0

for file in test/unit/*.test.ts; do
  echo "== ${file}"

  if ! bun test "${file}"; then
    status=1
  fi
done

exit "${status}"
