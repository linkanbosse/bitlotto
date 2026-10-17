package com.linkan.bitlotto.mining;

public final class Hex {
    private Hex() {}

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    public static byte[] decode(String s) {
        if (s == null) return new byte[0];
        int n = s.length();
        if ((n & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String encode(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = DIGITS[(b[i] >> 4) & 0xf];
            c[i * 2 + 1] = DIGITS[b[i] & 0xf];
        }
        return new String(c);
    }

    /** Big-endian hex of the low (bytes*8) bits of value, zero-padded to bytes*2 chars. */
    public static String fixed(long value, int bytes) {
        char[] c = new char[bytes * 2];
        for (int i = bytes - 1; i >= 0; i--) {
            int idx = bytes - 1 - i;
            int b = i < 8 ? (int) ((value >>> (i * 8)) & 0xff) : 0;
            c[idx * 2] = DIGITS[(b >> 4) & 0xf];
            c[idx * 2 + 1] = DIGITS[b & 0xf];
        }
        return new String(c);
    }

    public static byte[] reverse(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }
}
