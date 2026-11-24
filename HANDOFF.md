# Handoff: Pinyin Keys (Android keyboard)

Read **CLAUDE.md first**. Its privacy and public-repo rules override everything here.

## What it is
A personal Android keyboard (IME) for one owner who is learning Mandarin and chats in apps
like WeChat. The owner types English. The keyboard shows and inserts casual Mandarin with
pinyin, and also helps them practise typing it. The owner is new to Android, so keep the
code readable and add short comments for non-obvious Android concepts.

## Status
- **Current version:** 1.5.0 (glide typing, undo, one-handed, emoji search). Releases are on GitHub Releases, built and
  signed by CI. Everything listed below is built, and the owner has tested most of it on
  their phone.
- **Work branch:** `claude/milestone-1-translation-jeovyb`. PR #1 into `main` is open, and
  the owner merges it themselves.
- **Your job:** push only to that branch. Don't open new PRs unless the owner asks.

## Features (where to look)
| Feature | Files |
|---|---|
| IME service: all state, typing, autocorrect flow, clipboard, warm-up | `PinyinKeysService.kt` (large; splitting it is a good cleanup) |
| All keyboard UI in Compose: keys, toolbar, suggestion/candidate/correction/paste bars, Assist and Practice panels, themes | `KeyboardUi.kt` |
| Emoji, clipboard and edit panels; number pad | `Panels.kt`, `Emoji.kt`, `Clips.kt`, `NumberPad.kt` |
| **Assist (助):** 译 Translate sends the English, then shows one card per sentence (hanzi, pinyin, English). Tapping a card shows word chips (meaning, tap to hear) and "Why?". Insert replaces the English with the Chinese. ☆ saves the sentence, 🔊 speaks it (`Speech.kt`, phone TTS). | `Translator.kt`, `translate/*`, `AssistState.kt` |
| **Tone button:** 随 casual → 俏 slang → 敬 polite. Each tone is cached separately. | `translate/Prompts.kt` |
| **解 Explain:** copy a Chinese message, tap 解. Shows sentences with pinyin and English, plus a suggested reply. | same |
| **Practice (练):** the Chinese is hidden, and the owner types each syllable in pinyin. Tones are optional (a setting can require them). | `Practice.kt` |
| **Guided typing:** after translating, tap 中. The English is cleared and the owner types the Chinese in pinyin with the cards still visible; each card shows ✓ when done. | service + `KeyboardUi.kt` |
| **Chinese typing (中/En key):** offline pinyin → characters. Picks whole phrases by DP segmentation. Dictionary data from AOSP PinyinIME (Apache 2.0). | `PinyinDictionary.kt` |
| **English suggestions and autocorrect:** offline. Wordlist from AOSP LatinIME (Apache 2.0). Corrections use where the finger touched each key plus the owner's learned per-key touch bias. Also apostrophe fixes, learned fixes, user words, and a "keep as typed" option. | `EnglishDictionary.kt`, `TouchTracker.kt` |
| **Next-word and phrase prediction:** learned word pairs plus a phrase table from Tatoeba (CC BY 2.0 FR). | `NextWords.kt`, `assets/en_phrases.txt` |
| **Glide typing, undo/redo, one-handed mode, emoji search** | `Glide.kt`, `Undo.kt` (InputConnection wrapper), `EmojiSearch.kt` (`assets/emoji_names.tsv`, emojibase-data MIT), `KeyboardUi.kt` |
| **Saved sentences:** stored in Room and browsed in the app | `saved/*` |
| **Settings app:** API key, base URL and model; theme; typing options; "What I've learned"; screenshot opt-in | `SetupActivity.kt`, `Settings.kt` |

## How translation works (speed matters to the owner)
1. Split the text into sentences locally.
2. For each sentence, check `assets/phrasebook.tsv` (about 430 casual sentences, instant, offline).
3. Then check `SentenceCache`: encrypted, with keys prefixed by tone.
4. Send only the misses to the Claude Messages API in a single request:
   - Raw `HttpURLConnection`, SSE streaming.
   - Structured output (`json_schema`), effort low, thinking disabled.
   - Default model `claude-haiku-5-5`, and the owner can change it in settings.
   - Cards appear as they stream, via `JsonItemScanner`.
5. Word breakdown and "Why?" are fetched only when a card is tapped, then cached.

Result: about 2 s for new text, instant for repeats.

The **owner declined "translate-ahead"** (sending text in the background). Translation stays tap-only.

## Owner-approved exceptions to the base privacy rules (all in CLAUDE.md)
- Warm-up `GET /v1/models` when the keyboard opens, at most once a minute, with no text.
- Clipboard history of the last 15 copies, encrypted, skipping sensitive clips and password fields.
- Opt-in screenshot suggestion: only the newest screenshot from the last couple of minutes.
- On-device learning:
  - word pairs, learned typo fixes, user words and word counts;
  - per-letter touch-offset averages.
  - The owner can view and delete all of this in "What I've learned".
- Password and no-learning fields always get a plain keyboard that reads, sends and stores nothing.

## Build and release
- **CI:** `.github/workflows/build.yml` on GitHub Actions. There is no Gradle wrapper; CI uses
  Gradle 8.11.1 through `gradle/actions/setup-gradle`.
- **Toolchain:** Kotlin 2.1.0, AGP 8.7.3, Compose BOM 2024.12.01, minSdk 26, targetSdk 35.
- **Signing:** keystore and passwords come from Actions secrets (`SIGNING_*`). Never commit them.
- **Publishing:** every signed build publishes a **full release** (the owner wants no
  pre-releases) tagged `v<versionName>`, if that tag doesn't exist yet. The release body comes
  from `RELEASE_NOTES.md`.
- **To ship a change:**
  1. Bump `versionName` in `app/build.gradle.kts`.
  2. Add a "New in x.y.z" section at the top of `RELEASE_NOTES.md`.
  3. Commit with the noreply identity and push.
  4. Check the run with the GitHub MCP tools (`actions_list`, `get_job_logs`), not the gh CLI.
- **No local Android SDK here:** CI is the compiler. If a build fails, read the job log for
  `e:` lines, fix the code and push again.
- **R8/proguard:** `proguard-rules.pro` strips `Log.d` and `Log.v`, keeps serialization, and
  silences warnings about Tink annotations.

## Known gaps / ideas not done
- Unit tests (`app/src/test/.../KeyboardLogicTest.kt`, run in CI before every build) cover
  autocorrect, pinyin phrases, Practice matching, tone detection. Add more as you go.
- `PinyinKeysService.kt` is over 1,200 lines and should be split (Chinese composing,
  English/autocorrect, clipboard, assist).
- Lighter Compose redraws while typing were proposed but not done.
- Possible later features: flashcards from saved sentences; a privacy self-review pass.

## Working with the owner
- They test on their phone and send screenshots or videos. Reply in plain language and tell
  them exactly what to try after each release.
- Before every commit:
  - check the diff for secrets or identifying details;
  - make sure examples use generic made-up sentences;
  - leave out any device model, location or personal names.
- If the owner shares a screenshot that contains an API key, tell them to rotate the key, and
  never copy the key anywhere.
