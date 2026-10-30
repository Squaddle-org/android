package org.squaddle.app.update

import android.content.Context

/**
 * `play` flavor's version of this class (see the `github` flavor's own copy for the real
 * implementation; `fdroid` carries an identical no-op, same doc comment reasoning) - the Play
 * Store owns updates for a `play`-flavor install, the same way F-Droid's client owns them for
 * `fdroid`. No network call here either.
 */
public class SelfUpdateChecker {
    public suspend fun checkForUpdate(): UpdateCheckResult = UpdateCheckResult.UpToDate

    public suspend fun downloadAndInstall(context: Context, update: UpdateCheckResult.UpdateAvailable): InstallOutcome =
        InstallOutcome.Failed("not supported on this build")
}
