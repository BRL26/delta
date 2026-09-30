package com.blurr.voice.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A single scheduled reminder.
 *
 * [triggerAtMillis] is always the *next* occurrence, not the original one, so
 * a repeating reminder reads the same whether it has fired zero or forty times.
 */
data class Reminder(
    val id: Int,
    val label: String,
    val triggerAtMillis: Long,
    val repeatDays: Set<Int> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val taskInstruction: String? = null
) {
    val repeats: Boolean get() = repeatDays.isNotEmpty()
}

/**
 * Stores reminders and arms an [AlarmManager] alarm for each one.
 *
 * Android has no "remind me about this" intent - timers and alarms are handed
 * to the clock app, but reminders have no OS-level equivalent - so this keeps
 * its own list and fires a notification when one comes due.
 *
 * Reminders are persisted because a reboot drops every pending alarm, and a
 * reminder that silently evaporates overnight is worse than useless.
 */
object ReminderScheduler {
    private const val TAG = "ReminderScheduler"
    private const val PREFS = "reminders"
    private const val KEY_LIST = "list"
    private const val NEXT_ID_KEY = "next_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    // ---------------------------------------------------------------- storage

    fun all(context: Context): List<Reminder> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val days = o.optJSONArray("days")?.let { d ->
                    (0 until d.length()).mapNotNull { d.optInt(it).takeIf { v -> v > 0 } }.toSet()
                } ?: emptySet()
                Reminder(
                    id = o.optInt("id"),
                    label = o.optString("label"),
                    triggerAtMillis = o.optLong("triggerAt"),
                    repeatDays = days,
                    createdAt = o.optLong("createdAt"),
                    taskInstruction = o.optString("task").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not parse stored reminders", e)
            emptyList()
        }
    }

    private fun save(context: Context, reminders: List<Reminder>) {
        val arr = JSONArray()
        reminders.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("label", r.label)
                put("triggerAt", r.triggerAtMillis)
                put("createdAt", r.createdAt)
                if (r.taskInstruction != null) put("task", r.taskInstruction)
                val days = JSONArray()
                r.repeatDays.forEach { days.put(it) }
                put("days", days)
            })
        }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    // ---------------------------------------------------------------- schedule

    /**
     * @return the new reminder's id
     */
    fun schedule(
        context: Context,
        label: String,
        triggerAtMillis: Long,
        repeatDays: Set<Int> = emptySet(),
        taskInstruction: String? = null
    ): Int {
        val p = prefs(context)
        val id = p.getInt(NEXT_ID_KEY, 1)
        p.edit().putInt(NEXT_ID_KEY, id + 1).apply()

        val reminder = Reminder(
            id = id,
            label = label,
            triggerAtMillis = triggerAtMillis,
            repeatDays = repeatDays,
            taskInstruction = taskInstruction
        )

        save(context, all(context).filterNot { it.id == id } + reminder)
        arm(context, reminder)
        Log.d(TAG, "Scheduled reminder #$id '$label' at $triggerAtMillis (repeats=$reminder.repeats)")
        return id
    }

    fun cancel(context: Context, id: Int): Boolean {
        val existing = all(context)
        val removed = existing.firstOrNull { it.id == id } ?: return false
        save(context, existing.filterNot { it.id == id })
        disarm(context, removed)
        Log.d(TAG, "Cancelled reminder #$id")
        return true
    }

    /** Cancels every reminder whose label matches, case-insensitively. */
    fun cancelByLabel(context: Context, label: String): Int {
        val existing = all(context)
        val target = label.trim().lowercase()
        val doomed = existing.filter { it.label.trim().lowercase() == target }
        if (doomed.isEmpty()) return 0
        save(context, existing - doomed.toSet())
        doomed.forEach { disarm(context, it) }
        Log.d(TAG, "Cancelled ${doomed.size} reminder(s) labelled '$label'")
        return doomed.size
    }

    // ------------------------------------------------------------- AlarmManager

    private fun pendingIntent(context: Context, id: Int, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_ID, id)
        }
        // The data URI keeps PendingIntent.getBroadcast from treating two
        // different reminders as the same request, which would silently
        // overwrite every earlier alarm with the last one scheduled.
        intent.data = android.net.Uri.parse("blurr://reminder/$id")

        val flags = if (mutable) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }

    private fun arm(context: Context, reminder: Reminder) {
        val am = alarmManager(context) ?: return
        val pi = pendingIntent(context, reminder.id)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Without the exact-alarm grant, `setExact` is a no-op. Fall
                // back to an inexact window so the reminder still arrives -
                // a reminder a few minutes late beats one that never fires.
                Log.w(TAG, "Exact alarms not permitted; scheduling reminder #${reminder.id} inexactly.")
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pi)
                return
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm denied, falling back to inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pi)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to arm reminder #${reminder.id}", e)
        }
    }

    private fun disarm(context: Context, reminder: Reminder) {
        val am = alarmManager(context) ?: return
        try {
            am.cancel(pendingIntent(context, reminder.id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disarm reminder #${reminder.id}", e)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Re-arms everything after a reboot. Alarms do not survive one, so this is
     * called from BootReceiver. Repeating reminders whose time already passed
     * while the phone was off roll forward to their next occurrence rather than
     * firing immediately on unlock.
     */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        all(context).forEach { reminder ->
            var r = reminder
            if (r.triggerAtMillis <= now) {
                val next = nextOccurrence(r, now) ?: run {
                    Log.d(TAG, "Reminder #${r.id} has no future occurrence; dropping.")
                    cancel(context, r.id)
                    return@forEach
                }
                save(context, all(context).map { if (it.id == r.id) r.copy(triggerAtMillis = next) else it })
                r = r.copy(triggerAtMillis = next)
            }
            arm(context, r)
        }
        Log.d(TAG, "Rescheduled ${all(context).size} reminder(s)")
    }

    /**
     * Next time [r] should fire, at or after [from].
     * Returns null for a one-off reminder whose moment has already passed.
     */
    fun nextOccurrence(r: Reminder, from: Long = System.currentTimeMillis()): Long? {
        if (!r.repeats) return null

        // Time-of-day comes from the stored occurrence, so setting a repeating
        // reminder for "08:15" keeps firing at 08:15 without storing the clock
        // time separately.
        val ref = Calendar.getInstance().apply { timeInMillis = r.triggerAtMillis }
        val hour = ref.get(Calendar.HOUR_OF_DAY)
        val minute = ref.get(Calendar.MINUTE)

        // Scan today then the next seven days and take the first slot at or
        // after `from`. Scanning in chronological order is what makes this the
        // *earliest* valid time rather than merely a valid one - without it, a
        // reminder set for 8am at 2pm would jump a whole week.
        for (dayOffset in 0..7) {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = from
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.get(Calendar.DAY_OF_WEEK) in r.repeatDays &&
                candidate.timeInMillis >= from
            ) {
                return candidate.timeInMillis
            }
        }
        return null
    }
}
