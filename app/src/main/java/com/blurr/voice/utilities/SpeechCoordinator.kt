package com.blurr.voice.utilities

import android.content.Context
import android.util.Log
import com.blurr.voice.api.GoogleTts
import com.blurr.voice.api.TTSVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class SpeechCoordinator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SpeechCoordinator"

        @Volatile private var INSTANCE: SpeechCoordinator? = null

        fun getInstance(context: Context): SpeechCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpeechCoordinator(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val ttsManager = TTSManager.getInstance(context)
    private val sttManager = STTManager(context)

    // Mutex to ensure only one speech operation at a time
    private val speechMutex = Mutex()
    private var ttsPlaybackJob: Job? = null
    // State tracking
    private var isListening = false

    /**
     * Whether audio is coming out of the speaker right now, published so something
     * that is holding a microphone can get out of the way.
     *
     * A flow rather than only [isSpeaking] because the answer is "hold the microphone
     * shut while the assistant talks", and a microphone cannot ask a question: it needs
     * to be told when to open and when to shut. Polling a plain getter for that means a
     * timer in the recogniser, which is a guess about when speech ends rather than the
     * fact of it.
     *
     * Thread-safe because these are written from inside [speechMutex] on whatever
     * dispatcher the caller happened to be on, and read from the session UI's main
     * thread. The old plain boolean had the same exposure and got away with it; a flow
     * does not need the luck.
     */
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /**
     * Speak text using TTS, ensuring STT is not listening
     * @param text The text to speak
     */
    suspend fun speakText(text: String) {
        val cleanedText = text.replace("*", "")
        speechMutex.withLock {
            try {
                // Unconditional rather than `if (isListening)`. That flag is reported by
                // the engine, and the engine reports false during the gaps between its
                // own short sessions -- so the conditional version left the recogniser
                // running, holding the microphone, and transcribing the reply that was
                // about to come out of the speaker. Nothing to ask: stopping a
                // recogniser that was not listening is free.
                Log.d(TAG, "Stopping STT before speaking: $cleanedText")
                sttManager.stopListening()
                isListening = false
                delay(250) // Brief pause to ensure STT is fully stopped

                _speaking.value = true
                Log.d(TAG, "Starting TTS: $cleanedText")

                // This is a suspend call that will wait until TTS is actually done.
                ttsManager.speakText(cleanedText)

                // FIXED: The inaccurate, estimated delay has been removed!

                Log.d(TAG, "TTS completed: $cleanedText")

            } finally {
                // Ensure the speaking flag is always reset
                _speaking.value = false
            }
        }
    }

    /**
     * Speak text to user, ensuring STT is not listening
     * @param text The text to speak to the user
     */
    suspend fun speakToUser(text: String) {
        val cleanedText = text.replace("*", "")
        speechMutex.withLock {
            try {
                // Unconditional for the same reason as speakText: isListening is the
                // engine's own report, and false is exactly what it says while it is
                // still holding the microphone.
                Log.d(TAG, "Stopping STT before speaking to user: $cleanedText")
                sttManager.stopListening()
                isListening = false
                delay(250) // Brief pause

                _speaking.value = true
                Log.d(TAG, "Starting TTS to user: $cleanedText")

                ttsManager.speakToUser(cleanedText)


                Log.d(TAG, "TTS to user completed: $cleanedText")

            } finally {
                // Ensure the speaking flag is always reset
                _speaking.value = false
            }
        }
    }
    /**
     * Plays raw audio data directly using TTSManager, bypassing synthesis.
     * Ideal for playing cached voice samples.
     */
    suspend fun playAudioData(data: ByteArray) {
        ttsPlaybackJob?.cancel(CancellationException("New audio data request received"))
        ttsPlaybackJob = CoroutineScope(Dispatchers.IO).launch {
            speechMutex.withLock {
                try {
                    // Unconditional: see speakText. Every path that makes the phone
                    // speak has to actually free the microphone first, whether or not
                    // the engine thinks it is using it.
                    sttManager.stopListening()
                    isListening = false
                    delay(200)
                    // Directly use the TTSManager's playback function
                    // Announced through [speaking] like the synthesised paths, because it
                    // is the same problem: audio out of the speaker while something is
                    // holding a microphone. A settings-screen voice preview is not a reply
                    // to anybody, but it is still the phone talking.
                    _speaking.value = true
                    try {
                        ttsManager.playAudioData(data)
                    } finally {
                        _speaking.value = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error during audio data playback", e)
                }
            }
        }
    }
    /**
     * Start listening with STT, ensuring TTS is not speaking
     * @param onResult Callback for speech recognition results
     * @param onError Callback for speech recognition errors
     * @param onListeningStateChange Callback for listening state changes
     */
    suspend fun testVoice(text: String, voice: TTSVoice) {
        ttsPlaybackJob?.cancel(CancellationException("New voice test request received"))
        ttsPlaybackJob = CoroutineScope(Dispatchers.IO).launch {
            speechMutex.withLock {
                try {
                    // Unconditional: see speakText.
                    sttManager.stopListening()
                    isListening = false
                    delay(200)
                    // 1. Synthesize audio with the specific voice HERE
                    val audioData = GoogleTts.synthesize(text, voice)

                    // 2. Play the synthesized audio data, announced through [speaking] like every
                    // other path that makes the phone talk out loud.
                    _speaking.value = true
                    try {
                        ttsManager.playAudioData(audioData)
                    } finally {
                        _speaking.value = false
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error during voice test", e)
                }
            }
        }
    }

    fun stop() {
        // Cancel the coroutine managing the playback
        ttsPlaybackJob?.cancel(CancellationException("Playback stopped by user action"))
        // Same reasoning as stopSpeaking: a stop that leaves the published state lying
        // would hand the microphone the wrong answer about whether the phone is talking.
        _speaking.value = false
        // Call the underlying TTS Manager's stop function to halt the hardware
        ttsManager.stop()
        Log.d(TAG, "All TTS playback stopped by coordinator.")
    }

    suspend fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStateChange: (Boolean) -> Unit,
        onPartialResult: (String) -> Unit
    ) {
        stop() // Use our new stop function to ensure TTS is stopped before listening
        speechMutex.withLock {
            try {

                // If TTS is speaking, wait for it to complete. Reading the flow rather
                // than a plain flag is what makes this a wait on the fact of speech
                // ending instead of on a field that might be written by another thread.
                if (_speaking.value) {
                    Log.d(TAG, "Waiting for TTS to complete before starting STT")
                    while (_speaking.value) {
                        delay(100) // Check every 100ms
                    }
                    delay(250) // Additional pause after TTS completes
                }

                isListening = true
                sttManager.startListening(
                    onResult = { result -> onResult(result) },
                    onError = { error -> onError(error) },
                    onListeningStateChange = { listening ->
                        isListening = listening
                        onListeningStateChange(listening)
                    },
                    onPartialResult = { partialText -> onPartialResult(partialText) }
                )

            } catch (e: Exception) {
                isListening = false
                onError("Failed to start speech recognition: ${e.message}")
            }
        }
    }

    fun stopListening() {
        if (isListening) {
            sttManager.stopListening()
            isListening = false
        }
    }
    fun stopSpeaking() {
        ttsManager.stop()
        // Cleared rather than left to the caller's finally block, because "stop now" is
        // exactly when something downstream is waiting to be told the phone has gone
        // quiet. Leaving it set would keep a microphone shut against a speaker that has
        // already stopped, which is a worse failure than the echo this flag prevents.
        _speaking.value = false
        Log.d("SpeechCoordinator", "Speaking explicitly stopped.")
    }


    fun isCurrentlySpeaking(): Boolean = _speaking.value

    fun isCurrentlyListening(): Boolean = isListening

    fun isSpeechActive(): Boolean = _speaking.value || isListening

    suspend fun waitForSpeechCompletion() {
        while (isSpeechActive()) {
            delay(100)
        }
    }

    fun shutdown() {
        stopListening()
        sttManager.shutdown()
    }
}