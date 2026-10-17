package com.linkan.bitlotto.mining;

import java.math.BigInteger;
import java.util.List;

/** A mining.notify job (Stratum v1). */
public final class StratumJob {
    public final String jobId;
    public final String prevHash;
    public final String coinb1;
    public final String coinb2;
    public final List<String> merkleBranch;
    public final String version;
    public final String nbits;
    public final String ntime;
    public final boolean cleanJobs;

    public StratumJob(String jobId, String prevHash, String coinb1, String coinb2, List<String> merkleBranch,
                      String version, String nbits, String ntime, boolean cleanJobs) {
        this.jobId = jobId;
        this.prevHash = prevHash;
        this.coinb1 = coinb1;
        this.coinb2 = coinb2;
        this.merkleBranch = merkleBranch;
        this.version = version;
        this.nbits = nbits;
        this.ntime = ntime;
        this.cleanJobs = cleanJobs;
    }

    /** Builds the 80-byte header with nonce = 0. */
    public byte[] buildHeader(String extranonce1, String extranonce2) {
        byte[] coinbase = Hex.decode(coinb1 + extranonce1 + extranonce2 + coinb2);
        byte[] root = Sha256.sha256d(coinbase);
        for (String branch : merkleBranch) {
            byte[] b = Hex.decode(branch);
            byte[] cat = new byte[64];
            System.arraycopy(root, 0, cat, 0, 32);
            System.arraycopy(b, 0, cat, 32, 32);
            root = Sha256.sha256d(cat);
        }

        byte[] h = new byte[80];
        writeLE(h, 0, (int) Long.parseLong(version, 16));
        // Stratum sends prevhash as 8 words, each word byte-swapped relative to header order.
        byte[] p = Hex.decode(prevHash);
        if (p.length != 32) throw new IllegalArgumentException("bad prevhash");
        for (int i = 0; i < 8; i++) {
            h[4 + i * 4] = p[i * 4 + 3];
            h[4 + i * 4 + 1] = p[i * 4 + 2];
            h[4 + i * 4 + 2] = p[i * 4 + 1];
            h[4 + i * 4 + 3] = p[i * 4];
        }
        System.arraycopy(root, 0, h, 36, 32);
        writeLE(h, 68, (int) Long.parseLong(ntime, 16));
        writeLE(h, 72, (int) Long.parseLong(nbits, 16));
        return h;
    }

    /** Network target decoded from nbits. */
    public BigInteger networkTarget() {
        long bits = Long.parseLong(nbits, 16);
        int exp = (int) (bits >>> 24);
        BigInteger mant = BigInteger.valueOf(bits & 0x007fffffL);
        return exp <= 3 ? mant.shiftRight(8 * (3 - exp)) : mant.shiftLeft(8 * (exp - 3));
    }

    public double networkDifficulty() {
        BigInteger t = networkTarget();
        if (t.signum() == 0) return 0;
        return MiningEngine.DIFF1.doubleValue() / t.doubleValue();
    }

    static void writeLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}
