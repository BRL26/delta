package com.blurr.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
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

        // No role held: fall back to the previous behaviour, which starts the
        // conversational service and lets it open the microphone. This is the path that
        // works before the user has ever picked blurr as their assistant, which is
        // exactly when they are most likely to be testing the assist key.
        if (!ConversationalAgentService.isRunning) {
            val serviceIntent = Intent(this, ConversationalAgentService::class.java).apply {
                action = "com.blurr.voice.ACTION_START_FROM_ASSIST"
                putExtra("source", "assist_gesture")       // optional metadata
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            // e.g., tell the service to bring its overlay/mic UI to front
            sendBroadcast(Intent("com.blurr.voice.ACTION_SHOW_OVERLAY"))
        }
    }
}
