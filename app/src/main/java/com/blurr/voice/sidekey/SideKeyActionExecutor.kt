package com.blurr.voice.sidekey

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import com.blurr.voice.RoleRequestActivity
import com.blurr.voice.ScreenInteractionService
import com.blurr.voice.assistant.AssistantRoleService

/**
 * Runs a [SideKeyActionSpec] against the live [service].
 *
 * The `when` here is the only place an action is turned into behaviour, and it is the
 * only thing that has to change to add one: a [SideKeyActionRegistry.actions] entry
 * describes it to the user, an arm here does it. Everything upstream -- the press rows,
 * the picker, the prefs -- is data and picks the new entry up on its own.
 */
object SideKeyActionExecutor {

    private const val TAG = "SideKeyActionExecutor"

    /**
     * Runs [action] against the live [service].
     *
     * @return true when the action was dispatched. False means the caller should
     *   record it, because an action that silently does nothing is worse than one
     *   that admits it could not run.
     */
    suspend fun execute(service: ScreenInteractionService, action: SideKeyActionSpec): Boolean {
        // A chosen app or link is stored as an id rather than being a catalogue entry,
        // so it is matched by shape first. Without this it would fall to the arm
        // below, which treats an unrecognised id as a failure.
        SideKeyTargets.parse(action.id)?.let { target ->
            return SideKeyTargets.launch(service, target)
        }

        SideKeyShortcuts.keyCodeFor(action.id)?.let { keyCode ->
            return SideKeyShortcuts.mediaKey(service, keyCode)
        }

        return when (action.id) {
            SideKeyActionRegistry.NONE_ID -> true

            SideKeyActionRegistry.OPEN_ASSISTANT_ID -> showAssistant(service)

            SideKeyActionRegistry.CIRCLE_TO_SEARCH_ID ->
                CircleToSearchOutcome.OPENED == AksCircleToSearch.trigger(service)

            SideKeyActionRegistry.GOOGLE_LENS_SCREEN_ID ->
                LensScreenSearchOutcome.LENS_OPENED == LensScreenSearch.searchScreen(service)

            SideKeyActionRegistry.TOGGLE_FLASHLIGHT_ID ->
                SideKeyShortcuts.toggleFlashlight(service)

            SideKeyActionRegistry.OPEN_QUICK_SETTINGS_ID ->
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

            else -> {
                // A stored id with no implementation behind it. Treated as a
                // failure rather than a silent no-op so a half-removed action
                // cannot look like it is working.
                Log.w(TAG, "No implementation for action id '${action.id}'")
                false
            }
        }
    }

    /**
     * Opens the assistant popup through [AssistantRoleService], the platform's own
     * entry point, so the key joins the same conversation window as the power-button
     * gesture and MainActivity instead of starting a second front end.
     *
     * When this app does not hold the assistant role there is no popup to show, and
     * the press opens the screen that asks for the role instead -- what
     * MainActivity does for the same reason: the request has nowhere else to land,
     * and a key that silently did nothing would read as a broken key.
     *
     * @return true when the press produced something on screen.
     */
    private fun showAssistant(service: ScreenInteractionService): Boolean {
        if (AssistantRoleService.showAssistantPopup()) return true
        return runCatching {
            service.startActivity(
                Intent(service, RoleRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrElse { error ->
            Log.w(TAG, "Could not open the assistant or ask for the role", error)
            false
        }
    }
}
