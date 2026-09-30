package com.blurr.voice.sidekey

import android.content.Context
import android.view.KeyEvent

/** What a single Essential Key gesture is allowed to trigger. */
enum class SideKeyAction {
    /** Nothing; the gesture is intentionally unused. */
    NONE,

    /**
     * Snap the screen, analyse it in the background for anything worth being
     * reminded about, schedule what is found, and notify with a summary.
     */
    SNAP_AND_SCHEDULE
}

/**
 * The Essential Key's configuration: whether the integration is on, which
 * scanCode identifies the key, and which gesture maps to which [SideKeyAction].
 *
 * Nothing OS publishes the key as `keyCode=0` with Linux `scanCode=250` on
 * verified devices. The scanCode can be re-learned on the Key Test screen; the
 * default matches every Nothing build seen so far, and a press whose scanCode
 * differs is simply ignored rather than misfiring.
 */
object EssentialKeyMapper {

    private const val TAG = "EssentialKeyMapper"
    private const val PREFS_NAME = "BlurrSettings"

    /** The scanCode Nothing devices report for the Essential Key. */
    const val DEFAULT_SCAN_CODE = 250

    /** Used by ScreenInteractionService to guard the whole key pipeline. */
    private const val KEY_ENABLED = "side_key_enabled"

    private const val KEY_SCAN_CODE = "side_key_scan_code"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the Essential Key integration is turned on in Settings. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The scanCode currently believed to be the Essential Key. */
    fun learnedScanCode(context: Context): Int =
        prefs(context).getInt(KEY_SCAN_CODE, DEFAULT_SCAN_CODE)

    /** Store a re-learned scanCode from Key Test. */
    fun setLearnedScanCode(context: Context, scanCode: Int) {
        prefs(context).edit().putInt(KEY_SCAN_CODE, scanCode).apply()
    }

    /** The action mapped to [gesture], or [SideKeyAction.NONE] when unused. */
    fun actionFor(context: Context, gesture: KeyGesture): SideKeyAction = when (gesture) {
        // v1 maps only the single press: snap + auto-schedule. The double,
        // triple, and long presses stay free for the user's next round of
        // functionality; NONE keeps them from firing anything today.
        KeyGesture.SINGLE_PRESS -> SideKeyAction.SNAP_AND_SCHEDULE
        else -> SideKeyAction.NONE
    }

    /**
     * Only the gestures with a real mapping need recognising. Passing the
     * resulting set to the classifier lets it resolve the single press
     * immediately instead of waiting out the multi-tap window.
     */
    fun gesturesToRecognize(context: Context): Set<KeyGesture> =
        KeyGesture.entries.filter { actionFor(context, it) != SideKeyAction.NONE }.toSet()
}

/** Pure filter decisions for candidate key events, extracted for unit tests. */
object SideKeyEventFilter {

    /** Volume keys must never be mistaken for the Essential Key. */
    fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    /** The (currently learned) Essential Key answers to its scanCode. */
    fun matchesKey(eventScanCode: Int, learnedScanCode: Int): Boolean =
        eventScanCode == learnedScanCode

    /**
     * A captured event is worth showing on the Key Test screen. Volume keys are
     * excluded so the user cannot accidentally learn a volume rocker as the
     * Essential Key.
     */
    fun isLearnableCandidate(keyCode: Int): Boolean = !isVolumeKey(keyCode)
}