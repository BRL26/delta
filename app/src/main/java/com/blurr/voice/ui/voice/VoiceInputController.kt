package com.blurr.voice.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the microphone bar should be showing right now. */
data class VoiceInputState(
    /** A recognition engine exists on this device. */
    val available: Boolean = false,
    /** RECORD_AUDIO has been granted to the app. */
    val permissionGranted: Boolean = false,
    /**
     * Whether the microphone should be treated as open.
     *
     * Deliberately *not* "an engine session is running". A session ends after every
     * utterance and has to be re-asked for, so reporting that truthfully would make the
     * label flicker between Listening and Tap-to-talk several times a minute, which
     * reads as the microphone dropping out. This is the user's intent -- did they ask
     * for it to be open -- and it stays true across the engine's own recycling.
     */
    val listening: Boolean = false,
    /**
     * Growing transcript shown under the mic while the user speaks.
     *
     * Covers the words already finished *and* the utterance still in progress, joined
     * together, because both are text the user has said and is waiting to see confirmed.
     * It is the same text that [transcripts] will emit, so what the user watches being
     * written is what actually gets sent.
     */
    val partial: String = "",
    /** Set after a failure worth telling the user about; cleared when listening starts. */
    val error: String? = null,
    /**
     * Consecutive engine sessions that ended without hearing anything, out of
     * [MaxSilentListens].
     *
     * Shown rather than only used to decide whether to quit, because a microphone that
     * has silently given up looks identical to one that is still working. This lets the
     * sheet say it is about to close instead of just closing.
     */
    val silentListens: Int = 0,
)

/**
 * Thin wrapper around the platform [SpeechRecognizer] client API, which keeps the
 * microphone open across a whole conversation rather than for one utterance at a time.
 *
 * Why the client API rather than our own
 * [com.blurr.voice.assistant.AssistantRecognitionService]: that service exists only so
 * the VoiceInteractionService counts as *qualified* so the system will bind it at all,
 * and it deliberately never touches the mic. This class asks Android which recognition
 * engine the user has configured and drives it, so the assistant works with whatever
 * recogniser is actually installed instead of shipping and licensing its own.
 *
 * This is deliberately *not* [com.blurr.voice.utilities.SpeechCoordinator], which
 * drives the same engine for the conversational service. Two owners of one recogniser
 * means one of them loses: the coordinator is a singleton that holds the mic for the
 * duration of a conversation, and the session UI holds it only while its sheet is up.
 * Keeping the session's recogniser separate means the popup cannot be left silent
 * because the overlay flow already had it.
 *
 * ## Why the engine is recycled but the microphone is not
 *
 * A `SpeechRecognizer` session is one utterance long. It reports its result, goes idle,
 * and has to be asked again -- and if you ask again too quickly it answers
 * ERROR_RECOGNIZER_BUSY instead. So the engine genuinely does cycle, and there is a gap
 * of a few hundred milliseconds that no amount of wanting avoids.
 *
 * What can be avoided is the *user seeing* it. The obvious arrangement -- the UI cancels
 * and restarts listening after every transcript -- makes the label drop to "tap to talk"
 * and back for that gap, several times a minute, and the complaint is always the same:
 * it sounds like the microphone keeps cutting out. So the recycling lives in here, where
 * nobody is looking: [keepAlive] is the user's intent, it survives the gap, and
 * [VoiceInputState.listening] stays true straight through it. The label changes when the
 * user asks for a different thing and at no other point.
 *
 * All calls must happen on the main thread; [SpeechRecognizer] enforces this.
 *
 * ## Why sending waits for a pause
 *
 * The same one-utterance-per-session engine is why nothing is submitted the moment it is
 * recognised. Its idea of the end of a sentence is a short pause, and a short pause
 * happens constantly in the middle of one: between the thing you want and how you want it
 * done. Sending on that boundary produces a request that is answered before its second
 * half is spoken, and the answer arrives for a question you no longer mean to ask.
 *
 * So recognised words accumulate in [pending] and go out once the callbacks stop arriving
 * for [InactivitySendMillis], which makes the wait self-cancelling -- there is no separate
 * "still talking" flag to get wrong. A second sentence before the deadline extends it
 * instead of starting a second message.
 */
