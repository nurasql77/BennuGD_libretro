/*
 * sorr_guard.c - checks that the host APK is signed with our certificate.
 *
 * It reads the APK Signing Block (v2 / v3 / v3.1) straight from the APK file,
 * takes the signer certificate(s), hashes them with SHA-256 and compares the
 * result to a hash that is patched into this library at build time
 * (tools/patch_guard.py). No JNI is needed, so it works from a plain
 * libretro core loaded with dlopen().
 */
#include "sorr_guard.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sorr_strings.h"

#ifdef __ANDROID__
#include <jni.h>
#endif

/* ------------------------------------------------------------------ */
/* Placeholder patched by CI: "SORRGD01" + 32 bytes (obfuscated hash) + "END!" */
/* volatile: stops the compiler from folding the zero bytes in. */
__attribute__((used))
static volatile unsigned char g_blob[44] = {
    'S', 'O', 'R', 'R', 'G', 'D', '0', '1',
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    'E', 'N', 'D', '!'
};

/* ------------------------------------------------------------------ */
/* Minimal SHA-256                                                     */
typedef struct {
    uint32_t h[8];
    uint64_t len;
    unsigned char buf[64];
    size_t used;
} sha256_ctx;

static const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha256_block(sha256_ctx *c, const unsigned char *p) {
    uint32_t w[64], a, b, cc, d, e, f, g, h, t1, t2;
    int i;
    for (i = 0; i < 16; i++)
        w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
               ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
    for (i = 16; i < 64; i++) {
        uint32_t s0 = ROR(w[i - 15], 7) ^ ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = ROR(w[i - 2], 17) ^ ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    a = c->h[0]; b = c->h[1]; cc = c->h[2]; d = c->h[3];
    e = c->h[4]; f = c->h[5]; g = c->h[6]; h = c->h[7];
    for (i = 0; i < 64; i++) {
        uint32_t S1 = ROR(e, 6) ^ ROR(e, 11) ^ ROR(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        t1 = h + S1 + ch + K[i] + w[i];
        uint32_t S0 = ROR(a, 2) ^ ROR(a, 13) ^ ROR(a, 22);
        uint32_t mj = (a & b) ^ (a & cc) ^ (b & cc);
        t2 = S0 + mj;
        h = g; g = f; f = e; e = d + t1; d = cc; cc = b; b = a; a = t1 + t2;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d;
    c->h[4] += e; c->h[5] += f; c->h[6] += g; c->h[7] += h;
}

static void sha256_init(sha256_ctx *c) {
    static const uint32_t init[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };
    memcpy(c->h, init, sizeof(init));
    c->len = 0;
    c->used = 0;
}

static void sha256_update(sha256_ctx *c, const unsigned char *data, size_t n) {
    c->len += n;
    while (n > 0) {
        size_t take = 64 - c->used;
        if (take > n) take = n;
        memcpy(c->buf + c->used, data, take);
        c->used += take; data += take; n -= take;
        if (c->used == 64) { sha256_block(c, c->buf); c->used = 0; }
    }
}

static void sha256_final(sha256_ctx *c, unsigned char out[32]) {
    uint64_t bits = c->len * 8;
    unsigned char pad = 0x80, zero = 0;
    int i;
    sha256_update(c, &pad, 1);
    while (c->used != 56) sha256_update(c, &zero, 1);
    for (i = 7; i >= 0; i--) {
        unsigned char b = (unsigned char)(bits >> (i * 8));
        sha256_update(c, &b, 1);
    }
    for (i = 0; i < 8; i++) {
        out[i * 4]     = (unsigned char)(c->h[i] >> 24);
        out[i * 4 + 1] = (unsigned char)(c->h[i] >> 16);
        out[i * 4 + 2] = (unsigned char)(c->h[i] >> 8);
        out[i * 4 + 3] = (unsigned char)(c->h[i]);
    }
}

/* ------------------------------------------------------------------ */
/* Little-endian helpers                                               */
static uint32_t rd32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static uint64_t rd64(const unsigned char *p) {
    return (uint64_t)rd32(p) | ((uint64_t)rd32(p + 4) << 32);
}

/* Reads a length-prefixed (u32) field from buf[*pos..end). */
static int take_field(const unsigned char *buf, size_t end, size_t *pos,
                      const unsigned char **data, size_t *len) {
    uint32_t n;
    if (end - *pos < 4) return 0;
    n = rd32(buf + *pos);
    *pos += 4;
    if (n > end - *pos) return 0;
    *data = buf + *pos;
    *len = n;
    *pos += n;
    return 1;
}

/* Checks every signer's first certificate of one v2/v3 block value. */
static int check_signers(const unsigned char *val, size_t vlen, const unsigned char *expected) {
    size_t pos = 0, signers_len, spos = 0, count = 0;
    const unsigned char *signers;

    if (!take_field(val, vlen, &pos, &signers, &signers_len)) return 0;

    while (spos < signers_len) {
        const unsigned char *signer, *signed_data, *digests, *certs, *cert;
        size_t signer_len, sd_len, dig_len, certs_len, cert_len, p = 0, cp = 0;
        unsigned char hash[32];
        sha256_ctx ctx;

        if (!take_field(signers, signers_len, &spos, &signer, &signer_len)) return 0;
        if (!take_field(signer, signer_len, &p, &signed_data, &sd_len)) return 0;

        p = 0;
        if (!take_field(signed_data, sd_len, &p, &digests, &dig_len)) return 0;
        if (!take_field(signed_data, sd_len, &p, &certs, &certs_len)) return 0;
        if (!take_field(certs, certs_len, &cp, &cert, &cert_len)) return 0;

        sha256_init(&ctx);
        sha256_update(&ctx, cert, cert_len);
        sha256_final(&ctx, hash);

        if (memcmp(hash, expected, 32) != 0) return 0;
        count++;
    }
    return count > 0;
}

/* Looks for the signing block and verifies it. */
static int verify_apk(const char *path, const unsigned char *expected) {
    FILE *f = fopen(path, "rb");
    unsigned char *tail = NULL, *blk = NULL;
    long fsize;
    size_t tail_len, i;
    int ok = 0, found_eocd = 0;
    uint32_t cd_off = 0;
    uint64_t block_size;
    unsigned char footer[24];

    if (!f) return 0;
    if (fseek(f, 0, SEEK_END) != 0) goto done;
    fsize = ftell(f);
    if (fsize < 22 + 32) goto done;

    /* End of central directory record */
    tail_len = (size_t)(fsize < 65557 ? fsize : 65557);
    tail = (unsigned char *)malloc(tail_len);
    if (!tail) goto done;
    if (fseek(f, fsize - (long)tail_len, SEEK_SET) != 0) goto done;
    if (fread(tail, 1, tail_len, f) != tail_len) goto done;

    for (i = tail_len - 22 + 1; i-- > 0;) {
        if (rd32(tail + i) == 0x06054b50u) {
            cd_off = rd32(tail + i + 16);
            found_eocd = 1;
            break;
        }
    }
    if (!found_eocd || cd_off < 32) goto done;

    /* Signing block footer: u64 size + "APK Sig Block 42" */
    if (fseek(f, (long)cd_off - 24, SEEK_SET) != 0) goto done;
    if (fread(footer, 1, 24, f) != 24) goto done;
    if (memcmp(footer + 8, "APK Sig Block 42", 16) != 0) goto done;
    block_size = rd64(footer);
    if (block_size < 24 || block_size > (uint64_t)cd_off - 8 || block_size > 64u * 1024 * 1024) goto done;

    {
        /* pairs region = block_size - 24 bytes, right after the leading u64 */
        size_t pairs_len = (size_t)(block_size - 24), pos = 0;
        long pairs_off = (long)cd_off - (long)block_size - 8 + 8;
        blk = (unsigned char *)malloc(pairs_len ? pairs_len : 1);
        if (!blk) goto done;
        if (fseek(f, pairs_off, SEEK_SET) != 0) goto done;
        if (pairs_len && fread(blk, 1, pairs_len, f) != pairs_len) goto done;

        while (pairs_len - pos >= 12) {
            uint64_t plen = rd64(blk + pos);
            uint32_t id;
            pos += 8;
            if (plen < 4 || plen > pairs_len - pos) break;
            id = rd32(blk + pos);
            if (id == 0x7109871au || id == 0xf05368c0u || id == 0x1b93ad61u) {
                if (check_signers(blk + pos + 4, (size_t)plen - 4, expected)) ok = 1;
                else { ok = 0; break; }   /* any block that does not match = reject */
            }
            pos += (size_t)plen;
        }
    }

done:
    free(blk);
    free(tail);
    fclose(f);
    return ok;
}

int sorr_guard_check(void) {
    unsigned char expected[32];
    unsigned char any = 0;
    const char *path = getenv("SORR_APK_PATH");
    int i;

    if (!path || !*path) return 0;

    for (i = 0; i < 32; i++) {
        expected[i] = (unsigned char)(g_blob[8 + i] ^ (unsigned char)(0x5A + i * 7));
        any |= g_blob[8 + i];
    }
    if (!any) return 0;   /* not patched by CI -> fail closed */

    return verify_apk(path, expected);
}


/* ------------------------------------------------------------------ */
/* Защищённые строки: base64 -> XOR                                    */
static int b64v(int c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

/* Возвращает malloc-строку (нужно free) или NULL. */
static char *sorr_decode(const char *b64) {
    size_t n = strlen(b64), o = 0, i;
    unsigned acc = 0;
    int bits = 0;
    char *out = (char *)malloc(n / 4 * 3 + 4);
    if (!out) return NULL;

    for (i = 0; i < n; i++) {
        int v = b64v((unsigned char)b64[i]);
        if (v < 0) continue;   /* '=' и прочее */
        acc = (acc << 6) | (unsigned)v;
        bits += 6;
        if (bits >= 8) {
            unsigned char b;
            bits -= 8;
            b = (unsigned char)((acc >> bits) & 0xFFu);
            acc &= (1u << bits) - 1u;
            out[o] = (char)(b ^ SORR_KEY[o % 8]);
            o++;
        }
    }
    out[o] = '\0';
    return out;
}

/* ------------------------------------------------------------------ */
/* JNI: Kotlin вызывает Native.link(id) и Native.verified()            */
#ifdef __ANDROID__
JNIEXPORT jstring JNICALL
Java_com_nurasql77_sorr_Native_link(JNIEnv *env, jclass cls, jint id) {
    jstring result;
    char *text;
    (void)cls;

    if (id < 0 || id >= SORR_STRING_COUNT) return (*env)->NewStringUTF(env, "");
    if (!sorr_guard_check()) return (*env)->NewStringUTF(env, "");   /* подпись не совпала */

    text = sorr_decode(SORR_STRINGS[id]);
    if (!text) return (*env)->NewStringUTF(env, "");
    result = (*env)->NewStringUTF(env, text);
    free(text);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_nurasql77_sorr_Native_verified(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return sorr_guard_check() ? JNI_TRUE : JNI_FALSE;
}
#endif