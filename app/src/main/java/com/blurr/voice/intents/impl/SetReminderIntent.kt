package com.blurr.voice.intents.impl

import android.content.Context
import android.content.Intent
import com.blurr.voice.intents.AppIntent
import com.blurr.voice.intents.ParameterSpec
import com.blurr.voice.reminders.ReminderScheduler
import com.blurr.voice.reminders.ReminderTimeParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Schedules a spoken reminder that fires as a notification.
 *
 * Unlike alarms and timers, Android offers no standard intent for "tell me
 * about this later", so this arms an alarm of its own. See [ReminderScheduler].
 *
 * This overrides [perform] rather than [buildIntent] because the outcome is a
 * scheduled notification, not a screen: there is nothing to launch.
 */
class SetReminderIntent : AppIntent {
    override val name: String = "SetReminder"

    override fun description(): String =
        "Set a reminder that will alert you later with a notification and read the " +
            "reminder out loud. Use this for 'remind me to take the pizza out in 20 " +
            "minutes', 'remind me to call the dentist tomorrow at 6pm', or 'remind me " +
            "every weekday to stretch'. Pass the time as it was spoken - do not convert " +
            "it yourself. Can also cancel: use action 'cancel'."

    override fun parametersSpec(): List<ParameterSpec> = listOf(
        ParameterSpec(
            name = "label",
            type = "string",
            required = true,
            description = "What to be reminded about, e.g. 'to take the pizza out'. " +
                "Write it as a complete phrase so it reads naturally when spoken."
        ),
        ParameterSpec(
            name = "when",
            type = "string",
            required = true,
            description = "When to remind, in the user's own words: 'in 20 minutes', " +
                "'tomorrow at 6pm', 'at 7:30am', 'every weekday at 9am'. Pass it " +
                "through verbatim; do not convert durations into seconds."
        ),
        ParameterSpec(
            name = "repeat",
            type = "string",
            required = false,
            description = "Optional repetition: 'daily', 'weekdays', 'weekends', or a " +
                "comma-separated day list like 'mon,wed,fri'. Omit for a one-off."
        ),
        ParameterSpec(
            name = "action",
            type = "string",
            required = false,
            description = "Use 'cancel' to delete a pending reminder instead of creating one."
        ),
        ParameterSpec(
            name = "task",
            type = "string",
            required = false,
            description = "Optional agent instruction to carry out when it fires, for " +
                "'remind me to check the oven in 15 minutes'. Omit if it is just a nudge."
        )
    )

    override fun buildIntent(context: Context, params: Map<String, Any?>): Intent? = null

    override fun perform(context: Context, params: Map<String, Any?>): String? {
        val label = params["label"]?.toString()?.trim().orEmpty()
        if (label.isEmpty()) {
            return "SetReminder needs a 'label' saying what to be reminded about."
        }

        if (params["action"]?.toString()?.trim()?.equals("cancel", ignoreCase = true) == true) {
            return cancel(context, label)
        }

        val whenText = params["when"]?.toString()?.trim().orEmpty()
        if (whenText.isEmpty()) {
            return "SetReminder needs a 'when' saying when to remind (e.g. 'in 10 minutes')."
        }

        val triggerAt = resolveTrigger(whenText)
            ?: return "Could not work out the time from \"$whenText\". " +
                "Try phrasing like \"in 20 minutes\", \"at 7am tomorrow\", or \"at 6pm\"."

        val repeatDays = ReminderTimeParser.parseRepeatDays(
            params["repeat"]?.toString()?.takeIf { it.isNotBlank() }
                ?: whenText.takeIf { containsRepeatWord(it) }
        )

        val task = params["task"]?.toString()?.trim()?.takeIf { it.isNotBlank() }

        val id = ReminderScheduler.schedule(
            context = context,
            label = label,
            triggerAtMillis = triggerAt,
            repeatDays = repeatDays,
            taskInstruction = task
        )

        val when_ = format(triggerAt)
        val repeatText = if (repeatDays.isEmpty()) "" else " every ${describeDays(repeatDays)}"
        return "Reminder #$id set for $when_$repeatText: $label"
    }

    private fun cancel(context: Context, label: String): String {
        val direct = ReminderScheduler.cancelByLabel(context, label)
        if (direct > 0) {
            return if (direct == 1) {
                "Cancelled 1 reminder: $label"
            } else {
                "Cancelled $direct reminders: $label"
            }
        }

        val needle = label.lowercase(Locale.US)
        // The stored label keeps the reminder's own phrasing ("to call the
        // dentist") while the user cancelling may just say "dentist", so match
        // substrings in both directions.
        val matches = ReminderScheduler.all(context).filter {
            val stored = it.label.lowercase(Locale.US)
            stored.contains(needle) || needle.contains(stored)
        }
        matches.forEach { ReminderScheduler.cancel(context, it.id) }

        return if (matches.isEmpty()) {
            val pending = ReminderScheduler.all(context)
            if (pending.isEmpty()) {
                "There are no reminders to cancel."
            } else {
                "No reminder matched \"$label\". Pending: " +
                    pending.joinToString("; ") { it.label }
            }
        } else {
            "Cancelled reminder: ${matches.joinToString("; ") { it.label }}"
        }
    }

    /**
     * The clock reading in a repeating phrase ("every weekday at 9am") is a time
     * of day, not a countdown, so it has to be anchored to an upcoming slot
     * rather than added to now.
     */
    private fun containsRepeatWord(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        return listOf("every ", "each ", "daily", "weekday", "weekend").any { t.contains(it) }
    }

    private fun resolveTrigger(whenText: String): Long? {
        val now = System.currentTimeMillis()

        if (containsRepeatWord(whenText)) {
            val cleaned = whenText
                .replace(Regex("""\b(every|each|daily|on|weekdays?|weekends?)\b"""), " ")
                .trim()
            val parsed = ReminderTimeParser.parse(cleaned, now)
                ?: ReminderTimeParser.parse(whenText, now)
            return parsed?.takeIf { it > now } ?: parsed?.let(::nextDayAt)
        }

        return ReminderTimeParser.parse(whenText, now)
    }

    /**
     * Moves a time-of-day that has already passed today to tomorrow. The repeat
     * day check is left to [ReminderScheduler.nextOccurrence] on the first
     * firing, which walks forward to a day the user actually selected.
     */
    private fun nextDayAt(millis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val next = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        return next.timeInMillis
    }

    private fun format(millis: Long): String {
        val today = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val sameYear = today.get(Calendar.YEAR) == target.get(Calendar.YEAR)
        val dayOffset = if (sameYear) {
            target.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
        } else 999

        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(millis))
        return when (dayOffset) {
            0 -> "today at $time"
            1 -> "tomorrow at $time"
            else -> SimpleDateFormat("EEEE h:mm a", Locale.US).format(Date(millis))
        }
    }

    private fun describeDays(days: Set<Int>): String {
        val names = mapOf(
            Calendar.MONDAY to "Mon", Calendar.TUESDAY to "Tue", Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu", Calendar.FRIDAY to "Fri", Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun"
        )
        val allSeven = (Calendar.SUNDAY..Calendar.SATURDAY).all { it in days }
        if (allSeven) return "day"
        return days.sorted().joinToString(", ") { names[it] ?: "?" }
    }
}
