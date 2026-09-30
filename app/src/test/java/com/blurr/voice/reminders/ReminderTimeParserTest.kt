package com.blurr.voice.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * These cover the phrases the conversational layer actually produces. The
 * model is told to pass time expressions through verbatim, so anything it
 * might emit has to parse.
 */
class ReminderTimeParserTest {

    private fun at(base: Long, h: Int, m: Int, d: Int, mon: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(2026, mon - 1, d, h, m, 0)
        }.timeInMillis

    // A Tuesday, 29 September 2026, 10:00 local.
    private val tuesday10am = at(0, 10, 0, 29, 9)

    @Test
    fun `duration with unit`() {
        assertEquals(300L, ReminderTimeParser.parseDuration("5 minutes"))
        assertEquals(300L, ReminderTimeParser.parseDuration("5 min"))
        assertEquals(7200L, ReminderTimeParser.parseDuration("2 hours"))
        assertEquals(90L, ReminderTimeParser.parseDuration("90 seconds"))
        assertEquals(90L, ReminderTimeParser.parseDuration("90s"))
    }

    @Test
    fun `compound duration`() {
        assertEquals(5400L, ReminderTimeParser.parseDuration("1h30m"))
        assertEquals(5400L, ReminderTimeParser.parseDuration("1 hour 30 minutes"))
        assertEquals(5400L, ReminderTimeParser.parseDuration("1 hour and 30 mins"))
    }

    @Test
    fun `bare number is minutes`() {
        assertEquals(600L, ReminderTimeParser.parseDuration("10"))
    }

    @Test
    fun `no number at all`() {
        assertNull(ReminderTimeParser.parseDuration("soon"))
        assertNull(ReminderTimeParser.parseDuration(""))
    }

    @Test
    fun `duration wins over a clock reading`() {
        // "10" must be read as ten minutes, not ten o'clock.
        val result = ReminderTimeParser.parse("in 10 minutes", tuesday10am)
        assertEquals(tuesday10am + 10 * 60_000L, result)
    }

    @Test
    fun `word numbers are not understood`() {
        // Spelled-out numbers are the model's job to normalise; the parser
        // only reads digits, and returning null surfaces that as an error
        // rather than scheduling something at the wrong time.
        assertNull(ReminderTimeParser.parseDuration("twenty minutes"))
    }

    @Test
    fun `clock time later today`() {
        // 10:00 -> 6pm the same day.
        assertEquals(
            at(0, 18, 0, 29, 9),
            ReminderTimeParser.parse("at 6pm", tuesday10am)
        )
    }

    @Test
    fun `clock time already past rolls to tomorrow`() {
        // 10:00 -> 8am, which has gone, so it lands tomorrow.
        assertEquals(
            at(0, 8, 0, 30, 9),
            ReminderTimeParser.parse("at 8am", tuesday10am)
        )
    }

    @Test
    fun `explicit tomorrow`() {
        assertEquals(
            at(0, 18, 0, 30, 9),
            ReminderTimeParser.parse("tomorrow at 6pm", tuesday10am)
        )
    }

    @Test
    fun `twelve hour clock`() {
        assertEquals(at(0, 0, 30, 30, 9), ReminderTimeParser.parse("at 12:30am", tuesday10am))
        assertEquals(at(0, 12, 30, 29, 9), ReminderTimeParser.parse("at 12:30pm", tuesday10am))
    }

    @Test
    fun `morning is not treated as pm`() {
        // 7 with "morning" present must stay 7am, not become 7pm.
        val result = ReminderTimeParser.parse("at 7 in the morning", tuesday10am)
        assertEquals(at(0, 7, 0, 30, 9), result)
    }

    @Test
    fun `garbage yields null`() {
        assertNull(ReminderTimeParser.parse("whenever I feel like it", tuesday10am))
    }

    @Test
    fun `repeat days`() {
        val weekdays = ReminderTimeParser.parseRepeatDays("weekdays")
        assertEquals(5, weekdays.size)
        assert(weekdays.contains(Calendar.MONDAY))
        assert(!weekdays.contains(Calendar.SATURDAY))

        val weekends = ReminderTimeParser.parseRepeatDays("weekends")
        assertEquals(setOf(Calendar.SATURDAY, Calendar.SUNDAY), weekends)

        assertEquals(7, ReminderTimeParser.parseRepeatDays("daily").size)
        assertEquals(setOf(Calendar.MONDAY, Calendar.FRIDAY), ReminderTimeParser.parseRepeatDays("mon, fri"))
        assertEquals(setOf(Calendar.WEDNESDAY), ReminderTimeParser.parseRepeatDays("every Wednesday"))
        assertEquals(emptySet<Int>(), ReminderTimeParser.parseRepeatDays(null))
        assertEquals(emptySet<Int>(), ReminderTimeParser.parseRepeatDays("none"))
    }

    @Test
    fun `next occurrence is today when the time is still ahead`() {
        // Repeating at 6pm, from 10am on a Tuesday.
        val r = Reminder(
            id = 1,
            label = "x",
            triggerAtMillis = at(0, 18, 0, 29, 9),
            repeatDays = setOf(Calendar.TUESDAY)
        )
        assertEquals(at(0, 18, 0, 29, 9), ReminderScheduler.nextOccurrence(r, tuesday10am))
    }

    @Test
    fun `next occurrence rolls forward a week when todays slot has passed`() {
        // Repeating Tuesdays at 8am, from 10am on a Tuesday: today is gone.
        val r = Reminder(
            id = 1,
            label = "x",
            triggerAtMillis = at(0, 8, 0, 29, 9),
            repeatDays = setOf(Calendar.TUESDAY)
        )
        val next = ReminderScheduler.nextOccurrence(r, tuesday10am)
        assertEquals(at(0, 8, 0, 6, 10), next) // +7 days
    }

    @Test
    fun `next occurrence skips to the next selected weekday`() {
        // Repeating Fridays at 9am, from Tuesday.
        val r = Reminder(
            id = 1,
            label = "x",
            triggerAtMillis = at(0, 9, 0, 2, 10),
            repeatDays = setOf(Calendar.FRIDAY)
        )
        val next = ReminderScheduler.nextOccurrence(r, tuesday10am)
        assertEquals(at(0, 9, 0, 2, 10), next)
    }

    @Test
    fun `one-off reminder has no next occurrence`() {
        val r = Reminder(id = 1, label = "x", triggerAtMillis = tuesday10am, repeatDays = emptySet())
        assertNull(ReminderScheduler.nextOccurrence(r, tuesday10am))
    }
}
