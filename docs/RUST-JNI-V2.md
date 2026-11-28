# Rust JNI v2 — bounded read-only projections

Scope: existing projection, historical, collections and report algorithms. Keep scalar correction ABI v1. Release uses Rust and independent Kotlin equality; ordinary debug stays Kotlin. No schema, JSON, signing or license changes. Date conversion and input validation remain Kotlin. No customer names, phone numbers, notes or raw identifiers cross JNI; identifiers are remapped per invocation. No native state or pinned arrays.

Input: seven signed 64-bit words per record (insertion order): positive entry key, zero-based owner index, kind 1/2/3, amount in kopecks, due flag/day, reversed key (0 for absent), local epoch day. Limit 100000 rows. PROJECT due flag 1 means nonempty ISO due earlier than cutoff. COLLECTIONS due epoch or Long.MIN_VALUE. HISTORICAL uses local epoch days only for target owner. REPORT uses epoch days. Owner -1 means absent.

Outputs: PROJECT [balance, overdue]; HISTORICAL [maximum]; COLLECTIONS five words per owner [balance, overdue, upcoming, next due or NONE, last index]; REPORT [opening, closing, four movement amounts, count, active owners] then five totals per calendar day (zero count means absent). JNI statuses: 0 success, -1 invalid shape, -2 calculation error, -3 JNI transport exception, -4 panic. No output consumed after nonzero return. JNI table slot checks against Java headers, -Xcheck:jni host test, packaged Android instrumentation, existing correction/transaction gates and four ELF ABI symbol checks.

Decision: use bounded region copies rather than adding an unpinned Rust dependency or native pointers to application memory. Existing four Rust algorithms are reused, not reimplemented. Kotlin parity runs before native and checks the full returned result. This is a correctness-first migration; no speedup claimed. Future work: measure full book projections and repeated per-client calls, reduce duplication only after evidence, migrate extended AnalyticsProjection metrics separately.

Reference: https://docs.oracle.com/en/java/javase/17/docs/specs/jni/functions.html
