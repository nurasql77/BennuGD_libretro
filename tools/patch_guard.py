#!/usr/bin/env python3
"""Patches the expected APK-certificate SHA-256 into the core (.so).

usage: patch_guard.py <libbennugd_libretro.so> <cert_sha256_hex>

If the core has no guard placeholder yet, it prints a warning and exits 0.
"""
import sys

MARK = b"SORRGD01"
END = b"END!"


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: patch_guard.py <so> <sha256hex>")
        return 2

    path, hexhash = sys.argv[1], sys.argv[2].strip().lower()
    digest = bytes.fromhex(hexhash)
    if len(digest) != 32:
        print("hash must be 32 bytes (64 hex chars)")
        return 2

    data = bytearray(open(path, "rb").read())
    count = data.count(MARK)
    if count == 0:
        print("WARNING: guard placeholder not found in the core, skipping")
        return 0
    if count > 1:
        print("ERROR: guard placeholder found more than once")
        return 1

    i = data.find(MARK)
    if bytes(data[i + 40:i + 44]) != END:
        print("ERROR: guard placeholder layout is wrong")
        return 1

    for k in range(32):
        data[i + 8 + k] = digest[k] ^ ((0x5A + k * 7) & 0xFF)

    open(path, "wb").write(data)
    print("guard patched at offset", i)
    return 0


if __name__ == "__main__":
    sys.exit(main())