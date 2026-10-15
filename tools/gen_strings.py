#!/usr/bin/env python3
"""Генерирует sorr/sorr_strings.h из tools/strings.txt.

Каждая строка шифруется XOR со случайным 8-байтовым ключом и кодируется в base64.
Это обфускация (чтобы ссылка не находилась командой `strings`), а не настоящее шифрование.
"""
import base64
import os
import secrets
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "strings.txt")
OUT = os.path.join(ROOT, "sorr", "sorr_strings.h")


def main() -> int:
    items = {}
    with open(SRC, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            key, value = line.split("=", 1)
            items[int(key.strip())] = value.strip()

    if not items:
        print("strings.txt is empty")
        return 1
    count = max(items) + 1
    for i in range(count):
        if i not in items:
            print(f"missing string number {i}")
            return 1

    key = bytes(secrets.randbelow(255) + 1 for _ in range(8))
    encoded = []
    for i in range(count):
        raw = items[i].encode("utf-8")
        xored = bytes(b ^ key[n % 8] for n, b in enumerate(raw))
        encoded.append(base64.b64encode(xored).decode("ascii"))

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("/* Создано tools/gen_strings.py. Не редактировать вручную. */\n")
        f.write("#ifndef SORR_STRINGS_H\n#define SORR_STRINGS_H\n\n")
        f.write(f"#define SORR_STRING_COUNT {count}\n\n")
        f.write("__attribute__((unused)) static const unsigned char SORR_KEY[8] = {\n    ")
        f.write(", ".join(f"0x{b:02x}" for b in key))
        f.write("\n};\n\n")
        f.write("__attribute__((unused)) static const char *const SORR_STRINGS[SORR_STRING_COUNT] = {\n")
        for e in encoded:
            f.write(f'    "{e}",\n')
        f.write("};\n\n#endif\n")
    print(f"generated {OUT} ({count} strings)")
    return 0


if __name__ == "__main__":
    sys.exit(main())