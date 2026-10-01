package com.blurr.voice.sidekey

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/**
 * The fixed actions that are not about reading the screen: the phone's own quick
 * toggles and media keys.
 *
 * These are here rather than in [SideKeyActionExecutor] because each one has real
 * platform behaviour to get right, and getting it wrong is visible rather than
 * silent -- a torch that never lights, or a media key sent once so the player skips
 * instead of toggling.
 */
object SideKeyShortcuts {

    private const val TAG = "SideKeyShortcuts"
    private const val PREFS_NAME = "BlurrSettings"
    private const val KEY_TORCH_ON = "side_key_torch_on"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Flips the torch, and reports whether the flip was accepted.
     *
     * Toggle rather than "turn on", because a press mapped to a light is worth nothing
     * if pressing it twice leaves the user holding a lit torch in a dark room.
     *
     * The platform exposes only `setTorchMode(cameraId, on)` -- there is no getter to
     * read the current state back -- so "what was it before" is kept here instead.
     * That is a deliberate trade: if the user turns the torch off from the lock screen
     * after using this action, our record is stale and the next press turns it back on
     * rather than off. Reading a real getter would fix that but the platform does not
     * offer one, and treating every press as "turn on" would strand a lit torch with no
     * way to put it out from the key.
     *
     * @return false when the device has no flash unit, which is the one case the user
     *   can act on by mapping something else.
     */
    fun toggleFlashlight(context: Context): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        val cameraId = manager.cameraIdList.firstOrNull { hasFlash(manager, it) } ?: return false
        val wasOn = prefs(context).getBoolean(KEY_TORCH_ON, false)
        return runCatching {
            manager.setTorchMode(cameraId, !wasOn)
            prefs(context).edit().putBoolean(KEY_TORCH_ON, !wasOn).apply()
            true
        }.getOrElse {
            // The flash can be claimed by another app -- the camera app, most often.
            Log.w(TAG, "torch refused", it)
            false
        }
    }

    /**
     * Sends a media key as a full down/up pair.
     *
     * Both halves matter: dispatching only the down leaves the player believing the
     * key is still held, which is why a one-shot dispatch tends to work once and then
     * stop responding.
     */
    fun mediaKey(context: Context, keyCode: Int): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        }.getOrElse {
            Log.w(TAG, "media key $keyCode refused", it)
            false
        }
    }

    /** The key code for a press type's media action, as stored in the registry. */
    fun keyCodeFor(actionId: String): Int? = when (actionId) {
        SideKeyActionRegistry.MEDIA_PLAY_PAUSE_ID -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        SideKeyActionRegistry.MEDIA_NEXT_ID -> KeyEvent.KEYCODE_MEDIA_NEXT
        SideKeyActionRegistry.MEDIA_PREVIOUS_ID -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> null
    }

    /**
     * Whether [cameraId] has a flash unit.
     *
     * Read through [runCatching] because a camera can disappear between listing the
     * ids and describing them, and one dead id should not hide the working one behind
     * it.
     */
    private fun hasFlash(manager: CameraManager, cameraId: String): Boolean = runCatching {
        manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }.getOrDefault(false)
}