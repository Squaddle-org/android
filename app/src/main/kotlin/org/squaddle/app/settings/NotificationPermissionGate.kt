package org.squaddle.app.settings

import android.content.Context

/** Whether `MainActivity.kt`'s `SignedInApp` has already asked for `POST_NOTIFICATIONS` once -
 * asked at most once, ever, the first time a room exists (not blindly on every cold start, which
 * is "on first launch" before there's even anything to notify about - see that call site's own
 * doc comment). Not secret, same plain-SharedPreferences shape as [DeveloperSettingsStore]/
 * [DisplayNameStore]. */
public interface NotificationPermissionGate {
    public fun hasPrompted(): Boolean
    public fun setPrompted()
}

public class AndroidNotificationPermissionGate(context: Context) : NotificationPermissionGate {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun hasPrompted(): Boolean = prefs.getBoolean(KEY_PROMPTED, false)

    override fun setPrompted() {
        prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "notification_permission_gate"
        const val KEY_PROMPTED = "prompted"
    }
}
