package com.blurr.voice.assistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets the agent loop shrink the assistant popup out of the way while it works.
 *
 * ## Why the popup has to get out of the way
 *
 * A `VoiceInteractionSession` dock window is created full-screen, focus-taking and
 * dimming whatever is behind it. That is right for a conversation and fatal for a
 * screen task: an accessibility service reads `rootInActiveWindow`, which is the
 * topmost *focused* window, so while the popup is up the assistant reads its own
 * user interface and concludes the user's app is a chat transcript. It then taps
 * coordinates that belong to itself. The model cannot work around this because it
 * never gets to see the real screen in the first place.
 *
 * Collapsing to a small bubble is the fix, and the flags that do it are the reason it
 * works:
 *
 *  * `FLAG_NOT_FOCUSABLE` hands input focus back to the app underneath, so
 *    `rootInActiveWindow` is the user's app again.
 *  * `FLAG_NOT_TOUCH_MODAL` lets touches *outside* the bubble fall through to that
 *    app, so a dispatched tap lands where it was aimed. `NOT_TOUCHABLE` would have
 *    been too strong: it would make the bubble unclickable, and tapping it to bring
 *    the transcript back is the user's way out.
 *
 * ## Why this is a bridge and not a parameter
 *
 * The caller is the task agent, which runs on a background dispatcher and has no
 * idea a window exists. The session is created and destroyed by the system on every
 * invocation, so there is nothing to inject. The session registers itself here and
 * this object holds the only reference that outlives an invocation.
 *
 * Ported from the MBTG assistant, where the collapse/dismiss/restore behaviour was
 * arrived at over a long debugging session on a real device. The temporary
 * diagnostics that were in that version are deliberately not carried over.
 */
object SessionBridge {

    private val _collapsed = MutableStateFlow(false)

    /** True while the popup is a bubble rather than the full sheet. */
    val collapsed: StateFlow<Boolean> = _collapsed.asStateFlow()

    private val _stayCollapsed = MutableStateFlow(false)

    /**
     * Whether a finished task should leave the popup as a bubble.
     *
     * The user sets this from a button beside the new-chat control. It is a mode
     * rather than a one-shot because the thing it describes is not "hide this one
     * answer" but "when I ask for something to be done, get out of the way and stay
     * out of the way until I ask". Re-arming it per task would mean a button press
     * before every single request, which is the busywork the button exists to remove.
     *
     * It survives a session teardown, unlike [collapsed]. A new invocation still opens
     * as the full sheet -- the microphone needs somewhere to show a transcript, and a
     * popup that opened as a bubble would be showing a spinner for work nobody asked
     * for -- but every task run inside that invocation then ends as a bubble.
     *
     * In memory only. It is lost if the process dies, which is survivable: the mode
     * comes back off and the next task expands as it always did.
     */
    val stayCollapsed: StateFlow<Boolean> = _stayCollapsed.asStateFlow()

    private val _sessionVisible = MutableStateFlow(false)

    /**
     * True between the window being shown and being hidden.
     *
     * Read by [com.blurr.voice.ConversationalAgentService] to stand its own overlay
     * interface down while the session popup is up. Two assistant UIs on screen at
     * once is not a cosmetic problem: the overlay holds its own input field and
     * transcription caption, and the accessibility service reads whichever window is
     * focused, so the task agent would end up tapping the overlay instead of the
     * user's app.
     */
    val sessionVisible: StateFlow<Boolean> = _sessionVisible.asStateFlow()

    @Volatile
    private var session: AssistantSession? = null

    /**
     * When the screen was last disturbed, on the monotonic clock.
     *
     * Monotonic rather than wall time because this is only ever subtracted from itself
     * to measure an elapsed duration, and a wall clock that steps backwards mid-turn
     * would produce a negative remainder and skip the wait entirely.
     */
    @Volatile
    private var disturbedAt: Long = 0L

    /**
     * The session's callbacks and window both live on the main looper, but the agent
     * loop does not, so window changes are posted rather than made inline.
     */
    private val main = Handler(Looper.getMainLooper())

    internal fun attach(active: AssistantSession) {
        session = active
        // Always start a new invocation as the full sheet, even if the previous one was
        // collapsed when the system tore it down, and even if the mode is still armed.
        // A popup that opened as a bubble would be showing the user a progress line
        // for work they have not asked about yet. _stayCollapsed deliberately is not
        // cleared here: the mode describes how a task ends, and the user has not
        // turned it off.
        //
        // The one exception -- a restore from a dismissal -- is not decided here. The
        // restore's marker travels in the show's own args and is applied in onShow,
        // which every show passes through. Deciding from a flag that outlived a
        // refused restore is what once made a *fresh* long-press open as a pill.
        _collapsed.value = false
        // A new invocation starts with no pending close. The flag only means anything
        // within one session -- "the user dismissed this and I should not fight it" --
        // so carrying it forward would make the very first swipe of the next
        // conversation get ignored.
        closeRequested = false
    }

