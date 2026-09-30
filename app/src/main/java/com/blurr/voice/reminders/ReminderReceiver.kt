package com.blurr.voice.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blurr.voice.MainActivity
import com.blurr.voice.utilities.TTSManager
import com.blurr.voice.v2.AgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when a [ReminderScheduler] alarm comes due: posts a notification, says
 * the reminder out loud, optionally hands a task to the agent, and re-arms
 * itself if the reminder repeats.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.blurr.voice.action.FIRE_REMINDER"
        const val ACTION_DISMISS = "com.blurr.voice.action.DISMISS_REMINDER"
        const val EXTRA_ID = "com.blurr.voice.EXTRA_REMINDER_ID"
        const val CHANNEL_ID = "RemindersChannel"
        private const val TAG = "ReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) {
            Log.w(TAG, "Reminder broadcast with no id; ignoring.")
            return
        }

        if (intent.action == ACTION_DISMISS) {
            Log.d(TAG, "Reminder #$id dismissed.")
            return
        }

        if (intent.action != ACTION_FIRE) return

        val reminder = ReminderScheduler.all(context).firstOrNull { it.id == id }
        if (reminder == null) {
            Log.d(TAG, "Reminder #$id no longer exists (cancelled); dropping broadcast.")
            return
        }

        Log.d(TAG, "Reminder firing: '${reminder.label}'")
        notify(context, reminder)
        speak(context, reminder)
        runTask(context, reminder)
        advance(context, reminder)
    }

    /** Repeating reminders re-arm for their next slot; one-offs are removed. */
    private fun advance(context: Context, reminder: Reminder) {
        if (!reminder.repeats) {
            ReminderScheduler.cancel(context, reminder.id)
            return
        }
        val next = ReminderScheduler.nextOccurrence(reminder)
        if (next == null) {
            // The repeat set can only fail to yield a future slot if the device
            // clock jumped past a full week; drop it rather than leave an alarm
            // pointing at a time that no longer exists.
            ReminderScheduler.cancel(context, reminder.id)
            return
        }
        // cancel-then-schedule because the id is the alarm request code, and
        // re-scheduling over a live alarm without clearing it first leaves the
        // old PendingIntent registered.
        ReminderScheduler.cancel(context, reminder.id)
        ReminderScheduler.schedule(
            context = context,
            label = reminder.label,
            triggerAtMillis = next,
            repeatDays = reminder.repeatDays,
            taskInstruction = reminder.taskInstruction
        )
        Log.d(TAG, "Repeating reminder #${reminder.id} next fires at $next")
    }

    /**
     * "Remind me to call the dentist at 6" can be answered by the agent itself
     * rather than by the user tapping the notification.
     */
    private fun runTask(context: Context, reminder: Reminder) {
        val task = reminder.taskInstruction ?: return
        try {
            AgentService.start(context, task)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start agent for reminder #${reminder.id}", e)
        }
    }

    private fun notify(context: Context, reminder: Reminder) {
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            reminder.id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = PendingIntent.getBroadcast(
            context,
            reminder.id + DISMISS_REQUEST_OFFSET,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_DISMISS
                putExtra(EXTRA_ID, reminder.id)
                data = Uri.parse("blurr://reminder-dismiss/${reminder.id}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(reminder.label)
            .setContentText("Reminder")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(reminder.id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted. The spoken reminder still happens,
            // so this is a degraded result rather than a total failure.
            Log.w(TAG, "Cannot post reminder notification: permission denied", e)
        }
    }

    private fun speak(context: Context, reminder: Reminder) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                TTSManager.getInstance(context).speakToUser(reminder.label)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to speak reminder", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for reminders you set by voice."
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}

// Request codes must not collide between the fire and dismiss PendingIntents,
// or one silently replaces the other.
private const val DISMISS_REQUEST_OFFSET = 100_000
