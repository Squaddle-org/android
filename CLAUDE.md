# CLAUDE.md — android repo

Android app for Squaddle (Compose, Material 3). Protocol logic lives ONLY in `org.squaddle:core`
(sibling `../core`), never re-implemented here. Read `../spec/ROADMAP.md` (M4) and `../spec/ARCHITECTURE.md` first.

## Rules
- applicationId `org.squaddle`. Product flavors: `fdroid` (no Google deps at all, F-Droid; never
  self-updates - F-Droid's own client owns that), `github` (same GMS-free posture, but carries the
  real `SelfUpdateChecker` and is what's attached to GitHub Releases), `play` (Play Billing only).
  CI must build `fdroidRelease`/`githubRelease` without any `com.google.android.gms` / Firebase
  artifact on the classpath.
- Push via UnifiedPush; background sync via WorkManager. No FCM.
- App Links for `https://squaddle.org/j/*` with `autoVerify="true"`; the matching `assetlinks.json` lives in `../website`.
- Seed at rest encrypted with an Android Keystore key. Never log keys, seeds, invite secrets or URL fragments.
- Backups only via Storage Access Framework (user picks Drive/Nextcloud/local).
- No analytics, crash reporters or trackers.
- Local dev against an unreleased core: set `SQUADDLE_CORE_DIR=../core` → composite build (`includeBuild`).
