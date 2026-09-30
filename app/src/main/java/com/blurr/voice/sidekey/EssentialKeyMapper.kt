package com.blurr.voice.sidekey

import android.content.Context
import android.view.KeyEvent

/**
 * The Essential Key's configuration: whether the integration is on, which
 * scanCode identifies the key, and which gesture maps to which action.
 *
 * Nothing OS publishes the key as `keyCode=0` with Linux `scanCode=250` on
 * verified devices. The scanCode can be re-learned on the Key Test screen; the
 * default matches every Nothing build seen so far, and a press whose scanCode
 * differs is simply ignored rather than misfiring.
 *
 * Mappings are stored as [SideKeyActionSpec.id] strings under one key per
 * gesture, so a press can be pointed at any action in
 * [SideKeyActionRegistry] and an id that is later removed reads back as
 * [SideKeyActionRegistry.NONE_ID] instead of failing.
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

    /** The prefs key holding [gesture]'s action id. */
    private fun keyFor(gesture: KeyGesture): String = "side_key_action_${gesture.name.lowercase()}"

    /** The raw action id stored for [gesture], or null when never set. */
    fun actionIdFor(context: Context, gesture: KeyGesture): String? =
        prefs(context).getString(keyFor(gesture), null)

    /** Assigns [actionId] to [gesture]; anything unrecognised stores as "do nothing". */
    fun setActionIdFor(context: Context, gesture: KeyGesture, actionId: String) {
        val known = SideKeyActionRegistry.actions.firstOrNull { it.id == actionId }
        val stored = known?.id ?: SideKeyActionRegistry.NONE_ID
        prefs(context).edit().putString(keyFor(gesture), stored).apply()
    }

    /** The action mapped to [gesture], or "do nothing" when unmapped. */
    fun actionFor(context: Context, gesture: KeyGesture): SideKeyActionSpec =
        SideKeyActionRegistry.specFor(actionIdFor(context, gesture))

    /**
     * Only the gestures with a real mapping need recognising. Passing the
     * resulting set to the classifier is what makes timing adaptive: a lone
     * single-press mapping resolves the instant the key comes up, and adding a
     * double or triple mapping is enough to make the classifier wait out the
     * multi-tap window so both still work.
     */
    fun gesturesToRecognize(context: Context): Set<KeyGesture> =
        KeyGesture.entries
            .filter { actionIdFor(context, it) != null && actionIdFor(context, it) != SideKeyActionRegistry.NONE_ID }
            .toSet()
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
