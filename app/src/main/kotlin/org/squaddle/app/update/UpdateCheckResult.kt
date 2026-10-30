package org.squaddle.app.update

/** Flavor-agnostic result shapes, shared by every flavor's own `SelfUpdateChecker` (that class
 * itself has no shared interface - same pattern as [org.squaddle.app.referrer.InstallReferrerChecker],
 * see its own doc comment - but the results it produces are identical regardless of which
 * flavor produced them, so `ui/settings/SettingsScreen.kt` can pattern-match on these without any
 * `BuildConfig.FLAVOR` branching of its own). */
public sealed interface UpdateCheckResult {
    public data object UpToDate : UpdateCheckResult
    public data class UpdateAvailable(
        public val versionName: String,
        public val notes: String,
        public val downloadUrl: String,
        public val sha256: String,
    ) : UpdateCheckResult
    public data class CheckFailed(public val message: String) : UpdateCheckResult
}

public sealed interface InstallOutcome {
    /** The system's own install-confirmation UI was launched - this is not "installed", just
     * "handed off"; there is no further callback, the same way a plain `ACTION_VIEW` install
     * intent has none. */
    public data object Launched : InstallOutcome
    public data class Failed(public val message: String) : InstallOutcome
}
