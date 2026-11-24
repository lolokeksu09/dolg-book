# Расчёт — Kotlin / MVVM / Compose / Rust migration

Baseline: main 31fb1e1851ece6d3ddfe160a14a6e4112248e8b8, 0.29.0-beta.1, versionCode 34.
Scope authorized by owner: audit and migrate the Android product to Kotlin, MVVM, Compose, Rust ledger engine. Do not alter the web and server implementations, deploy, publish a release, replace the signing key or touch real data as part of this migration.

## Source audit (initial register)

| ID | Finding and evidence | Priority | Treatment |
|---|---|---|---|
| A01 | MainActivity.java: 2542 lines; render() recreates navigation and page, owns forms, data access, privacy/auth, export, recovery. | High | Split by screen; use lifecycle state, repository, single-flight write actions. |
| A02 | LedgerStore.insertEntry reads the entire history inside SQLite transaction; balance scans it and recomputes reversals. MainActivity renders synchronously. Risk for 100k entries and weaker tablets; runtime latency not measured. | High | Establish workload baseline and move reads to IO; preserve transaction guards. |
| A03 | Correction and reversal rely on append-only semantics and current last active ID; correctedBalance shared by preview and transaction. | Critical invariant | One calculation contract; preserve old error behavior for valid books; reject out-of-domain inputs; test stale selection and atomic rollback. |
| A04 | LedgerStore supports JSON v1–v4; MainActivity external import limit 5 MiB, RecoveryVault plaintext cap 10 MiB; exportJson has no corresponding byte limit. Large valid books may be non-round-trippable. | High candidate defect | Reproduce with synthetic data; unify limits or stream safely; never silently truncate. |
| A05 | ClientDirectory extras (limits, favorites, archive, contact plans), QuickDebtDrafts and LedgerDrafts are local preferences; import clears these. JSON is not a full app backup. | High product/contract gap | Explicit coverage policy; versioned app-backup format only after owner approves contract change. |
| A06 | RecoveryVault uses AES-GCM/AndroidKeyStore and local files; portable JSON external export is separate. | Security boundary | Preserve existing key alias, format and failure behavior; check interrupted recovery and wrong/tampered data. No claim of protection against rooted device. |
| A07 | tests/android-bundle.test.js, android-daily-ops.test.js, android-onboarding.test.js, android-collections.test.js read exact Java paths/source fragments. Renaming classes alone breaks gates. | High | Replace brittle source checks individually with equal or stronger executable tests, not wholesale removal. |
| A08 | PR #15 exact head 0ac9039: three successful workflows, 49 Node checks and 43 instrumentation checks reported; signed release NOT RUN. | Release blocker | Every slice requires current revision evidence; signed upgrade and physical acceptance remain mandatory. |
| A09 | PROJECT.md and root README describe historic web v0.2; Android README carries 0.29.0. | Medium | Separate Android/web/server status; maintain a single active plan. |
| A10 | DatedPayments, CollectionsInsights, LedgerStore, PaymentPromises have distinct date semantics: recorded sequence for FIFO, local dates for overdue, historical receipt guards, active-payment anchor for promises. | Critical invariant | Do not collapse these to chronological sorting. Capture parity before any Rust authority switch. |

Source review, not proof of actual device performance, full security audit or exploitability. A04 is a source-level mismatch, not an executed reproduction. Confirm deployed server and real device separately.

## Immutable product contracts

- Keep package dev.ventry.raschet, preview dev.ventry.raschet.nativev031, existing signing identity, SQLite schema, table and preference names, JSON v1–v4, operation UUIDs and sequence, all existing client and purchase data.
- Money in signed 64-bit integer kopecks; existing 100_000_000_000 per-operation/balance cap. No floating point in financial decisions. Corrections are atomic reversal + replacement; old entries remain.
- Existing four tabs, AMOLED brand, tablet layouts, accessibility, onboarding, lock/recovery, documents and offline operation. No new network access.
- Rust may compute and validate deterministic snapshots or commands, but Kotlin/SQLite owns atomic transaction and revalidation. Do not make a JNI failure silently produce a zero balance or switch implementations mid-transaction.
- Preserve all previously supported device ABIs and minSdk 26. Use existing signing policy and no real credentials in source.

## Dependent work and acceptance

| REQ | Deliverable | Gate | Current |
|---|---|---|---|
| M01 | Kotlin interoperable build and a real financial slice | Java call → Kotlin, original tests plus shared contract | Implemented in branch; full Android CI pending |
| M02 | Rust arithmetic reference and parity | same contract inputs; cargo fmt/clippy/test; no runtime activation | First correction function and tests added; Rust not locally executed |
| M03 | Audited pure engine: balance, FIFO, overdue, historical payments, reversal, reports/import validation | golden fixtures from exact Java baseline + property/differential tests for date ordering, amounts, reversals, duplicate IDs | NOT STARTED |
| M04 | Thin JNI bridge and NDK build | pinned toolchain, all supported ABIs, malformed inputs, panic boundary, exact APK smoke | NOT STARTED |
| M05 | Kotlin data layer + Repository; transaction boundary retained | old DB opened with existing records, rollback/restore, JSON v1–v4, concurrency, large synthetic book | NOT STARTED |
| M06 | MVVM state holders and coroutine dispatcher ownership | lifecycle, process recreation, stale result rejection, retries and repeated tap, no I/O in UI | NOT STARTED |
| M07 | Jetpack Compose UI one feature at a time | same navigation, screenshots and interaction on phone/tablet, font scaling, keyboard, D-pad only if applicable | NOT STARTED |
| M08 | End-to-end acceptance and Java retirement | full CI, data upgrade/roundtrip, performance comparison, signed release/device gate | NOT STARTED |

## Toolchain decision for slice M01

Kotlin 2.2.21 and AGP 8.10.1, Gradle 8.11.1, JVM 17. Keep SDK levels and package identity. This is a deliberately bounded bridge using mutually compatible tooling, not a claim that these are the latest available releases; separately evaluate AGP 9.x, Kotlin 2.4.x and current Compose libraries after the baseline is green. Rust edition 2024; dependency-free first crate, toolchain 1.85.1 pinned for repeatable CI. No JNI, Compose, Room, Hilt or coroutine dependency is enabled in this slice.

References:
- https://kotlinlang.org/docs/gradle-configure-project.html
- https://developer.android.com/build/kotlin-support
- https://developer.android.com/build/releases/agp-8-10-0-release-notes

## Verification and continuation

Standalone JVM: LedgerArithmetic.kt + tests/contracts/CorrectionContract.kt compiled with locally available kotlinc 1.9.0, jvm-target 17; 18/18 shared vectors PASS. This does not prove the new Android Gradle toolchain. Rust/Android/emulator on migration revision NOT RUN locally (no Cargo/Android SDK/Gradle); GitHub Actions gates are required. No release, merge, device changes or database migration authorized by this report.

Continue only after exact branch CI, then M03 with extracted legacy golden fixtures. Maintain existing preview/release isolation.
