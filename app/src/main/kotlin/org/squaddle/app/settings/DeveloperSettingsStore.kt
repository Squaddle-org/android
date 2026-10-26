package org.squaddle.app.settings

import android.content.Context

/** Debug-build-only override for the relay host [org.squaddle.app.di.AppContainer] otherwise
 * defaults to from `BuildConfig.DEFAULT_RELAY_HOST`. Not secret, so a plain [IdentityVault]-style
 * interface+impl+fake split (same shape as [org.squaddle.app.crypto.IdentityVault]) without any
 * encryption at rest. */
public interface DeveloperSettingsStore {
    public fun relayHostOverride(): String?
    public fun setRelayHostOverride(host: String?)
}

public class AndroidDeveloperSettingsStore(context: Context) : DeveloperSettingsStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun relayHostOverride(): String? = prefs.getString(KEY_RELAY_HOST, null)

    override fun setRelayHostOverride(host: String?) {
        prefs.edit().apply {
            if (host == null) remove(KEY_RELAY_HOST) else putString(KEY_RELAY_HOST, host)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "developer_settings"
        const val KEY_RELAY_HOST = "relay_host_override"
    }
}
