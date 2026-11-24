#!/usr/bin/env python3
"""Builds app/src/main/assets/en_zh.tsv: the everyday Chinese for common English words.

Source: CC-CEDICT (https://www.mdbg.net/chinese/dictionary?page=cc-cedict), licensed
CC BY-SA 4.0. Chinese word frequencies to pick the most natural choice: wordfreq 3.1.1
(CC BY-SA 4.0). Run (with wordfreq[cjk]):  python3 tools/build_en_zh.py cedict_ts.u8

Output lines: "english<TAB>simplified<TAB>pinyin with tone marks", for English words in
the keyboard's word list. For each English word the CEDICT entries that translate it as
a single word are compared, preferring earlier senses and more common Chinese words.
"""
import re, sys
from wordfreq import zipf_frequency

MARKS = {"a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò", "u": "ūúǔù", "ü": "ǖǘǚǜ"}
def tone_marks(syl):
    syl = syl.lower().replace("u:", "ü").replace("v", "ü")
    if not syl or not syl[-1].isdigit(): return syl
    tone, letters = int(syl[-1]), syl[:-1]
    if tone not in (1, 2, 3, 4): return letters
    if "a" in letters: i = letters.index("a")
    elif "e" in letters: i = letters.index("e")
    elif "ou" in letters: i = letters.index("o")
    else:
        idx = [k for k, c in enumerate(letters) if c in MARKS]
        if not idx: return letters
        i = idx[-1]
    return letters[:i] + MARKS[letters[i]][tone - 1] + letters[i + 1:]

english = []
for line in open("app/src/main/assets/en_words.txt", encoding="utf-8"):
    w, f = line.rstrip("\n").split("\t")
    if w.isalpha() and w.islower() and int(f) >= 90: english.append(w)
# Grammar words have no single Chinese equivalent worth showing ("are", "of", "your").
STOP = set("""a an the and or but if of to in on at by for from with about as into onto upon than then so
is am are was were be been being do does did done have has had having will would shall should can could
may might must i me my mine you your yours he him his she her hers it its we us our ours they them their
theirs this that these those there here what which who whom whose when where why how all any some no not
nor only own same too very just also up down out off over under again further once both each few more
most other such s t don doesn didn won isn aren wasn weren hasn haven hadn wouldn shouldn couldn mustn
let lets get got go goes went gone come came make made one ones yes oh ok okay well even still yet ever
something anything nothing everything someone anyone everyone because while until though although""".split())
wanted = set(w for w in english[:30000] if w not in STOP)

ENTRY = re.compile(r"^(\S+) (\S+) \[([^\]]+)\] /(.*)/$")
SKIP = ("variant of", "surname", "see ", "abbr.", "old variant", "used in", "CL:", "classifier", "(Tw)", "(dialect)", "(old)")
best = {}
for line in open(sys.argv[1], encoding="utf-8"):
    if line.startswith("#"): continue
    m = ENTRY.match(line.strip())
    if not m: continue
    trad, simp, pinyin, defs = m.groups()
    if re.search(r"[A-Za-z0-9·，]", simp) or len(simp) > 4: continue
    if pinyin[:1].isupper(): continue  # proper nouns
    zf = zipf_frequency(simp, "zh")
    if zf < 2.5: continue
    for i, sense in enumerate(defs.split("/")):
        if i > 1 or any(s in sense for s in SKIP): continue  # only the first two senses
        for j, sub in enumerate(sense.split(";")):
            # Slang, loanwords, figurative or colloquial senses aren't the plain word.
            if '"' in sub: continue  # idioms quoted as examples
            if re.search(r"\((coll|slang|Internet|loanword|lit\.|fig\.|onom|derog|vulgar|euphemism|archaic|literary|in names)", sub, re.I): continue
            d = re.sub(r"\([^)]*\)", "", sub).strip().lower()
            d = re.sub(r"^(to|a|an|the) ", "", d).strip(" .,!?")
            if d not in wanted: continue
            score = zf - 2.5 * min(i, 3) - 0.4 * j - (0.4 if len(simp) > 2 else 0)
            if d not in best or score > best[d][0]:
                marks = " ".join(tone_marks(s) for s in pinyin.split())
                best[d] = (score, simp, marks)

# Everyday chat meanings where the dictionary's top pick is too formal or another sense.
OVERRIDES = {
    "morning": ("早上", "zǎo shang"), "phone": ("手机", "shǒu jī"), "call": ("打电话", "dǎ diàn huà"),
    "late": ("晚", "wǎn"), "hot": ("热", "rè"), "miss": ("想念", "xiǎng niàn"), "cat": ("猫", "māo"),
    "spicy": ("辣", "là"), "wait": ("等", "děng"), "night": ("晚上", "wǎn shang"),
}
for w, (h, p) in OVERRIDES.items():
    best[w] = (99, h, p)

with open("app/src/main/assets/en_zh.tsv", "w", encoding="utf-8") as out:
    for w in english:
        if w in best:
            out.write(f"{w}\t{best[w][1]}\t{best[w][2]}\n")
print("words:", len(best))
