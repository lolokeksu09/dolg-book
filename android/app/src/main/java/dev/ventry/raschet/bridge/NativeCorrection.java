package dev.ventry.raschet.bridge;

/**
 * JNI ABI v1 for the Rust correction engine. Not used by the application until
 * Android ABI packaging and runtime parity have been verified.
 */
public final class NativeCorrection {
    private NativeCorrection() {}
    public static final int DEBT = 1;
    public static final int PAYMENT = 2;
    public static final long INVALID = -1L;
    public static final long PAID = -2L;
    public static final long RANGE = -3L;
    public static final long INTERNAL = -4L;

    /** Returns nonnegative kopecks or one of the explicit negative error codes. */
    public static native long correctedBalance(int kind, long available, long oldAmount, long replacement);
}
