package org.squaddle.app.referrer

import android.content.Context

/**
 * `foss` flavor's version of this class (see the `play` flavor's own copy for the real
 * implementation) - same fully-qualified name in both flavor source sets, deliberately not a
 * shared interface in `main`, so `MainActivity.kt` (flavor-agnostic) can call this
 * unconditionally with zero `BuildConfig.FLAVOR` branching, the same way `BuildConfig` itself
 * works across flavors. `foss` has no Play Services / Install Referrer library on its classpath
 * at all (android/CLAUDE.md: "no GMS in foss", CI's own `fossRelease` check), so there is
 * nothing to check here - always `null`.
 */
public class InstallReferrerChecker {
    public suspend fun referrerUrl(context: Context): String? = null
}
