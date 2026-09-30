package com.blurr.voice.assistant

/**
 * Whether the popup should go back to being the full sheet.
 *
 * Split out from [SessionBridge] so the rule can be tested. The bridge is an object
 * holding a window and a main-looper [android.os.Handler], neither of which a JVM
 * unit test can construct, and a test that needed one would end up asserting things
 * about the Android framework rather than about the decision.
 *
 * There are two callers with opposite intentions:
 *
 *  * A turn finished. Bring the answer back, *unless* the user armed the
 *    keep-as-a-bubble mode, in which case they asked for the screen to stay clear.
 *  * The user tapped the bubble. Always expand. A standing preference about how tasks
 *    end does not outrank someone reaching for the transcript, and if it did, reading
 *    a reply would require disarming a button first.
 *
 * Ported from the MBTG assistant, where the same two rules were arrived at on a
 * device over a long debugging session. They are kept as two separate functions
 * rather than folded into one flag, because the case that made the second one
 * necessary -- a turn that opened an app -- is not a preference at all and has to be
 * readable as its own rule.
 */
internal object SessionCollapse {

    /**
     * True when the popup should become the full sheet again.
     *
     * [turnFinished] distinguishes the two callers by intent rather than by identity,
     * so the rule reads as the sentence it is: a turn ending expands unless the mode is
     * armed, and a tap always expands.
     */
    fun shouldExpand(turnFinished: Boolean, stayCollapsed: Boolean): Boolean =
        if (turnFinished) !stayCollapsed else true

    /**
     * True when a turn ending should leave the popup as a pill whatever the mode says.
     *
     * [openedApp] is the case this exists for, and it is deliberately not part of
     * [shouldExpand]'s inputs. A tap must still expand, and it does, because the user
     * asking for the transcript means the same thing whether or not the last turn
     * happened to launch something. Only the turn-ending path is governed.
     *
     * The reason an app launch is special: the user asked for the app. The sheet coming
     * back at full height covers the thing they just asked to see, one word of answer
     * ("Opened Settings") is not worth that, and unlike every other turn there is
     * something on the screen they are actually waiting for. So the popup gets out of
     * the way instead of announcing itself over the top of it.
     */
    fun shouldStayPill(turnFinished: Boolean, openedApp: Boolean): Boolean =
        turnFinished && openedApp
}
