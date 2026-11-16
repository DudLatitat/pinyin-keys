# Handoff: WeChat Pinyin Keyboard (Android)

## Status (start here)
- Done: repo, rules, Milestone 1 code (Compose QWERTY IME, switch-back key, setup
  screen) and a CI workflow that builds a debug APK and publishes it as a prerelease.
- Milestone 1 tested in WeChat by the owner: works. Signed release builds work in CI.
- Milestone 2 code written: settings screen (API key in EncryptedSharedPreferences, base URL,
  model), Translate button, sentence cards, Insert Chinese. Raw HTTPS to the Messages API with
  structured outputs (json_schema) and effort=low. Tested by the owner in WeChat: works.
- Milestone 3 code written: tap a card for structure chips, "Why?" fetched on tap and cached
  encrypted on the phone (SentenceCache, keyed by SHA-256 of the sentence). Tested: works.
- Speed: thinking disabled; Translate returns only en/hanzi/pinyin. Chunks + syllables load
  when a card is opened (cached like Why). Translate went from 6-9 s to about 2 s.
- Milestone 4 code written (Practice.kt + PracticePanel): Assist/Practice toggle, syllable
  matching (tones optional, setting to require them; v or u for ü), auto punctuation, Hint,
  Restart. Backspace in Practice only edits the pinyin buffer. Tested by the owner: works.
- Milestone 5 code written: ☆ on each Assist card saves en/hanzi/pinyin/chunksJson/savedAt to
  Room (saved/SavedSentences.kt); "Saved sentences" screen in the app with Delete. Tested: works.
- Keyboard polish (written, untested): number row on top, key preview popup, held backspace
  repeats, tap haptics, auto-capitalize via getCursorCapsMode, double-space period, shift
  cycles off/one/lock. Wide screens (>= 600dp): keys centred at 88% width, taller (KeyHeights).
- Chinese typing mode ("En/中" key): pinyin composing text + candidate bar from
  PinyinDictionary (exact, prefix completions, then leading-part matches). Space takes the
  top candidate, enter keeps raw letters, Chinese punctuation in this mode.
- Look: restyled like a standard phone keyboard (KbColors in KeyboardUi.kt). Globe key removed
  at the owner's request; switching keyboards uses the system's keyboard button.
- Speed: keys fire on touch-down (pointerInput), profileinstaller added, auto-caps only asks
  the app after non-letters, HTTP connection reused (no disconnect), and Translate streams
  (SSE) so cards appear one by one (JsonItemScanner). Insert waits for the full result.
- Toolbar icons open panels in place of the keys (Panels.kt): emoji (Emoji.kt, recents),
  clipboard (reads the clip only when the panel is opened; pinned snippets stored encrypted;
  no background clipboard history, per CLAUDE.md), text editing (arrows, select/cut/copy/paste),
  settings shortcut. Clipboard panel is hidden in password fields.
