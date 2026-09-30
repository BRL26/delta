package com.blurr.voice.v2

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.blurr.voice.assistant.AssistantSessionState
import com.blurr.voice.assistant.SessionBridge
import com.blurr.voice.v2.actions.ActionExecutor
import com.blurr.voice.v2.fs.FileSystem
import com.blurr.voice.v2.llm.GeminiApi
import com.blurr.voice.v2.llm.GeminiMessage
import com.blurr.voice.v2.message_manager.MemoryManager
import com.blurr.voice.v2.perception.Perception
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.overlay.OverlayDispatcher
import com.blurr.voice.overlay.OverlayManager
import com.blurr.voice.overlay.OverlayPriority
import com.blurr.voice.overlay.OverlayPosition
import com.blurr.voice.SettingsActivity
import kotlinx.coroutines.delay

/**
 * The main conductor of the agent.
 * This class owns all the necessary components and runs the primary SENSE -> THINK -> ACT loop.
 *
 * @param settings The agent's configuration.
 * @param memoryManager The agent's short-term memory and prompt builder.
 * @param perception The agent's "eyes," responsible for analyzing the screen.
 * @param llmApi The client for communicating with the Gemini LLM.
 * @param actionExecutor The agent's "hands," responsible for executing actions on the device.
 * @param fileSystem The agent's long-term file storage.
 * @param context The Android application context.
 */
