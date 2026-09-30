package com.blurr.voice.triggers

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Monitors the notification shade and executes configured notification triggers.
 *
 * It also maintains a live snapshot of the currently active notifications so the
 * agent's `notifications` tool can answer "what notifications do I have?" without
 * opening the shade and reading the screen. The snapshot is keyed by the system
 * notification key (package + per-app key), capped to the newest [MAX_SNAPSHOT]
 * entries, and served from [current], which any component may read. The app's own
 * foreground notification is deliberately excluded.
 */
class DeltaNotificationListenerService : NotificationListenerService() {

    private val TAG = "DeltaNotification"
    private lateinit var triggerManager: TriggerManager

    override fun onCreate() {
        super.onCreate()
        triggerManager = TriggerManager.getInstance(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        current = try {
            activeNotifications?.toList().orEmpty()
                .filter { it.packageName != packageName }
                .sortedByDescending { it.postTime }
                .take(MAX_SNAPSHOT)
                .map { it.toSnapshot() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load active notifications", e)
            emptyList()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Access was revoked; a stale snapshot would mislead the agent.
        current = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        Log.d(TAG, "Notification posted from package: $packageName")

        if (packageName != this.packageName) {
            current = (current.filterNot { it.key == sbn.key } + sbn.toSnapshot())
                .sortedByDescending { it.postTime }
                .take(MAX_SNAPSHOT)
        }

        if (packageName == this.packageName) {
            Log.d(TAG, "Ignoring notification from own package.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val notificationTriggers = triggerManager.getTriggers()
                .filter { it.type == TriggerType.NOTIFICATION && it.isEnabled }

            // First, check for the "All Applications" trigger
            var matchingTrigger = notificationTriggers.find { it.packageName == "*" }

            // If no "All Applications" trigger is found, check for a specific app trigger
            if (matchingTrigger == null) {
                matchingTrigger = notificationTriggers.find { it.packageName == packageName }
            }

            if (matchingTrigger != null) {
                val extras = sbn.notification.extras
                val title = extras.getString("android.title") ?: ""
                val text = extras.getCharSequence("android.text")?.toString() ?: ""
                val notificationContent = "Notification Content: $title - $text"
                val finalInstruction = "${matchingTrigger.instruction}\n\n$notificationContent"

                Log.d(TAG, "Found matching trigger for package: $packageName. Executing instruction: $finalInstruction")
                // Use the TriggerReceiver to start the agent service
                val intent = android.content.Intent(this@DeltaNotificationListenerService, TriggerReceiver::class.java).apply {
                    action = TriggerReceiver.ACTION_EXECUTE_TASK
                    putExtra(TriggerReceiver.EXTRA_TASK_INSTRUCTION, finalInstruction)
                }
                sendBroadcast(intent)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        current = current.filterNot { it.key == sbn.key }
    }

    /**
     * A single active notification, reduced to what the agent actually needs.
     */
    data class Snapshot(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val postTime: Long
    )

    /**
     * Reduces a [StatusBarNotification] to the fields the agent cares about.
     */
    private fun StatusBarNotification.toSnapshot(): Snapshot {
        val extras = notification.extras
        return Snapshot(
            key = key,
            packageName = packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "(no title)",
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(240) ?: "",
            postTime = postTime
        )
    }

    companion object {
        private const val MAX_SNAPSHOT = 30

        /**
         * The live snapshot of active notifications (newest first), or an empty
         * list if the listener has not connected yet. Read-only for callers.
         */
        @Volatile
        var current: List<Snapshot> = emptyList()
            private set
    }
}