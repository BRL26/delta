package com.blurr.voice.intents.impl

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.blurr.voice.intents.AppIntent
import com.blurr.voice.intents.ParameterSpec
import com.blurr.voice.reminders.ReminderTimeParser

/**
 * Starts a countdown timer in the device's clock app.
 *
 * Uses ACTION_SET_TIMER so the countdown is owned by the OS clock - it keeps
 * running, keeps ringing, and is cancellable from the lock screen, which is the
 * behaviour people actually expect from a timer.
 */
class SetTimerIntent : AppIntent {
    override val name: String = "SetTimer"

    override fun description(): String =
        "Start a countdown timer that runs in the clock app. Use this for " +
            "'set a timer for 5 minutes', 'time my pasta for 10 minutes', or " +
            "'start a 90 second timer'. For a specific time of day use SetAlarm; " +
            "to be told about something later use SetReminder."

    override fun parametersSpec(): List<ParameterSpec> = listOf(
        ParameterSpec(
            name = "seconds",
            type = "integer",
            required = true,
            description = "Timer length in seconds. Accepts either a plain number of " +
                "seconds (300) or a duration in the user's words ('5 minutes', '90s', " +
                "'1h30m') - either is fine, the app converts it."
        ),
        ParameterSpec(
            name = "label",
            type = "string",
            required = false,
            description = "Optional name for the timer, e.g. 'pasta' or 'tea'."
        ),
        ParameterSpec(
            name = "skip_ui",
            type = "boolean",
            required = false,
            description = "If true (default) the clock app starts the timer immediately " +
                "instead of showing a confirmation screen."
        )
    )

    override fun buildIntent(context: Context, params: Map<String, Any?>): Intent? {
        val seconds = parseSeconds(params["seconds"]) ?: return null
        if (seconds < 1) return null

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(
                AlarmClock.EXTRA_SKIP_UI,
                params["skip_ui"]?.toString()?.trim()
                    ?.equals("false", ignoreCase = true) == false
            )
        }

        params["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }

        if (intent.resolveActivity(context.packageManager) == null) return null
        return intent
    }

    /**
     * Accepts a bare number of seconds, or a duration phrase. A bare number is
     * read as seconds here (not minutes as [ReminderTimeParser] does by
     * default) because this parameter is explicitly named "seconds" - the
     * model already converted the units, so re-interpreting "10" as ten
     * minutes would be second-guessing a correct answer.
     */
    private fun parseSeconds(raw: Any?): Int? {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null

        text.toIntOrNull()?.let { return it }

        return ReminderTimeParser.parseDuration(text)?.let { seconds ->
            if (seconds in 1..Int.MAX_VALUE.toLong()) seconds.toInt() else null
        }
    }
}
