# Visual design pass — summary

No feature changes; a visual/motion/accessibility pass over the existing `foss` debug build,
done live on the Android emulator (API 34) against `relay.squaddle.org`, with the `core` CLI
harness seeding realistic multi-room, multi-language content as a second identity. Screenshots
in this folder are from that run. Commits landed per logical unit of work in the `android` repo
(see `git log` for the full sequence); nothing in `core` from this pass has been pushed.

## Before / after

| Screen | Before | After |
|---|---|---|
| Rooms list | `rooms-list-before.png` | `rooms-list-after.png` (+ `-dark`, `-de`, `-tablet`) |
| Room / ideas list | `room-ideas-before.png` | `room-ideas-after.png` |
| Idea detail | `idea-detail-before.png` | `idea-detail-after.png` |
| Members | `members-before.png` | `members-after.png` |
| Empty states | *(plain text, no illustration)* | `rooms-empty-after.png`, `room-ideas-empty-after.png`, `room-events-empty-after.png` |
| Motion | *(instant cut / plain cross-fade)* | `motion-container-transform-midflight.png` — a screenshot caught mid-transition, with the idea title visibly in flight between its list and detail position |

## Design tokens

**Color** — `ui/theme/Color.kt` (unchanged this pass; already a hand-built M3 scheme seeded from
brand coral `#FF5D3A`, light + dark, no dynamic color). Contrast re-verified by computing WCAG
ratios for every text/container pairing the new vote-pill and avatar work introduced — all ≥7:1,
well above the 4.5:1 AA floor (worst case: `onSurface` on dark-mode `primaryContainer`, 7.17:1).

**Typography** — new `ui/theme/Type.kt`. Two bundled OFL fonts (`res/font/`, license text in
`assets/licenses/`):
- **Fraunces** (SemiBold/Bold) — display/headline/title roles: room names, idea titles, screen
  headers. The one characterful voice.
- **Inter** (Regular/Medium/SemiBold/Bold) — body/label roles: comments, member names, buttons,
  anything dense or functional. Full German glyph coverage.

Sizes/line-heights/letter-spacing otherwise follow M3's own baseline `Typography()` scale — this
is a voice change, not a new scale.

**Shape** — new `ui/theme/Shape.kt`. Visibly rounder than M3 defaults across every size step
(`extraSmall` 8dp → `extraLarge` 28dp) — cards read as distinct "sticky notes" rather than flat
list rows.

**Spacing** — no new token file; existing ad hoc `dp` values (`8/12/16/24`) were kept and reused
rather than introducing a parallel spacing scale on top of them.

