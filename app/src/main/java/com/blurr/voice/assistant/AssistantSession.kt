package com.blurr.voice.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.blurr.voice.SettingsActivity
import com.blurr.voice.ui.chat.AssistantChatScreen
import com.blurr.voice.ui.theme.BlurrTheme

/**
 * Hosts the assistant UI inside a [VoiceInteractionSession].
 *
 * This is the part that differs most from a normal screen: there is no Activity, no
 * window token owned by us and no `setContent` plumbing, so the popup is drawn by
 * returning a View from [onCreateContentView] and the Compose tree has to be given
 * its Lifecycle/ViewModelStore/SavedState owners by hand.
 *
 * Call order enforced by the platform (`VoiceInteractionSession.doCreate` /
 * `doShow`) is:
 *   onCreate() -> onCreateContentView() -> onShow() -> window shown
 * so the owners are created in onCreate and started before the view is returned.
 *
 * Ported from the MBTG assistant, which runs the identical arrangement on a real
 * device. The window-flag reasoning, the in-place layoutParams mutation and the
 * dismissal handling are all load-bearing and are explained where they happen.
 */
class AssistantSession(
    context: Context,
    handler: Handler? = null,
) : VoiceInteractionSession(context, handler) {

    private val appContext: Context = context.applicationContext

    private val owners = SessionOwners(appContext)

    /**
     * Posts to the main looper, which is where the window and these callbacks live.
     *
     * The session is constructed with a Handler, so the platform already has one, but
     * it is not exposed and the constructor's handler parameter is nullable. Making
     * our own is cheaper than depending on a null-check at every call site.
     */
    private val main = Handler(Looper.getMainLooper())

    private var contentView: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        owners.onCreate()
        SessionBridge.attach(this)
    }

    override fun onCreateContentView(): View {
        configureWindow()

        val composeView = ComposeView(appContext).apply {
            // These three must be set before the view is attached. ComposeView resolves
            // all of them while building the composition and throws if any is missing
            // -- a plain Dialog window supplies none of them.
            setViewTreeLifecycleOwner(owners)
            setViewTreeViewModelStoreOwner(owners)
            setViewTreeSavedStateRegistryOwner(owners)

            // Ties the composition's lifetime to the session lifecycle, so the
            // recomposer and the saveable registry are torn down with the session
            // instead of leaking into the next invocation.
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )

            // MarginLayoutParams for the reason given in applyWindowMode: the
            // DecorView casts this to MarginLayoutParams during every measure.
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            setContent {
                BlurrTheme {
                    AssistantChatScreen(
                        onOpenSettings = ::openSettings,
                    )
                }
            }
        }

        contentView = composeView
        owners.onStart()
        return composeView
    }

    /**
     * The session window is created lazily by the platform -- `getWindow()` is still
     * null during onCreate() -- so it is tuned here, the first point at which it
     * exists.
     *
     * The framework already puts this dock window at MATCH_PARENT with
     * FLAG_LAYOUT_IN_SCREEN and FLAG_DIM_BEHIND, so only the scrim strength and
     * gravity are adjusted here.
     */
    private fun configureWindow() {
        val dialog = window ?: return
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setDimAmount(DIM_AMOUNT)
            // ADJUST_NOTHING, and it has to be ADJUST_NOTHING. The dock window defaults
            // to adjust=pan, which slides the whole full-screen window up with the
            // keyboard and clips the top; forcing adjust-resize left the window alone
            // while the composition applied the ime inset itself, so the sheet ended up
            // two keyboards clear of the keys. One mechanism only, and it must not be
            // the window: the sheet container in AssistantChatScreen lifts itself from
            // WindowInsets.ime, which is also the only path that still works after a
            // pill restore, where the platform re-shows the window and may re-set its
            // mode.
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            // The display must stay awake for as long as the assistant is on it.
            //
            // Set on the window rather than on the view because the bubble is the case
            // that matters and it is the one a view-level flag would not cover: the
            // ComposeView is not re-created when the window collapses, and the flag does
            // survive, but tying it to the window makes the lifetime obviously identical
            // to the window's. There is no state where the flag is set and the assistant
            // is not on screen, so this cannot leave a phone awake in a pocket.
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyWindowMode(collapsed = false)
        }
    }

    /**
     * Swaps the window between the full sheet and the small bubble.
     *
     * Called on the main looper by [SessionBridge] when the agent loop decides the
     * assistant is about to work on the screen, and again when it has its answer. The
     * reasoning for the flags is on [SessionBridge]; what is worth saying here is that
     * the *view* has to be resized as well as the window.
     *
     * That is the non-obvious part. A `ComposeView` is added to the window with its own
     * `MATCH_PARENT` layout params, and a child that insists on filling its parent
     * will make the window full-screen no matter what the window's own width and height
     * say. Changing `WindowManager.LayoutParams` alone therefore has no visible effect
     * at all, which reads as the flags not working.
     *
     * The soft input mode is deliberately left alone. It only matters for the sheet,
     * and while collapsed the assistant never raises a keyboard.
     */
    internal fun applyWindowMode(collapsed: Boolean) {
        val target = window?.window ?: return

        val size = if (collapsed) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        // Changed in place rather than replaced, and that is the whole trick.
        //
        // The content view's parent is the DecorView's FrameLayout, and a FrameLayout
        // casts each child's params to its *own* LayoutParams type during every measure
        // pass. Any freshly constructed params object is therefore wrong the moment it
        // is assigned, whatever supertype it was built from: a plain
        // ViewGroup.LayoutParams and then a ViewGroup.MarginLayoutParams both crashed
        // the app with a ClassCastException on the relayout this method exists to
        // cause.
        //
        // Mutating the existing object sidesteps the cast entirely, because whatever
        // concrete type the platform gave the view is by definition the one its parent
        // expects. Reassigning it is what schedules the measure.
        contentView?.let { view ->
            view.layoutParams = view.layoutParams?.apply {
                width = size
                height = size
            }
        }

        val params = target.attributes
        if (collapsed) {
            // Dimming the app the user is asking about is the opposite of helpful.
            target.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // NOT_FOCUSABLE gives input focus back to the app underneath, which is what
            // makes rootInActiveWindow the user's app again. NOT_TOUCH_MODAL lets
            // touches outside this bubble reach it. NOT_TOUCHABLE is cleared as well
            // because the platform dock window sets it in some builds, and it would
            // make the bubble itself impossible to tap.
            target.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            )
            target.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            params.gravity = Gravity.TOP or Gravity.START
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = dp(BUBBLE_INSET_DP)
            params.y = dp(BUBBLE_INSET_DP)
        } else {
            target.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            target.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
            params.gravity = Gravity.BOTTOM
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
        }
        target.attributes = params
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // The one decision about how the window opens, for every kind of show. Only
        // our own restore -- marked in the args by restoreAsPill() -- opens as the
        // pill, because that dismissal is the screen the user asked back for.
        // Everything else is a fresh request and opens as the full sheet: a new
        // invocation must never start as a pill, and a pill already sitting in the
        // corner being asked for again expands to the overlay instead of ignoring the
        // press. SessionBridge applies the state and the window together, so the frame
        // and the Compose tree cannot disagree -- the stretched, touch-deaf pill was
        // exactly that disagreement.
        val asPill = args?.getBoolean(ARGS_RESTORE_AS_PILL) == true
        SessionBridge.onSessionShown(asPill)
        // Re-asserted after super, because the platform imposes its own soft-input
        // mode on this dock window at show time: configureWindow's ADJUST_NOTHING was
        // observed overwritten with adjust=pan, and pan translates the window while the
        // composition also pads the ime inset -- together they lifted the sheet two
        // keyboards clear of the keys. Re-setting it here, after the platform's own
        // setup, leaves the ime padding in the sheet container as the single thing
        // that moves the sheet.
        @Suppress("DEPRECATION")
        window?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
        )
        // And once more a beat later. The gap was observed on the *first* keyboard open
        // after a show and gone after the next show, which points at the platform
        // imposing its mode after the onShow callback rather than before it.
        main.postDelayed({
            @Suppress("DEPRECATION")
            window?.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            )
        }, RESTORE_DELAY_MS)
    }

    /**
     * The system wants the session gone.
     *
     * This is the hook that matters. A `VoiceInteractionSession` is not a
     * `Window.Callback`, so there is no `onWindowDismissed` to intercept, and a swipe
     * is not a click, so the Compose scrim behind the sheet never sees it either.
     * What the platform does with a swipe away, an assist gesture or a back press is
     * call this. The default implementation calls [finish], which is the whole problem:
     * once that has run the window is gone, and there is no pill left to tap.
     *
     * So the dismissal is absorbed and the popup drops to a bubble instead. The gesture
     * means "let me see my screen", and a screen you cannot see is not a result, it is
     * the reason the bubble exists in the first place. The one thing that does end the
     * session is the pill's own close button, which says so unambiguously and sets a
     * flag rather than relying on being re-derivable later.
     */
    override fun onBackPressed() {
        // Deliberately no super call when absorbed: super is what calls finish().
        if (SessionBridge.absorbDismissal()) return
        super.onBackPressed()
    }

    /** Ends the session for real. Used by the pill's close button. */
    fun finishNow() {
        finish()
    }

    /**
     * The window is being taken away, and the reason is not always ours.
     *
     * This is the reliable half of exit detection. `onBackPressed` fires for a back
     * press but not for the edge-swipe -- the log shows the platform's gesture monitor
     * stealing that one *before* it ever reaches our window, so there is no callback
     * left to intercept and no way to veto it. `onHide` fires either way, which makes
     * it the one signal that cannot be missed.
     *
     * So when the window goes away without the user having asked for it to, the session
     * is brought back as a pill instead of being allowed to end. That is the difference
     * between the assistant getting out of the way and the assistant disappearing: the
     * answer is still there, one tap away, and the screen underneath is usable.
     *
     * Not attempted when the user closed it on purpose, which is the whole reason
     * [SessionBridge.requestClose] records the intent rather than just calling finish.
     */
    override fun onHide() {
        super.onHide()
        val hiddenByUser = SessionBridge.onWindowHidden()
        // Always false first: whatever happens next, the overlay interface is now
        // allowed to draw again, and a restore that the platform refuses must not leave
        // it suppressed with nothing on screen.
        SessionBridge.onSessionHidden()
        // Stop the recomposer while the window is not visible. Deliberately after the
        // restore decision: bringing the window back needs the composition alive.
        if (!hiddenByUser) owners.onStop()
        else main.postDelayed({ owners.onStart() }, RESTORE_DELAY_MS)
    }

    /**
     * Puts the window back as a pill.
     *
     * [VoiceInteractionSession.show] is the platform's own entry point and the only
     * thing here that can bring a hidden session window back, so it is what gets used.
     * It is posted rather than called inline because [onHide] runs while the window is
     * still mid-teardown, and re-showing inside that window is ignored.
     *
     * A no-op return is possible: the platform refuses a `show()` it did not ask for on
     * some builds, and when that happens the assistant is simply gone. That is the one
     * part of the request the platform does not allow, and no amount of retrying in this
     * process changes it -- the dismissal is owned by the input dispatcher, not by us.
     */
    internal fun restoreAsPill() {
        // The marker is what makes the re-show open as a pill: onShow reads it from the
        // args, so the intent travels with the show itself instead of living in a field
        // that could outlive a restore the platform refused -- which is how a fresh
        // long-press once ended up opening as a pill.
        val args = Bundle().apply { putBoolean(ARGS_RESTORE_AS_PILL, true) }
        main.post { show(args, SHOW_SOURCE_ACTIVITY) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Before anything else, so an action still running cannot post a window change
        // to a session that is on its way out.
        SessionBridge.detach(this)
        SessionBridge.onSessionHidden()
        // Dispose the composition before the lifecycle reaches DESTROYED: the
        // composition is still observing it at this point.
        contentView?.disposeComposition()
        contentView = null
        owners.onDestroy()
    }

    /**
     * Hand over to the app's settings Activity, which is where the AI provider and the
     * permissions are configured. Runtime permissions can only be requested from an
     * Activity, so the session opens one rather than trying to ask for them itself.
     */
    private fun openSettings() {
        val intent = Intent(appContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open settings", it) }
        finish()
    }

    private companion object {
        const val TAG = "AssistantSession"
        const val DIM_AMOUNT = 0.45f

        /**
         * How far the bubble sits from the top-left corner, in dp.
         *
         * Small enough to stay out of the way of a status bar or an app's own top row,
         * which is the one place a bubble in the corner is genuinely likely to cover
         * something the assistant then wants to tap.
         */
        const val BUBBLE_INSET_DP = 12

        /**
         * Args key marking a [show] as our own restore, so [onShow] can tell a
         * dismissed-and-brought-back window apart from a fresh request. Namespaced so
         * no args key the system passes can collide with it.
         */
        const val ARGS_RESTORE_AS_PILL = "com.blurr.voice.assistant.restore_as_pill"

        /**
         * How long after the window is hidden before trying to bring it back, and how
         * long after a show before re-asserting the soft-input mode.
         *
         * Long enough for the hide to have finished. Re-showing inside that window is
         * silently dropped, and a dropped restore is the bug this whole path exists to
         * fix, so the wait is the safer side to err on.
         */
        const val RESTORE_DELAY_MS = 350L
    }
}
