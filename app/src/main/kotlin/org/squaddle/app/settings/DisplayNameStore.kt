package org.squaddle.app.settings

import android.content.Context

/** The display name chosen during onboarding ("What should we call you?") - applied
 * automatically to every room this device creates or joins afterward
 * ([org.squaddle.app.data.SyncGateway.setProfile] is per-room, PROTOCOL.md's `ProfileSet` op
 * itself has no single global/account-wide form). Not secret, so a plain SharedPreferences store
 * with no encryption at rest - same shape as [DeveloperSettingsStore]. */
public interface DisplayNameStore {
    public fun displayName(): String?
    public fun setDisplayName(name: String)
}

public class AndroidDisplayNameStore(context: Context) : DisplayNameStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun displayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)

    override fun setDisplayName(name: String) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    private companion object {
        const val PREFS_NAME = "display_name"
        const val KEY_DISPLAY_NAME = "display_name"
    }
}
