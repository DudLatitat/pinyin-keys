#!/usr/bin/env python3
"""Builds app/src/main/assets/en_words.txt from AOSP LatinIME's English word list.

Source (Apache License 2.0, (c) The Android Open Source Project):
  platform/packages/inputmethods/LatinIME, dictionaries/en_US_wordlist.combined.gz
Run:  python3 tools/build_english_dict.py en_US_wordlist.combined   (the un-gzipped file)

Output: "word<TAB>frequency" per line, most common first. Words flagged as possibly
offensive or not-a-word are dropped (they are never suggested), as is anything after
the 80,000 most common words.
"""
import sys

rows = []
for line in open(sys.argv[1], encoding="utf-8"):
    if not line.startswith(" word="):
        continue
    fields = dict(p.split("=", 1) for p in line.strip().split(",") if "=" in p)
    if fields.get("possibly_offensive") == "true" or fields.get("not_a_word") == "true":
        continue
    rows.append((fields["word"], int(fields["f"])))
rows.sort(key=lambda r: -r[1])
with open("app/src/main/assets/en_words.txt", "w", encoding="utf-8") as out:
    for w, f in rows[:80000]:
        out.write(f"{w}\t{f}\n")
print("wrote", min(len(rows), 80000), "words")
