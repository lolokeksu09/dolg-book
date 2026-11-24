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
| M01 | Kotlin interoperable build and a real financial slice | Java call → Kotlin, original tests plus shared contract | First-slice Android APK, unsigned release and contract CI PASS; emulator pending |
| M02 | Rust arithmetic reference and parity | same contract inputs; cargo fmt/clippy/test; no runtime activation | Correction and FIFO projection CI PASS; dated epoch-day guard added, exact new CI pending |
| M03 | Audited pure engine: balance, FIFO, overdue, historical payments, reversal, reports/import validation | golden fixtures from exact Java baseline + property/differential tests for date ordering, amounts, reversals, duplicate IDs | PARTIAL: Kotlin balance, canceled IDs, overdue, historical payments and collection analytics active through Java facades. Rust balance/FIFO and historical guard implemented; new CI pending. Reports, import validation and Rust aggregate insights remain |
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

Maintain existing preview/release isolation. No merge or delivery until exact-head CI passes.

## M03 — balance / FIFO projection slice

- Kotlin `LedgerProjection.kt`: checked integer arithmetic, exact prior cancellation and insertion-order FIFO behavior, client isolation, prior ISO due-date string comparison preserved deliberately. Existing `LedgerStore.Entry` implements the read-only interface with getters; no copied storage model, database schema change or transaction change. `LedgerStore.canceled`, `balance`, `overdue` now delegate to Kotlin.
- Rust `projection.rs`: independent checked projection, shared ten fixtures and an overflow rejection test. Rust still not used by the APK; JNI and ABI packaging remain M04. No performance claims.
- Shared fixtures cover empty book, date/order distinction, reversed payments and debts, replacement, client isolation, due today/no date, one kopeck, max balance and full repayment. Local Kotlin 1.9.0/JVM 17: **10 fixed cases and 500 deterministic differential checks PASS** against the transcribed baseline loops over 250 synthetic histories (80 operations, two clients). Java standalone adapter calling the Kotlin module: PASS. Exact Kotlin module, test harness and fixture blob SHA matched the tested local files. Production-facade JUnit test added but full Android Gradle verification is pending.
- Prior baseline `46ff504`: contract CI (45479114768), Android APK (45479114762), unsigned release (45479114724) all PASS; emulator (45479114647) was still in progress at last check. These results are **not** evidence for the new revision.
- Next: verify exact-head Android/Rust CI, then migrate DatedPayments and CollectionsInsights with independent regression evidence. Retain transaction writes, JSON v1–v4, old dates and correction/restore invariants.

## M03 — historical receipts and collections slice

- `DatedLedger.kt` preserves local-day conversion (Instant / OffsetDateTime), current-balance guard, minimum end-of-day balance through today, original sequence for reversal lookup, and rejection of missing reversal references. `LedgerStore.Entry` adds a read-only timestamp getter; `DatedPayments.java` is a Kotlin compatibility facade. No change to transaction or stored timestamps.
- `CollectionProjection.kt` projects all active rows in one grouped pass, preserves FIFO, cancellation and recent-operation index, overdue vs today-through-seven-days inclusive, earliest upcoming date, and empty clients. Existing Java facade keeps customer order and search/sort behavior.
- Rust `historical.rs` computes historical maximum over normalized platform-local epoch days. The paired fixtures `dated.tsv` / `dated-epoch.tsv` include the same 14 cases; added JVM test verifies epoch-day conversion agrees with Rust fixture, including Europe/Moscow midnight. Rust is not linked into Android yet.
- Local standalone Kotlin 1.9.0/JVM 17: **14 historical cases and 5 collection scenarios PASS**. Exact blob hashes for both production Kotlin modules, historical harness and timestamp fixture match tested local sources. New production Java-facade JUnit tests and normalized-fixture JUnit test added; Android Gradle execution on new head not yet observed. Rust local toolchain unavailable.
- Previous head `61b862a`: Android APK (45479812919), unsigned release (45479812879) and Rust job (45479812977) PASS. Kotlin job in the latter workflow failed before compiling because Maven Central returned HTTP 429 while resolving AGP dependencies; not a test failure and not a pass. Emulator (45479812908) was in progress at initial check. Do not suppress the failing check or change dependency repositories just to hide a transient rate limit.
- Existing Node source checks for the migrated methods now verify Kotlin and facade wiring; independent JUnit behavior checks remain. Next: exact-head CI, period reports and Rust collection aggregation, then JNI and the data/UI architecture phases. No change to app IDs, SQLite schema, JSON contract, version, signing or real data.
