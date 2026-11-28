# CI efficiency pilot

Baseline: PR #44, head `372861c015bf7fe2e1aa515dde67224b8c302329` (all required PR workflows passed). Job durations from GitHub Actions API (start to completion, excluding queue):

| Job | Run | Seconds |
|---|---:|---:|
| Native smoke emulator | 46171229041 | 1225 |
| Licensed staging integration | 46171228981 | 503 |
| Rust JNI preview | 46171228976 | 368 |
| Public licensed candidate (unsigned PR) | 46171228974 | 186 |
| Android APK | 46171228985 | 137 |
| Kotlin contract | 46171228993 | 136 |
| JNI host / Rust contract | 46171228993 | 25 / 12 |

The native emulator action itself took 1209 seconds, including the test script; this is not boot time. SDK provisioning was 3–5 seconds in ordinary Android jobs; Android Rust ABI compilation took 11 seconds on the JNI preview job. Gradle setup caching already exists; independent jobs already run in parallel. Do not equate adding caches with acceleration.

## Pilot scope

- Use one shared API 35 x86_64 Pixel 6 clean-snapshot action for native smoke, Rust JNI runtime and licensed staging. Cache only the AVD before app installation; no adb keys, APKs, test data or TLS state in this cache. Fingerprint installed SDK emulator/image revisions and hosted runner image; no broad restore keys. Snapshot generation is optional; missing snapshot uses a cold boot. Required test command and failure behavior remain unchanged. No post-test snapshot save.
- Allow Gradle dependency-cache writes within ordinary PR scope, including native smoke, JNI preview, Android APK and Kotlin contract. Staging and signed candidate caching rules unchanged. No shared signing material or signed artifacts.
- Keep all required job names, scripts and variant boundaries. Do not merge while the current signed candidate is being verified. The current release SHA and release profile are unchanged.
- SDK bulk caching and Cargo target caching are deferred pending measured benefit; dependency/target cache transfer can cost more than the observed short install/build. Cross-workflow APK reuse is deferred because debug, native preview, staging and owner-signed releases have different variants, and changing the independent required workflows' dependency graph needs separate exact-SHA artifact provenance and regression verification.

## Acceptance and rollout

1. Run PR cold path, verify all affected workflows, clean package precondition, snapshot seed and artifact outputs. Record native test duration separately from runner preparation. Validate actual snapshot availability or the explicit cold fallback.
2. Rerun the same PR revision to measure cache hit and compare the same jobs and assertions. GitHub cache scope permits same-PR reuse; cross-PR benefits require a trusted default-branch seed and matching environment. Record cache transfer cost, hit/miss and durations. Revert if slower or unstable.
3. No skipped required tests, no changes to signing, license, JSON, SQLite or production. Do not treat cached state as test evidence. Record exact SHA, workflow run IDs and cold/warm results before merge.
4. Only after pilot success, evaluate further duplicate build removal and shared adoption in other Ventry Android repositories.

Reference: https://github.com/ReactiveCircus/android-emulator-runner#usage