class VoiceInputController(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(
        VoiceInputState(
            available = SpeechRecognizer.isRecognitionAvailable(appContext),
            permissionGranted = hasMicPermission(),
        ),
    )
    val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    /**
     * What the user has said, emitted once they have been silent long enough.
     *
     * Not one emission per utterance. Several utterances that arrive inside the wait are
     * joined into one message, which is the only way a sentence split across a pause
     * survives intact.
     */
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

    /**
     * Emits once the microphone has been open and heard nothing for [MaxSilentListens]
     * sessions in a row, after which the caller is expected to tear the session down.
     *
     * A separate signal rather than a flag in [VoiceInputState] because the decision to
     * quit belongs to whoever opened the session, not to the recogniser: closing is a
     * thing you do to a VoiceInteractionSession and stop a foreground service, and a
     * class that only wraps an engine has no business deciding it.
     */
    private val _gaveUp = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val gaveUp: SharedFlow<Unit> = _gaveUp.asSharedFlow()

    /**
     * Engine sessions in a row that heard nothing.
     *
     * Reset by any sign of life from the user -- speech starting, a partial result, a
     * final result -- so only an unbroken run of silence counts. That matters because the
     * engine reports ERROR_NO_MATCH for a pause in the middle of a sentence just as
     * readily as for an empty room, and giving up on the third of those would punish
     * exactly the slow, thinking-out-loud speaker the keep-alive behaviour was built for.
     */
    private var silentListens = 0

    private var recognizer: SpeechRecognizer? = null

    /**
     * Whether the microphone should stay open, regardless of what the engine is doing.
     *
     * The whole point of the class. Set by [start] and cleared by [stop], [cancel] and
     * [release], which is what makes "keep the mic open" a property of the session UI
     * being on screen rather than a decision the UI has to keep re-making.
     */
    @Volatile
    private var keepAlive = false

    /** Guards against two restarts being queued for the same gap. */
    private var restartQueued = false

    /**
     * Every path that ends an open microphone funnels through here.
     *
     * It was four near-identical three-line preambles, which is exactly the shape that
     * goes wrong: a fifth path closes the microphone, forgets one line, and the system
     * stream stays muted for the rest of the session. One place to be wrong is better than
     * five.
     */
    private fun endKeepAlive() {
        keepAlive = false
        restartQueued = false
        main.removeCallbacksAndMessages(RESTART_TOKEN)
        restoreSystemSounds(appContext)
    }

    /**
     * Words the user has finished saying, waiting out [InactivitySendMillis] before they
     * are sent.
     *
     * A separate buffer from the engine's own state, because the engine has already moved
     * on by the time we have the words: it has reported a result and been asked to listen
     * again. Holding the text here is what lets a second sentence join the first instead of
     * being sent as its own message.
     */
    private val pending = StringBuilder()

    /**
     * The utterance currently being spoken, as the engine streams it.
     *
     * Kept apart from [pending] because the two are not the same thing: the live text may
     * still change, and appending it to [pending] would commit a guess that the next
     * partial result then contradicts, producing "what's the whats the" in the transcript.
     */
    private var live = ""

    private val main = Handler(Looper.getMainLooper())

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /** Re-reads permission availability, e.g. after returning from system settings. */
    fun refreshPermission() {
        _state.value = _state.value.copy(permissionGranted = hasMicPermission())
    }

    /**
     * Opens the microphone and keeps it open until told otherwise.
     *
     * Returns false when the request could not be made at all, so the caller can explain
     * why instead of leaving a dead button. Idempotent: asking again while it is already
     * open does nothing, so a recomposition cannot cause a restart.
     */
    fun start(): Boolean {
        refreshPermission()
        if (!hasMicPermission()) {
            endKeepAlive()
            _state.value = _state.value.copy(listening = false, error = ERROR_PERMISSION)
            return false
        }
        if (!_state.value.available) {
            endKeepAlive()
            _state.value = _state.value.copy(listening = false, error = ERROR_UNAVAILABLE)
            return false
        }
        if (_state.value.listening) return true

        keepAlive = true
        // Before the engine is asked, not after: the tone plays a few hundred milliseconds
        // after onStartListening, so muting once the first session is already audible
        // would leave exactly the beep this exists to remove.
        silenceRecognitionTone(appContext)
        // listening is set before the engine is asked, and is not cleared again until
        // stop/cancel. That is the whole trick described on the class.
        _state.value = _state.value.copy(listening = true, partial = "", error = null)
        return launchSession()
    }

    /** Closes the microphone. [start] is needed to open it again. */
    fun stop() {
        endKeepAlive()
        // Sent rather than dropped, even though the microphone is closing. This is the
        // path the collapsing popup takes, and the words were said while it was still up;
        // the microphone moving to the other surface is not the user retracting them.
        sendPending()
        runCatching { recognizer?.stopListening() }
        _state.value = _state.value.copy(listening = false)
    }

    /** Closes the microphone and throws away whatever was being said. */
    fun cancel() {
        endKeepAlive()
        // Sent for the same reason as [stop]: switching to the keyboard is not a retraction
        // of something already said, and the text composer taking over with the sentence
        // the user just dictated missing is its own bug.
        sendPending()
        runCatching { recognizer?.cancel() }
        _state.value = _state.value.copy(listening = false, partial = "")
    }

    /** Must be called from the main thread when the session goes away. */
    fun release() {
        endKeepAlive()
        // Dropped, and this is the one place that is right. There is no session left to
        // render the message into, and submitting here would start a service on the way
        // out of the assistant -- a request the user never got to see or interrupt.
        discardPending()
        runCatching { recognizer?.destroy() }
            .onFailure { Log.w(TAG, "destroy failed", it) }
        recognizer = null
        _state.value = _state.value.copy(listening = false)
    }

    private val intent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
    }

    /**
     * Asks the engine for one utterance.
     *
     * Split from [start] because it is also what the recycling calls, and the difference
     * matters: this says nothing about [keepAlive] and nothing about the published
     * state, so re-entering it between utterances cannot be mistaken for the user
     * opening the microphone a second time.
     */
    private fun launchSession(): Boolean {
        val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext)
            .also { created ->
                created.setRecognitionListener(listener)
                recognizer = created
            }

        val result = runCatching { engine.startListening(intent) }
        result.exceptionOrNull()?.let { Log.w(TAG, "startListening failed", it) }
        if (result.isFailure) {
            _state.value = _state.value.copy(listening = false, error = ERROR_UNAVAILABLE)
            keepAlive = false
        }
        return result.isSuccess
    }

    /**
     * Queues the next utterance, if the microphone is still meant to be open.
     *
     * The delay is not politeness, it is the only thing that works: the engine tears
     * down its connection when a session ends and answers a request made during that
     * window with ERROR_RECOGNIZER_BUSY. Rather than treat that as a failure -- which is
     * what the UI used to do, and why the microphone appeared to give up mid-sentence --
     * a busy engine is simply asked again a moment later.
     *
     * Guarded by [restartQueued] so that an end-of-speech followed by a result, which is
     * the normal shape of one utterance, does not queue two engines against each other.
     */
    private fun queueRestart(delayMillis: Long = RestartDelayMillis) {
        if (!keepAlive || restartQueued) return
        restartQueued = true
        main.postAtTime(
            {
                restartQueued = false
                // Re-checked rather than assumed: the gap is long enough for the user to
                // have collapsed the popup, and starting a microphone nobody asked for is
                // worse than not restarting.
                if (!keepAlive) return@postAtTime
                launchSession()
            },
            RESTART_TOKEN,
            SystemClock.uptimeMillis() + delayMillis,
        )
    }

    /**
     * Restarts the countdown on the pending words.
     *
     * Called from every callback that means the user is still talking, or has only just
     * stopped. That is the whole mechanism: the send happens when the callbacks stop, so
     * "three seconds of inactivity" needs no timer of its own beyond this one, and a
     * second sentence simply pushes the deadline out instead of racing the first.
     */
    private fun touchActivity() {
        if (pending.isEmpty() && live.isEmpty()) return
        // Re-posted unconditionally rather than skipped when one is already queued. The
        // deadline is the entire mechanism, so a queued send that fires in the middle of
        // the next sentence is the exact bug this replaces: it is cancelled and replaced,
        // never left to run.
        cancelPendingSend()
        main.postAtTime(
            {
                sendPending()
            },
            SEND_TOKEN,
            SystemClock.uptimeMillis() + InactivitySendMillis,
        )
    }

    /**
     * Publishes what the user has said so far as one message.
     *
     * The preview is cleared at the same moment, which is what makes the wait legible: the
     * words stay on screen for the whole countdown as the growing transcript, and then
     * move into the conversation as a sent message. Clearing them at the end of the
     * utterance instead -- the obvious thing to do -- makes the text vanish for three
     * seconds and reappear, which looks like the app lost the message.
     */
    private fun sendPending() {
        val text = pending.toString().trim()
        pending.setLength(0)
        live = ""
        _state.value = _state.value.copy(partial = "")
        if (text.isNotEmpty()) _transcripts.tryEmit(text)
    }

    /**
     * Records a finished utterance and shows the result alongside anything still waiting.
     *
     * Joining with a space rather than a newline is deliberate: the engine is handed one
     * utterance at a time and cannot know that two of them are one sentence, so this is
     * where that knowledge has to come from.
     */
    private fun appendPending(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        live = ""
        if (pending.isNotEmpty() && !pending.last().isWhitespace()) pending.append(' ')
        pending.append(trimmed)
        _state.value = _state.value.copy(partial = pending.toString())
        touchActivity()
    }

    /** Shows the utterance in progress after the words already finished. */
    private fun publishLive(text: String) {
        live = text
        _state.value = _state.value.copy(
            partial = buildString {
                if (pending.isNotEmpty()) append(pending)
                if (live.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append(live)
                }
            },
        )
        touchActivity()
    }

    /** Drops the queued send without sending, for when there is nothing left to wait for. */
    private fun cancelPendingSend() {
        main.removeCallbacksAndMessages(SEND_TOKEN)
    }

    /**
     * Records one session that heard nothing, and reports whether to give up.
     *
     * Returns true when the run has reached [MaxSilentListens], in which case the caller
     * must not restart the engine: the microphone is closed and [gaveUp] has been
     * emitted, and restarting afterwards would reopen a microphone nobody is behind.
     */
    private fun countSilence(): Boolean {
        silentListens++
        _state.value = _state.value.copy(silentListens = silentListens)
        if (silentListens < MaxSilentListens) return false
        // Said words still go out. The user may have given up on the microphone, but they
        // did say it, and throwing away a request because the next few seconds were
        // silent would be its own bug.
        sendPending()
        keepAlive = false
        restartQueued = false
        main.removeCallbacksAndMessages(RESTART_TOKEN)
        restoreSystemSounds(appContext)
        runCatching { recognizer?.cancel() }
        _state.value = _state.value.copy(listening = false, partial = "")
        _gaveUp.tryEmit(Unit)
        return true
    }

    /** Any sign the user is there clears the run of silent sessions. */
    private fun heardSomething() {
        silentListens = 0
        if (_state.value.silentListens != 0) {
            _state.value = _state.value.copy(silentListens = 0)
        }
    }

    /** Forgets the words without sending them. Used only when the session is going away. */
    private fun discardPending() {
        cancelPendingSend()
        pending.setLength(0)
        live = ""
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() {
            // The user started again before the countdown ran out. Which is the point of
            // having a countdown.
            heardSomething()
            touchActivity()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // The user has stopped talking but the engine has not finished deciding what
            // they said. The live text is left alone -- the result is about to replace it,
            // and blanking it now would flicker the preview -- and the deadline is pushed
            // out because the result that carries the words is still to come.
            touchActivity()
        }

        override fun onError(error: Int) {
            when (error) {
                // Not hearing anything is the normal state of an open microphone between
                // sentences, not a failure. Reporting it is what made the bar flash an
                // error and stop listening every time the user paused to think.
                //
                // These are also the two errors that count towards giving up, because
                // "heard nothing at all, three times running" is the only reliable
                // signal that the user opened the microphone and then walked away. Nothing
                // else can distinguish that case: the engine looks identical whether it
                // is listening to an empty room or to someone deciding what to say.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> {
                    if (countSilence()) return
                    queueRestart()
                }

                // The engine is still shutting the previous session down. Asking again
                // shortly is the documented way through it. Not counted as silence: the
                // engine never got to listen, so it has not told us anything.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> queueRestart(BusyRetryDelayMillis)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    endKeepAlive()
                    _state.value = _state.value.copy(listening = false, error = ERROR_PERMISSION)
                }

                else -> {
                    // Anything else is a real failure and there is no point looping into
                    // it: a recogniser that has just thrown will usually do it again, and
                    // the user is left with a microphone that blinks forever.
                    endKeepAlive()
                    _state.value = _state.value.copy(
                        listening = false,
                        partial = "",
                        error = ERROR_UNAVAILABLE,
                    )
                    // Half a sentence is not a sentence. An engine that died mid-utterance
                    // leaves [live] holding a guess with no result to confirm it, and
                    // sending the guess would be worse than dropping it.
                    discardPending()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) heardSomething()
            appendPending(text)
            queueRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) heardSomething()
            publishLive(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val TAG = "VoiceInput"

        const val ERROR_PERMISSION = "permission"
        const val ERROR_UNAVAILABLE = "unavailable"

        /**
         * The streams that have to go quiet for the tone to be inaudible.
         *
         * Two of them because the device gives two contradictory answers about where the
         * tone lives, and picking the wrong one leaves the beep exactly as audible as
         * before:
         *
         *  - The engine builds the tone as a legacy-stream [android.media.AudioTrack] with
         *    `streamType = 5`, which is [AudioManager.STREAM_NOTIFICATION]. A legacy
         *    stream type, not the usage attribute, decides the volume for that track.
         *  - The same track reports `usage=USAGE_ASSISTANCE_SONIFICATION`, and the audio
         *    policy's attribute-to-stream table maps that usage to
         *    [AudioManager.STREAM_SYSTEM].
         *
         * Muting only [AudioManager.STREAM_SYSTEM] was tried first, on the strength of that
         * mapping table, and the tone was still clearly audible. So both, rather than
         * guessing again and spending another round trip on the user to find out which one
         * was right. Narrowing this to a single stream is a one-line change once something
         * can measure which of the two actually carries it.
         */
        private val ToneStreams = intArrayOf(
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
        )

        /**
         * Which of [ToneStreams] this class muted, so that exactly those are restored and
         * a stream the user had muted themselves is left alone.
         *
         * Process-wide rather than per controller, deliberately. The streams are device
         * settings, so two controllers disagreeing about them would leave them muted after
         * one of them closed, and the record has to survive being called from a service
         * that has no controller to ask. Static for the same reason: the app is a single
         * process, and a controller-scoped flag would not be visible to the backstop in
         * `ConversationalAgentService.onDestroy`.
         */
        private val toneMutedStreams = mutableSetOf<Int>()

        /**
         * Mutes the streams the recognition engine's "listening" tone is played on, for as
         * long as the microphone is open.
         *
         * The tone is not ours and there is no way to switch it off at the source. When
         * `SpeechRecognizer` opens a session, the engine this device happens to use (the
         * Google recogniser, which is also the TTS engine) plays a short confirmation tone,
         * a few hundred milliseconds after each `onStartListening`. No extra on
         * [android.speech.RecognizerIntent] disables it, and it is not a notification, so
         * silencing the notification channels does nothing to it -- which is why the
         * version of this that treated it as one was wrong.
         *
         * It is also worse than a single beep. The engine session ends after every
         * utterance and is re-asked for, so keeping the microphone open means the tone
         * returns every few seconds for as long as the user is talking. Muted for the whole
         * life of the open microphone rather than per session, which fixes the repeats and
         * the first one together.
         *
         * The cost, stated plainly: [ToneStreams] also carry notification sounds, key
         * clicks, lock screen feedback and the volume-adjust tone, so those go quiet while
         * the assistant's microphone is open. That window is a modal overlay the user
         * opened on purpose and it closes itself after nine seconds of silence, and there
         * is no per-session or per-usage volume to narrow it to. The alternative is one
         * tone per utterance.
         *
         * Needs `MODIFY_AUDIO_SETTINGS`, which is a normal permission, so there is nothing
         * to request at runtime.
         */
        @Synchronized
        @JvmStatic
        fun silenceRecognitionTone(context: Context) {
            val app = context.applicationContext
            // A session starting cancels a restore that was waiting out the end of the
            // previous one. Without this, opening the assistant again inside the delay
            // window would unmute the streams out from under a live microphone.
            mainHandler.removeCallbacks(pendingToneRestore)
            if (toneMutedStreams.isNotEmpty()) return
            val audio = app.getSystemService(AudioManager::class.java) ?: return
            for (stream in ToneStreams) {
                // Already muted, most likely by the user, who is entitled to that. Left out
                // of the record so it is not unmuted later: undoing a mute the app did not
                // apply is the app overruling the user's volume setting.
                if (audio.isStreamMute(stream)) continue
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                toneMutedStreams += stream
            }
            if (toneMutedStreams.isNotEmpty()) toneAppContext = app
        }

        /**
         * Undoes [silenceRecognitionTone], and the backstop for a session that ends without
         * the controller being told.
         *
         * Safe to call from anywhere and as often as anything likes: it only ever unmutes
         * a stream that this class muted. The service calls it on the way out so a
         * conversation stopped by the notification's Stop button, a crash in the UI layer
         * or a swipe away from recents cannot leave the phone with no system sounds.
         *
         * Deferred by [ToneRestoreDelayMillis] rather than done immediately, and that
         * delay is the whole reason the closing sound exists. There are two sounds around a
         * conversation and only one of them comes from the recogniser. The opening one is
         * the engine's tone and is gone as soon as the streams are muted. The closing one
         * is not: it arrives *after* the session ends, from outside the app, and it is
         * already over by the time any log line mentions it -- capturing a full open/close
         * cycle shows every scrap of recogniser audio finishing before the close and
         * nothing at all afterwards, which is the signature of a `SoundPool` chime rather
         * than an `AudioTrack`. Unmuting in the same breath as the close therefore un-mutes
         * straight into it.
         *
         * Nothing in the app plays it, so the only handle on it is being quiet for slightly
         * longer than the session. A second and a half covers a chime that is already
         * starting when the session ends, and costs the user nothing they can perceive --
         * they are done talking and the overlay is on its way out.
         *
         * The one gap is the process being killed outright, which skips every cleanup path
         * and leaves the streams muted until something restores them. Accepted rather than
         * papered over with a marker in shared preferences, because a persisted mute flag
         * that disagrees with reality is worse than the narrow case it would fix.
         */
        @Synchronized
        @JvmStatic
        fun restoreSystemSounds(context: Context) {
            if (toneMutedStreams.isEmpty()) return
            toneAppContext = context.applicationContext
            mainHandler.removeCallbacks(pendingToneRestore)
            mainHandler.postDelayed(pendingToneRestore, ToneRestoreDelayMillis)
        }

        /** The actual unmute, run on the main looper once [ToneRestoreDelayMillis] is up. */
        @Synchronized
        private fun unmuteToneStreams() {
            val app = toneAppContext
            val muted = toneMutedStreams
            toneAppContext = null
            if (app != null && muted.isNotEmpty()) {
                app.getSystemService(AudioManager::class.java)?.let { audio ->
                    for (stream in muted) {
                        audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
            }
            muted.clear()
        }

        /**
         * How long the streams stay muted after the microphone closes.
         *
         * Long enough to cover the closing chime, which is already sounding by the time
         * the session ends. See [restoreSystemSounds].
         */
        private const val ToneRestoreDelayMillis = 1_500L

        /**
         * Where the delayed unmute is posted. On the main looper because that is where the
         * session lifecycle already runs, and so that a restore posted from the service's
         * `onDestroy` on a background thread still executes.
         */
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * The application context the delayed unmute needs, captured at mute time. Held
         * rather than passed into the runnable so there is only ever one pending restore,
         * which is what makes cancelling it on a new session correct.
         */
        private var toneAppContext: Context? = null

        private val pendingToneRestore = Runnable { unmuteToneStreams() }

        /**
         * Marker for the queued restarts, so [stop] can cancel a pending one without
         * touching anything else the main looper is doing.
         */
        private val RESTART_TOKEN = Any()

        /**
         * Marker for the queued send. Separate from [RESTART_TOKEN] because the two have
         * opposite intentions: a restart is always safe to drop, whereas a dropped send
         * is a sentence the user said that never gets sent.
         */
        private val SEND_TOKEN = Any()

        /**
         * How long the user has to be silent before what they said is sent.
         *
         * The reason to wait at all is that the engine ends an utterance on its own
         * schedule, and its idea of a finished sentence is not the user's. It stops on a
         * pause of half a second, which is well inside the gap between thinking "open
         * YouTube" and thinking "and play lofi beats" -- so without a wait the request gets
         * cut in half, sent, and answered before the rest of it is spoken.
         *
         * Three seconds is the compromise between the two failure modes. Shorter splits
         * sentences; longer makes every single-sentence request feel broken, because the
         * user has visibly stopped talking and nothing happens. It is worth a shorter
         * value for quick commands and a longer one when dictating a longer ask, and the
         * wait is the only thing that has to move if you want to tune that.
         */
        private const val InactivitySendMillis = 3_000L

        /**
         * How many consecutive silent sessions end the microphone.
         *
         * Three, because that is the point where "the user is thinking" and "the user has
         * gone" stop being distinguishable in practice. A single silent session is a
         * pause and must never be treated as anything else; a microphone left open in an
         * empty room is worse than useless, because the session window is still up, the
         * notification is still there, and the app looks busy doing nothing.
         *
         * The engine's own timeout sets the pace, so this is roughly nine seconds of
         * silence -- see the comment on the field for why a pause mid-sentence does not
         * count against the user.
         */
        const val MaxSilentListens = 3

        /**
         * How long to leave between one utterance ending and the next being requested.
         *
         * Long enough to be past the engine's teardown, short enough that the gap is not
         * something you notice. Anything longer reads as a dropped microphone, which is
         * the exact complaint this replaced.
         */
        private const val RestartDelayMillis = 250L

        /**
         * The same wait for a busy engine.
         *
         * Longer, because ERROR_RECOGNIZER_BUSY means the previous session is *still*
         * going rather than nearly gone, and asking again on the short delay reliably
         * earns a second busy error.
         */
        private const val BusyRetryDelayMillis = 500L
    }
}
