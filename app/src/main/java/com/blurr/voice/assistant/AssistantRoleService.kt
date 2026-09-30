package com.blurr.voice.assistant

import android.content.Intent
import android.os.IBinder
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * The top-level, always-running voice interaction service.
 *
 * This is the thing that makes the app selectable as the system assistant, and the
 * platform keeps it bound for as long as the user has chosen us. The body is
 * therefore kept trivial: the session UI lives in [AssistantSessionService] /
 * [AssistantSession]. Do not move real work here.
 */
class AssistantRoleService : VoiceInteractionService() {

    /**
     * The platform binds this only while the app holds the assistant role, so the
     * binding handle is itself the proof of that, and it is recorded so the app's own
     * Activities can open the popup through the real code path rather than faking it.
     */
    override fun onBind(intent: Intent): IBinder? {
        bound = this
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent): Boolean {
        if (bound === this) bound = null
        return super.onUnbind(intent)
    }

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "Voice interaction service ready")
    }

    companion object {
        private const val TAG = "AssistantRole"

        @Volatile
        private var bound: AssistantRoleService? = null

        /** Whether the platform currently holds this service bound, i.e. we are the assistant. */
        val isAssistantRoleHeld: Boolean get() = bound != null

        /**
         * Opens the assistant popup through the exact code path the power-button
         * gesture uses. Returns false when the service is not bound, i.e. when the
         * app is not (yet) the default assistant.
         */
        fun showAssistantPopup(): Boolean {
            val service = bound ?: return false
            return runCatching {
                service.showSession(null, VoiceInteractionSession.SHOW_SOURCE_APPLICATION)
                true
            }.getOrElse { error ->
                Log.w(TAG, "showSession failed", error)
                false
            }
        }
    }
}
