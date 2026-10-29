package org.squaddle.app.backup

/** In-memory [AutoBackupSettingsStore] test double - no `SharedPreferences`. */
public class FakeAutoBackupSettingsStore : AutoBackupSettingsStore {
    private var enabled = false
    private var uri: String? = null
    private var interval = 24

    override fun isEnabled(): Boolean = enabled
    override fun uriString(): String? = uri
    override fun intervalHours(): Int = interval

    override fun set(enabled: Boolean, uriString: String?, intervalHours: Int) {
        this.enabled = enabled
        this.uri = if (enabled) uriString else null
        this.interval = intervalHours
    }
}
