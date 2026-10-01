package com.blurr.voice

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.blurr.voice.sidekey.AksCircleToSearch
import com.blurr.voice.sidekey.CircleToSearchOutcome
import com.blurr.voice.sidekey.EssentialKeyMapper
import com.blurr.voice.sidekey.KeyGesture
import com.blurr.voice.sidekey.LensScreenSearch
import com.blurr.voice.sidekey.LensScreenSearchOutcome
import com.blurr.voice.sidekey.SideKeyActionExecutor
import com.blurr.voice.sidekey.SideKeyActionRegistry
import com.blurr.voice.sidekey.SideKeyTarget
import com.blurr.voice.sidekey.SideKeyTargets
import com.blurr.voice.ui.sidekey.SideKeyActionDialog
import com.blurr.voice.ui.sidekey.SideKeyPickerNav
import com.blurr.voice.ui.theme.BlurrTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single-choice action picker for one Essential Key press type.
 *
 * ## How it is hosted
 *
 * The dialog is a [ComposeView] inside an [android.app.Dialog] rather than a Compose
 * [androidx.compose.ui.window.Dialog] built at the call site, because the settings
 * screen that opens it is itself Compose but the caller is not: this keeps one
 * implementation that works regardless of how the host screen is built. The
 * [android.app.Dialog] window is transparent and sized to wrap, so the composable's own
 * sheet is the whole thing the user sees.
 *
 * The list is rendered from the same action registry the settings rows read, so adding
 * an action later needs no change here. The "Test it" button runs the highlighted action
 * immediately -- which matters most for the screen-reading actions, since the thing they
 * read is whatever happens to be on the display at the time, and that is usually not the
 * settings screen.
 */
object SideKeyActionPicker {

    /** Human-readable press names, in the order the settings rows show them. */
    val pressOrder: List<Pair<KeyGesture, String>> = listOf(
        KeyGesture.SINGLE_PRESS to "Single press",
        KeyGesture.DOUBLE_PRESS to "Double press",
        KeyGesture.TRIPLE_PRESS to "Triple press",
        KeyGesture.LONG_PRESS to "Long press"
    )

    /** @return the display name of [gesture], for the settings rows. */
    fun pressLabel(gesture: KeyGesture): String =
        pressOrder.firstOrNull { it.first == gesture }?.second ?: gesture.name

    /**
     * Shows the picker for [gesture] and stores the choice.
     *
     * @param onChanged invoked after a selection is written, so the caller can
     *   refresh the press row's current-action label.
     */
    fun show(activity: Activity, gesture: KeyGesture, onChanged: () -> Unit) {
        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val nav = SideKeyPickerNav()
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                BlurrTheme {
                    SideKeyActionDialog(
                        gestureLabel = pressLabel(gesture),
                        currentId = EssentialKeyMapper.actionIdFor(activity, gesture),
                        nav = nav,
                        onPick = { id ->
                            EssentialKeyMapper.setActionIdFor(activity, gesture, id)
                            onChanged()
                            dialog.dismiss()
                        },
                        onTest = { id -> testAction(activity, id) },
                        onDismiss = { dialog.dismiss() },
                    )
                }
            }
        }
        dialog.setContentView(composeView)

        // The window sees the back key before anything inside Compose does, and the
        // dialog's own answer to it is to dismiss -- which, from "Which app?" or
        // "Which link?", would throw away the half-finished choice. So back is asked
        // of the step state first and only closes the dialog when there is no step
        // to go back to.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP && !nav.back()) dialog.dismiss()
            true
        }
        dialog.show()

        // After show(), so it is not overwritten by the show pass: the dialog window
        // has to shrink-wrap its content. Left at the match-parent default the
        // ComposeView fills the screen and the sheet is drawn against the display
        // edges, with the dialog's own dim behind nothing.
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        // The surface is only as wide as the window allows, and a wrap-content window
        // measured from an unconstrained ComposeView can land narrower than the phone.
        // Without this the sheet shows at a fraction of the screen width.
        composeView.minimumWidth = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
    }

    /**
     * Runs [actionId] once without pressing the key, so the user can see what it
     * actually does before mapping it to a press.
     *
     * The stored id arrives rather than a resolved spec, because the two targeted
     * actions -- a chosen app, a chosen link -- keep their target inside the id. A
     * spec alone would only say "open an app", which is the idea of the action and
     * not the one the user picked.
     */
    private fun testAction(activity: Activity, actionId: String) {
        // A chosen target is tested by running it, and it goes first because it needs
        // nothing but an ordinary startActivity: an app or link mapping can be tried
        // even when the accessibility service is down, which is the very state the
        // message below is about.
        SideKeyTargets.parse(actionId)?.let { target ->
            if (!SideKeyTargets.launch(activity, target)) {
                val what = when (target) {
                    is SideKeyTarget.OpenApp -> SideKeyTargets.appLabel(activity, target.packageName)
                    is SideKeyTarget.OpenLink -> "that link"
                }
                Toast.makeText(activity, "Could not open $what.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val service = ScreenInteractionService.instance
        if (service == null) {
            Toast.makeText(
                activity,
                "Delta's accessibility service is not connected, so it cannot dispatch actions.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        when (actionId) {
            SideKeyActionRegistry.NONE_ID ->
                Toast.makeText(activity, "Do nothing has nothing to test.", Toast.LENGTH_SHORT).show()

            SideKeyActionRegistry.CIRCLE_TO_SEARCH_ID -> {
                Toast.makeText(activity, "Opening Circle to Search...", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.Main).launch {
                    val outcome = AksCircleToSearch.trigger(service)
                    if (outcome == CircleToSearchOutcome.FAILED) {
                        Toast.makeText(
                            activity,
                            "Circle to Search did not open. Check it's installed and its accessibility service is on.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            SideKeyActionRegistry.GOOGLE_LENS_SCREEN_ID -> {
                Toast.makeText(activity, "Sending this screen to Google Lens...", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.Main).launch {
                    val outcome = LensScreenSearch.searchScreen(service)
                    if (outcome == LensScreenSearchOutcome.FAILED) {
                        Toast.makeText(
                            activity,
                            "Google Lens did not open. The screen could not be captured, or Lens refused it.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            // Everything the executor can carry out is tested through the executor,
            // so a test cannot drift from what the key itself would do.
            SideKeyActionRegistry.TOGGLE_FLASHLIGHT_ID,
            SideKeyActionRegistry.OPEN_QUICK_SETTINGS_ID,
            SideKeyActionRegistry.MEDIA_PLAY_PAUSE_ID,
            SideKeyActionRegistry.MEDIA_NEXT_ID,
            SideKeyActionRegistry.MEDIA_PREVIOUS_ID -> {
                val spec = SideKeyActionRegistry.specFor(actionId)
                CoroutineScope(Dispatchers.Main).launch {
                    if (!SideKeyActionExecutor.execute(service, spec)) {
                        Toast.makeText(
                            activity,
                            "${spec.label} did not run.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            else -> Toast.makeText(
                activity,
                "No test available for ${SideKeyActionRegistry.specFor(actionId).label}.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