@RequiresApi(Build.VERSION_CODES.R)
class Agent(
    private val settings: AgentSettings,
    private val memoryManager: MemoryManager,
    private val perception: Perception,
    private val llmApi: GeminiApi,
    private val actionExecutor: ActionExecutor,
    private val fileSystem: FileSystem,
    private val context: Context
) {
    // The agent's internal state, which is updated at each step.
    val state: AgentState = AgentState()
    private val TAG = "AgentV2"
    
    // Speech coordinator for voice notifications
    private val speechCoordinator = SpeechCoordinator.getInstance(context)

    // A complete, long-term record of the entire session.
    // We use <Unit> because we haven't defined a custom structured output for the 'done' action yet.
    val history: AgentHistoryList<Unit> = AgentHistoryList()

    /**
     * The main entry point to start the agent's execution loop.
     *
     * @param initialTask The high-level task requested by the user.
     * @param maxSteps The maximum number of steps the agent can take before stopping.
     */
    suspend fun run(initialTask: String, maxSteps: Int = 150) {
        memoryManager.addNewTask(initialTask)
        state.stopped = false
        Log.d(TAG, "--- Agent starting task: '$initialTask' ---")

        val overlay = OverlayManager.getInstance(context)

        // The pill's X stops the whole executor rather than just this loop, so
        // it goes through the service - the same path the notification's stop
        // action uses, which is what onDestroy then cleans up.
        val stopAgent = {
            Log.d(TAG, "Stop requested from the status pill.")
            AgentService.stop(context)
        }

        overlay.showAgentStatus(initialTask, "Starting", 0, maxSteps, stopAgent)

        // Get the assistant popup out of the way before the first read.
        //
        // This is not cosmetic and the loop cannot work around it: perception reads the
        // topmost *focused* window, and a full-screen session dock window is exactly
        // that. Left up, the agent reads its own transcript, concludes the user's app is a
        // chat log and taps coordinates that belong to it -- with no way to tell, because
        // every read afterwards is consistent. beginScreenWork collapses the popup to a
        // non-focusable bubble and starts the settling clock; awaitScreenReady then waits
        // out the relayout and the focus handover before anything is read.
        //
        // Called per step rather than once before the loop, because the user can expand
        // the bubble mid-task by tapping it. Every step re-asserts that the agent is
        // about to look at a screen, and both calls are no-ops once the popup is already
        // a bubble and the grace period has passed.
        SessionBridge.beginScreenWork()
        SessionBridge.awaitScreenReady()

        while (!state.stopped && state.nSteps <= maxSteps) {
            Log.d(TAG,"\n--- Step ${state.nSteps}/$maxSteps ---")
            overlay.showAgentStatus(initialTask, "Looking at the screen", state.nSteps, maxSteps, stopAgent)
            SessionBridge.beginScreenWork()
            SessionBridge.awaitScreenReady()

            // 1. SENSE: Observe the current state of the screen.
            Log.d(TAG,"👀 Sensing screen state...")
            val screenState = perception.analyze()

            // 2. THINK (Prepare Prompt): Update memory with the results of the LAST step
            // and create the new prompt using the CURRENT screen state.
            Log.d(TAG,"🧠 Preparing prompt...")
            memoryManager.createStateMessage(
                modelOutput = state.lastModelOutput,
                result = state.lastResult,
                stepInfo = AgentStepInfo(state.nSteps, maxSteps),
                screenState = screenState
            )

            // 3. THINK (Get Decision): Send the prepared messages to the LLM.
            Log.d(TAG,"🤔 Asking LLM for next action...")
            val messages = memoryManager.getMessages()
            val agentOutput = llmApi.generateAgentOutput(messages)

            // --- Handle LLM Failure ---
            if (agentOutput == null) {
                Log.d(TAG,"❌ LLM failed to return a valid action. Retrying...")
                state.consecutiveFailures++
                // Add a corrective message for the next attempt.
                memoryManager.addContextMessage(GeminiMessage(text = "System Note: Your previous output was not valid JSON. Please ensure your response is correctly formatted."))
                if (state.consecutiveFailures >= settings.maxFailures) {
                    Log.d(TAG,"❌ Agent failed too many times consecutively. Stopping.")
                    overlay.hideAgentStatus()
                    speechCoordinator.speakToUser("Agent failed after multiple attempts. Stopping execution.")
                    break
                }
                delay(1000) // Wait a moment before retrying
                continue // Skip to the next loop iteration
            }
            state.consecutiveFailures = 0
            state.lastModelOutput = agentOutput
            Log.d(TAG, agentOutput.toString())
            Log.d(TAG, agentOutput.toString())
            Log.d(TAG,"🤖 LLM decided: ${agentOutput.nextGoal}")

            // The nextGoal is the model's own plain-language description of what
            // it is about to do, which is exactly what belongs in the pill.
            val stepGoal = agentOutput.nextGoal?.takeIf { it.isNotBlank() } ?: "Working"
            overlay.showAgentStatus(
                goal = initialTask,
                activity = stepGoal,
                step = state.nSteps,
                maxSteps = maxSteps,
                onStop = stopAgent
            )
            // The same line, into the assistant popup's own transcript.
            //
            // Two destinations rather than one because there are two status surfaces and
            // they are shown in different situations: the pill appears when there is no
            // popup (notification), and the popup's activity row when the user
            // is talking to it. AssistantSessionState.activity sets the resting text of
            // the collapsed bubble as well as appending, so one call covers the row and
            // the bubble it will be replaced by.
            AssistantSessionState.setThinking(true)
            AssistantSessionState.activity(stepGoal)

            // Show thoughts if enabled
            val sharedPrefs = context.getSharedPreferences("BlurrSettings", Context.MODE_PRIVATE)
            if (sharedPrefs.getBoolean(SettingsActivity.KEY_SHOW_THOUGHTS, false)) {
                val thoughtText = buildString {
                    agentOutput.thinking?.let { if (it.isNotEmpty()) append("Thinking: ${agentOutput.thinking}\n") }
                    agentOutput.memory?.let { if (it.isNotEmpty()) append("Memory: ${agentOutput.memory}\n") }
                    agentOutput.nextGoal?.let { if (it.isNotEmpty()) append("Next Goal: ${agentOutput.nextGoal}") }
                }.trim()

                if (thoughtText.isNotEmpty()) {
                    OverlayDispatcher.show(
                        text = thoughtText,
                        priority = OverlayPriority.TASKS,
                        duration = 8000L, // Show for 8 seconds
                        position = OverlayPosition.TOP
                    )
                }
            }

            // 4. ACT: Execute the LLM's planned actions.
            Log.d(TAG,"💪 Executing actions...")
            val actionResults = mutableListOf<ActionResult>()
            for (action in agentOutput.action) {
                val result = actionExecutor.execute(action, screenState, context, fileSystem)
                actionResults.add(result)
                Log.d(TAG,"  - Action '${action::class.simpleName}' executed. Result: ${result.longTermMemory ?: result.error ?: "OK"}")

                // If an action fails, stop executing further actions in this step.
                if (result.error != null) {
                    Log.d(TAG,"  - 🛑 Action failed. Stopping current step's execution.")
                    break
                }
            }
            state.lastResult = actionResults

            // 5. RECORD: Save the complete step to the long-term history.
            history.addItem(
                AgentHistory(
                    modelOutput = agentOutput,
                    result = actionResults,
                    state = screenState,
                    metadata = null // You can add timing/token metadata here later
                )
            )

            // --- Check for Task Completion ---
            if (actionResults.any { it.isDone == true }) {
                Log.d(TAG,"✅ Agent finished the task.")
                overlay.hideAgentStatus()
                speechCoordinator.speakToUser("Task completed successfully.")
                state.stopped = true
            }

            state.nSteps++
            delay(1000) // A small, polite delay between steps.
        }

        // --- Loop Finished ---
        overlay.hideAgentStatus()
        // The task is over, so the popup may have its answer back. settleAfterTurn
        // decides between the full sheet and a bubble from the turn's own facts -- an app
        // launch, or the keep-as-a-bubble mode the user armed -- and it is the only
        // place that decision lives, so both callers cannot drift apart.
        //
        // Ordered before the state is marked idle: settleAfterTurn reads the app-launch
        // flag, and clearing the activity first would leave a bubble claiming to be
        // working during the gap.
        SessionBridge.settleAfterTurn()
        AssistantSessionState.clearActivity()
        AssistantSessionState.setThinking(false)
        if (state.nSteps > maxSteps) {
            Log.d(TAG,"--- 🏁 Agent reached max steps. Stopping. ---")
            speechCoordinator.speakToUser("Agent reached maximum steps limit. Stopping execution.")
        } else {
            Log.d(TAG,"--- 🏁 Agent run finished. ---")
        }
    }
}