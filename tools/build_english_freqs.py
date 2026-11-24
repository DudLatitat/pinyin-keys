#!/usr/bin/env python3
"""Re-ranks app/src/main/assets/en_words.txt with real usage frequencies.

Word list: AOSP LatinIME (Apache 2.0), as built by build_english_dict.py (spellings and
capitals come from there). Frequencies: wordfreq 3.1.1 (https://github.com/rspeer/wordfreq),
data licensed CC BY-SA 4.0, which mixes subtitles, chat/social media, news and books, so
everyday chat words ("lol", "gonna") rank realistically.

Run (with wordfreq installed):  python3 tools/build_english_freqs.py
Frequency column: (zipf - 1) * 40, clamped to 1..255 (zipf 7.4+ = 255, zipf 3 = 80).
Common chat words missing from LatinIME are added, except likely misspellings (a much
more common word is one edit away, or adding an apostrophe gives a word: "dont").
"""
import re, sys
from wordfreq import zipf_frequency, top_n_list

PATH = "app/src/main/assets/en_words.txt"
base = {}
for line in open(PATH, encoding="utf-8"):
    w, f = line.rstrip("\n").split("\t")
    key = w.lower()
    if key not in base or int(f) > base[key][1]:
        base[key] = (w, int(f))

def zipf(w):
    return zipf_frequency(w, "en")

def scaled(z):
    return max(1, min(255, round((z - 1.0) * 40)))

LETTERS = "abcdefghijklmnopqrstuvwxyz"
def edits1(w):
    out = set()
    for i in range(len(w) + 1):
        if i < len(w): out.add(w[:i] + w[i + 1:])
        if i + 1 < len(w): out.add(w[:i] + w[i + 1] + w[i] + w[i + 2:])
        for c in LETTERS:
            if i < len(w): out.add(w[:i] + c + w[i + 1:])
            out.add(w[:i] + c + w[i:])
    out.discard(w)
    return out

rows = {}
for key, (w, latin_f) in base.items():
    z = zipf(key)
    # Not seen by wordfreq: a rare word, ranked by its old LatinIME value.
    if z == 0: z = 1.0 + latin_f / 255 * 1.5
    rows[key] = (w, scaled(z))

added = 0
WORD = re.compile(r"^[a-z]{2,}$")
for key in top_n_list("en", 100000):
    if key in rows or not WORD.match(key):
        continue
    z = zipf(key)
    if z < 3.0:
        continue
    if any(key[:i] + "'" + key[i:] in base for i in range(1, len(key))):
        continue  # "dont" -> "don't" is a fix, not a word
    if any(e in base and zipf(e) >= z + 1.0 for e in edits1(key)):
        continue  # "teh" (the), "recieve" (receive): misspellings
    if any(key[:i] in base and key[i:] in base and min(zipf(key[:i]), zipf(key[i:])) >= 4.5
           for i in range(1, len(key))):
        continue  # "aswell", "atleast": two words run together
    rows[key] = (key, scaled(z))
    added += 1

with open(PATH, "w", encoding="utf-8") as out:
    for w, f in sorted(rows.values(), key=lambda r: -r[1]):
        out.write(f"{w}\t{f}\n")
print("words:", len(rows), "added:", added)