**Motion** — `SharedTransitionLayout` added around the whole `NavHost` (`MainActivity.kt`); the
idea title is a shared element between its list row and detail header
(`ui/room/RoomScreen.kt`'s `ideaTitleSharedElement`). Vote score changes animate a 180ms
directional slide+fade (`AnimatedContent`, M3-motion-token-range duration) instead of snapping.
Both vote buttons fire a `HapticFeedbackType.LongPress` tick. Predictive back and per-destination
enter/exit transitions were already in place from an earlier phase (M4b) and weren't touched here.

## What changed, screen by screen (commits)

1. **Edge-to-edge** (`aae9527`) — `Theme.Squaddle` hardcoded a solid coral `android:statusBarColor`;
   replaced with `enableEdgeToEdge()` + transparent system bars, auto light/dark icon contrast.
   Affects every screen.
2. **Typography + shapes** (`8221f5f`) — Fraunces/Inter type scale, rounder `Shapes()`.
3. **Vote control redesign** (`8791b8c`) — the old stacked-arrows `VoteControl` was nearly the
   full height of an idea card by itself (3 rows: up arrow / bare number / down arrow), which is
   why a dense room only showed 4-5 ideas per screen. Now a single horizontal pill that tints to
   the primary/error container on this device's own vote, animates the score, and fires haptics.
   **A full 8-idea room now fits on screen without scrolling partway through the list** (verified
   live).
4. **Empty-state illustrations** (`95504a3`) — `StickyNoteIllustration` (two tilted rounded-rect
   "notes" fanned behind a plain M3 icon, drawn with `Canvas`, no bitmap/SVG asset) reused across
   Rooms/Ideas/Events empty states. Existing EN/DE copy (already localized pre-pass) kept, given
   more visual weight.
5. **Member avatars** (`4526535`) — deterministic HSL color from a sum-of-bytes hash of the user
   key, filled with initials when a display name is known or a small dot-grid mark when it isn't
   (pending joiners, pre-profile-set members).
6. **App icon** (`c98f247`) — added the missing `<monochrome>` adaptive-icon layer (Android 13+
   themed icons); full-color icon verified correct on the emulator launcher/hotseat, themed mode
   wasn't directly toggleable on this AOSP emulator image.
7. **Roborazzi screenshot tests** (`4107ddd`) — `RoomsListScreenshotTest.kt`, 6 reference PNGs
   (`app/src/test/screenshots/rooms/`): empty + populated, light/dark, EN/DE, phone/tablet.
   `./gradlew testFossDebugUnitTest -Proborazzi.test.verify=true` passes clean against them.
8. **Accessibility fix** (`04726ca`) — the outbox/install-ntfy banner was bare `Text` floating
   over the list; at font scale 2.0 its own wrapped second line drew directly on top of a room row
   behind it, both illegible. Now a solid `Surface` (inverse-surface tonal card, same treatment an
   M3 Snackbar would use) — legible at any font scale, though it still visually overlaps the row
   behind it (no layout-reserved space; a deliberately scoped fix, not a full banner-placement
   rework).
9. **Container-transform motion** (`e2d81f5`) — idea title as a shared element between list and
   detail.

## Accessibility audit

- **Contrast**: ≥7:1 on every new color pairing (computed, not eyeballed — see above).
- **Touch targets**: vote-pill buttons deliberately kept at M3's default 48dp `IconButton` size
  rather than a tighter custom size, specifically to not undercut this.
- **Content descriptions**: audited via `uiautomator dump` on the Rooms list and Room screens —
  every interactive icon carries one ("Back", "Mute notifications", "Redeem voucher", "Members",
  "Invite", "New idea", "Upvote"/"Downvote" per row, "Settings", "Developer settings", "Create
  room"). Decorative icons (the empty-state illustration's icon) correctly carry `null` since the
  adjacent text already labels the state.
- **Font scale 2.0**: found and fixed one real clipping/legibility bug (the banner, above). No
  other clipping found on the Rooms list / Room / Idea detail screens at 2.0×.

## Performance

`adb shell dumpsys gfxinfo org.squaddle` after scrolling the 30-idea PermTestRoom list:
consistently **87-94% janky frames** across repeated runs, 90th percentile 100-200ms. This is
**on the emulator's software (SwiftShader) GL renderer**, which is known not to reflect real-device
GPU performance — the number didn't meaningfully change before/after the card-density redesign,
which is itself a sign this is dominated by emulator rendering cost rather than Compose
recomposition cost. Flagging as a real measurement taken, not a confirmed regression or fix;
should be re-validated on a real device or a hardware-accelerated emulator before acting on it.

## Findings not fixed (out of "no feature changes" scope)

- **Room names are not a protocol concept** — `createRoom` stores the display name only in the
  creator's own local `AppLocalStore`; every other joiner sees a hardcoded "Squaddle room"
  placeholder forever. Visible in this session's own seeded data (two rooms still show the
  placeholder). A real product gap, not a visual one — needs a protocol change (`core`/`spec`) to
  fix, not a design pass.
- **"Remove from room" has no confirmation dialog** — tapping it in the overflow menu executes
  instantly. A minor safety gap, not touched (adding a confirmation is a interaction/feature
  change).
- **Rooms list doesn't auto-refresh after joining a room via deep link** — needs a manual restart
  or the debug "Trigger sync now" button. Pre-existing, not a visual regression.
- StrictMode flags main-thread disk/network I/O during `joinViaLink` — pre-existing perf debt
  (M4b item 11's own territory), not touched here.

## Not done in this pass

- Roborazzi coverage is scoped to the Rooms list screen only. `RoomScreen`/`MembersScreen` need a
  real `RoomState`/`MembershipHistory` test fixture first (`RoomViewModelTest`/
  `MembersViewModelTest` already show how those are built); the identical
  `setContent { SquaddleTheme { ... } } + captureRoboImage(...)` shape extends to them directly
  once that fixture exists.
- Adaptive list-detail layout on tablet width (ideas list stays single-pane at `sw720dp` — the
  Roborazzi tablet reference shows this as-is, not yet split into list+detail panes).
- Small-phone (`wm size 720x1280`) and font-scale-1.3 variant sweeps weren't captured as their
  own artifacts, though the font-scale-2.0 pass (above) covers the riskiest end of that range.
