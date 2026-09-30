package com.blurr.voice.intents.impl

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.blurr.voice.intents.AppIntent
import com.blurr.voice.intents.ParameterSpec
import java.util.Calendar

/**
 * Sets an alarm in the device's clock app.
 *
 * Uses ACTION_SET_ALARM so the alarm is a real, OS-managed alarm the user can
 * see, edit and dismiss like any other - rather than a notification the app
 * fakes and then has to keep track of itself.
 */
class SetAlarmIntent : AppIntent {
    override val name: String = "SetAlarm"

    override fun description(): String =
        "Set an alarm in the clock app for a specific time of day, optionally with a label " +
            "and repeat days. Use this for 'wake me up at 7', 'alarm for 6:30am', or " +
            "'set a weekday alarm'. For countdowns use SetTimer instead."

    override fun parametersSpec(): List<ParameterSpec> = listOf(
        ParameterSpec(
            name = "hour",
            type = "integer",
            required = true,
            description = "Hour in 24-hour form, 0-23."
        ),
        ParameterSpec(
            name = "minute",
            type = "integer",
            required = true,
            description = "Minute, 0-59."
        ),
        ParameterSpec(
            name = "label",
            type = "string",
            required = false,
            description = "Optional name for the alarm, e.g. 'gym'."
        ),
        ParameterSpec(
            name = "days",
            type = "string",
            required = false,
            description =
                "Optional comma-separated days to repeat: one or more of " +
                    "mon,tue,wed,thu,fri,sat,sun. Omit for a one-off alarm."
        ),
        ParameterSpec(
            name = "vibrate",
            type = "boolean",
            required = false,
            description = "Whether the alarm should vibrate. Defaults to false."
        )
    )

    override fun buildIntent(context: Context, params: Map<String, Any?>): Intent? {
        val hour = params["hour"]?.toString()?.trim()?.toIntOrNull() ?: return null
        val minute = params["minute"]?.toString()?.trim()?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            // EXTRA_SKIP_UI matters: without it some clock apps show an
            // "edit alarm" confirmation screen, which the agent would then have
            // to drive through blind.
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
        }

        params["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }

        params["days"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val days = parseDays(raw) ?: return null
            intent.putExtra(AlarmClock.EXTRA_DAYS, days)
        }

        intent.putExtra(
            AlarmClock.EXTRA_VIBRATE,
            params["vibrate"]?.toString()?.trim()?.equals("true", ignoreCase = true) ?: false
        )

        // If nothing on the device can handle this, report it as unbuildable
        // rather than firing an intent that silently does nothing.
        if (intent.resolveActivity(context.packageManager) == null) return null

        return intent
    }

    /** Maps "mon,tue" to Calendar.MONDAY..Calendar.SUNDAY. Null if unparseable. */
    private fun parseDays(raw: String): IntArray? {
        val map = mapOf(
            "mon" to Calendar.MONDAY,
            "monday" to Calendar.MONDAY,
            "tue" to Calendar.TUESDAY,
            "tues" to Calendar.TUESDAY,
            "tuesday" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY,
            "thur" to Calendar.THURSDAY,
            "thurs" to Calendar.THURSDAY,
            "thursday" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY,
            "friday" to Calendar.FRIDAY,
            "sat" to Calendar.SATURDAY,
            "saturday" to Calendar.SATURDAY,
            "sun" to Calendar.SUNDAY,
            "sunday" to Calendar.SUNDAY
        )

        val out = LinkedHashSet<Int>()
        for (token in raw.split(",", " ", "/")) {
            val key = token.trim().lowercase()
            if (key.isEmpty()) continue
            val day = map[key] ?: return null
            out.add(day)
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }
}
