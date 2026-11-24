A personal Android keyboard that shows Chinese and pinyin for the English you type.

**Install:** download `pinyin-keys-<version>.apk` below, open it on the phone, then turn on
Pinyin Keys in the system keyboard settings. Add your Claude API key in the Pinyin Keys app.

## New in 1.11.0
- Fixed: the Paste button now comes back after you copy something or take a screenshot
  and the keyboard closes and reopens (switching apps); it's offered until you paste or
  type (copies within the last minute, screenshots within two).
- Chinese for the word you're typing: the suggestion bar shows e.g. 吃 chī while you type
  "eat". Tap it to hear it, hold it to type the Chinese instead. Offline (CC-CEDICT).
- Emoji suggestions: typing "pizza" offers 🍕 next to the suggestions; tap to add it.
- Learned capitals: names you write with a capital in the middle of a sentence are
  capitalised for you after twice.
- Password fields: no emoji button.
- The keyboard's buttons use clean line icons instead of emoji.
- Under the hood: the keyboard's main file is split into smaller parts by feature.

## New in 1.10.0
- Invisible key resizing: a tap near the edge between two keys becomes the letter that
  makes a far more likely word (after "th", a tap between E and R gives E). A tap near the
  middle of a key always gives that key. In the typing test this stops about a quarter of
  fast-typing slips before they happen; words right overall: 98.1% careful, 92.1% fast.

## New in 1.9.0
- Suggestion bar like Gboard's: the middle word is exactly what space will type, shown in
  bold accent colour when it will replace your word; what you typed is on the left in
  quotes (tap to keep it), another option on the right.
- Autocorrect understands context: a word-sequence model (built from ~2 million example
  sentences, stored in the app and read in place, so it uses almost no memory) tells it
  which word fits after the previous two. In the built-in typing test, typos fixed went
  from 90% to 93% (careful) and 84% to 87% (fast), still with no correct words changed.
  The app is about 14 MB bigger because of it.

