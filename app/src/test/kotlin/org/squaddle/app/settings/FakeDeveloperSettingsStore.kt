package org.squaddle.app.settings

/** In-memory [DeveloperSettingsStore] test double - no Android `SharedPreferences`, no disk. */
public class FakeDeveloperSettingsStore : DeveloperSettingsStore {
    private var override: String? = null

    override fun relayHostOverride(): String? = override
    override fun setRelayHostOverride(host: String?) {
        override = host
    }
}
