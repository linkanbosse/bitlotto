package com.linkan.bitlotto.mining;

/**
 * JNI bridge to the C scanner (ARMv8 SHA2 instructions when available).
 * {@link #available()} is only true if the library loaded AND a genesis-block self-test passed.
 */
public final class NativeHasher {
    private NativeHasher() {}

    private static final boolean LOADED;
    private static final boolean HW;
    private static final boolean OK;
    private static final String ERROR;

    static {
        boolean loaded = false, hw = false, ok = false;
        String err = null;
        try {
            System.loadLibrary("bitminer");
            loaded = true;
            hw = nativeHasSha2();
            ok = selfTest(0) && (!hw || selfTest(2)) && selfTest(1);
            if (!ok) err = "självtest misslyckades";
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOADED = loaded;
        HW = hw;
        OK = ok;
        ERROR = err;
    }

    public static boolean available() { return OK; }

    public static boolean hardwareSha2() { return OK && HW; }

    public static String engineName() {
        if (OK) return HW ? "ARMv8 SHA2 (hårdvara)" : "C (native)";
        return "Java" + (LOADED || ERROR != null ? " – native ej tillgänglig: " + ERROR : "");
    }

    /**
     * Scans count nonces from start; candidate nonces with hash difficulty >= ~1/256
     * are written to found. Returns the number of candidates.
     */
    public static int scan(int[] midstate, int[] tail, int start, int count, int[] found) {
        return nativeScan(midstate, tail, start, count, found, 0);
    }

    /** Genesis block: the known nonce must be reported, in the given mode. */
    private static boolean selfTest(int mode) {
        byte[] h = Hex.decode(
                "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e"
                        + "67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c");
        HeaderHasher hh = new HeaderHasher(h);
        int nonce = (int) 2083236893L;
        int[] found = new int[64];
        int n = nativeScan(hh.midstate(), hh.tail(), nonce - 501, 1002, found, mode);
        boolean hit = false;
        for (int i = 0; i < n; i++) hit |= found[i] == nonce;
        return hit;
    }

    private static native boolean nativeHasSha2();

    private static native int nativeScan(int[] midstate, int[] tail, int start, int count, int[] found, int mode);
}
