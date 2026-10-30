# Release checklist

> **Before any real public launch**: `website`'s Impressum and privacy pages still carry
> obviously-fake draft placeholder text (not real legal name/address/email/VAT ID - a deliberate
> choice made during v0.1.0 prep, tracked, not forgotten). Do not point a real audience at this
> app until those are replaced with real information. This blocks a *public* launch, not internal/
> test releases.

## What's automated (`.github/workflows/release.yml`, tag-triggered on `v*`)

1. Push a tag (`git tag v0.1.1 && git push origin v0.1.1`).
2. `release` environment's required reviewer approves the `build` job (signs `fdroidRelease` +
   `githubRelease` with the production keystore, inside a pinned container).
3. Required reviewer approves the `release` job (drafts a GitHub Release with both APKs + SHA-256
   checksums attached, an auto-generated English changelog, and opens a PR in `website` updating
   `public/android/latest.json`).
4. **Not automated, do by hand before publishing the release**: add the short German summary
   where the draft release notes say `TODO: kurze deutsche Zusammenfassung`, then merge the
   `website` PR, then click "Publish release" on the still-draft GitHub Release.
5. `core`'s own `release.yml` (same tag-triggered + `release`-environment pattern) publishes to
   Maven Central the same way - a separate tag in the `core` repo, not implied by android's own
   tag.

## Manual smoke test, on a real device, before publishing

The automated build only proves the APK compiles, signs, and installs - not that it works. Do
this against the actual `app-github-release.apk` (or `app-fdroid-release.apk`) from the draft
release, on real hardware, not just the emulator these were developed against.

1. **Fresh install.** Uninstall any existing copy first (`adb uninstall org.squaddle` or manually)
   - a fresh install is the only way to exercise onboarding and the first-run POST_NOTIFICATIONS
     prompt timing for real.
2. **Onboarding.** Create a new identity, confirm the 24-word seed displays, complete setup.
   Confirm the rooms list loads (empty state).
3. **Join via link.** From a second device/browser, open an invite link for a room created by
   another identity (`core/cli` harness, or a second phone) - confirm the App Link opens directly
   into the join confirmation screen (not a browser disambiguation sheet - this is what
   `assetlinks.json`'s real fingerprint, landed this release prep, is actually for). Join, confirm
   the room appears with its real content.
4. **Push.** With the app backgrounded, have another client post an idea/comment/event in a room
   this identity is in. Confirm a real system notification arrives (not just "trigger sync now" -
   that's debug-only and won't exist in this release build), grouped correctly per room, and
   tapping it opens the right room.
5. **Backup/restore.** Settings -> Create backup (pick a real location via the SAF picker, not
   just "cancel"); then, on a second fresh install (or after clearing app data), Settings ->
   Restore from backup with the same file + passphrase. Confirm the room list comes back.
6. **Self-update, 0.1.0 -> a 0.1.1-test build** (`github` flavor only - skip for `fdroid`, whose
   `SelfUpdateChecker` is permanently a no-op by design):
   - Install the real `v0.1.0` `app-github-release.apk` first.
   - Build and tag a throwaway `v0.1.1-test` so the release workflow produces a second, newer
     signed APK (same production keystore - a real version bump is what makes the self-update
     path meaningful to test, a locally-signed debug APK isn't).
   - Point `website`'s `public/android/latest.json` at that `v0.1.1-test` build (the automated PR
     does this for a real tag; for a throwaway test tag, edit it by hand and don't merge/keep that
     change).
   - On the device running `v0.1.0`: Settings -> Check for updates -> confirm "Version 0.1.1-test
     is available" appears with real notes -> Download & install -> confirm the SHA-256
     verification passes (it fails loudly, not silently, on a mismatch - check this by briefly
     pointing `sha256` at a wrong value first, confirm the error dialog, then fix it and confirm
     the real path) -> confirm the system's own install-confirmation UI appears and completes the
     update.
   - Revert `website`'s `latest.json` back afterward - this step's whole point was a throwaway
     test tag, not a real release.

## Verifying core resolves from Maven Central with no token

Confirms android's own dependency on `org.squaddle:core` doesn't secretly depend on the
composite-build substitution (`SQUADDLE_CORE_DIR`) to resolve - a real consumer, cloning this repo
fresh with no sibling `core` checkout and no Maven credentials configured anywhere, must be able to
build it.

```bash
# A throwaway clone, not your working checkout - SQUADDLE_CORE_DIR must be genuinely unset, not
# just unexported in your current shell.
git clone --depth 1 https://github.com/squaddle-org/android.git /tmp/android-clean-check
cd /tmp/android-clean-check
unset SQUADDLE_CORE_DIR
./gradlew :app:assembleFdroidRelease --no-build-cache
```

A failure here (dependency not found, or a prompt for Maven credentials) means `core`'s Central
Portal publish either hasn't landed yet or hasn't finished propagating (Central Portal's own
"available for download" step can take 10-30 minutes after a successful publish - its own
documented behavior, not a bug) - wait and retry before assuming something's actually broken.
