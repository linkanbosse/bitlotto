package com.linkan.bitlotto.mining;

/**
 * Hashes an 80-byte block header for many nonces. Not thread-safe: use one instance per thread.
 */
public final class HeaderHasher {
    private final int[] mid = new int[8];
    private final int[] st = new int[8];
    private final int[] w = new int[64];
    private final int t0, t1, t2;

    /** @param header 80-byte header; bytes 76..79 (nonce) are ignored. */
    public HeaderHasher(byte[] header) {
        if (header.length != 80) throw new IllegalArgumentException("header must be 80 bytes");
        System.arraycopy(Sha256.IV, 0, mid, 0, 8);
        for (int i = 0; i < 16; i++) w[i] = Sha256.readBE(header, i * 4);
        Sha256.compress(mid, w);
        t0 = Sha256.readBE(header, 64);
        t1 = Sha256.readBE(header, 68);
        t2 = Sha256.readBE(header, 72);
    }

    /**
     * Computes sha256d(header with nonce). The nonce is the integer value that is stored
     * little-endian in the header. out receives 8 big-endian words: the hash bytes in
     * internal (non-reversed) order are out[0] MSB first ... out[7] LSB last.
     */
    public void hash(int nonce, int[] out) {
        int[] s = st;
        int[] x = w;
        System.arraycopy(mid, 0, s, 0, 8);
        x[0] = t0; x[1] = t1; x[2] = t2;
        x[3] = Integer.reverseBytes(nonce);
        x[4] = 0x80000000;
        for (int i = 5; i < 15; i++) x[i] = 0;
        x[15] = 640;
        Sha256.compress(s, x);

        System.arraycopy(s, 0, x, 0, 8);
        x[8] = 0x80000000;
        for (int i = 9; i < 15; i++) x[i] = 0;
        x[15] = 256;
        System.arraycopy(Sha256.IV, 0, out, 0, 8);
        Sha256.compress(out, x);
    }

    /** Converts hash words to bytes in internal order. */
    public static byte[] toBytes(int[] h) {
        byte[] b = new byte[32];
        for (int i = 0; i < 8; i++) {
            b[i * 4] = (byte) (h[i] >>> 24);
            b[i * 4 + 1] = (byte) (h[i] >>> 16);
            b[i * 4 + 2] = (byte) (h[i] >>> 8);
            b[i * 4 + 3] = (byte) h[i];
        }
        return b;
    }
}
