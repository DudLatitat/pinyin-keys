#!/usr/bin/env python3
"""Builds app/src/main/assets/en_phrases.txt: likely next words for 1- and 2-word contexts.

Source: Tatoeba English sentences (https://tatoeba.org), licensed CC BY 2.0 FR.
Download eng_sentences.tsv.bz2 from https://downloads.tatoeba.org/exports/per_language/eng/
Run:  python3 tools/build_phrase_table.py eng_sentences.tsv.bz2

Output lines: "context<TAB>next1:count next2:count ...", up to 6 next words with how often
each followed the context. The context is one lowercase word or two words joined by a
space ("^" marks the start of a sentence). Only contexts and words seen often enough are
kept, so the file stays a few MB.
"""
import bz2, re, sys
from collections import Counter, defaultdict

WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
bi = defaultdict(Counter)   # w1 -> next counts
tri = defaultdict(Counter)  # "w1 w2" -> next counts

with bz2.open(sys.argv[1], "rt", encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        # Split into clauses at sentence punctuation; each clause starts fresh.
        for clause in re.split(r"[.!?;:\"()]+", parts[2]):
            words = WORD.findall(clause)
            if not words:
                continue
            toks = ["^"] + [w if w == "I" or w.startswith("I'") else w.lower() for w in words]
            for i in range(1, len(toks)):
                bi[toks[i - 1].lower()][toks[i]] += 1
                if i >= 2:
                    tri[toks[i - 2].lower() + " " + toks[i - 1].lower()][toks[i]] += 1

# Tatoeba's example sentences use these names constantly; they'd crowd out real guesses.
SKIP = {"tom", "tom's", "mary", "mary's", "john", "john's", "ken", "jane", "jack", "mike", "bob",
        "sami", "sami's", "layla", "layla's", "fadil", "salima", "yanni", "rima", "dania", "ziri",
        "skura", "mennad", "baya", "tatoeba"}

def top(counter, min_count):
    return [(w, c) for w, c in counter.most_common(10) if c >= min_count and w.lower() not in SKIP][:6]

rows = []
for ctx, c in bi.items():
    if ctx in SKIP:
        continue
    if sum(c.values()) >= 8:
        t = top(c, 2)
        if t: rows.append((ctx, t))
for ctx, c in tri.items():
    if any(w in SKIP for w in ctx.split()):
        continue
    if sum(c.values()) >= 12:
        t = top(c, 3)
        if t: rows.append((ctx, t))
rows.sort()
with open("app/src/main/assets/en_phrases.txt", "w", encoding="utf-8") as out:
    for ctx, t in rows:
        out.write(ctx + "\t" + " ".join(f"{w}:{c}" for w, c in t) + "\n")
print("contexts:", len(rows))
