/*
 * Host test for sha256_core.h. Builds on x86-64 (portable path) and on aarch64
 * (portable + ARMv8 SHA2 path, e.g. under qemu-aarch64 -cpu max).
 * Uses the Bitcoin genesis block header.
 */
#include <stdio.h>
#include <stdlib.h>
#include "../main/cpp/sha256_core.h"

static uint32_t be(const uint8_t *p) { return ((uint32_t) p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]; }

static int hexval(char c) { return c <= '9' ? c - '0' : (c | 32) - 'a' + 10; }

int main(void) {
    const char *hex =
        "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e"
        "67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c";
    uint8_t h[80];
    for (int i = 0; i < 80; i++) h[i] = (uint8_t) (hexval(hex[2 * i]) << 4 | hexval(hex[2 * i + 1]));

    uint32_t mid[8], w[16], tail[3];
    memcpy(mid, BL_IV, sizeof(mid));
    for (int i = 0; i < 16; i++) w[i] = be(h + 4 * i);
    bl_compress_c(mid, w);
    for (int i = 0; i < 3; i++) tail[i] = be(h + 64 + 4 * i);

    const uint32_t nonce = 2083236893u;
    uint32_t out[8];
    bl_hash_nonce_c(mid, tail, nonce, out);
    /* Expected internal-order words of 000000000019d668...ce26f reversed. */
    printf("c: h7=%08x h6=%08x h0=%08x\n", out[7], out[6], out[0]);
    int ok = out[7] == 0 && out[6] == 0x68d61900u && out[0] == 0x6fe28c0au;

    uint32_t found[256];
    int failures = !ok;

    int n = bl_scan_c(mid, tail, nonce - 1000, 2001, found, 256);
    int hit = 0;
    for (int i = 0; i < n; i++) hit |= found[i] == nonce;
    printf("c scan: %d candidates, genesis nonce found=%d\n", n, hit);
    failures += !hit;

#if defined(__aarch64__)
    int m = bl_scan_arm(mid, tail, nonce - 1000, 2001, found, 256);
    int hit2 = 0;
    for (int i = 0; i < m; i++) hit2 |= found[i] == nonce;
    printf("arm scan: %d candidates, genesis nonce found=%d\n", m, hit2);
    failures += !hit2 || m != n;

    /* Cross-check both implementations on a larger range with a different start. */
    uint32_t fa[256], fb[256];
    int ca = bl_scan_c(mid, tail, 0x12345678u, 3000001, fa, 256);
    int cb = bl_scan_arm(mid, tail, 0x12345678u, 3000001, fb, 256);
    int same = ca == cb;
    for (int i = 0; same && i < ca; i++) same = fa[i] == fb[i];
    printf("cross-check 3M nonces: c=%d arm=%d identical=%d\n", ca, cb, same);
    failures += !same;
#endif

    printf(failures ? "FAIL\n" : "PASS\n");
    return failures ? 1 : 0;
}
