# Manual testing (M4b)

One phone (or emulator with Play Services absent - the `foss` flavor needs none) running the app,
plus `core/cli`'s JVM harness (`cd ../core && ./gradlew :cli:installDist`, then
`cli/build/install/cli/bin/cli ...`) as a second client against the same relay. Each item below
gets its own section as it lands; sections aren't deleted once an item changes later - update in
place instead.

Prerequisites, done once:
```bash
# relay (a local build, not production, for all of the below)
cd relay && go build -o /tmp/squaddle-relay ./cmd/relay
/tmp/squaddle-relay -db /tmp/squaddle-relay.db -limits none &

# core CLI harness
cd core && SQUADDLE_SPEC_DIR=../spec ./gradlew :cli:installDist
CLI=core/cli/build/install/cli/bin/cli
```

## Item 5: UnifiedPush + WorkManager background sync

Needs [ntfy](https://ntfy.sh) (F-Droid or Play Store) installed as the UnifiedPush distributor
to exercise the real push path; without it, the periodic-fallback path below is what to check
instead.

**Push path (ntfy installed):**
1. Fresh install, complete onboarding, create a room. Confirm the system notification
   permission prompt appears (API 33+ devices/emulators only) and grant it.
2. Confirm ntfy shows a new subscription appear shortly after room creation (UnifiedPush
   registered a real endpoint) - `adb logcat | grep SquaddlePushService` is not wired (no
   logging per android/CLAUDE.md's "never log keys/seeds/invite secrets" - a bit broader here,
   but intentionally minimal logging throughout this app), so ntfy's own subscription list is
   the confirmation point.
3. From the CLI harness, join the same room (use the invite link/QR the app's Invite screen
   shows) and `idea add`:
   ```bash
   $CLI room join <relayHost> <inviteLink>
   $CLI idea add <roomRef> "Camping trip"
   ```
4. Within a few seconds, the phone should show a system notification "New idea in <room name>"
   on the "New ideas" channel - without the app having to be open or even in the foreground.
   Tap it; confirm it opens the app (not yet deep-linked to the specific room - a known,
   deliberate M4b scope cut, see RoomSyncNotifier's doc comment).
5. Repeat with `$CLI idea comment`, `$CLI event create`, `$CLI event rsvp` - confirm each posts
   to its own channel ("New comments"/"New events"/"RSVP updates").
6. Tap the bell icon in the room's top bar to mute it (toggles to a crossed-out bell).
7. Repeat step 3; confirm no notification appears, but reopening the room still shows the new
   idea (push delivery and in-app state are independent of mute).

**Fallback path (no distributor installed):**
1. Fresh install with no UnifiedPush distributor on the device (uninstall ntfy first, or test on
   a stock emulator). Complete onboarding.
2. Confirm the in-app banner "Install ntfy for instant updates..." appears at the bottom of the
   rooms list.
3. Confirm (via `adb shell dumpsys jobscheduler | grep squaddle`, or Android Studio's App
   Inspection > Background Task Inspector) that a periodic `SyncAllRoomsWorker` job is scheduled
   roughly every 6 hours.
4. From the CLI harness, create an idea in a shared room; force-run the periodic worker early
   (Background Task Inspector supports this) rather than waiting 6h; confirm the notification
   still appears via this path with no push involved at all.

## Item 6: Events + RSVP + calendar export

1. In a room, switch to the "Events" tab (empty state should read "No events yet...").
2. Open an idea, tap "Make this an event" (only visible if `canCreateEvent` - admin/owner
   always, member only if the room allows it); fill in date/time/location, Save. Confirm it now
   appears on the Events tab, sorted correctly against any other events by start time.
3. From the Events tab's own + FAB, create a second, standalone event (no `ideaId`).
4. From the CLI harness: `$CLI event rsvp <roomRef> <eventIdHex> yes` as a second user; confirm
   the phone's RSVP chip counts update after the next pull/refresh.
5. On the phone, tap Yes/Maybe/No chips on an event; confirm the selected chip highlights and
   the count moves accordingly, and that re-tapping the same chip is idempotent (no duplicate
   RSVP row - `rsvp.set` is keyed by (event, author), so this is really a core-side guarantee,
   just worth eyeballing here).
6. Tap the calendar icon on an event; confirm the device's calendar app opens pre-filled with
   title/time/location/all-day correctly, with nothing actually saved until the user confirms
   there (this app never writes calendar data itself).
7. Tap the share icon; confirm a real `.ics` file attaches (not a text blob) to whatever app is
   chosen (email, a calendar app's own "import" intent, etc.) - opening the `.ics` in a desktop
   calendar app is the strongest check that `DTSTART`/`DTEND`/`UID` are well-formed.
8. As a non-admin member in a room with "members can create events" disabled (needs a room with
   that `roomSettings` entry actually applied - not yet exposed in any UI, core-protocol-only for
   now): confirm the idea detail screen has no "Make this an event" button and the Events tab has
   no + FAB.
9. Cancel an event you created (or any event, as admin/owner); confirm it disappears from the
   list (cancelled events are filtered out, never shown struck-through).

## Item 7: Backup

1. From the rooms list, tap the gear icon (Settings - distinct from the debug-only bug icon).
2. Tap "Reveal seed"; confirm the device's biometric/PIN/pattern prompt appears and the 24 words
   only show after a real successful authentication (cancelling the prompt must leave the seed
   hidden). Tap "Hide" and confirm "Reveal seed" is required again - no "remember" window.
3. Tap "Create backup"; the system's own "save as" (SAF) picker opens - save to a real location
   (local storage, or a Drive/Nextcloud "Files" doc provider if signed into one on the device).
   Enter a passphrase; confirm "Backup saved."
4. Tap "Restore from backup", pick the file just created, enter the SAME passphrase; confirm
   "Restored N room(s)." and that every room reappears in the rooms list unchanged (entropy
   matches the signed-in account, so only `rooms[]` is re-imported - identity/device cert are
   untouched, by design, see `SettingsViewModel`'s own doc comment).
5. Repeat step 4 with the WRONG passphrase; confirm a clean "wrong passphrase..." message, no
   crash, no partial import.
6. On a second device/fresh install signed in with a DIFFERENT seed, try restoring the same
   backup file; confirm "This backup belongs to a different account..." and that nothing is
   imported.
7. Toggle "Automatic backups" on; the SAF picker opens again (a persisted file, distinct from the
   manual backup above) - choose a passphrase. Confirm `adb shell dumpsys jobscheduler | grep
   squaddle` shows the periodic `AutoBackupWorker` job. Change the interval field; confirm the
   job reschedules with the new interval without being asked for the file/passphrase again.
8. Force-run the auto-backup job early (Android Studio's Background Task Inspector); confirm the
   chosen file actually updates (its mtime changes) and restoring from it later works the same
   as a manual backup.
9. Toggle "Automatic backups" off; confirm the scheduled job is cancelled
   (`dumpsys jobscheduler` no longer lists it) and a later app restart does not silently
   re-enable it.

**Regression check:** existing push registration (`POST /v1/push`) must not interfere with
`registerDevice()`'s own device-cert publish - both run from the same `LaunchedEffect(Unit)`
cold-start block in `MainActivity.kt`'s `SignedInApp` and must both still succeed independently
(one failing must not block the other - confirmed by code review, both are caught
independently; a device offline at cold start recovers both on the next cold start).
