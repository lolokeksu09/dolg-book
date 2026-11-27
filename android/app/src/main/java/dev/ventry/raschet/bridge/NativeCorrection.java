package dev.ventry.raschet.bridge;

/**
 * JNI ABI v1 for the Rust correction engine in release and native-enabled debug builds.
 */
public final class NativeCorrection {
    private NativeCorrection() {}
    public static final int DEBT = 1;
    public static final int PAYMENT = 2;
    public static final long INVALID = -1L;
    public static final long PAID = -2L;
    public static final long RANGE = -3L;
    public static final long INTERNAL = -4L;

    /** Explicit loading at the financial correction boundary. */
    public static void loadAndroidLibrary() {
        System.loadLibrary("raschet_jni");
    }

    /** Returns nonnegative kopecks or one of the explicit negative error codes. */
    public static native long correctedBalance(int kind, long available, long oldAmount, long replacement);
}
