package com.blurr.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blurr.voice.api.Eyes
//import com.blurr.voice.services.AgentTaskService
import com.blurr.voice.utilities.SpeechCoordinator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import com.blurr.voice.agents.ClarificationAgent
import com.blurr.voice.utilities.TTSManager
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import com.blurr.voice.utilities.FreemiumManager
import com.blurr.voice.utilities.DeltaState
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.v2.AgentService
import com.blurr.voice.assistant.AssistantInput
import com.blurr.voice.assistant.AssistantSessionState
import com.blurr.voice.assistant.SessionBridge
import com.blurr.voice.data.UserMemory
import com.google.ai.client.generativeai.type.TextPart
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.blurr.voice.utilities.ServicePermissionManager
import com.blurr.voice.utilities.DeltaStateManager
import com.blurr.voice.v2.perception.Perception
import com.blurr.voice.v2.perception.SemanticParser
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

data class ModelDecision(
    val type: String = "Reply",
    val reply: String,
    val instruction: String = "",
    val shouldEnd: Boolean = false
)

class ConversationalAgentService : Service() {

    private val speechCoordinator by lazy { SpeechCoordinator.getInstance(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var conversationHistory = listOf<Pair<String, List<Any>>>()
    private val ttsManager by lazy { TTSManager.getInstance(this) }
    private val deltaStateManager by lazy { DeltaStateManager.getInstance(this) }
    private var isTextModeActive = false
    private val freemiumManager by lazy { FreemiumManager() }
    private val servicePermissionManager by lazy { ServicePermissionManager(this) }

    private var clarificationAttempts = 0
    private val maxClarificationAttempts = 1
    private var sttErrorAttempts = 0

    private val clarificationAgent = ClarificationAgent()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var cachedMemories = listOf<UserMemory>()
    private var hasHeardFirstUtterance = false
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private lateinit var perception: Perception
    private val client = OkHttpClient()

    
    // Firebase instances for conversation tracking
    private val db = Firebase.firestore
    private val auth = Firebase.auth
    private var conversationId: String? = null // Track current conversation session


    companion object {
        const val NOTIFICATION_ID = 3
        const val CHANNEL_ID = "ConversationalAgentChannel"
        const val ACTION_STOP_SERVICE = "com.blurr.voice.ACTION_STOP_SERVICE"
        var isRunning = false
        const val MEMORY_ENABLED = true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        Log.d("ConvAgent", "Service onCreate")
        
        // Initialize Firebase Analytics
        firebaseAnalytics = Firebase.analytics
        
        // Track service creation
        firebaseAnalytics.logEvent("conversational_agent_started", null)
        
        isRunning = true
        createNotificationChannel()
        initializeConversation()
        clarificationAttempts = 0 // Reset clarification attempts counter
        sttErrorAttempts = 0 // Reset STT error attempts counter
        // usedMemories.clear() // Removed
        hasHeardFirstUtterance = false // Reset first utterance flag

        fetchMemories() // Start async memory fetch

        // Start state monitoring and set initial state
        deltaStateManager.startMonitoring()
        deltaStateManager.setState(DeltaState.IDLE)


    }

    /**
     * Whether the assistant popup is on screen and is therefore the user-facing half of
     * the conversation right now.
     *
     * Read by everything in this service that would otherwise speak or listen. While the
     * popup is up it *is* the conversation: it has its own composer, its own recogniser
     * and its own transcript. Two recognisers at once is not a cosmetic duplication --
     * the second one fails with ERROR_RECOGNIZER_BUSY, and the loser is whichever one the
     * user is actually talking to.
     */
    private fun popupOwnsScreen(): Boolean = SessionBridge.sessionVisible.value

    /**
     * Call this when the user starts interacting with the text input.
     * It stops any ongoing voice interaction.
     */
    private fun enterTextMode() {
        if (isTextModeActive) return
        Log.d("ConvAgent", "Entering Text Mode. Stopping STT/TTS.")
        
        // Track text mode activation
        firebaseAnalytics.logEvent("text_mode_activated", null)
        
        isTextModeActive = true
        deltaStateManager.setState(DeltaState.IDLE)
        speechCoordinator.stopListening()
        speechCoordinator.stopSpeaking()
    }


    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ConvAgent", "Service onStartCommand")

        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i("ConvAgent", "Received stop action. Stopping service.")
            // Silence before the service goes. stopSelf only cancels this service's
            // coroutines; words already handed to the TTS engine keep playing, and an
            // open recogniser keeps holding the microphone until it is told to let go.
            // The point of this action is that the assistant is over *now*, so the audio
            // hardware is released here rather than in onDestroy.
            speechCoordinator.stopSpeaking()
            speechCoordinator.stopListening()
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Check if we have the required RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("ConvAgent", "RECORD_AUDIO permission not granted. Cannot start foreground service.")
            Toast.makeText(this, "Microphone permission required for voice assistant", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: SecurityException) {
            serviceScope.launch {
                speechCoordinator.speakText("Hello, please give microphone permission or some other type of permission you have not given me!")
                delay(2000) // Wait for TTS to complete before closing service
                stopSelf()
            }
            Log.e("ConvAgent", "Failed to start foreground service: ${e.message}")
            Toast.makeText(this, "Cannot start voice assistant - permission missing", Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }

        if (!servicePermissionManager.isMicrophonePermissionGranted()) {
            Log.e("ConvAgent", "RECORD_AUDIO permission not granted. Shutting down.")
            serviceScope.launch {
                ttsManager.speakText(getString(R.string.microphone_permission_not_granted))
                delay(2000)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // A request from the assistant popup (com.blurr.voice.ui.chat.AssistantChatScreen).
        //
        // The user has already spoken or typed it, so it goes straight into
        // processUserInput instead of opening the microphone first: listening again here
        // would have the service's recogniser and the popup's both live at once, and
        // SpeechRecognizer allows exactly one of those at a time. The loser's callbacks
        // then arrive on whichever engine happened to win, which is how a request from
        // the popup ends up as a half-transcribed utterance.
        val submitted = intent
            ?.takeIf { it.action == AssistantInput.ACTION_SUBMIT_TEXT }
            ?.getStringExtra(AssistantInput.EXTRA_TEXT)
            ?.trim()

        if (!submitted.isNullOrEmpty()) {
            Log.d("ConvAgent", "Request from the assistant popup: '$submitted'")
            firebaseAnalytics.logEvent("conversation_initiated", null)
            trackConversationStart()
            // Thinking is published rather than inferred from the absence of a reply, so
            // the popup shows a spinner from the moment the request is sent instead of
            // sitting blank until the answer lands.
            AssistantSessionState.setThinking(true)
            mainHandler.post { processUserInput(submitted) }
            return START_STICKY
        }

        // Nothing starts this service without a request any more: the assistant popup
        // sends ACTION_SUBMIT_TEXT and the notification sends ACTION_STOP_SERVICE. This
        // branch is only reached when START_STICKY restarts the service with a null
        // intent after the process was killed -- there is no popup behind it and no text
        // to act on, and the old behaviour was to open the microphone into an empty
        // screen and hold it there. There is no interface to show an answer to, so the
        // honest thing is to end.
        Log.d("ConvAgent", "No request in the start intent; stopping.")
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Gets a personalized greeting using the user's name from memories if available
     * NOTE: This method is kept for potential future use but no longer called on startup
     */
    private fun getPersonalizedGreeting(): String {
        try {
            val userProfile = UserProfileManager(this@ConversationalAgentService)
            Log.d("ConvAgent", "No name found in memories, using generic greeting")
            return "Hey ${userProfile.getName()}!"
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error getting personalized greeting", e)
            return "Hey!"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun speakAndThenListen(text: String, draw: Boolean = true) {
        // The popup is the assistant's only face. If it is gone, there is nobody left to
        // say this to: speaking into an empty screen is the old arrangement where the
        // popup was a view onto a conversation running without it, and after the reply the
        // conversation then opened the microphone, heard nothing, apologised and tried
        // again. Ending here is the honest answer, and it is what stops that loop -- the
        // service's own stop path takes the window away as well.
        //
        // Checked before publishing rather than after speaking: the whole point is to not
        // make a sound.
        if (!popupOwnsScreen()) {
            Log.d("ConvAgent", "Assistant popup is gone; ending instead of speaking to nothing.")
            SessionBridge.endOnServiceShutdown()
            stopSelf()
            return
        }
        // Only update system prompt with memories if we've heard the first utterance
//        if (hasHeardFirstUtterance) {
//            updateSystemPromptWithMemories()
//        }
        updateSystemPromptWithTime()
        // Into the assistant popup's transcript, which is the same funnel: every reply,
        // question, clarification and farewell is spoken through here, so this is the one
        // place that catches all of them without repeating itself at each call site.
        //
        // Published *before* the audio, not after. speakText suspends until playback has
        // finished, so doing this the other way round left the popup on its thinking
        // spinner for the whole length of the answer -- several seconds of "thinking"
        // and then the words the user had already been reading out loud. The transcript is
        // something the user reads; the audio is something that follows it.
        //
        // Thinking is cleared at the same moment. The popup's spinner is tied to "a
        // request is outstanding", and a reply arriving is exactly that becoming false --
        // which matters most for the question cases, where the model asks something and
        // the turn is not over, but the user is plainly no longer waiting on an answer.
        AssistantSessionState.reply(text)
        AssistantSessionState.setThinking(false)
        AssistantSessionState.clearActivity()

        deltaStateManager.setState(DeltaState.SPEAKING)
        speechCoordinator.speakText(text)
        Log.d("ConvAgent", "Delta said: $text")
        // The microphone belongs to the popup for the whole conversation. The reply above
        // is what the user hears; their answer arrives back here as a fresh
        // ACTION_SUBMIT_TEXT from the popup's own recogniser. So this turn ends with the
        // words, exactly where it started -- there is deliberately no listening here. Two
        // recognisers is a loss for whichever one loses, and the popup is the one the user
        // is looking at.
    }


    // --- CHANGED: Rewritten to process the new custom text format ---
    @RequiresApi(Build.VERSION_CODES.R)
    private fun processUserInput(userInput: String) {
        serviceScope.launch {
            // --- Echo guard ---
            // The recogniser stays open while TTS plays, so our own voice can be
            // transcribed as a user turn and then answered by the model ("Is there
            // anything else you need?" -> "No, I'm all set!"). This is the single
            // funnel every request passes through; drop anything that is
            // substantially the last thing we spoke, within seconds of saying it.
            if (ttsManager.isRecentSpeech(userInput)) {
                Log.d("ConvAgent", "Dropping echo of our own speech: '$userInput'")
                return@launch
            }
            // Into the assistant popup's transcript, before anything can fail.
            //
            // Published here rather than at the recogniser or in trackMessage because
            // this is the one place every request passes through, whether it was spoken
            // or typed. trackMessage was the tempting single hook and is the wrong one
            // twice over: it returns early for a signed-out user, so the popup would show
            // an empty conversation for anyone not logged in, and it runs inside its own
            // coroutine, so the line would arrive late and out of order.
            AssistantSessionState.userInput(userInput)
            AssistantSessionState.setThinking(true)
            updateSystemPromptWithAgentStatus()
            updateSystemPromptWithScreenContext()
            updateSystemPromptWithTime()
            // Mark that we've heard the first utterance and trigger memory extraction if not already done
            if (!hasHeardFirstUtterance) {
                hasHeardFirstUtterance = true
                Log.d("ConvAgent", "First utterance received via processUserInput, triggering memory extraction")
                try {
                    updateSystemPromptWithScreenContext()
                } catch (e: Exception) {
                    Log.e("ConvAgent", "Error during first utterance memory extraction", e)
                    // Continue execution even if memory extraction fails
                }
            }

            conversationHistory = addResponse("user", userInput, conversationHistory)
            
            // Track user message in Firebase
            trackMessage("user", userInput, "input")

            // Track user input
            val inputBundle = android.os.Bundle().apply {
                putString("input_type", if (isTextModeActive) "text" else "voice")
                putInt("input_length", userInput.length)
                putBoolean("is_command", userInput.equals("stop", ignoreCase = true) || userInput.equals("exit", ignoreCase = true))
            }
            firebaseAnalytics.logEvent("user_input_processed", inputBundle)

            try {
                if (userInput.equals("stop", ignoreCase = true) || userInput.equals("exit", ignoreCase = true)) {
                    firebaseAnalytics.logEvent("conversation_ended_by_command", null)
                    trackMessage("model", "Goodbye!", "farewell")
                    gracefulShutdown("Goodbye!", "command")
                    return@launch
                }
                deltaStateManager.setState(DeltaState.PROCESSING)
                val defaultJsonResponse = """{"Type": "Reply", "Reply": "I'm sorry, I had an issue.", "Instruction": "", "Should End": "Continue"}"""
                // A plain "open <app>" request is deterministic: the agent resolves
                // and launches the app without reading the screen, so the reasoning
                // model gets no say here. It has misclassified these as small talk
                // (answering its own previous "Is there anything else you need?"
                // with "No, I'm all set!"), which leaves the user staring at a
                // spinner while nothing opens. Bypass its judgment -- and save its
                // latency -- by synthesising the Task decision directly.
                val simpleOpen = isSimpleOpenRequest(userInput)
                val rawModelResponse = if (simpleOpen) {
                    Log.d("ConvAgent", "Deterministic routing: '$userInput' -> Task (plain app-open).")
                    val escapedInstruction = userInput
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                    //language=json
                    """{"Type": "Task", "Reply": "", "Instruction": "$escapedInstruction", "Should End": "Continue"}"""
                } else {
                    getReasoningModelApiResponse(conversationHistory) ?: defaultJsonResponse
                }
                val decision = parseModelResponse(rawModelResponse)
                Log.d("TTS_DEBUG", "Reply received from GeminiApi: -->${rawModelResponse}<--")
                when (decision.type) {
                    "Task" -> {
                        // Track task request
                        val taskBundle = android.os.Bundle().apply {
                            putString("task_instruction", decision.instruction.take(100)) // Limit length for analytics
                            putBoolean("agent_already_running", AgentService.isRunning)
                        }
                        firebaseAnalytics.logEvent("task_requested", taskBundle)
                        
                        if (AgentService.isRunning) {
                            firebaseAnalytics.logEvent("task_rejected_agent_busy", null)
                            val busyMessage = "I'm already working on '${AgentService.currentTask}'. Please let me finish that first, or you can ask me to stop it."
                            speakAndThenListen(busyMessage)
                            conversationHistory = addResponse("model", busyMessage, conversationHistory)
                            return@launch
                        }

                        if (!servicePermissionManager.isAccessibilityServiceEnabled()) {
                            speakAndThenListen(getString(R.string.accessibility_permission_needed_for_task))
                            conversationHistory = addResponse("model", R.string.accessibility_permission_needed_for_task.toString(), conversationHistory)
                            return@launch
                        }

                        Log.d("ConvAgent", "Model identified a task. Checking for clarification...")
                        // --- NEW: Check if the task instruction needs clarification ---
                        if(freemiumManager.canPerformTask()){
                            Log.d("ConvAgent", "Allowance check passed. Proceeding with task.")

                            if (clarificationAttempts < maxClarificationAttempts) {
                                val (needsClarification, questions) = checkIfClarificationNeeded(
                                    decision.instruction
                                )
                                Log.d("ConcAgent", needsClarification.toString())
                                Log.d("ConcAgent", questions.toString())

                                if (needsClarification) {
                                    // Track clarification needed
                                    val clarificationBundle = android.os.Bundle().apply {
                                        putInt("clarification_attempt", clarificationAttempts + 1)
                                        putInt("questions_count", questions.size)
                                    }
                                    firebaseAnalytics.logEvent("task_clarification_needed", clarificationBundle)
                                    
                                    clarificationAttempts++
                                    val questionToAsk =
                                        "I can help with that, but first: ${questions.joinToString(" and ")}"
                                    Log.d(
                                        "ConvAgent",
                                        "Task needs clarification. Asking: '$questionToAsk' (Attempt $clarificationAttempts/$maxClarificationAttempts)"
                                    )
                                    conversationHistory = addResponse(
                                        "model",
                                        "Clarification needed for task: ${decision.instruction}",
                                        conversationHistory
                                    )
                                    trackMessage("model", questionToAsk, "clarification")
                                    speakAndThenListen(questionToAsk, false)
                                } else {
                                    Log.d(
                                        "ConvAgent",
                                        "Task is clear. Executing: ${decision.instruction}"
                                    )
                                    
                                    // Track task execution
                                    firebaseAnalytics.logEvent("task_executed", taskBundle)
                                    
                                    val originalInstruction = decision.instruction
                                    AgentService.start(applicationContext, originalInstruction)
                                    trackMessage("model", decision.reply, "task_confirmation")
                                    gracefulShutdown(decision.reply, "task_executed", keepWindow = true)
                                }
                            } else {
                                Log.d(
                                    "ConvAgent",
                                    "Max clarification attempts reached ($maxClarificationAttempts). Proceeding with task execution."
                                )
                                
                                // Track max clarification attempts reached
                                firebaseAnalytics.logEvent("task_executed_max_clarification", taskBundle)
                                
                                // Guard against the model leaking raw UI actions
                                // into the instruction (e.g. "tap element [10]").
                                // That made the executor tap whatever happened to
                                // be at that index instead of doing the real task.
                                val instruction = decision.instruction
                                val uiActionLeak = Regex(
                                    """(?i)\b(tap|click|swipe|press)\b.*\[\d+\]|\belement\s*\[\s*\d+\s*]"""
                                ).containsMatchIn(instruction)

                                if (uiActionLeak) {
                                    Log.w("ConvAgent", "Instruction leaked a UI action: '$instruction'")
                                    val repaired = rewriteInstructionAsGoal(instruction)
                                    if (repaired.isNullOrBlank()) {
                                        val clarify = "Sorry, I wasn't sure what you wanted me to do. Could you say that again?"
                                        speakAndThenListen(clarify)
                                        conversationHistory = addResponse("model", clarify, conversationHistory)
                                        return@launch
                                    }
                                    Log.d("ConvAgent", "Repaired instruction: '$repaired'")
                                    AgentService.start(applicationContext, repaired)
                                } else {
                                    AgentService.start(applicationContext, instruction)
                                }
                                trackMessage("model", decision.reply, "task_confirmation")
                                gracefulShutdown(decision.reply, "task_executed", keepWindow = true)
                            }
                        }else{
                            Log.w("ConvAgent", "User has no tasks remaining. Denying request.")
                            
                            // Track freemium limit reached
                            firebaseAnalytics.logEvent("task_rejected_freemium_limit", null)
                            
                            val upgradeMessage = "Hey! You've used all your free tasks for the month. Please upgrade in the app to unlock more. We can still talk in voice mode."
                            conversationHistory = addResponse("model", upgradeMessage, conversationHistory)
                            trackMessage("model", upgradeMessage, "freemium_limit")
                            speakAndThenListen(upgradeMessage)
                        }
                    }
                    "KillTask" -> {
                        Log.d("ConvAgent", "Model requested to kill the running agent service.")
                        
                        // Track kill task request
                        val killTaskBundle = android.os.Bundle().apply {
                            putBoolean("task_was_running", AgentService.isRunning)
                        }
                        firebaseAnalytics.logEvent("kill_task_requested", killTaskBundle)
                        
                        if (AgentService.isRunning) {
                            AgentService.stop(applicationContext)
                            trackMessage("model", decision.reply, "kill_task_response")
                            gracefulShutdown(decision.reply, "task_killed")
                        } else {
                            val noTaskMessage = "There was no automation running, but I can help with something else."
                            trackMessage("model", noTaskMessage, "kill_task_response")
                            speakAndThenListen(noTaskMessage)
                        }
                    }
                    else -> { // Default to "Reply"
                        // Track conversational reply
                        val replyBundle = android.os.Bundle().apply {
                            putBoolean("conversation_ended", decision.shouldEnd)
                            putInt("reply_length", decision.reply.length)
                        }
                        firebaseAnalytics.logEvent("conversational_reply", replyBundle)
                        
                        if (decision.shouldEnd) {
                            Log.d("ConvAgent", "Model decided to end the conversation.")
                            firebaseAnalytics.logEvent("conversation_ended_by_model", null)
                            trackMessage("model", decision.reply, "farewell")
                            gracefulShutdown(decision.reply, "model_ended")
                        } else {
                            conversationHistory = addResponse("model", rawModelResponse, conversationHistory)
                            trackMessage("model", decision.reply, "reply")
                            speakAndThenListen(decision.reply)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("ConvAgent", "Error processing user input: ${e.message}", e)
                
                // Trigger error state in state manager
                deltaStateManager.triggerErrorState()
                
                // Track processing errors
                val errorBundle = android.os.Bundle().apply {
                    putString("error_message", e.message?.take(100) ?: "Unknown error")
                    putString("error_type", e.javaClass.simpleName)
                }
                firebaseAnalytics.logEvent("input_processing_error", errorBundle)
                
                speakAndThenListen("closing voice mode")
            }
        }
    }

    //    private suspend fun getGroundedStepsForTask(taskInstruction: String): String {
//        Log.d("ConvAgent", "Performing grounded search for task: '$taskInstruction'")
//
//        // We create a specific prompt for the search.
//        val searchPrompt = """
//        Search the web and provide a concise, step-by-step guide for a human assistant to perform the following task on an Android phone: '$taskInstruction'.
//        Focus on the exact taps and settings involved.
//    """.trimIndent()
//
//        // Here we use the direct REST API call with search that we created previously.
//        // We need an instance of GeminiApi to call it.
//        // NOTE: You might need to adjust how you get your GeminiApi instance.
//        // For now, we'll assume we can create one or access it.
//        val geminiApi = GeminiApi("gemini-2.5-flash", ApiKeyManager, 2)
//
//        val searchResult = geminiApi.generateGroundedContent(searchPrompt)
//        Log.d("CONVO_SEARCH", searchResult.toString())
//        return if (!searchResult.isNullOrBlank()) {
//            searchResult
//        } else {
//            ""
//        }
//    }
    private suspend fun checkIfClarificationNeeded(instruction: String): Pair<Boolean, List<String>> {
        Log.d("ConvAgent", "Checking for clarification on instruction: '$instruction'")
        return Pair(false, listOf())
    }

    private fun initializeConversation() {
        val memoryContextSection = if (MEMORY_ENABLED) {
            """
            Use these memories to answer the user's question with his personal data
            ### Memory Context Start ###
            {memory_context}
            ### Memory Context Ends ###
            """
        } else {
            """
            ### Memory Status ###
            Memory system is temporarily disabled. Delta cannot remember or learn from previous conversations at this time.
            some memories added by developers
            {memory_context}
            ### End Memory Status ###
            """
        }

        val systemPrompt = """
            You are a helpful voice assistant called Delta that can either have a conversation or ask an executor to execute tasks on the user's phone.
            The executor can speak, listen, see the screen, tap the screen, and basically use the phone as a normal human would.

            {agent_status_context}

            ### Current Screen Context ###
            {screen_context}
            ### End Screen Context ###

            Some Guideline:
            1. If the user ask you to do something creative, you do this task and be the most creative person in the world.
            2. If you know the user's name from the memories, refer to them by their name to make the conversation more personal and friendly as often as possible.
            3. Use the current screen context to better understand what the user is looking at and provide more relevant responses.
            4. If the user asks about something on the screen, you can reference the screen content directly.
            5. Always ask for clarification if the user's request is ambiguous or unclear.
            6. When the user ask to sing, shout or produce any sound, just generate text, we will sing it for you.
            7. Give a warning for the tasks related to banking, games, shopping and app with Canvas (no a11y tree) that you wont be able to do them properly but you will try your best.
            8. Timers, alarms and reminders are ACTIONS, not conversation. "Set a timer for 5 minutes", "wake me up at 7", "set an alarm for 6:30am" and "remind me to call mum tomorrow at 6" are all "Type": "Task", never "Type": "Reply".
            9. For those, write the "Instruction" as the complete request, keeping the time exactly as the user said it - e.g. "Set a timer for 5 minutes" or "Remind me to call the dentist tomorrow at 6pm". Do NOT convert times to seconds or work out the clock time yourself; the executor schedules these properly.
            10. Confirm briefly in "Reply" (e.g. "Sure, setting a 5 minute timer.") and let the executor do the work. Do not claim it is done until the executor reports back.
            11. Device-state questions ("what's my battery", "how much battery do I have", "is my wifi on", "is my screen on") are TASKS, not conversation. "Type": "Task", "Instruction": "Report the device state: battery level, charging status, screen, and network."
            12. Notification questions ("what notifications do I have", "any new messages", "anything new") are TASKS. "Type": "Task", "Instruction": "Read and summarize my current notifications."
            13. File questions ("what files do I have", "show my notes", "read my todo") are TASKS. "Type": "Task", "Instruction": "Use the file tools to show my files."
            14. NEVER answer "I can't check that" or "I can't do that" for battery, time, notifications, reminders, device state, files, installed apps, or anything a task agent tool could cover. The executor has tools that read these directly without the screen, so route them as Tasks even when the screen is locked or off.
            15. Time questions ("what time is it", "what day is it") are conversation: you have the current time above, so answer directly in "Reply". Never say you cannot tell the time.
            16. Reminder/timer status questions ("what's my next reminder", "what reminders do I have", "check my timer") are TASKS. "Type": "Task", "Instruction": "List my scheduled reminders and when they fire. Note countdown timers run in the clock app and cannot be read back."
            17. If a task needs the screen (opening an app, typing, tapping) while the phone is locked, the agent will surface the real unlock screen itself and wait for the user - route it as a normal Task and DO NOT refuse or ask the user to unlock first.
            18. If the user asks to open an app you do not see in the <installed_apps> list below, still route it as a Task with the app's exact name: that list can be truncated and only the task agent can confirm an app is truly missing.
            
            Use these memories to answer the user's question with his personal data
            ### Memory Context Start ###
            {memory_context}
            ### Memory Context Ends ###
            
            Analyze the user's request and respond ONLY with a single, valid JSON object.
            Do not include any text, notes, or explanations outside of the JSON object.
            The JSON object must have the following structure:
            
            {
              "Type": "String",
              "Reply": "String",
              "Instruction": "String",
              "Should End": "String"
            }

            Here are the rules for the JSON values:
            - "Type": Must be one of "Task", "Reply", or "KillTask".
              - Use "Task" if the user is asking you to DO something on the device (e.g., "open settings", "send a text to Mom").
              - Use "Reply" for conversational questions (e.g., "what's the weather?", "tell me a joke").
              - Use "KillTask" ONLY if an automation task is running and the user wants to stop it.
            - "Reply": The text to speak to the user. This is a confirmation for a "Task", or the direct answer for a "Reply".
            - "Instruction": The task agent's GOAL, restated in plain natural language (e.g. "Open the ChatGPT app", "Search for pizza places near me and tell me the top result"). This field should be an empty string "" if the "Type" is not "Task".
              - CRITICAL: Write the WHAT, never the HOW. Do NOT describe taps, swipes, coordinates, or element indexes, and do NOT copy UI actions out of the screen context above (e.g. "tap element [10]" is WRONG). The task agent has its own eyes and will work out the steps itself.
              - If the user names an app, use the app's exact name from the <installed_apps> list when it is present there.
              - NEVER decide in this layer that an app is missing: the <installed_apps> list can be truncated, and only the task agent (which checks the full package list) can confirm an app truly cannot be opened. Route app requests as "Task" with the app's name even when it is not listed; if the task agent reports the app cannot be opened, tell the user at that point.
            - "Should End": Must be either "Continue" or "Finished". Use "Finished" only when the conversation is naturally over.
        
            Current Time : {time_context}
        """.trimIndent()

        // The conversational layer needs the same app catalog the task agent
        // gets, otherwise it cannot tell whether a requested app exists and
        // substitutes a different one. Appended to the same message so the
        // history is not reset.
        val installedApps = com.blurr.voice.v2.InstalledAppsCatalog.describeForPrompt(this, maxEntries = 250)
        val finalPrompt = if (installedApps.isNotBlank()) {
            "$systemPrompt\n\n$installedApps"
        } else {
            systemPrompt
        }

        conversationHistory = addResponse("user", finalPrompt, emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateSystemPromptWithTime() {
        val currentPromptText = conversationHistory.firstOrNull()?.second
            ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

        val currentTime = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        val formattedTime = currentTime.format(formatter)

        // Matches "Current Time : {time_context}" OR "Current Time : 2025-12-11..."
        // This ensures we can update it even if the placeholder is already gone.
        val timeRegex = Regex("Current Time : (\\{time_context\\}|.*)")
        val newTimeLine = "Current Time : $formattedTime"

        val updatedPromptText = timeRegex.replace(currentPromptText, newTimeLine)

        // Replace the first system message with the updated prompt
        conversationHistory = conversationHistory.toMutableList().apply {
            set(0, "user" to listOf(TextPart(updatedPromptText)))
        }
        Log.d("ConvAgent", "System prompt updated with time: $formattedTime")
    }
    private fun updateSystemPromptWithAgentStatus() {
        val currentPromptText = conversationHistory.firstOrNull()?.second
            ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

        val agentStatusContext = if (AgentService.isRunning) {
            """
            IMPORTANT CONTEXT: An automation task is currently running in the background.
            Task Description: "${AgentService.currentTask}".
            If the user asks to stop, cancel, or kill this task, you MUST use the "KillTask" type.
            """.trimIndent()
        } else {
            "CONTEXT: No automation task is currently running."
        }

        val updatedPromptText = currentPromptText.replace("{agent_status_context}", agentStatusContext)

        // Replace the first system message with the updated prompt
        conversationHistory = conversationHistory.toMutableList().apply {
            set(0, "user" to listOf(TextPart(updatedPromptText)))
        }
        Log.d("ConvAgent", "System prompt updated with agent status: ${AgentService.isRunning}")
    }

    /**
     * Updates the system prompt with relevant memories and current screen context
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun updateSystemPromptWithScreenContext() {
        try {

            perception = Perception(Eyes(this), SemanticParser())
            val analysis = perception.analyze(all = true)
            Log.d("ConvAgent", "Screen analysis: ${analysis.uiRepresentation}")
            val currentPrompt = conversationHistory.firstOrNull()?.second
                ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

            // Update screen context first
            var updatedPrompt = currentPrompt.replace("{screen_context}", analysis.uiRepresentation)

            // Check if memory is enabled before processing memories
            if (!MEMORY_ENABLED) {
                var userProfile = UserProfileManager(this@ConversationalAgentService)

                Log.d("ConvAgent", "Memory is disabled, skipping memory operations")
                Log.d("ConvAgent", "User name is ${userProfile.getName()}")
                // Replace memory context with disabled message

                updatedPrompt = updatedPrompt.replace("{memory_context}", "User name is ${userProfile.getName()}")
            } else {
                // Use cached memories from Firestore
                if (cachedMemories.isNotEmpty()) {
                    Log.d("ConvAgent", "Injecting ${cachedMemories.size} cached memories into context")
                    
                    // Take top 100 memories (already sorted by date desc in fetch logic if needed, or just take latest)
                    // For now, we just take the list as is, assuming it's not huge, or take top 100.
                    val topMemories = cachedMemories.take(100)
                    
                    val memoryContext = topMemories.joinToString("\n") { memory -> 
                        "- ${memory.text} (Source: ${memory.source})" 
                    }
                    updatedPrompt = updatedPrompt.replace("{memory_context}", memoryContext)
                } else {
                     Log.d("ConvAgent", "No cached memories available yet")
                     updatedPrompt = updatedPrompt.replace("{memory_context}", "No memories available yet.")
                }
            }

            if (updatedPrompt.isNotEmpty()) {
                // Replace the first system message with updated prompt
                conversationHistory = conversationHistory.toMutableList().apply {
                    set(0, "user" to listOf(TextPart(updatedPrompt)))
                }
                Log.d("ConvAgent", "Updated system prompt with screen context and memories")
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error updating system prompt with memories and screen context", e)
        }
    }

    /**
     * Extracts current memory context from the system prompt
     */
    private fun extractCurrentMemoryContext(prompt: String): List<String> {
        return try {
            val memorySection = prompt.substringAfter("##### MEMORY CONTEXT #####")
                .substringBefore("##### END MEMORY CONTEXT #####")
                .trim()

            if (memorySection.isNotEmpty() && !memorySection.contains("{memory_context}")) {
                memorySection.lines()
                    .filter { it.trim().startsWith("- ") }
                    .map { it.trim().substring(2) } // Remove "- " prefix
                    .filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error extracting current memory context", e)
            emptyList()
        }
    }
    /**
     * True when the user's request is only about opening a single app and nothing
     * more ("open camera", "launch whatsapp"). Such requests are routed to the
     * agent deterministically, skipping the reasoning model entirely -- it has
     * repeatedly misclassified them as small talk, and the agent can resolve and
     * launch the app on its own in milliseconds. Must stay in lockstep with
     * [com.blurr.voice.v2.Agent.isSimpleOpenRequest], which makes the same
     * cut on its side.
     */
    private fun isSimpleOpenRequest(request: String): Boolean {
        val lowered = request.trim().lowercase()
        val rest = Regex("^\\s*(?:open|launch|start|open up|take me to)\\s+(.+)$")
            .find(lowered)?.groupValues?.get(1)?.trim() ?: return false
        if (rest.isBlank()) return false
        val words = rest.split(Regex("\\s+")).filter {
            it.isNotBlank() && it != "the" && it != "app" && it != "application"
        }
        if (words.size > 3) return false
        val actionHints = setOf(
            "and", "then", "take", "send", "record", "capture", "snap", "show",
            "check", "find", "search", "play", "set", "list", "read", "write",
            "call", "message", "text", "with", "after", "before", "stop"
        )
        return words.none { it in actionHints }
    }

    private fun parseModelResponse(response: String): ModelDecision {
        try {
            // Models often wrap JSON in ```json fences or add prose around it.
            // Strip that before parsing, otherwise we silently fall back to the
            // "tangled thoughts" reply and the task never runs.
            val cleaned = extractJsonObject(response)

            val json = JSONObject(cleaned)
            Log.d("justchecking", json.toString())
            // Use optString for safety, providing a default value if the key doesn't exist.
            val rawType = json.optString("Type", "Reply")
            // Normalise casing/whitespace. The dispatch below compares with ==,
            // so an un-normalised "task" would fall into the default branch and
            // the assistant would just chat instead of doing anything.
            val type = rawType.trim().replaceFirstChar { it.uppercase() }
                .let { when (it) {
                    "Task" -> "Task"
                    "Killtask" -> "KillTask"
                    "Reply" -> "Reply"
                    else -> {
                        Log.w("ConvAgent", "Unrecognised model Type '$rawType'; treating as Reply.")
                        "Reply"
                    }
                } }
            val reply = json.optString("Reply", "")
            val instruction = json.optString("Instruction", "")
            val shouldEndStr = json.optString("Should End", "Continue")
            val shouldEnd = shouldEndStr.equals("Finished", ignoreCase = true)
            
            // Add a fallback reply if the model provides an empty one for a conversational turn.
            val finalReply = if (reply.isEmpty() && type.equals("Reply", ignoreCase = true)) {
                "I'm not sure how to respond to that."
            } else {
                reply
            }

            if (type == "Task" && instruction.isBlank()) {
                Log.w("ConvAgent", "Model returned Type=Task with an empty Instruction; treating as Reply.")
                return ModelDecision("Reply", "I wasn't sure what you wanted me to do.", "", shouldEnd)
            }

            return ModelDecision(type, finalReply, instruction, shouldEnd)
        } catch (e: org.json.JSONException) {
            Log.e("ConvAgent", "Error parsing JSON response, falling back. Response: $response", e)
            // Fallback for malformed JSON
            return ModelDecision(reply = "I seem to have gotten my thoughts tangled. Could you repeat that?")
        } catch (e: Exception) {
            Log.e("ConvAgent", "Generic error parsing model response, falling back. Response: $response", e)
            return ModelDecision(reply = "I had a minor issue processing that. Could you try again?")
        }
    }

    /**
     * Pulls the outermost JSON object out of a model response, tolerating
     * markdown code fences and surrounding prose.
     */
    private fun extractJsonObject(response: String): String {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start != -1 && end > start) return response.substring(start, end + 1)
        return response
    }

    /**
     * Last-resort repair for an instruction that is really a UI action.
     *
     * Strips the low-level phrasing ("tap element [10]", "swipe up on the ...")
     * and asks the model to restate the underlying goal. Returns null if the
     * repair is not usable, so the caller can ask the user to repeat.
     */
    private suspend fun rewriteInstructionAsGoal(badInstruction: String): String? {
        return try {
            val prompt = """
                The following text was meant to be a task description but instead
                contains low-level screen actions:

                "$badInstruction"

                Rewrite it as a single, plain-language statement of what the user
                wants accomplished on their phone, without mentioning taps, swipes,
                element indexes, or coordinates.

                Reply with ONLY the rewritten goal as plain text. No JSON, no quotes.
            """.trimIndent()

            val response = getReasoningModelApiResponse(
                listOf("user" to listOf(prompt))
            )

            val cleaned = response?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
            if (cleaned.isBlank()) return null

            // Reject a "repair" that is still a UI action or wrapped in JSON.
            if (Regex("""(?i)\b(tap|click|swipe|press)\b.*\[\d+\]""").containsMatchIn(cleaned)) {
                return null
            }
            if (cleaned.startsWith("{") || cleaned.startsWith("[")) return null

            cleaned.take(300)
        } catch (e: Exception) {
            Log.e("ConvAgent", "Failed to repair instruction: ${e.message}")
            null
        }
    }
    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ConversationalAgentService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Deliberately says nothing about listening. This notification is on screen for
        // the whole conversation, and "Listening for your commands..." is a claim that is
        // only true for part of it -- and misleadingly true for the rest, because the
        // microphone is closed while the assistant thinks and speaks. The status pill and
        // the session popup are the surfaces that know the real state; this one just says
        // what is running.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Delta")
            .setContentText("Assistant is active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause, // Using built-in pause icon as stop button
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    /**
     * The channel the foreground notification lives on, deliberately silent and unvibrating.
     *
     * IMPORTANCE_DEFAULT is what the notification inherited when the assistant had no
     * proper system UI of its own, and it is the reason the phone beeped when listening
     * started and beeped again when it stopped: that channel's default sound is played
     * every time a notification is posted to it, so the two beeps bracketed every
     * conversation without either of them being something the user asked for.
     *
     * A foreground service is not allowed to drop its notification, so the fix is not to
     * remove it but to make it unremarkable -- LOW keeps it out of the shade's visible
     * section and off the lock screen, and a null sound means the only thing that changes
     * when the microphone opens and closes is the assistant's own interface. The user
     * already has the session popup and the status pill telling them it is listening;
     * the notification's job here is only to keep the service alive.
     *
     * Existing installs keep the old channel settings, because Android ignores sound and
     * vibration changes made to an existing channel. Deleting it first forces the
     * corrected definition to be applied.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.deleteNotificationChannel(CHANNEL_ID)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Conversational Agent Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private suspend fun gracefulShutdown(
        exitMessage: String? = null,
        endReason: String = "graceful",
        keepWindow: Boolean = false,
    ) {
        // Track graceful shutdown
        val shutdownBundle = android.os.Bundle().apply {
            putBoolean("had_exit_message", exitMessage != null)
            putInt("conversation_length", conversationHistory.size)
            putBoolean("text_mode_used", isTextModeActive)
            putInt("clarification_attempts", clarificationAttempts)
            putInt("stt_error_attempts", sttErrorAttempts)
        }
        firebaseAnalytics.logEvent("conversation_ended_gracefully", shutdownBundle)
        
        // Track conversation end in Firebase

        trackConversationEnd(endReason)
        

        if (exitMessage != null) {
                speechCoordinator.speakText(exitMessage)
                delay(2000) // Give TTS time to finish
            }
            // 1. Extract memories from the conversation before ending
            // Removed old memory extraction logic
            triggerMemoryGeneration()
            
            // 3. Stop the service, and clear the popup with it
            //
            // keepWindow is true only when the conversation is being handed to the task
            // agent: there the popup is not ending, it is about to become the bubble the
            // user watches the task through. Every other reason to be here -- the model
            // said goodbye, the user said stop, the retries ran out -- really is the end,
            // and the window has to go with the service or the transcript sits on screen
            // over a conversation that is no longer running.
            if (!keepWindow) SessionBridge.endOnServiceShutdown()
            stopSelf()

    }

    /**
     * Immediately stops all TTS, STT, and background tasks, hides all UI, and stops the service.
     * This is used for forceful termination, like an outside tap.
     */
    private suspend fun instantShutdown() {
        // Track instant shutdown
        val instantShutdownBundle = android.os.Bundle().apply {
            putInt("conversation_length", conversationHistory.size)
            putBoolean("text_mode_used", isTextModeActive)
            putInt("clarification_attempts", clarificationAttempts)
            putInt("stt_error_attempts", sttErrorAttempts)
        }
        firebaseAnalytics.logEvent("conversation_ended_instantly", instantShutdownBundle)
        
        // Track conversation end in Firebase
        trackConversationEnd("instant")
        
        Log.d("ConvAgent", "Instant shutdown triggered by user.")
        withContext(Dispatchers.Main) {
            speechCoordinator.stopSpeaking()
            speechCoordinator.stopListening()
        }

        // Make a thread-safe copy of the conversation history.
        // Removed old memory extraction logic
        triggerMemoryGeneration()

        // The window goes too: this is the forceful end, and a session left showing the
        // transcript of an instantly-abandoned conversation would be the popup outliving
        // the assistant it exists to show.
        SessionBridge.endOnServiceShutdown()

        serviceScope.cancel("User tapped outside, forcing instant shutdown.")

        stopSelf()
    }

    /**
     * Tracks the conversation start in Firebase by creating a new conversation entry.
     * This method is inspired by AgentService's Firebase operations.
     */
    private fun trackConversationStart() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.w("ConvAgent", "Cannot track conversation, user is not logged in.")
            return
        }

        // Generate a unique conversation ID
        conversationId = "${System.currentTimeMillis()}_${currentUser.uid.take(8)}"

        serviceScope.launch {
            try {
                val conversationEntry = hashMapOf(
                    "conversationId" to conversationId,
                    "startedAt" to Timestamp.now(),
                    "endedAt" to null,
                    "messageCount" to 0,
                    "textModeUsed" to false,
                    "clarificationAttempts" to 0,
                    "sttErrorAttempts" to 0,
                    "endReason" to null, // "graceful", "instant", "command", "model", "stt_errors"
                    "tasksRequested" to 0,
                    "tasksExecuted" to 0
                )

                // Append the conversation to the user's conversationHistory array
                db.collection("users").document(currentUser.uid)
                    .update("conversationHistory", FieldValue.arrayUnion(conversationEntry))
                    .await()

                Log.d("ConvAgent", "Successfully tracked conversation start in Firebase for user ${currentUser.uid}: $conversationId")
            } catch (e: Exception) {
                Log.e("ConvAgent", "Failed to track conversation start in Firebase", e)
                // Don't fail the conversation if Firebase tracking fails
            }
        }
    }

    /**
     * Tracks individual messages in the conversation.
     * Fire and forget operation.
     */
    private fun trackMessage(role: String, message: String, messageType: String = "text") {
        val currentUser = auth.currentUser
        if (currentUser == null || conversationId == null) {
            return
        }

        serviceScope.launch {
            try {
                val messageEntry = hashMapOf(
                    "conversationId" to conversationId,
                    "role" to role, // "user" or "model"
                    "message" to message.take(500), // Limit message length for storage
                    "messageType" to messageType, // "text", "task", "clarification"
                    "timestamp" to Timestamp.now(),
                    "inputMode" to if (isTextModeActive) "text" else "voice"
                )

                // Append the message to the user's messageHistory array
                db.collection("users").document(currentUser.uid)
                    .update("messageHistory", FieldValue.arrayUnion(messageEntry))
                    .await()

                Log.d("ConvAgent", "Successfully tracked message in Firebase: $role - ${message.take(50)}...")
            } catch (e: Exception) {
                Log.e("ConvAgent", "Failed to track message in Firebase", e)
            }
        }
    }

    /**
     * Updates the conversation completion status in Firebase.
     * Fire and forget operation.
     */
    private fun trackConversationEnd(endReason: String, tasksRequested: Int = 0, tasksExecuted: Int = 0) {
        val currentUser = auth.currentUser
        if (currentUser == null || conversationId == null) {
            return
        }

        serviceScope.launch {
            try {
                val completionEntry = hashMapOf(
                    "conversationId" to conversationId,
                    "endedAt" to Timestamp.now(),
                    "messageCount" to conversationHistory.size,
                    "textModeUsed" to isTextModeActive,
                    "clarificationAttempts" to clarificationAttempts,
                    "sttErrorAttempts" to sttErrorAttempts,
                    "endReason" to endReason,
                    "tasksRequested" to tasksRequested,
                    "tasksExecuted" to tasksExecuted,
                    "status" to "completed"
                )

                // Append the completion status to the user's conversationHistory array
                db.collection("users").document(currentUser.uid)
                    .update("conversationHistory", FieldValue.arrayUnion(completionEntry))
                    .await()

                Log.d("ConvAgent", "Successfully tracked conversation end in Firebase: $conversationId ($endReason)")
            } catch (e: Exception) {
                Log.e("ConvAgent", "Failed to track conversation end in Firebase", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ConvAgent", "Service onDestroy")
        
        // Backstop for every other way out of this service. The stop action and the
        // graceful path both release the audio hardware before asking to stop, but the
        // service can also be killed by the platform, and cancelling the coroutine scope
        // below does not un-speak words already handed to the TTS engine or close a
        // recogniser that is still holding the microphone.
        speechCoordinator.stopSpeaking()
        speechCoordinator.stopListening()
        firebaseAnalytics.logEvent("conversational_agent_destroyed", null)
        
        // Track conversation end if not already tracked
        if (conversationId != null) {
            trackConversationEnd("service_destroyed")
        }
        
        serviceScope.cancel()
        isRunning = false
        
        // Stop state monitoring and set final state
        deltaStateManager.setState(DeltaState.IDLE)
        deltaStateManager.stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun fetchMemories() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.w("ConvAgent", "User not logged in, cannot fetch memories")
            return
        }

        Log.d("ConvAgent", "Starting async memory fetch for user: ${currentUser.uid}")
        db.collection("users").document(currentUser.uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("ConvAgent", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val memoriesList = snapshot.get("memories") as? List<Map<String, Any>>
                    if (memoriesList != null) {
                        cachedMemories = memoriesList.mapNotNull { map ->
                            try {
                                UserMemory(
                                    id = map["id"] as? String ?: "",
                                    text = map["text"] as? String ?: "",
                                    source = map["source"] as? String ?: "User",
                                    createdAt = (map["createdAt"] as? Timestamp)?.toDate() ?: java.util.Date()
                                )
                            } catch (e: Exception) {
                                Log.e("ConvAgent", "Error parsing memory", e)
                                null
                            }
                        }.sortedByDescending { it.createdAt } // Sort by newest first
                        
                        Log.d("ConvAgent", "Fetched ${cachedMemories.size} memories from Firestore")
                    } else {
                        Log.d("ConvAgent", "No memories field found in user document")
                        cachedMemories = emptyList()
                    }
                } else {
                    Log.d("ConvAgent", "Current data: null")
                }
            }
    }

    private fun triggerMemoryGeneration() {
        val currentUser = auth.currentUser
        val userEmail = currentUser?.email

        if (userEmail == null) {
            Log.w("ConvAgent", "User email not found, cannot trigger memory generation")
            return
        }

        Log.d("ConvAgent", "Triggering memory generation for email: $userEmail")

        val json = JSONObject()
        json.put("email", userEmail)

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://getuserdatabyemail-w7fh6zvo4q-uc.a.run.app")
            .addHeader("X-API-Key", BuildConfig.GCLOUD_PROXY_URL_KEY)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ConvAgent", "Memory generation request failed", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e("ConvAgent", "Memory generation request failed with code: ${response.code}")
                    } else {
                        Log.d("ConvAgent", "Memory generation request sent successfully. Response: ${response.body?.string()}")
                    }
                }
            }
        })
    }

}