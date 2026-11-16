# Working on this repo

A personal Android keyboard (IME) that shows Chinese + pinyin for English typed into
chat apps, with an Assist mode and a Practice mode. Single user, not distributed.
The owner is new to Android: keep code readable and explain non-obvious Android
concepts in short comments.

## This repo is PUBLIC

Everything committed here is visible to anyone, forever (git history keeps deleted
files). Treat every commit as published.

### No identifying information in the repo
Never put any of these in code, comments, docs, tests, commit messages, branch names,
issue/PR text, or screenshots:
- Real names, nicknames, usernames, emails, phone numbers, WeChat IDs
- Names or details of family, partners or contacts
- Locations more specific than "US" / "China" (no cities, addresses, schools, workplaces)
- Exact device model, serial numbers, IMEI, Android IDs
- Server hostnames, IP addresses, domains, or relay URLs the owner runs
- Names of the owner's other private repos or apps
- Real message text. Test data and examples use made-up generic sentences
  ("I miss you.", "Did you eat?"), never anything copied from real chats.

Git author identity: commits use the GitHub noreply email, never a personal email.

### Secrets never enter the repo
- The API key is typed into the in-app settings screen and stored with
  EncryptedSharedPreferences (Android Keystore-backed). No hardcoded keys, no keys in
  `BuildConfig`, `gradle.properties`, `local.properties`, or test files.
- The signing keystore (`*.jks`, `*.keystore`) and its passwords live only in GitHub
  Actions secrets. `.gitignore` must cover `*.jks`, `*.keystore`, `local.properties`,
  `*.apk`, `*.aab`, `build/`, `.idea/`, `*.env`.
- Base URL / model name defaults are generic public values (the official API endpoint).
  A private relay URL is entered in settings on the phone, never committed.
- Before every commit, check the diff for anything in the lists above. If a secret or
  identifying detail is ever committed, stop and tell the owner: removing it needs a
  history rewrite and (for keys) rotating the key, not just a new commit.

## Data stays on the phone

- All user data (saved sentences, settings, cached "Why?" answers) is stored only in
  the app's private storage on the device. No accounts, sync, cloud backup, analytics,
  crash reporting, or telemetry SDKs.
- Set `android:allowBackup="false"` and exclude everything in `dataExtractionRules`,
  so data isn't copied to cloud backups.
- The ONLY data that leaves the phone is the text being translated / explained, sent
  to the configured translation API, and only when the user explicitly taps a button.
  Never in the background, never automatically, never to any other host.
- The only network permission use is that API call over HTTPS. No other outbound
  requests (no update checks, no remote config, no fonts or assets from the web).
- Exception (owner's decision): when the keyboard opens in a non-sensitive field, it may
  send one small request to the configured API (list models) to set up the connection
  early, at most once a minute. It carries the API key but never any typed or message text.
- Translations are cached on the phone (encrypted) so repeated sentences need no request.
- Never log message content, translations, or the API key. Log lengths and error
  types only. Strip `Log.d`/`Log.v` in release builds.
- Password and sensitive fields: if `EditorInfo.inputType` is a password variation or
  `IME_FLAG_NO_PERSONALIZED_LEARNING` is set, show a plain keyboard. Read nothing,
  send nothing, store nothing.
- Text field contents are read only on an explicit user action.
- Clipboard (owner's decision): the keyboard keeps a history of the last 15 copies (text
  and images) so they can be pasted later. It's stored encrypted (images in the app's
  private files) on the phone only and never sent anywhere. Clips Android marks as
  sensitive (e.g. from password managers) and copies made while a password field is
  focused are skipped.
- Screenshots (owner's decision, opt-in): if the owner turns on "Suggest new screenshots"
  and grants photo access, the keyboard looks only for an image in the Screenshots folder
  taken within the last minute and offers to paste it. Nothing else in the photo library
  is read, and the image is copied only when Paste is tapped.
- Next-word suggestions learn which word follows which from what the owner types. These
  word pairs stay in the app's encrypted settings on the phone and are never learned in
  password, email or web-address fields, or in fields that ask for no learning.
- Typing style is learned the same way, on the phone only: typos fixed by picking a
  suggestion, words typed repeatedly that aren't in the dictionary, and word counts.
  Tap positions on keys are used for autocorrect while typing and never stored.

## Dependencies

- Prefer AndroidX and the Kotlin standard library. Before adding a library, check it is
  maintained and has no tracking/analytics. State what was added and why.
- No Google Play Services or Firebase.

## Builds

- CI builds on GitHub Actions with standard hosted runners (free for public repos).
- APKs go to GitHub Releases, never committed to the repo.
