package com.blurr.voice.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Creates a new [AssistantSession] for each invocation.
 *
 * The system instantiates this class by name from the `sessionService` attribute of
 * `res/xml/voice_interaction_service.xml` -- it is resolved from that string rather
 * than by name, so it needs no `<service>` intent filter, and R8 cannot see the
 * reference at all, which is why it is listed in proguard-rules.pro.
 */
class AssistantSessionService : VoiceInteractionSessionService() {

    // Service.getMainHandler() is @hide, so bind to the main looper explicitly. The
    // session's callbacks are dispatched on this handler, so it must be the main one.
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Turns the assistant off when the screen does.
     *
     * ## Why this is needed at all
     *
     * [AssistantSession.onHide] answers a window that went away without the user asking
     * by re-showing it as a pill, so a dismissed sheet gets out of the way instead of
     * disappearing. Screen-off is a window going away without the user asking, so it
     * took the same branch: the assistant was still there when the phone came back on,
     * sitting on the unlock screen with whatever it had been saying, holding a
     * microphone the entire time it was in a pocket.
     *
     * The rule that has to exist is the difference between *the user looked away* and
     * *the user looked away and does not want this any more*. A swipe, a back press and
     * a tap outside are all "I want my screen back" and are answered with a pill.
     * Turning the phone off is not: nobody turns a phone off mid-conversation and
     * expects the conversation to still be waiting when they come back, and there is no
     * one there to press the X on the pill.
     *
     * So screen-off closes rather than collapses, and it stops the services as well
     * rather than only the window -- a hidden session still has a foreground service
     * behind it holding the mic, and releasing the window alone would leave the part
     * that is actually audible still running.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            Log.i(TAG, "Screen off; closing the assistant rather than collapsing it")
            // requestClose marks the close as intended, which is what stops
            // AssistantSession.onHide from answering this hide with a pill. That ordering
            // matters: finish() is asynchronous, so onHide runs after this returns, and
            // by then the flag has to already be set.
            SessionBridge.requestClose()
            // Deliberately the shared stop route rather than finish() alone. It releases
            // the microphone, cancels the session window and clears the service's own
            // overlays in each service's onDestroy, which is where that cleanup lives.
            AssistantInput.stop(context.applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Dynamic rather than declared in the manifest because the receiver is scoped to
        // a live session: there is nothing to tear down if no session was ever created.
        // A manifest receiver would outlive the session and would be a process-wide
        // reason for this app to exist on a locked phone.
        //
        // RECEIVER_NOT_EXPORTED is correct here even though this is a system broadcast:
        // the system is exempt from the flag, so it is received, and nothing else can
        // send this intent to us and make the assistant close itself.
        ContextCompat.registerReceiver(
            this,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        // Unregistered rather than left to the process dying: the process can outlive
        // the service (the assistant role holder keeps it alive), and a second
        // registration is an IllegalArgumentException.
        runCatching { unregisterReceiver(screenOff) }
            .onFailure { Log.w(TAG, "Screen-off receiver was not registered", it) }
        super.onDestroy()
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AssistantSession(applicationContext, mainHandler)

    private companion object {
        const val TAG = "AssistantSessionService"
    }
}
