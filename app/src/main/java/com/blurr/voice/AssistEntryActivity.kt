package com.blurr.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.blurr.voice.assistant.AssistantRoleService

/**
 * Where the platform sends an assist request.
 *
 * The app now has the real system-assistant role ([AssistantRoleService]), so the
 * long-press of the power button usually does not come here at all -- the platform binds
 * our [android.service.voice.VoiceInteractionService] and asks it for a session, and the
 * popup is drawn by
 * [com.blurr.voice.assistant.AssistantSession]. This Activity remains for the requests
 * that still arrive as `ACTION_ASSIST`: a hardware-assist key from a device that routes
 * it as an intent, and the "Ask assistant" affordances in other apps.
 *
 * Kept, rather than removed in favour of the role, because dropping it would leave those
 * paths either unhandled or handled by whatever else claims the action. It has no UI of
 * its own and finishes immediately either way.
 */
class AssistEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAssistLaunch(intent)
        // No UI — finish immediately
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleAssistLaunch(intent)
        finish()
    }

    private fun handleAssistLaunch(intent: Intent?) {
        Log.d("AssistEntryActivity", "Assistant invoked via ACTION_ASSIST, intent=$intent")

        // The popup, through the same code path the power-button gesture uses, whenever
        // the platform still holds us as the assistant.
        //
        // Forwarding to the role service rather than drawing anything here is the point
        // of the exercise: two front ends for one conversation means the user gets a
        // different assistant depending on which gesture they used, and whichever one
        // they did not use is the one with the transcript in it.
        if (AssistantRoleService.showAssistantPopup()) {
            Log.d("AssistEntryActivity", "Opened the assistant popup through the role service.")
            return
        }

        // No role held, so there is no session window for the platform to give us and
        // no popup to draw. The old fallback started the conversational service and let
        // it paint its own overlay UI; that UI is gone, so the request now goes where it
        // can actually be answered -- the "make Delta your assistant" screen.
        Log.i("AssistEntryActivity", "Assistant role not held; asking for it.")
        runCatching {
            startActivity(
                Intent(this, RoleRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w("AssistEntryActivity", "Could not open the role request", it) }
    }
}
