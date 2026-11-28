package dev.ventry.raschet.bridge;

/** JNI ABI v2: one bounded call per projection; primitive arrays only. */
public final class NativeLedger {
    private NativeLedger() {}
    public static final int PROJECT = 1, HISTORICAL = 2, COLLECTIONS = 3, REPORT = 4;
    public static final long NONE = Long.MIN_VALUE;
    // Row: entry ID, owner index, operation, kopecks, due flag/day, reversed ID, local epoch day.
    // Output shape is fixed by mode. A nonzero status never authorizes use of output.
    public static native int compute(int mode, long[] rows, int ownerCount, long a, long b, long c, long[] output);
}
