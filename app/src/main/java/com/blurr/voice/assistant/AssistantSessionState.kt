package com.blurr.voice.assistant

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who produced a line in the assistant transcript.
 *
 * [Activity] is not a speaker: it is the line the assistant writes about its own
 * progress while a task runs, which the chat renders as an inset status row rather
 * than as a speech bubble. Keeping it a role rather than a boolean on the message
 * is what lets the transcript stay a flat list the Compose side can render with a
 * single `when`, and it matches how the two are actually displayed.
 */
enum class AssistantLineRole { User, Assistant, Activity }

/** One line of the conversation. */
data class AssistantLine(
    val id: String = UUID.randomUUID().toString(),
    val role: AssistantLineRole,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/**
 * Everything the assistant popup draws, as one immutable snapshot.
 *
 * Held pre-formatted on purpose: [activity] is a readable line ("Opening Settings")
 * rather than a tool name plus arguments, because the only consumer is a preview
 * row, and carrying arguments up to a composable would put the phrasing in code
 * that no unit test can reach.
 */
data class AssistantChatState(
    val lines: List<AssistantLine> = emptyList(),
    /** True between a request going out and the answer arriving. */
    val isThinking: Boolean = false,
    /** What the assistant is doing right now, or null between steps. */
    val activity: String? = null,
)

/**
 * The conversation the assistant popup renders.
 *
 * ## Why this is a mirror and not the conversation
 *
 * The conversation itself is owned by [com.blurr.voice.ConversationalAgentService],
 * which holds the Gemini history, the speech coordinator and the task agent, and
 * which outlives any popup: a task keeps running after the user swipes the
 * assistant away, and the transcript has to still be there when they tap the pill
 * to read it. Rebuilding that inside the session would mean moving the agent, the
 * memory lookups and the TTS into a window the platform destroys on a whim.
 *
 * So this holds the *displayed* conversation and nothing else. The service publishes
 * into it at the handful of points where something a user would recognise happens --
 * they said something, it answered, a task started or moved on -- and the session UI
 * only ever reads. The result is that the popup is a view onto a conversation that
 * is running without it.
 *
 * ## Why it is an object rather than a class
 *
 * A `VoiceInteractionSession` is created and torn down by the system on every
 * invocation, so anything held in a session-scoped ViewModel would be lost the
 * moment the popup is dismissed -- which is the exact moment a transcript has to
 * survive. A process-wide holder is the only lifetime that matches the
 * conversation's.
 */
object AssistantSessionState {

    private val _state = MutableStateFlow(AssistantChatState())

    /** What the popup draws. Collected by the session's Compose tree. */
    val state: StateFlow<AssistantChatState> = _state.asStateFlow()

    /**
     * Ceiling on retained lines, so a long session cannot grow the transcript
     * without limit.
     *
     * Generous enough that nobody hits it in a normal conversation, small enough
     * that the list stays cheap to hand to a LazyColumn. Dropped from the *front*:
     * the recent end is the part anybody scrolls to.
     */
    private const val MAX_LINES = 200

    /** The user said something. */
    fun userInput(text: String) = append(AssistantLine(role = AssistantLineRole.User, text = text))

    /** The assistant said something. */
    fun reply(text: String) = append(AssistantLine(role = AssistantLineRole.Assistant, text = text))

    /**
     * A progress line about the assistant's own work.
     *
     * Also becomes the resting text of the collapsed pill, which is why it is set
     * here as well as appended: the pill shows the current step, the transcript
     * keeps the history of them.
     */
    fun activity(text: String) {
        _state.value = _state.value.copy(
            activity = text,
            lines = appendLine(_state.value.lines, AssistantLine(
                role = AssistantLineRole.Activity,
                text = text,
            )),
        )
    }

    /** Between steps, so the pill can stop claiming to be working. */
    fun clearActivity() {
        _state.value = _state.value.copy(activity = null)
    }

    fun setThinking(thinking: Boolean) {
        _state.value = _state.value.copy(isThinking = thinking)
    }

    private fun append(line: AssistantLine) {
        _state.value = _state.value.copy(lines = appendLine(_state.value.lines, line))
    }

    private fun appendLine(
        lines: List<AssistantLine>,
        line: AssistantLine,
    ): List<AssistantLine> {
        val next = lines + line
        return if (next.size <= MAX_LINES) next else next.takeLast(MAX_LINES)
    }

    /**
     * The user asked for a clean slate, from the header's new-chat button.
     *
     * Only the transcript is dropped. [activity] is cleared with it because a stale
     * "Opening Settings" left on a pill describing a conversation that no longer
     * exists is worse than no pill text at all.
     */
    fun clear() {
        _state.value = AssistantChatState()
    }
}