    internal fun detach(ended: AssistantSession) {
        // Only clear if it is still ours: the platform can rebind before tearing the
        // old session down, and clearing then would unhook the live one.
        if (session === ended) {
            session = null
            _collapsed.value = false
        }
    }

    /**
     * The assistant is about to touch the screen: get out of its way.
     *
     * Also starts the settling clock, because the two things that follow are the two
     * reasons a read taken immediately afterwards would be wrong: this window has not
     * finished shrinking and giving up focus, and the app underneath has not started
     * responding yet.
     */
    fun beginScreenWork() {
        disturbedAt = SystemClock.uptimeMillis()
        collapse()
    }

    /**
     * Down to the bubble, if not already there.
     *
     * Guarded because collapsing an already-collapsed popup would post a second
     * relayout for no visible change, and [settleAfterTurn] calls this on every turn
     * including the ones that spent their whole duration as a pill.
     */
    private fun collapse() {
        if (_collapsed.value) return
        _collapsed.value = true
        // Applies the current state at run time rather than the literal `true`: a show
        // can land between this post and its execution, and onShow (which also sets
        // the state) is then the newer decision -- applying a captured literal here
        // would put the window back in the old mode underneath the fresh state.
        main.post { session?.applyWindowMode(_collapsed.value) }
    }

    /**
     * Notes that the screen is now mid-change, without collapsing for it.
     *
     * For the moves that are not screen tasks, chiefly opening an app. There is
     * nothing to get out of the way for, but the next read still has to wait, so this
     * records the moment without touching the window.
     */
    fun noteDisturbance() {
        disturbedAt = SystemClock.uptimeMillis()
    }

    /**
     * Waits out the settling window before the screen is read or acted on.
     *
     * A floor, not a total. The task agent already waits for the screen fingerprint to
     * stop changing, and that handles the long tail. What it structurally cannot
     * handle is the *start* of a change: if the screen is currently static and the app
     * has not begun launching yet, two consecutive reads are identical, the wait
     * concludes all is well and returns immediately, and the app then opens underneath
     * the decision that was just made. A fixed grace period closes exactly that hole
     * and nothing else.
     */
    suspend fun awaitScreenReady() {
        val remaining = ScreenSettleGraceMillis - (SystemClock.uptimeMillis() - disturbedAt)
        if (remaining > 0) delay(remaining)
    }

    /** Back to the full sheet, for the answer and for the user tapping the bubble. */
    fun expand() {
        if (!_collapsed.value) return
        _collapsed.value = false
        // Current state, not the literal `false` -- same race as [collapse].
        main.post { session?.applyWindowMode(_collapsed.value) }
    }

    /**
     * The end of a turn: the sheet, or a pill when the user has asked for one.
     *
     * This *collapses* rather than merely declining to expand. Arming the mode and
     * getting no pill from a plain question is the mode looking broken: "what's on my
     * screen" gets an answer with no screen tool involved, so nothing collapsed it on
     * the way in, and refusing to expand on the way out leaves the full sheet up. A
     * turn that never collapsed has to collapse here, or the button only works for
     * tasks that happen to use a screen tool.
     */
    fun settleAfterTurn() {
        // Read and cleared in one step, because the flag describes *this* turn. Left
        // set, the next ordinary question would also refuse to expand, and the popup
        // would quietly get stuck as a pill until the session was torn down.
        val openedApp = appLaunched
        appLaunched = false

        if (SessionCollapse.shouldStayPill(turnFinished = true, openedApp = openedApp) ||
            !SessionCollapse.shouldExpand(turnFinished = true, stayCollapsed = _stayCollapsed.value)
        ) {
            collapse()
        } else {
            expand()
        }
    }

    /**
     * This turn put an app on the screen.
     *
     * The reason this is a flag rather than a table entry is that the decision it
     * changes is not about the action, it is about what happens at the *end* of the
     * turn. Reading a screen also moves it, and expanding over a screen read is
     * correct -- the user is waiting for an answer about a list. Opening an app is the
     * one move where the app, not the answer, is what they came for.
     */
    @Volatile
    private var appLaunched = false

    fun noteAppLaunched() {
        appLaunched = true
    }

    /**
     * The user asked for the transcript, which no standing preference can veto.
     *
     * Routed through the same rule as [settleAfterTurn] so the two callers cannot drift
     * apart, but with the tap's intent rather than the turn's. [expand] underneath is
     * still what actually resizes the window.
     */
    fun expandOnUserRequest() {
        if (!SessionCollapse.shouldExpand(turnFinished = false, stayCollapsed = _stayCollapsed.value)) return
        expand()
    }

    /**
     * The user pressed the bubble's close button, and meant it.
     *
     * The difference between this and "the popup was dismissed" is the whole reason
     * the bubble can come back at all. A swipe away, a back press and a tap outside
     * are all requests to see the screen again, and a screen you cannot see is
     * useless -- so those are turned into a pill rather than allowed to end the
     * session. The X on the pill is the one unambiguous "this is finished, take it
     * away", and it has to be remembered separately because by the time it is pressed
     * there is no popup left to consult: the next dismissal arrives with the window
     * already half-torn-down, and a flag is the only thing still in scope to say what
     * the user meant.
     */
    @Volatile
    private var closeRequested = false

