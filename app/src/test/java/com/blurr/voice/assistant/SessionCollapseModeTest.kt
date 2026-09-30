package com.blurr.voice.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a finished task should bring the sheet back.
 *
 * The mode is one boolean, so a test of the boolean itself would prove nothing. What
 * can be got wrong is the *wiring* around it, and each of these cases is a specific way
 * the user ends up trapped or surprised:
 *
 *  * A turn that ends while the mode is armed must leave the bubble alone. If it did not,
 *    the button would be decoration and the user would see the sheet anyway.
 *  * A deliberate tap must open the bubble whatever the mode says. If it did not, the
 *    only way to read a reply would be to disarm the button first, which is a needlessly
 *    clever thing to require of someone who wants to read a message.
 *  * Turning the mode off while sitting on a bubble must put the sheet back at once.
 *    Otherwise the button looks broken until the next request happens to come along.
 *
 * The rules are expressed as a small pure function so this can run on the JVM. The real
 * bridge is an object holding a window and a main-looper Handler, which no unit test can
 * construct, and a test that needed one would be testing the Android framework rather
 * than the decision.
 *
 * Carried over from the MBTG assistant, where these were arrived at on a device over a
 * long debugging session. The behaviours they pin are not guesses.
 */
class SessionCollapseModeTest {

    @Test
    fun `a turn ending with the mode armed leaves the bubble collapsed`() {
        assertFalse(
            "armed mode must survive a turn ending",
            SessionCollapse.shouldExpand(turnFinished = true, stayCollapsed = true),
        )
    }

    @Test
    fun `a turn ending with the mode off expands as it always did`() {
        assertTrue(
            SessionCollapse.shouldExpand(turnFinished = true, stayCollapsed = false),
        )
    }

    @Test
    fun `a deliberate tap expands even while the mode is armed`() {
        // The asymmetry is the whole point. A standing preference describes how tasks
        // end; it does not get to overrule the user reaching for the transcript.
        assertTrue(
            "user tap must win over the mode",
            SessionCollapse.shouldExpand(turnFinished = false, stayCollapsed = true),
        )
    }

    @Test
    fun `a tap expands regardless of the mode`() {
        assertTrue(SessionCollapse.shouldExpand(turnFinished = false, stayCollapsed = false))
    }

    // ---- a turn that opened an app ---------------------------------------

    /**
     * The bug this rule was added for.
     *
     * Opening an app collapsed the popup so the app could come forward, and then the
     * turn finished and expanded it again -- a full-height sheet landing on top of the
     * app the user had just successfully opened. Seen on the device: bubble at
     * 02:49:47.062, sheet again at 02:49:48.970, with focus still on the assistant.
     */
    @Test
    fun `a turn that opened an app stays a pill`() {
        assertTrue(
            "the sheet must not come back over the app just opened",
            SessionCollapse.shouldStayPill(turnFinished = true, openedApp = true),
        )
    }

    /**
     * An app launch is not a standing preference.
     *
     * The flag is read and cleared per turn, and this is the case that proves why: if it
     * leaked, every subsequent question would also refuse to expand and the popup would
     * stay a pill forever with no way to tell that anything had gone wrong.
     */
    @Test
    fun `an ordinary turn after one that opened an app is unaffected`() {
        assertFalse(
            SessionCollapse.shouldStayPill(turnFinished = true, openedApp = false),
        )
    }

    /**
     * A tap still opens the transcript after an app launch.
     *
     * Kept separate from [SessionCollapse.shouldExpand] on purpose. The user reaching for
     * the answer is the same request whether the turn launched an app or read a screen,
     * and the pill has to stay tappable in both cases -- it is the only way back to the
     * answer at all.
     */
    @Test
    fun `a tap still expands after an app launch`() {
        assertFalse(
            "a tap must never be refused",
            SessionCollapse.shouldStayPill(turnFinished = false, openedApp = true),
        )
    }
}
