package org.squaddle.app.backup

import android.content.Context

/** Which persisted SAF URI/interval auto-backup writes to - not secret (unlike the passphrase
 * itself, see [BackupPassphraseVault]), so a plain `SharedPreferences` store with no encryption
 * at rest, same shape as [org.squaddle.app.settings.DeveloperSettingsStore]. */
public interface AutoBackupSettingsStore {
    public fun isEnabled(): Boolean
    public fun uriString(): String?
    public fun intervalHours(): Int

    /** [uriString] is required (and [takePersistableUriPermission] must already have been
     * called on it by the caller) when [enabled] is true; ignored when disabling. */
    public fun set(enabled: Boolean, uriString: String?, intervalHours: Int)
}

public class AndroidAutoBackupSettingsStore(context: Context) : AutoBackupSettingsStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    override fun uriString(): String? = prefs.getString(KEY_URI, null)
    override fun intervalHours(): Int = prefs.getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)

    override fun set(enabled: Boolean, uriString: String?, intervalHours: Int) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, enabled)
            if (enabled && uriString != null) putString(KEY_URI, uriString) else remove(KEY_URI)
            putInt(KEY_INTERVAL_HOURS, intervalHours)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "auto_backup_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_URI = "uri"
        const val KEY_INTERVAL_HOURS = "interval_hours"
        const val DEFAULT_INTERVAL_HOURS = 24
    }
}
