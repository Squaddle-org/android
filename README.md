# Squaddle for Android

Not scaffolded yet on purpose — start after `core` milestone M3 (see [spec ROADMAP](https://github.com/squaddle-org/spec/blob/main/ROADMAP.md)).

Plan:
- Compose + Material 3, single-activity, `minSdk 26`, applicationId `org.squaddle`
- Flavors `fdroid` / `github` / `play`
- App Links: intent-filter `autoVerify="true"` for `https://squaddle.org/j/*` (+ `assetlinks.json` in the website repo, include the Play App Signing fingerprint)
- Background: UnifiedPush connector → WorkManager one-shot sync; periodic fallback sync
- Seed at rest: encrypted with an Android Keystore key (optional biometric lock)
- Backup: Storage Access Framework (`ACTION_CREATE_DOCUMENT`, persisted URI permission)
- Calendar: `CalendarContract` insert intent + `.ics` share
- Deferred deep link: clipboard check on first launch, Play Install Referrer (play flavor)
