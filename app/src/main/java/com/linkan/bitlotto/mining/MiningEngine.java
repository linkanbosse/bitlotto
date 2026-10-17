package com.linkan.bitlotto.mining;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/** Multi-threaded CPU SHA-256d miner working on Stratum jobs. */
public final class MiningEngine {

    public interface Listener {
        /** Called for hashes with difficulty >= ~1/256 (for "best hash" stats). */
        void onCandidate(double difficulty);

        /** Hash met the pool's share target. */
        void onShareFound(Work work, String extranonce2, String nonceHex, double difficulty, boolean isBlock);
    }

    public static final BigInteger DIFF1 =
            new BigInteger("00000000FFFF0000000000000000000000000000000000000000000000000000", 16);

    public static final class Work {
        public final StratumJob job;
        public final String extranonce1;
        public final int extranonce2Size;
        public final BigInteger shareTarget;
        public final BigInteger networkTarget;

        public Work(StratumJob job, String extranonce1, int extranonce2Size, double poolDifficulty) {
            this.job = job;
            this.extranonce1 = extranonce1;
            this.extranonce2Size = extranonce2Size;
            this.shareTarget = targetForDifficulty(poolDifficulty);
            this.networkTarget = job.networkTarget();
        }
    }

    private final Listener listener;
    private final AtomicLong hashes = new AtomicLong();
    private final AtomicLong extranonce2Counter = new AtomicLong();
    private final List<Thread> threads = new ArrayList<>();
    private volatile Work work;
    private volatile boolean paused;
    private volatile boolean running;

    public MiningEngine(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start(int threadCount, ThreadFactory factory) {
        if (running) return;
        running = true;
        for (int i = 0; i < threadCount; i++) {
            Thread t = factory.newThread(this::workerLoop);
            t.setName("miner-" + i);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    public synchronized void stop() {
        running = false;
        for (Thread t : threads) t.interrupt();
        threads.clear();
    }

    public void setWork(Work w) { this.work = w; }

    public Work getWork() { return work; }

    public void setPaused(boolean p) { this.paused = p; }

    public boolean isPaused() { return paused; }

    public long totalHashes() { return hashes.get(); }

    private void workerLoop() {
        int[] out = new int[8];
        while (running) {
            Work w = work;
            if (w == null || paused) {
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                continue;
            }
            long mask = w.extranonce2Size >= 8 ? -1L : (1L << (8 * w.extranonce2Size)) - 1;
            String en2 = Hex.fixed(extranonce2Counter.getAndIncrement() & mask, w.extranonce2Size);
            HeaderHasher hh;
            try {
                hh = new HeaderHasher(w.job.buildHeader(w.extranonce1, en2));
            } catch (RuntimeException e) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                continue;
            }
            if (useNative) {
                scanNative(w, en2, hh, out);
                continue;
            }
            long n = 0;
            while (n <= 0xFFFFFFFFL) {
                long end = Math.min(n + 0x10000L, 0x100000000L);
                for (; n < end; n++) {
                    hh.hash((int) n, out);
                    if ((out[7] & 0x00FFFFFF) == 0) evaluate(w, en2, (int) n, out);
                }
                hashes.addAndGet(0x10000L);
                if (!running || paused || work != w) break;
            }
        }
    }

    /** Native scan over the full nonce range; every candidate is re-hashed in Java before use. */
    private void scanNative(Work w, String en2, HeaderHasher hh, int[] out) {
        int[] mid = hh.midstate();
        int[] tail = hh.tail();
        int[] found = new int[256];
        long n = 0;
        while (n < 0x100000000L) {
            long chunk = Math.min(NATIVE_CHUNK, 0x100000000L - n);
            int c = NativeHasher.scan(mid, tail, (int) n, (int) chunk, found);
            for (int i = 0; i < c; i++) {
                hh.hash(found[i], out);
                if ((out[7] & 0x00FFFFFF) == 0) evaluate(w, en2, found[i], out);
            }
            n += chunk;
            hashes.addAndGet(chunk);
            if (!running || paused || work != w) return;
        }
    }

    private static final long NATIVE_CHUNK = 0x80000L;

    private final boolean useNative = NativeHasher.available();

    public String engineName() { return NativeHasher.engineName(); }

    private void evaluate(Work w, String en2, int nonce, int[] out) {
        BigInteger value = new BigInteger(1, Hex.reverse(HeaderHasher.toBytes(out)));
        double diff = value.signum() == 0 ? Double.MAX_VALUE : DIFF1.doubleValue() / value.doubleValue();
        listener.onCandidate(diff);
        if (value.compareTo(w.shareTarget) <= 0) {
            boolean isBlock = value.compareTo(w.networkTarget) <= 0;
            listener.onShareFound(w, en2, Hex.fixed(nonce & 0xFFFFFFFFL, 4), diff, isBlock);
        }
    }

    public static BigInteger targetForDifficulty(double difficulty) {
        if (!(difficulty > 0)) difficulty = 1;
        return new BigDecimal(DIFF1).divide(BigDecimal.valueOf(difficulty), 0, RoundingMode.FLOOR).toBigInteger();
    }
}
