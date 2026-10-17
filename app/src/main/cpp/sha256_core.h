/*
 * SHA-256d nonce scanner for Bitcoin block headers.
 *
 * Two implementations:
 *   - ARMv8 Cryptography Extensions (SHA256H/SHA256H2/SHA256SU0/SHA256SU1), two nonces
 *     interleaved per call so the hardware pipeline stays busy.
 *   - Portable C fallback.
 *
 * Inputs are the header midstate (state after compressing bytes 0..63) and the three
 * big-endian words at bytes 64..75 (merkle tail, ntime, nbits). The nonce is the integer
 * that is stored little-endian at bytes 76..79.
 */
#ifndef BITLOTTO_SHA256_CORE_H
#define BITLOTTO_SHA256_CORE_H

#include <stdint.h>
#include <string.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

static const uint32_t BL_K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

static const uint32_t BL_IV[8] = {
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
};

#define BL_ROTR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static inline uint32_t bl_bswap32(uint32_t x) {
    return (x >> 24) | ((x >> 8) & 0xff00) | ((x << 8) & 0xff0000) | (x << 24);
}

/* ---------------- portable C ---------------- */

static void bl_compress_c(uint32_t s[8], const uint32_t in[16]) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) w[i] = in[i];
    for (int i = 16; i < 64; i++) {
        uint32_t x = w[i - 15], y = w[i - 2];
        uint32_t s0 = BL_ROTR(x, 7) ^ BL_ROTR(x, 18) ^ (x >> 3);
        uint32_t s1 = BL_ROTR(y, 17) ^ BL_ROTR(y, 19) ^ (y >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = s[0], b = s[1], c = s[2], d = s[3], e = s[4], f = s[5], g = s[6], h = s[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = BL_ROTR(e, 6) ^ BL_ROTR(e, 11) ^ BL_ROTR(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + BL_K[i] + w[i];
        uint32_t S0 = BL_ROTR(a, 2) ^ BL_ROTR(a, 13) ^ BL_ROTR(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
    }
    s[0] += a; s[1] += b; s[2] += c; s[3] += d; s[4] += e; s[5] += f; s[6] += g; s[7] += h;
}

/** Full sha256d of one nonce; out receives the 8 final state words. */
static void bl_hash_nonce_c(const uint32_t mid[8], const uint32_t tail[3], uint32_t nonce, uint32_t out[8]) {
    uint32_t w[16];
    uint32_t st[8];
    memcpy(st, mid, sizeof(st));
    w[0] = tail[0]; w[1] = tail[1]; w[2] = tail[2]; w[3] = bl_bswap32(nonce);
    w[4] = 0x80000000u;
    for (int i = 5; i < 15; i++) w[i] = 0;
    w[15] = 640;
    bl_compress_c(st, w);
    for (int i = 0; i < 8; i++) w[i] = st[i];
    w[8] = 0x80000000u;
    for (int i = 9; i < 15; i++) w[i] = 0;
    w[15] = 256;
    memcpy(out, BL_IV, sizeof(BL_IV));
    bl_compress_c(out, w);
}

/**
 * Scans [start, start+count) and writes nonces whose final word satisfies
 * (h7 & 0x00FFFFFF) == 0 (hash difficulty >= ~1/256). Returns number written (<= cap).
 */
static int bl_scan_c(const uint32_t mid[8], const uint32_t tail[3], uint32_t start, uint32_t count,
                     uint32_t *found, int cap) {
    int n = 0;
    uint32_t out[8];
    for (uint32_t i = 0; i < count; i++) {
        uint32_t nonce = start + i;
        bl_hash_nonce_c(mid, tail, nonce, out);
        if ((out[7] & 0x00FFFFFFu) == 0 && n < cap) found[n++] = nonce;
    }
    return n;
}

/* ---------------- ARMv8 SHA2 ---------------- */
#if defined(__aarch64__)

#define BL_ARM_TARGET __attribute__((target("+crypto")))

/* 64 rounds on two independent messages at once (A and B), states in ABCD/EFGH form. */
#define BL_ROUND4(i)                                                                    \
    do {                                                                                \
        uint32x4_t k = vld1q_u32(&BL_K[4 * (i)]);                                       \
        uint32x4_t wka = vaddq_u32(ma[(i) & 3], k);                                     \
        uint32x4_t wkb = vaddq_u32(mb[(i) & 3], k);                                     \
        uint32x4_t ta = a0, tb = b0;                                                    \
        a0 = vsha256hq_u32(a0, a1, wka);                                                \
        b0 = vsha256hq_u32(b0, b1, wkb);                                                \
        a1 = vsha256h2q_u32(a1, ta, wka);                                               \
        b1 = vsha256h2q_u32(b1, tb, wkb);                                               \
        if ((i) < 12) {                                                                 \
            ma[(i) & 3] = vsha256su1q_u32(vsha256su0q_u32(ma[(i) & 3], ma[((i) + 1) & 3]), \
                                          ma[((i) + 2) & 3], ma[((i) + 3) & 3]);        \
            mb[(i) & 3] = vsha256su1q_u32(vsha256su0q_u32(mb[(i) & 3], mb[((i) + 1) & 3]), \
                                          mb[((i) + 2) & 3], mb[((i) + 3) & 3]);        \
        }                                                                               \
    } while (0)

BL_ARM_TARGET
static inline void bl_compress_arm_x2(uint32x4_t *sa0, uint32x4_t *sa1, const uint32x4_t wa[4],
                                      uint32x4_t *sb0, uint32x4_t *sb1, const uint32x4_t wb[4]) {
    uint32x4_t a0 = *sa0, a1 = *sa1, b0 = *sb0, b1 = *sb1;
    uint32x4_t ma[4] = {wa[0], wa[1], wa[2], wa[3]};
    uint32x4_t mb[4] = {wb[0], wb[1], wb[2], wb[3]};
    BL_ROUND4(0);  BL_ROUND4(1);  BL_ROUND4(2);  BL_ROUND4(3);
    BL_ROUND4(4);  BL_ROUND4(5);  BL_ROUND4(6);  BL_ROUND4(7);
    BL_ROUND4(8);  BL_ROUND4(9);  BL_ROUND4(10); BL_ROUND4(11);
    BL_ROUND4(12); BL_ROUND4(13); BL_ROUND4(14); BL_ROUND4(15);
    *sa0 = vaddq_u32(*sa0, a0); *sa1 = vaddq_u32(*sa1, a1);
    *sb0 = vaddq_u32(*sb0, b0); *sb1 = vaddq_u32(*sb1, b1);
}

BL_ARM_TARGET
static int bl_scan_arm(const uint32_t mid[8], const uint32_t tail[3], uint32_t start, uint32_t count,
                       uint32_t *found, int cap) {
    const uint32x4_t mid0 = vld1q_u32(&mid[0]);
    const uint32x4_t mid1 = vld1q_u32(&mid[4]);
    const uint32x4_t iv0 = vld1q_u32(&BL_IV[0]);
    const uint32x4_t iv1 = vld1q_u32(&BL_IV[4]);
    const uint32x4_t pad8 = {0x80000000u, 0, 0, 0};
    const uint32x4_t zero = vdupq_n_u32(0);
    const uint32x4_t len640 = {0, 0, 0, 640};
    const uint32x4_t len256 = {0, 0, 0, 256};
    const uint32x4_t pad4 = {0x80000000u, 0, 0, 0};

    int n = 0;
    uint32_t i = 0;
    for (; i + 1 < count; i += 2) {
        uint32_t na = start + i, nb = start + i + 1;
        uint32x4_t wa[4], wb[4];
        uint32_t ta[4] = {tail[0], tail[1], tail[2], bl_bswap32(na)};
        uint32_t tb[4] = {tail[0], tail[1], tail[2], bl_bswap32(nb)};
        wa[0] = vld1q_u32(ta); wa[1] = pad4; wa[2] = zero; wa[3] = len640;
        wb[0] = vld1q_u32(tb); wb[1] = pad4; wb[2] = zero; wb[3] = len640;

        uint32x4_t a0 = mid0, a1 = mid1, b0 = mid0, b1 = mid1;
        bl_compress_arm_x2(&a0, &a1, wa, &b0, &b1, wb);

        uint32x4_t xa[4] = {a0, a1, pad8, len256};
        uint32x4_t xb[4] = {b0, b1, pad8, len256};
        uint32x4_t fa0 = iv0, fa1 = iv1, fb0 = iv0, fb1 = iv1;
        bl_compress_arm_x2(&fa0, &fa1, xa, &fb0, &fb1, xb);

        uint32_t ha7 = vgetq_lane_u32(fa1, 3);
        uint32_t hb7 = vgetq_lane_u32(fb1, 3);
        if (__builtin_expect((ha7 & 0x00FFFFFFu) == 0, 0) && n < cap) found[n++] = na;
        if (__builtin_expect((hb7 & 0x00FFFFFFu) == 0, 0) && n < cap) found[n++] = nb;
    }
    if (i < count) n += bl_scan_c(mid, tail, start + i, 1, found + n, cap - n);
    return n;
}
#endif /* __aarch64__ */

#endif /* BITLOTTO_SHA256_CORE_H */
