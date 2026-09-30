package com.blurr.voice.assistant

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.blurr.voice.ConversationalAgentService
import com.blurr.voice.v2.AgentService

/**
 * The way the assistant popup hands a finished request to the conversation.
 *
 * ## Why an Intent and not a direct call
 *
 * The popup is a View inside a window the system owns and destroys whenever it likes.
 * The conversation is a foreground service that is meant to outlive that window -- a task
 * keeps stepping after the user swipes the assistant away, and the answer is still there
 * when they tap the pill to read it. So the popup does not *hold* the conversation and
 * cannot call into it as an object; it can only start it, exactly as the
 * notification's action button does.
 *
 * Going through the service rather than around it also means the session's requests
 * arrive with the same conversational context as every other entry point, instead of
 * needing a parallel code path that keeps its own history.
 *
 * Deliberately tiny. Everything the request needs travels in the Intent, so a request
 * survives the process being killed between the tap and the service starting.
 */
object AssistantInput {

    /**
     * Action for a request typed or spoken into the assistant popup.
     *
     * Namespaced with the app's own package, because a Service can be started by other
     * components and a bare string like `"SEND"` is a collision waiting to happen.
     */
    const val ACTION_SUBMIT_TEXT = "com.blurr.voice.assistant.action.SUBMIT_TEXT"

    /** The request itself. */
    const val EXTRA_TEXT = "com.blurr.voice.assistant.extra.TEXT"

    /**
     * Sends [text] to the conversation.
     *
     * Returns false when the service could not be started at all, so the caller can say
     * something rather than appearing to have accepted a request that is going nowhere.
     * Starting through [ContextCompat.startForegroundService] rather than
     * `startService` because the target is a microphone foreground service, and the
     * platform throws on a plain `startService` for one of those from the background.
     */
    fun submit(context: Context, text: String): Boolean {
        val intent = Intent(context, ConversationalAgentService::class.java).apply {
            action = ACTION_SUBMIT_TEXT
            putExtra(EXTRA_TEXT, text)
        }
        return runCatching {
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }.isSuccess
    }

    /**
     * Stops whatever the assistant is currently doing.
     *
     * Two services, because "what the assistant is doing" is two things. The
     * conversational service holds the microphone and the answer, and the v2 agent is a
     * separate service stepping through a screen task; stopping only the first leaves a
     * task tapping on by itself, and stopping only the second leaves the microphone open
     * on a turn the user has already abandoned.
     *
     * Both go through the same stop action their own notification uses, so there is one
     * cancellation path per service and this does not have to know how either of them
     * shuts down. Deliberately not `stopService`: both clean up after themselves in
     * `onDestroy`, and skipping that is how a half-removed overlay view survives into the
     * next conversation.
     *
     * The executor is stopped first, because it is the one with a foreground service
     * notification that would otherwise outlive the moment by a frame.
     */
    fun stop(context: Context) {
        val app = context.applicationContext
        runCatching { AgentService.stop(app) }
        runCatching {
            app.startService(
                Intent(app, ConversationalAgentService::class.java).apply {
                    action = ConversationalAgentService.ACTION_STOP_SERVICE
                },
            )
        }
    }
}
