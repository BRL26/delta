package com.blurr.voice.sidekey

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The single notification channel used by the Side Key snap flow. One channel,
 * one style: a short summary of what the background analysis actually did
 * ("Set a reminder for Oct 9, 2:00 PM — Doctor appointment"), never a canned
 * "task completed successfully".
 */
object SideKeyNotifications {

    private const val TAG = "SideKeyNotifications"
    const val CHANNEL_ID = "side_key"
    private const val CHANNEL_NAME = "Side Key snaps"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    description = "What a snapped screen was turned into: reminders set, and anything skipped."
                    setShowBadge(false)
                }
        )
    }

    /**
     * Posts [body] under [title] as a side-key notification. Uses a fresh id per
     * call so consecutive snaps each produce their own summary instead of
     * replacing the previous one.
     */
    fun notify(context: Context, title: String, body: String) {
        ensureChannel(context)
        if (!hasPostNotificationsPermission(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; dropping side-key summary.")
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun hasPostNotificationsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
}