- Emoji/clipboard restyled like the owner's default keyboard (panel header replaces toolbar,
  big emoji grid, clipboard card grid). "📋 Paste" chip: on keyboard open, only the clip's
  timestamp (ClipDescription) is checked; the text is read only when Paste is tapped.
  Clipboard history (owner's decision, CLAUDE.md updated): last 15 copies captured with
  OnPrimaryClipChangedListener; text in encrypted prefs, images copied to filesDir/clips and
  pasted via commitContent + FileProvider. Sensitive clips and password fields are skipped.
- Screenshot suggestion (opt-in setting + READ_MEDIA_IMAGES): newest image in a Screenshots
  folder from the last minute is offered via the Paste chip (Screenshots.kt); a MediaStore
  ContentObserver runs only while the keyboard is shown. Copied only when Paste is tapped.
- Ribbon: suggestions while typing / next-word after space / Paste chip with preview / tools
  (‹ shows tools). EnglishDictionary (assets/en_words.txt from AOSP LatinIME, Apache 2.0)
  gives completions + typo corrections (keyboard-neighbour edits); autocorrect on space
  (setting, on by default; backspace undoes). NextWords predicts from the last two words:
  learned-on-device first, then assets/en_phrases.txt (Tatoeba, CC BY 2.0 FR, built by
  tools/build_phrase_table.py), then common starters.
- Translate speed: Translator.kt splits sentences locally, answers from Phrasebook
  (assets/phrasebook.tsv, hand-written) or SentenceCache ("tr:" entries), and sends only
  misses as numbered lines (model returns hanzi+pinyin per line). warmUp() = GET /v1/models
  on keyboard open (no text; CLAUDE.md exception).
- 1.1.0: 解 explainChinese (clipboard on tap -> sentences + reply), polite tone (Settings.polite,
  separate cache keys, no phrasebook), space-bar cursor slide and long-press alternates
  (Key onCursor/alternates in KeyboardUi.kt), phrasebook ~430 lines.
- App icon: adaptive vector (white key with 中 on blue), with a monochrome layer.
- Releases (owner: no prereleases): every signed CI build publishes a full release
  `v<versionName>` with RELEASE_NOTES.md, skipping if that version already exists. Bump
  versionName in app/build.gradle.kts and update RELEASE_NOTES.md for each release.
  versionCode = GitHub run number.
  - Signing: keystore generated; CI signs the release when SIGNING_* Actions secrets are set.
- No Android SDK in the cloud workspace: app code is compiled only by CI. Read build
  failures from the workflow's job annotations/logs.
- Work in this repo only. Do not read or reference any other repository.

## What this is
A personal Android keyboard app (single user, not for distribution). While typing English in a chat app such as WeChat, it shows the Chinese translation and pinyin for each sentence. It can also coach the user to type the Chinese themselves in pinyin. Tapping a sentence shows a short breakdown of its structure.

The user is a beginner in Chinese and new to Android development. Keep the code readable, and explain non-obvious Android concepts in short code comments.

Target device: a foldable phone. The layout must work on both the narrow cover screen and the wide inner screen.

## Core decisions (already made)
1. **Build a custom keyboard (IME), not an overlay.** Use `InputMethodService`. Don't use an accessibility service or screen reading.
2. **Two modes, switched with a toggle at the top of the keyboard panel:**
   - **Assist:** shows the Chinese and pinyin. An "Insert Chinese" button replaces the English in the text field with the Chinese.
   - **Practice:** hides the Chinese. The user types each syllable in pinyin, and the keyboard checks it against the target and commits the correct character when it matches. Show a "Next: nǐ"-style prompt and a hint button. This is NOT a general pinyin IME: the target sentence is already known, so matching is only against the expected syllable, and no dictionary is needed.
3. **Explanations stay out of the way.** By default each sentence card shows only the Chinese and pinyin.
   - Tap a sentence → a row of chips showing its structure, e.g. `我 · Subject` `想 · Verb` `你 · Object` `了 · New state`.
   - Tap "Why?" → one or two sentences explaining the structure. Fetch this lazily, only when "Why?" is tapped.
   - Tap the sentence again → collapse it.
4. **Casual tone by default.** The messages are casual texts to close friends and family, so translations should be natural spoken Mandarin, not textbook phrasing.

## UI reference
The user approved a mockup with these elements, from top to bottom:
- A row with the mode toggle (Assist / Practice)
- A list of sentence cards, each showing hanzi (large) and pinyin with tone marks (accent color) under it, expanding to structure chips and then "Why?"
- An action row: "Insert Chinese" (Assist) or a progress/hint bar (Practice)
- A QWERTY key grid
- Also add: a key to switch back to the previous keyboard (`switchToPreviousInputMethod()`), plus backspace, space, and enter.

## Suggested stack
- Kotlin with Jetpack Compose for the keyboard UI. Standard Gradle (Kotlin DSL) setup.
- **Compose inside an IME needs extra wiring.** The IME window has no `LifecycleOwner` or `SavedStateRegistryOwner`. Make the service implement both (plus `ViewModelStoreOwner`) and set them on the window's decor view with `setViewTreeLifecycleOwner` and its siblings before creating the `ComposeView`, or Compose crashes.
- Room for the saved-sentences table.
- Ktor or OkHttp plus kotlinx.serialization for API calls.

## Translation backend
Use the Claude API (Messages endpoint) with JSON-only output. Keep the model name in settings; default to `claude-haiku-5-5` for speed.

**Call 1: translate** (on demand; see "Open questions")
Input: the English text. Output:
```json
{
  "sentences": [
    {
      "en": "I miss you.",
      "hanzi": "我想你了。",
      "pinyin": "wǒ xiǎng nǐ le.",
      "syllables": ["wo3","xiang3","ni3","le5"],
      "chunks": [
        {"hanzi":"我","pinyin":"wǒ","role":"Subject"},
        {"hanzi":"想","pinyin":"xiǎng","role":"Verb"},
        {"hanzi":"你","pinyin":"nǐ","role":"Object"},
        {"hanzi":"了","pinyin":"le","role":"New state"}
      ]
    }
  ]
}
```
- `syllables` drives Practice mode: one entry per character, number-tone form. Matching ignores tones by default; make tone strictness a setting.
- Validate the JSON. If parsing fails, show an inline "Couldn't translate. Try again." message and never crash the keyboard.

**Call 2: why** (lazy, cached per sentence)
Input: the sentence's hanzi, its English, and its chunks. Output: 1–2 plain-English sentences on why it's structured that way, e.g. "No 'did' and no word flip; add 吗 to a statement to make a yes/no question."

**API key:** entered on a settings screen and stored with EncryptedSharedPreferences (or the Jetpack DataStore equivalent). Never hardcode it or commit it to git.

**Provider choice and swappability:** The app is used mainly in the US, and the owner chose Claude Haiku 5.5 as the default provider. In translation comparisons for English to Simplified Chinese, Claude, DeepSeek and Gemini are close; DeepSeek has a slight edge, but those benchmarks use formal text, not casual texts. Haiku is the cheapest of the three and handles the structured JSON well.
- Put all translation behind a `TranslationService` interface. `ClaudeTranslationService` is the only implementation for now.
- Keep provider-specific details (base URL, model name, auth header, request/response mapping) inside the implementation. The rest of the app only sees the `sentences` / `why` data shapes above.
- Keep the prompt text in one place, shared by all providers, so a second provider gets the same instructions.
- Don't build a second provider now. The goal is that adding one later (e.g. `DeepSeekTranslationService` plus a provider picker in settings) is a small change, not a rebuild, if the user finds Claude's phrasing off.
- A configurable base URL (entered on the phone, never committed) also allows pointing at a self-hosted relay later, for use from regions where the API is blocked.

## Reading and writing WeChat's text field
- Read: `currentInputConnection.getExtractedText(ExtractedTextRequest(), 0)`, falling back to `getTextBeforeCursor(large N, 0)`.
- Replace (Assist): delete the English (`deleteSurroundingText` or select-all followed by `commitText`), then `commitText(hanzi, 1)`.
- Test in WeChat early. Some apps return partial extracted text, so log the length (not the content) during development.

## Privacy and safety
See CLAUDE.md ("This repo is PUBLIC" and "Data stays on the phone"); those rules win over anything here.
- Send text to the API only on an explicit trigger. Never send it in the background.
- Do nothing in password or sensitive fields: check `EditorInfo.inputType` for password variations and `IME_FLAG_NO_PERSONALIZED_LEARNING`, and show a plain keyboard there.
- Don't log message content. Saved sentences are stored only when the user taps save.

## Milestones
1. **Bare keyboard:** an IME that can be enabled in settings, QWERTY in Compose, types English in WeChat, and has a switch-back key. *Done when* the user can type and send an English message in WeChat with it.
2. **Assist mode:** a Translate button sends the field's text, sentence cards render hanzi and pinyin, and "Insert Chinese" replaces the text. Settings screen for the API key.
3. **Structure and Why:** tap-to-expand chips plus a lazy, cached "Why?".
4. **Practice mode:** syllable matching, character-by-character commit, hint (reveals the next pinyin), and a progress prompt.
5. **Saved sentences:** a bookmark button on each card, stored in Room, with a simple list screen in the app. Later this could export to another study app; keep the schema simple (en, hanzi, pinyin, chunks JSON, savedAt).

Ship each milestone as a working build before starting the next.

## Answered questions
- Translation trigger: a **Translate button** only. No auto-translate after a pause.
- Explanations: **English-only** for now.
- Chinese→English mode for received messages: maybe later, **not in scope now**.

## Out of scope
- (Was out of scope; the owner later asked for it.) A Chinese typing mode with candidates
  now exists, using an offline AOSP dictionary (assets/pinyin_dict.txt, Apache 2.0).
- Voice input
- Any accounts, sync, analytics, or Play Store release
