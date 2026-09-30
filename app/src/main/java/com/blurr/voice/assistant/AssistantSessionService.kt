package com.blurr.voice.assistant

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

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

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AssistantSession(applicationContext, mainHandler)
}
