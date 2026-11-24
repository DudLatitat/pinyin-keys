#!/usr/bin/env python3
"""Builds app/src/main/assets/pinyin_dict.txt from AOSP PinyinIME's raw dictionary.

Source (Apache License 2.0, (c) 2009 The Android Open Source Project):
  platform/packages/inputmethods/PinyinIME, jni/data/rawdict_utf16_65105_freq.txt
Download it as UTF-16 text, then run:
  python3 tools/build_pinyin_dict.py rawdict_utf16_65105_freq.txt

Output: one entry per line, "pinyin<TAB>word<TAB>frequency", pinyin without spaces
(e.g. "nihao"), sorted by pinyin then by frequency (highest first). Entries flagged as
rare/traditional (third column = 1) are dropped.
"""
import sys

def main(path):
    rows = []
    with open(path, encoding="utf-16") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4 or parts[2] != "0":
                continue
            word, freq, syllables = parts[0], float(parts[1]), parts[3:]
            rows.append(("".join(syllables), word, max(1, int(freq))))
    rows.sort(key=lambda r: (r[0], -r[2]))
    out = "app/src/main/assets/pinyin_dict.txt"
    with open(out, "w", encoding="utf-8") as f:
        for key, word, freq in rows:
            f.write(f"{key}\t{word}\t{freq}\n")
    print(f"wrote {len(rows)} entries to {out}")

if __name__ == "__main__":
    main(sys.argv[1])
