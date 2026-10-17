#include <jni.h>
#include <stdint.h>
#include "sha256_core.h"

#if defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#ifndef HWCAP_SHA2
#define HWCAP_SHA2 (1 << 6)
#endif
static int bl_has_sha2(void) { return (getauxval(AT_HWCAP) & HWCAP_SHA2) != 0; }
#else
static int bl_has_sha2(void) { return 0; }
#endif

static int g_use_hw = -1;

static int use_hw(void) {
    if (g_use_hw < 0) g_use_hw = bl_has_sha2();
    return g_use_hw;
}

JNIEXPORT jboolean JNICALL
Java_com_linkan_bitlotto_mining_NativeHasher_nativeHasSha2(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return use_hw() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Scans `count` nonces from `start`. midstate: int[8], tail: int[3], found: int[cap].
 * forceMode: 0 = auto, 1 = portable C, 2 = hardware. Returns number of candidates.
 */
JNIEXPORT jint JNICALL
Java_com_linkan_bitlotto_mining_NativeHasher_nativeScan(JNIEnv *env, jclass cls, jintArray midstate,
                                                       jintArray tail, jint start, jint count,
                                                       jintArray found, jint forceMode) {
    (void) cls;
    uint32_t mid[8], t[3];
    (*env)->GetIntArrayRegion(env, midstate, 0, 8, (jint *) mid);
    (*env)->GetIntArrayRegion(env, tail, 0, 3, (jint *) t);
    jsize cap = (*env)->GetArrayLength(env, found);
    if (cap > 256) cap = 256;
    uint32_t buf[256];
    int n;
#if defined(__aarch64__)
    int hw = forceMode == 2 ? use_hw() : (forceMode == 1 ? 0 : use_hw());
    if (hw) n = bl_scan_arm(mid, t, (uint32_t) start, (uint32_t) count, buf, cap);
    else n = bl_scan_c(mid, t, (uint32_t) start, (uint32_t) count, buf, cap);
#else
    (void) forceMode;
    n = bl_scan_c(mid, t, (uint32_t) start, (uint32_t) count, buf, cap);
#endif
    if (n > 0) (*env)->SetIntArrayRegion(env, found, 0, n, (const jint *) buf);
    return n;
}
