package org.squaddle.app.update

import android.content.Context

/**
 * `fdroid` flavor's version of this class (see the `github` flavor's own copy for the real
 * implementation; `play` carries an identical no-op) - same fully-qualified name in every flavor
 * source set, deliberately not a shared interface in `main` (see
 * [org.squaddle.app.referrer.InstallReferrerChecker]'s own doc comment for why), so
 * `ui/settings/SettingsScreen.kt` only shows the "Check for updates" section at all when
 * `BuildConfig.FLAVOR == "github"` - this class exists so the rest of the Settings screen's
 * plumbing compiles identically across flavors, not because it's ever actually called here.
 *
 * F-Droid's own client owns updates for an `fdroid`-flavor install - their reproducible-build
 * policy expects an app not to phone home checking for updates from outside F-Droid's own repo,
 * so this never makes a network call, not even to report "nothing to do here".
 */
public class SelfUpdateChecker {
    public suspend fun checkForUpdate(): UpdateCheckResult = UpdateCheckResult.UpToDate

    public suspend fun downloadAndInstall(context: Context, update: UpdateCheckResult.UpdateAvailable): InstallOutcome =
        InstallOutcome.Failed("not supported on this build")
}
