package com.blurr.voice.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed. Rescheduling alarms.")
            val triggerManager = TriggerManager.getInstance(context)

            // It's good practice to do this work off the main thread
            // Start the TriggerMonitoringService
            //
            // Must be startForegroundService, not startService: a broadcast
            // receiver counts as background, and TriggerMonitoringService calls
            // startForeground() in onStartCommand. Plain startService from here
            // throws BackgroundServiceStartNotAllowedException and takes the
            // whole process down on every boot and every app update.
            val serviceIntent = Intent(context, TriggerMonitoringService::class.java)
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d(TAG, "Started TriggerMonitoringService on boot.")
            } catch (e: Exception) {
                // Foreground-start restrictions can still bite (e.g. no
                // specialUse permission granted). Losing trigger monitoring is
                // not worth crashing the app over.
                Log.e(TAG, "Could not start TriggerMonitoringService on boot", e)
            }

            CoroutineScope(Dispatchers.IO).launch {
                val triggers = triggerManager.getTriggers()
                val scheduledTriggers = triggers.filter { it.isEnabled && it.type == TriggerType.SCHEDULED_TIME }
                scheduledTriggers.forEach { trigger ->
                    // In the future, we might have different logic for rescheduling
                    // but for now, just calling schedule is fine as it will recreate the alarm.
                    triggerManager.updateTrigger(trigger)
                }
                Log.d(TAG, "Finished rescheduling ${scheduledTriggers.size} alarms.")

                // Reminders are lost the same way triggers are, for the same
                // reason: AlarmManager does not restore pending alarms on boot.
                try {
                    com.blurr.voice.reminders.ReminderScheduler.rescheduleAll(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reschedule reminders", e)
                }
            }
        }
    }
}
