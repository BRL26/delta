package com.blurr.voice.assistant

import android.content.AttributionSource
import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Speech recognizer slot required to qualify as a system assistant.
 *
 * ## Why this exists when the app already does speech
 *
 * The platform only treats a VoiceInteractionService as *qualified* -- and therefore
 * only binds it and lets the assistant be invoked by a long-press or gesture -- if the
 * `<voice-interaction-service>` XML also names a `recognitionService` that resolves to
 * a service holding BIND_RECOGNITION_SERVICE. Without it the system logs
 * "Package ... has an unqualified voice interaction service", the role dialog can
 * still grant the app the assistant role (via the ACTION_ASSIST activity), but the
 * service is never bound, so no session is ever created and nothing is shown.
 *
 * ## What it deliberately does NOT do
 *
 * Grab the microphone. Speech is driven by
 * [com.blurr.voice.ui.voice.VoiceInputController] against whichever recogniser the
 * user actually has installed, from inside the session UI where the transcript can be
 * shown. This slot exists purely so the role is granted, and it says so honestly by
 * refusing a request with ERROR_CLIENT ("the client is at fault") rather than
 * silently hanging.
 */
class AssistantRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, recognizer: RecognitionService.Callback?) {
        // ERROR_CLIENT is the accurate code for "this assistant does not serve
        // platform-directed recognition requests".
        recognizer?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(recognizer: RecognitionService.Callback?) {
        Log.i(TAG, "onStopListening")
    }

    override fun onCancel(recognizer: RecognitionService.Callback?) {
        Log.i(TAG, "onCancel")
    }

    /**
     * Tell the system up front that this service cannot serve a recognition
     * request, so the platform does not route voice input here.
     */
    override fun onCheckRecognitionSupport(
        intent: Intent,
        attributionSource: AttributionSource,
        supportCallback: RecognitionService.SupportCallback,
    ) {
        supportCallback.onError(SpeechRecognizer.ERROR_CLIENT)
    }

    private companion object {
        const val TAG = "AssistantRecognizer"
    }
}
