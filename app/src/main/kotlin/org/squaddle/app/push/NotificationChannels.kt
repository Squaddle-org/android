package org.squaddle.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.squaddle.app.R

/** One channel per notification type (android/CLAUDE.md M4b item 5) - each independently
 * mutable by the user via system settings, same way Android expects. minSdk is already 26
 * (app/build.gradle.kts), so [NotificationChannel] is always available - no version guard
 * needed. */
public object NotificationChannels {
    public const val IDEA: String = "idea"
    public const val COMMENT: String = "comment"
    public const val EVENT: String = "event"
    public const val RSVP: String = "rsvp"

    /** Call once at app startup (idempotent - recreating an existing channel with the same id
     * is a no-op per [NotificationManager.createNotificationChannel]'s own contract, and never
     * resets a user's own per-channel settings). */
    public fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(IDEA, context.getString(R.string.notification_channel_idea), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(COMMENT, context.getString(R.string.notification_channel_comment), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(EVENT, context.getString(R.string.notification_channel_event), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(RSVP, context.getString(R.string.notification_channel_rsvp), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannels(channels)
    }
}