    /** The X on the pill: this one really is going away. */
    fun requestClose() {
        closeRequested = true
        session?.finishNow()
    }

    /**
     * A dismissal the system asked for, answered with a pill instead of an ending.
     *
     * Returns whether the dismissal was absorbed. False means the user genuinely wants
     * this gone, and the caller must let the platform finish the session.
     */
    fun absorbDismissal(): Boolean {
        if (closeRequested) return false
        collapse()
        return true
    }

    /**
     * The user asked for their screen back, by any route we can see.
     *
     * One funnel for the scrim tap, the back press and the window going away, because
     * the three used to disagree with each other -- the scrim dismissed, the back press
     * collapsed, and a swipe did whatever the platform felt like -- and the complaint
     * was not about any one of them. It was that the assistant kept vanishing instead
     * of getting out of the way.
     *
     * Unconditional. Not gated on the keep-as-a-bubble button, because arriving here
     * already means the user has dismissed the popup by hand, and asking them to have
     * armed a mode beforehand in order to be listened to is the wrong order of
     * operations. The button governs what happens when a turn *ends*; this governs
     * what happens when the user says "not now", and those are different questions.
     */
    fun dismissToPill() {
        collapse()
    }

    /**
     * The window is going away. Bring it back as a pill unless the user meant it.
     *
     * Returns whether a restore was attempted, so the caller knows whether it still has
     * to stop the recomposer.
     *
     * Skipped entirely when there is nothing to come back to. Restoring an idle session
     * with no transcript and no work in flight puts an empty pill on a screen the user
     * just finished looking at, which is worse than letting it go: the gesture meant
     * "I am done with the assistant", and an assistant that will not stay done is a
     * window that cannot be cleared.
     */
    fun onWindowHidden(): Boolean {
        val active = session
        val chat = AssistantSessionState.state.value
        if (closeRequested) return false
        if (chat.lines.isEmpty()) return false
        if (active == null) return false
        // The re-show lands on a pill because the marker rides in the show's own args
        // (AssistantSession.restoreAsPill), not because anything is remembered here.
        // So nothing pending survives a platform-refused restore, and the next fresh
        // invocation always opens as the full sheet.
        active.restoreAsPill()
        return true
    }

    /**
     * The window is being shown: open it in the mode the show asked for.
     *
     * The single funnel for every kind of show -- a long-press of the power button,
     * the platform re-showing a window it hid, and our own restore after a dismissal
     * -- because two things have to move together and used to drift apart: the Compose
     * tree reads [collapsed] to draw the sheet or the pill, and the window's flags,
     * frame and gravity come from `applyWindowMode`. Setting one without the other
     * produces the stretched, touch-deaf pill: a full-sheet window framing a bubble,
     * swallowing every tap that was not on the pill itself.
     *
     * Runs on the main looper, where the session callbacks live, and applies the
     * window synchronously: onShow is the last callback before the window is drawn.
     * The delayed pass re-asserts it, because the platform imposes parts of this dock
     * window's configuration *after* the callback, and it re-reads the current state
     * rather than `asPill`, since a turn may have collapsed or expanded the popup in
     * between.
     */
    internal fun onSessionShown(asPill: Boolean) {
        _collapsed.value = asPill
        _sessionVisible.value = true
        session?.applyWindowMode(asPill)
        main.postDelayed(
            { session?.applyWindowMode(_collapsed.value) },
            WindowModeReassertMillis,
        )
    }

    /**
     * The window went away for good, or is being hidden.
     *
     * Separate from [onWindowHidden]'s restore attempt because this is the flag the
     * overlay interface reads to decide whether it may draw.
     */
    internal fun onSessionHidden() {
        _sessionVisible.value = false
    }

    /** Arms or disarms the mode, from the button beside new-chat. */
    fun setStayCollapsed(enabled: Boolean) {
        _stayCollapsed.value = enabled
        // Disarming while sitting on a bubble should not wait for a turn to end, or the
        // button would appear to do nothing until the next request. Putting the sheet
        // back immediately is also what the user means by turning it off: stop holding
        // the screen for me.
        if (!enabled) expand()
    }

    /**
     * How long the screen is given to start reacting.
     *
     * Long enough to cover the two transitions that actually happen, and no longer: the
     * window relayout and focus handover above, and an app being asked to start.
     * Anything beyond this is the agent's own settle, and making this longer would just
     * add dead time to every screen task to solve a problem that waiting for stability
     * already solves better.
     */
    private const val ScreenSettleGraceMillis = 500L

    /**
     * How long after a show before the window mode is applied a second time.
     *
     * The same beat at which the soft-input re-assert in [AssistantSession.onShow]
     * runs, because that delay exists for the same reason: the platform imposes some
     * of this window's configuration after the onShow callback, and anything set
     * earlier can be overwritten a moment later than it was written.
     */
    private const val WindowModeReassertMillis = 500L
}
