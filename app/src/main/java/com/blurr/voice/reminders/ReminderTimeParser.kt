package com.blurr.voice.reminders

import java.util.Calendar
import java.util.Locale

/**
 * Turns the loose time expressions a spoken request produces ("in 10 minutes",
 * "tomorrow at 6pm", "at 7:30 in the evening") into a wall-clock instant.
 *
 * This exists because asking the model to do the arithmetic itself is
 * unreliable: it confidently returns "3600" for ninety minutes, or treats
 * "half an hour" as a parse failure. Handing it the raw phrase and doing the
 * conversion here means a slightly odd phrasing still works.
 */
object ReminderTimeParser {

    private val DURATION_RE = Regex(
        """(\d+)\s*(h(?:ou?rs?)?|m(?:in(?:ute)?s?)?|s(?:ec(?:ond)?s?)?)""",
        RegexOption.IGNORE_CASE
    )

    private val CLOCK_RE = Regex(
        """(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""",
        RegexOption.IGNORE_CASE
    )

    private val BARE_NUMBER_RE = Regex("""(\d{1,4})""")

    /** Words that pin a phrase to a clock reading rather than a countdown. */
    private val DAY_WORDS = listOf(
        "tomorrow", "tmrw", "tonight", "morning", "afternoon", "evening",
        "noon", "midnight", "o'clock", "oclock"
    )

    /** Weekday name or abbreviation to Calendar.DAY_OF_WEEK. */
    private val DAY_NAMES = mapOf(
        "monday" to Calendar.MONDAY, "mon" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY, "tue" to Calendar.TUESDAY, "tues" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "wed" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "thu" to Calendar.THURSDAY,
        "thur" to Calendar.THURSDAY, "thurs" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "fri" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY, "sat" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY, "sun" to Calendar.SUNDAY
    )

    /**
     * @return epoch millis, or null if the text contains no usable time.
     */
    fun parse(phrase: String, from: Long = System.currentTimeMillis()): Long? {
        val text = phrase.trim().lowercase(Locale.US)
        if (text.isEmpty()) return null

        // An explicit unit settles it: "10 minutes" is a countdown no matter
        // what else is in the string.
        if (DURATION_RE.containsMatchIn(text)) {
            parseDuration(text)?.let { return from + it * 1000L }
        }

        // Otherwise a clock reading beats a bare number. "at 6pm" contains a 6,
        // and without this check it would be read as six minutes from now.
        if (looksLikeClock(text)) return parseClock(text, from)

        // A lone number with no "am"/"pm" is a countdown - that is how people
        // actually say it ("in 10").
        parseDuration(text)?.let { return from + it * 1000L }
        return null
    }

    private fun looksLikeClock(text: String): Boolean {
        if (text.contains(':')) return true
        CLOCK_RE.find(text)?.let { m -> if (m.groupValues[3].isNotEmpty()) return true }
        return DAY_WORDS.any { text.contains(it) }
    }

    /**
     * Total seconds named by a duration phrase, or null if there is no duration.
     * Handles "10 minutes", "1h30m", "2 hours and 15 minutes", and a bare
     * "45" (read as minutes, which is how people say it).
     */
    fun parseDuration(text: String): Long? {
        var seconds = 0L
        var matched = false

        for (m in DURATION_RE.findAll(text)) {
            val amount = m.groupValues[1].toLongOrNull() ?: continue
            val unit = m.groupValues[2]
            seconds += amount * when {
                unit.startsWith("h") -> 3600L
                unit.startsWith("s") -> 1L
                else -> 60L
            }
            matched = true
        }

        if (matched) return if (seconds > 0) seconds else null

        // No unit anywhere. A lone number still means something - "remind me in
        // 10" is ten minutes, not ten o'clock.
        val bare = BARE_NUMBER_RE.find(text) ?: return null
        val value = bare.groupValues[1].toLongOrNull() ?: return null
        return if (value in 1..999) value * 60L else null
    }

    private fun parseClock(text: String, from: Long): Long? {
        val m = CLOCK_RE.find(text) ?: return null

        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val meridiem = m.groupValues[3].lowercase(Locale.US)

        if (meridiem == "pm" && hour in 1..11) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0
        // A bare "7" with no am/pm is ambiguous, but pm is the more common
        // intent for a reminder, and 7:00am has other words ("morning").
        if (meridiem.isEmpty() && hour in 1..11 && !text.contains("morning")) hour += 12
        if (hour !in 0..23 || minute !in 0..59) return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val mentionsNextDay = listOf("tomorrow", "tmrw", "tonight", "next day")
            .any { text.contains(it) }
        if (mentionsNextDay) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        } else if (cal.timeInMillis <= from) {
            // Already gone today, so the only sensible reading is tomorrow.
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Parses a repeat phrase into Calendar day-of-week constants.
     * Understands "daily", "every day", "weekdays", "weekends", and named
     * days. Returns an empty set for a one-off reminder.
     */
    fun parseRepeatDays(text: String?): Set<Int> {
        val t = text?.trim()?.lowercase(Locale.US).orEmpty()
        if (t.isEmpty() || t == "none" || t == "once" || t == "never") return emptySet()

        if (t.contains("weekday") && !t.contains("weekend")) {
            return setOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY
            )
        }
        if (t.contains("weekend")) {
            return setOf(Calendar.SATURDAY, Calendar.SUNDAY)
        }
        if (t.contains("daily") || t.contains("every day") || t.contains("everyday")) {
            return (Calendar.SUNDAY..Calendar.SATURDAY).toSet()
        }

        val days = LinkedHashSet<Int>()
        for (token in t.split(Regex("""[^a-z]+"""))) {
            if (token.isEmpty()) continue
            DAY_NAMES[token]?.let { days.add(it) }
        }
        return days
    }
}
