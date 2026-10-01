package com.blurr.voice.sidekey

import android.util.Log
import com.blurr.voice.ScreenInteractionService

/**
 * The one place an assigned action is actually carried out.
 *
 * The key pipeline only knows action ids, and the settings screen only knows how
 * to pick them, so every "what does this actually do" branch lives in one
 * `when` here. Adding an action means adding a [SideKeyActionRegistry.actions]
 * entry and one arm below -- nothing in the service or the settings UI has to
 * know the action exists.
 */
object SideKeyActionExecutor {

    private const val TAG = "SideKeyActionExecutor"

    /**
     * Runs [action] against the live [service].
     *
     * @return true when the action was dispatched. False means the user should
     *   hear about it, because an action that silently does nothing is worse
     *   than one that admits it could not run.
     */
    suspend fun execute(service: ScreenInteractionService, action: SideKeyActionSpec): Boolean =
        when (action.id) {
            SideKeyActionRegistry.NONE_ID -> true

            SideKeyActionRegistry.GOOGLE_LENS_SCREEN_ID ->
                LensScreenSearchOutcome.LENS_OPENED == LensScreenSearch.searchScreen(service)

            else -> {
                // A stored id with no implementation behind it. Treated as a
                // failure rather than a silent no-op so a half-removed action
                // cannot look like it is working.
                Log.w(TAG, "No implementation for action id '${action.id}'")
                false
            }
        }
}
