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

    /** Being promoted/demoted, having ownership transferred to you, or getting kicked - all
     * surprising-enough-to-matter membership changes, one channel (the request's own single
     * bullet "role changes/kick affecting me", not two separate asks). Importance HIGH - unlike
     * the content channels above, a user should actually notice this promptly. */
    public const val MEMBERSHIP: String = "membership"

    /** A join request on a room I admin/own needs *my* action - separate from [MEMBERSHIP] since
     * it's a different audience (admins/owners only, never a plain member) and a different kind
     * of urgency (nothing broke, something is just waiting on me). */
    public const val PENDING_APPROVAL: String = "pending_approval"

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
            NotificationChannel(MEMBERSHIP, context.getString(R.string.notification_channel_membership), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(PENDING_APPROVAL, context.getString(R.string.notification_channel_pending_approval), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannels(channels)
    }
}
