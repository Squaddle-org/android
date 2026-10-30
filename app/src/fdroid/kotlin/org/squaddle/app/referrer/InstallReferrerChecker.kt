package org.squaddle.app.referrer

import android.content.Context

/**
 * `fdroid` flavor's version of this class (see the `play` flavor's own copy for the real
 * implementation; `github` carries an identical no-op) - same fully-qualified name in every
 * flavor source set, deliberately not a shared interface in `main`, so `MainActivity.kt`
 * (flavor-agnostic) can call this unconditionally with zero `BuildConfig.FLAVOR` branching, the
 * same way `BuildConfig` itself works across flavors. `fdroid`/`github` have no Play Services /
 * Install Referrer library on their classpath at all (android/CLAUDE.md: only `play` does, CI's
 * own GMS/Firebase classpath check covers both GMS-free flavors), so there is nothing to check
 * here - always `null`.
 */
public class InstallReferrerChecker {
    public suspend fun referrerUrl(context: Context): String? = null
}
