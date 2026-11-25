#!/usr/bin/env bash
set -euo pipefail

# Retry only a confirmed Maven Central throttling failure before tests start.
# Never retry or suppress a genuine test failure.
mkdir -p android/build
for attempt in 1 2 3; do
  log="android/build/instrumentation-attempt-${attempt}.log"
  if gradle "$@" 2>&1 | tee "$log"; then
    exit 0
  else
    codes=("${PIPESTATUS[@]}")
  fi
  rc="${codes[0]}"
  if [[ "$rc" -eq 0 ]]; then exit "${codes[1]}"; fi
  if [[ "$attempt" -eq 3 ]] ||
     ! grep -Fq "Could not resolve all files for configuration ':app:_internal-unified-test-platform" "$log" ||
     ! grep -Eq 'repo\.maven\.apache\.org/maven2/.*Received status code 429' "$log" ||
     grep -Eq 'Starting [0-9]+ tests? on|Finished [0-9]+ tests? on|AssertionError|Tests failed' "$log"; then
    exit "$rc"
  fi
  delay=$((attempt*15))
  echo "Maven Central HTTP 429 before instrumentation; retry $((attempt+1))/3 in ${delay}s." >&2
  sleep "$delay"
done
exit 1
