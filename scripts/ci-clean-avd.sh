#!/usr/bin/env bash
set -euo pipefail

# The upstream emulator action executes each script line in a separate sh -c.
# Keep state and error propagation inside one Bash invocation.
packages=$(adb shell pm list packages | tr -d '\r')
if grep -Eq '^package:dev\.ventry\.raschet($|\.)' <<< "$packages"; then
  echo 'Unexpected Raschet package on pristine emulator' >&2
  exit 1
fi

case "${1:-}" in
  seed)
    echo 'PRISTINE_AVD_SEED_OK'
    ;;
  run)
    : "${RASCHET_CI_TEST_COMMAND:?Required emulator test command is missing}"
    start=$(date +%s)
    report_duration() {
      local rc=$?
      echo "RASCHET_TEST_DURATION_SECONDS=$(($(date +%s)-start)) exit=$rc"
      if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        printf 'Emulator test command: %s seconds (exit %s)\n' "$(($(date +%s)-start))" "$rc" >> "$GITHUB_STEP_SUMMARY"
      fi
    }
    trap report_duration EXIT
    bash -c "$RASCHET_CI_TEST_COMMAND"
    ;;
  *)
    echo 'Usage: ci-clean-avd.sh seed|run' >&2
    exit 2
    ;;
esac