## New in 1.8.0
- Tidier app: a short menu (Setup, Translation, Typing, Look, Practice, Clipboard, What
  I've learned, Saved sentences), each on its own page. Themes are a grid of previews,
  key height is a 3-way switch, and the API URL/model are under "Advanced".
- "What I've learned" has tabs (Fixes / Words), search, newest first, "Show more",
  and "Clear this list".
- Learned fixes are much stricter: a fix is only used after you've made it twice, unused
  fixes fade after a couple of months, implausible ones (like "v → so") and ones
  autocorrect already makes aren't kept, and undoing a fix forgets it. Your existing
  list is cleaned up once when you update (it may take a few seconds the first time).
- Fewer odd word splits ("absorbsion" no longer becomes "absorbs ion").
- Autocorrect is now also checked against ~4,000 real common misspellings on every build.

## New in 1.7.0
- Much better autocorrect (measured with a built-in typing test: fast, sloppy typing went
  from about 55% of typos fixed to 84%, with no correct words changed):
  - Real word frequencies from everyday writing and chat (so "lol", "gonna" are words and
    common words beat rare look-alikes).
  - Missed, doubled and swapped letters are fixed more readily ("helo", "tommorow", "becuase").
  - Several slips onto neighbouring keys in a long word ("domerhinf" → something).
  - Run-together words: "ofthe" → "of the", "thebcat" → "the cat"; a stray space inside a
    word is removed ("th e" → "the").
  - Half-typed words finish on space ("someth" → something), and a lone "u"/"o" tapped
    next to I becomes "I".
- Better next-word prediction from a phrase table about twice as big, with counts, so
  common phrases rank properly and a pair you typed once doesn't push them out.
- Stricter learning: a word counts as yours after 3 uses (was 2); your picks still come first.
- Terminal apps: the clipboard button, Paste and clipboard history now work there.

## New in 1.6.2
- Terminal and SSH apps: typing now works. In fields that want raw key presses, each key
  is sent as a real key press, and backspace sends a Delete key when the app doesn't share
  its text with the keyboard. Those fields get the plain keyboard (nothing learned).

## New in 1.6.1
- Fixed: the next word sometimes got a capital letter in the middle of a sentence. Capitals
  after a space are now decided from the text just before it (only after . ? ! or at the
  start), and picked suggestions and smart punctuation follow the same rule.

## New in 1.6.0
- Fixed: hitting a number by mistake inside a word ("h3llo") no longer stops suggestions
  and autocorrect; it's corrected like any other slip. Real numbers ("2nd") are left alone.
- Firmer learning from your corrections: pick a different word than you typed and it's
  shown first next time (and used for autocorrect if what you typed isn't a word).
  Undoing an autocorrect also forgets any earlier pick for that word.
- Learns from sending: new words you send without correcting become your words right away.
- "Learned so far" counter in the app's "What I've learned" section.

## New in 1.5.0
- Glide typing: slide across the letters of a word without lifting your finger. A trail
  shows your path; the other likely words appear above the keys (tap to swap), and
  backspace right after removes the whole word. Can be turned off under Typing.
- Undo / redo: ↶ in the top bar (and Undo / Redo in the ✎ panel) takes back the last word,
  paste, correction or Insert.
- Smarter punctuation: "hi ," becomes "hi, " after any word, and "im" → "I'm", "i'll" → "I'll".
- Shortcuts: hold space to switch keyboards, hold comma for Pinyin Keys settings.
- One-handed mode: ✎ → One-handed. Arrows move the keys to the other side, ⤢ goes back.
- Emoji search: 🔍 in the emoji panel, type a word ("laugh", "heart", "cat").

## New in 1.4.0
- Tone colours: pinyin on cards, word chips and saved sentences is coloured by tone
  (1st red, 2nd orange, 3rd green, 4th blue, neutral grey). Switch off under Practice.
- Key feedback settings: "Vibrate on key press" (on) and "Sound on key press" (off), under Typing.
- More reliable: what the keyboard learns is saved every 30 words, not only when it closes.
- Faster settings: the encrypted settings file is opened once instead of on every save.
- Automatic checks: autocorrect, pinyin, Practice and tone logic are tested on every build.

## New in 1.3.0
- Learns your touch: how far off-centre you hit each key, so corrections fit your fingers.
- Learns from erasing: delete a misspelled word and retype it, and that typo is fixed for
  you next time. Undoing a correction also forgets a wrong learned fix.
- Your phrases: two-word phrases you type often appear as one-tap suggestions.
- "What I've learned" in the app: see and remove learned typos and words, or forget
  everything. Long-press a suggestion to forget that word.
- Faster: dictionaries load in the background at start-up, and the keyboard asks the app
  you're typing in fewer questions.

## New in 1.2.0
- Touch-aware autocorrect: uses where your finger actually landed on each key, so slips
  onto a neighbouring key are fixed far more reliably (blpw → blow, fpod → food).
- Learns your fixes: when you correct a typo by tapping a suggestion, it's fixed
  automatically next time.
- Learns your words: slang and names you type a couple of times stop being "corrected".
- Fixes two slips in longer words, and uses the sentence so far to pick the right word.

## New in 1.1.8
- Going back to a finished word (backspace into it, or tap at its end) brings back its
  suggestions and autocorrect, instead of starting fresh.

## New in 1.1.7
- Fixed keys (like "o") sometimes staying stuck highlighted after typing.

## New in 1.1.6
- Fewer missed taps: the gaps between keys now count as part of the nearest key.
- Settings: key height (Normal / Tall / Extra tall) and a switch to hide the number row.

## New in 1.1.5
- Fast typing is more accurate: every key now types the instant it's touched, so quick
  overlapping taps no longer come out in the wrong order ("teh"). Holding a key still
  offers alternates, and sliding the space bar still moves the cursor.
- Autocorrect no longer loses track of the word when typing quickly.

## New in 1.1.4
- New 俏 slang tone for very casual texting (哈哈, 嗯嗯, 咋了, 绝了...). The tone button
  cycles 随 casual → 俏 slang → 敬 polite.

## New in 1.1.3
- Always know which mode you're in: the space bar says "English" or "拼音", the 中/En key
  lights up in Chinese mode, and a big 中 / EN flashes when you switch.

## New in 1.1.2
- Keys shrink slightly and spring back when pressed.
- Smarter word completions: they use the sentence so far ("I miss y" → you) and favour
  words you type often.
- Double-tap shift for caps lock.
- Punctuation after a picked suggestion removes the extra space ("word." not "word .").
- Space on the symbols page returns to letters.
- Holding backspace deletes whole words after about 1.5 seconds.

## New in 1.1.1
- Themes: System, Dark, Light, Black (AMOLED), Midnight, Sakura, Jade. Pick one in the
  Pinyin Keys app under Theme.
- Removed the small digit hints on the top letter row (holding a letter still gives its digit).

## New in 1.1.0
- 解 Explain: copy a Chinese message, tap 解 to see each sentence with pinyin and English,
  plus a suggested reply (Insert types it, or tap 中 to type it yourself).
- 随/敬: switch translations between casual and polite (for elders and strangers).
- Slide along the space bar to move the cursor.
- Hold a key for more: top-row digits, accents (é, ü, ñ...), and punctuation on the "." key.
- Phrasebook grew to ~430 common sentences that translate instantly, offline.

## New in 1.0.13
- Top bar buttons are evenly spaced.
- Hold an emoji to keep typing it.

## New in 1.0.12
- Word chips show each word's English meaning (这 · zhè · this); tap a chip to hear it.

## New in 1.0.11
- Sentence cards: smaller 🔊 and ☆ buttons stacked on the right, copy button removed,
  so the sentence gets more room.

## New in 1.0.10
- Type it yourself: after translating, tap 中 — the English is cleared and you type the
  Chinese in pinyin while the cards stay visible. Matching characters come first in the
  suggestion bar, and each card shows ✓ when you've typed that sentence.

## New in 1.0.9
- 🔊 on each card reads the Chinese aloud with the phone's own voice.
- Chinese typing suggests the whole phrase first: "xiexiehahahaha" → 谢谢哈哈哈哈,
  "woxiangnile" → 我想你了.
- Suggestions animate as they change; after an autocorrect the bar shows "teh → the"
  for a moment (tap to undo).

## New in 1.0.8
- Top bar uses icons: 助/练 (Assist/Practice), 译 (Translate), 📋 (Paste).
- Translation cards stay visible while typing in 中 mode; ⧉ on a card copies its pinyin.

## New in 1.0.7
- Number pad for number, phone and date fields (amounts, PINs, codes), with the app's
  Done/Next/Go key and decimal, minus or phone symbols where the field allows them.

## New in 1.0.6
- Faster Translate:
  - A built-in phrasebook of ~140 common chat sentences translates instantly, offline.
  - Every translated sentence is remembered on the phone; repeats are instant and free.
  - Only new sentences are sent, and replies are shorter.
  - The connection to the translation service is set up when the keyboard opens.

## New in 1.0.5
- Tapping a suggestion keeps the suggestion bar open with next-word guesses.

## New in 1.0.4
- Foldable inner screen: the keyboard now spans the screen (centred, ~88% wide) with
  slightly taller keys, instead of a half-width keyboard beside an empty area.

## New in 1.0.3
- Smarter next-word suggestions that use the last two words of your sentence
  ("how are" → you, "I'm going" → to), from a built-in table of common phrases.
- Learns your own phrases as you type and puts them first. Stays on the phone.

## New in 1.0.2
- Faster suggestions (worked out in the background, so typing never waits).
- Contractions: "arent" suggests and corrects to "aren't", "dont" to "don't", etc.
- Keep what you typed: an unknown word is offered in "quotes"; tap it to keep it. Undoing an
  autocorrect also stops that word from being corrected again.
- Screenshot Paste: finds the screenshots folder on more phones, and shows before suggestions.

## New in 1.0.1
- Word suggestions while typing, next-word suggestions, and autocorrect (backspace undoes it).
- The top bar changes with what you're doing: suggestions, a Paste button after copying, or tools.
- Clipboard keeps your last 15 copies, including images.
- Optional: offer to paste a screenshot you just took.

## Features
- **Assist:** tap Translate to see each sentence in casual Mandarin with pinyin; tap a card
  for the word breakdown and "Why?"; Insert replaces your English with the Chinese.
- **Practice:** the Chinese is hidden; type each syllable in pinyin to earn the character.
- **Chinese typing (中/En):** type pinyin and pick characters from suggestions, offline.
- **Saved sentences:** star a sentence to keep it; browse them in the app.
- **Emoji, clipboard (last 15 copies, pinning, Paste button) and text-editing panels.**
- Keyboard: number row, key preview, fast backspace, auto-capitals, double-space period,
  wide layout for foldable inner screens.

## Privacy
Text is sent to the translation API only when you tap a button. Settings, saved sentences
and clipboard history stay encrypted or private on the phone. No accounts, analytics or ads.
