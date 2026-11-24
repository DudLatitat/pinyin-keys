#!/usr/bin/env python3
"""Builds app/src/main/assets/en_lm.bin: a small word-sequence model (1-, 2- and 3-word counts).

Source: Tatoeba English sentences (https://tatoeba.org), licensed CC BY 2.0 FR.
Run:  python3 tools/build_ngram_lm.py eng_sentences.tsv.bz2

Autocorrect uses it to ask "how likely is this word after the previous two?" (stupid
backoff), so it can tell "from" from "form" by context. Words are lowercased; "^" marks
the start of a sentence. Rare sequences are dropped to keep the file small.

Binary format (big-endian): magic "PKLM", int version=1, int total unigram count, int N,
then N sorted 64-bit keys, then N 32-bit counts. A key is the FNV-1a 64-bit hash of the
UTF-8 n-gram text with words joined by single spaces (see NgramModel.kt).
"""
import bz2, re, struct, sys
from collections import Counter

WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
SKIP = {"tom", "tom's", "mary", "mary's", "john", "john's", "ken", "jane", "jack", "mike", "bob",
        "sami", "sami's", "layla", "layla's", "fadil", "salima", "yanni", "rima", "dania", "ziri",
        "skura", "mennad", "baya", "tatoeba"}
uni, bi, tri = Counter(), Counter(), Counter()
with bz2.open(sys.argv[1], "rt", encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        for clause in re.split(r"[.!?;:\"()]+", parts[2]):
            words = [w.lower() for w in WORD.findall(clause)]
            if not words:
                continue
            toks = ["^"] + words
            for i, w in enumerate(toks):
                uni[w] += 1
                if i >= 1: bi[toks[i - 1] + " " + w] += 1
                if i >= 2: tri[toks[i - 2] + " " + toks[i - 1] + " " + w] += 1

def has_skip(k): return any(w in SKIP for w in k.split(" "))
table = {}
for k, c in uni.items():
    if c >= 2 and not has_skip(k): table[k] = c
for k, c in bi.items():
    if c >= 2 and not has_skip(k): table[k] = c
for k, c in tri.items():
    if c >= 3 and not has_skip(k): table[k] = c

def fnv(s):
    h = 0xcbf29ce484222325
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h - (1 << 64) if h >= (1 << 63) else h  # as a signed Java long

rows = {}
for k, c in table.items():
    h = fnv(k)
    rows[h] = max(rows.get(h, 0), min(c, 2**31 - 1))
keys = sorted(rows)
total = sum(c for k, c in uni.items() if k != "^")
with open("app/src/main/assets/en_lm.bin", "wb") as out:
    out.write(b"PKLM" + struct.pack(">iii", 1, total, len(keys)))
    out.write(struct.pack(">%dq" % len(keys), *keys))
    out.write(struct.pack(">%di" % len(keys), *(rows[k] for k in keys)))
print("unigrams", sum(1 for k in table if " " not in k), "entries", len(keys), "tokens", total)
