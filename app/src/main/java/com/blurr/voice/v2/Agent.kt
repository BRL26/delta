package com.blurr.voice.v2

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.blurr.voice.assistant.AssistantSessionState
import com.blurr.voice.assistant.SessionBridge
import com.blurr.voice.api.Finger
import com.blurr.voice.v2.actions.Action
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

    // Set when the model has already been given its one "are you sure?"
    // second look before being allowed to give up (done with success=false).
    private var recheckAttempted = false

    // Repeat-guard bookkeeping. The model can fall into a spinning loop on
    // apps with sparse accessibility trees (cameras, games) where every read
    // shows the same near-empty screen; a repeated next_goal is the reliable
    // signal, and it is nudged (once) instead of the loop running to maxSteps.
    private var lastNextGoal: String? = null
    private var sameGoalStreak = 0
    private var repeatNudgeSent = false

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
        recheckAttempted = false
        lastNextGoal = null
        sameGoalStreak = 0
        repeatNudgeSent = false
        Log.d(TAG, "--- Agent starting task: '$initialTask' ---")

        val overlay = OverlayManager.getInstance(context)

        // The pill's X stops the whole executor rather than just this loop, so
        // it goes through the service - the same path the notification's stop
        // action uses, which is what onDestroy then cleans up.
        val stopAgent = {
            Log.d(TAG, "Stop requested from the status pill.")
            AgentService.stop(context)
        }

        // The agent's floating pill is a fallback: when the assistant popup is on
        // screen (bubble or sheet), its own Material UI carries the same status, and
        // a second overlay on top would just cover the screen. Show the pill only
        // when no session window is visible; [hideAgentStatus] still runs
        // unconditionally so a stale pill from an earlier arrangement is removed.
        if (!SessionBridge.sessionVisible.value) {
            overlay.showAgentStatus(initialTask, "Starting", 0, maxSteps, stopAgent)
        }

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

        // --- Deterministic fast path for plain app-opening requests ---
        // "open camera", "launch whatsapp", and similar need no model at all:
        // resolve the app name the same way open_app does and launch it directly.
        // This is the fix for the request that used to sit on a spinner while the
        // model's first response ground through the whole catalog: the launch
        // succeeds in milliseconds or the request falls through to the normal
        // loop below unchanged. Multi-step asks never match isSimpleOpenRequest,
        // so the model still gets full control over them. The within-loop
        // completion further down is the model-driven fallback for apps whose
        // launcher name differs from what the user said.
        val fastOpenHint = simpleOpenAppName(initialTask)
        val fastOpenPkg = if (isSimpleOpenRequest(initialTask) && fastOpenHint != null) {
            ActionExecutor.resolvePackage(fastOpenHint, context)
        } else {
            null
        }
        if (fastOpenPkg != null) {
            if (Finger(context).openApp(fastOpenPkg)) {
                Log.d(TAG, "✅ Direct-open fast path: '$fastOpenHint' ($fastOpenPkg).")
                SessionBridge.noteAppLaunched()
                overlay.hideAgentStatus()
                val completion = "Opened $fastOpenHint. Is there anything else you need?"
                speechCoordinator.speakToUser(completion)
                AssistantSessionState.reply(completion)
                // Same popup cleanup the loop epilogue performs, so the bubble over
                // the freshly opened app stays a bubble instead of a spinner.
                SessionBridge.settleAfterTurn()
                AssistantSessionState.clearActivity()
                AssistantSessionState.setThinking(false)
                state.stopped = true
            } else {
                Log.d(TAG, "Direct open of '$fastOpenPkg' failed; falling through to the agent loop.")
            }
        }
        if (state.stopped) return

        while (!state.stopped && state.nSteps <= maxSteps) {
            Log.d(TAG,"\n--- Step ${state.nSteps}/$maxSteps ---")
            if (!SessionBridge.sessionVisible.value) {
                overlay.showAgentStatus(initialTask, "Working with tools", state.nSteps, maxSteps, stopAgent)
            }
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

            // --- Repeat guard ---
            // A model that loops on a sparse screen (camera viewfinders, game
            // homescreens) re-states the same next_goal every step. Count
            // consecutive repeats and nudge it once to stop spinning: whichever
            // tool already made progress, or finishing with done() when the
            // request is effectively complete. Only one nudge per run; a legit
            // long task repeating a goal a couple of times is fine.
            val goalText = agentOutput.nextGoal?.trim().orEmpty()
            if (goalText.isNotBlank() && goalText.equals(lastNextGoal, ignoreCase = true)) {
                sameGoalStreak++
            } else {
                sameGoalStreak = 0
            }
            lastNextGoal = goalText
            if (sameGoalStreak >= 3 && !repeatNudgeSent) {
                repeatNudgeSent = true
                Log.d(TAG, "🔄 Model repeated its goal $sameGoalStreak times; nudging to progress or finish.")
                memoryManager.addContextMessage(
                    GeminiMessage(
                        text = "System Note: You have proposed the same next_goal several steps in a row " +
                            "without visible progress, which usually means you are re-reading the same state " +
                            "instead of acting. Re-run the <tool_selection_protocol>: if a tool has already " +
                            "completed the user's request, call done() now with the actual result in text; " +
                            "otherwise pick an action that CHANGES something on the screen. Do not repeat a " +
                            "read of the same state."
                    )
                )
            }

            // The nextGoal is the model's own plain-language description of what
            // it is about to do, which is exactly what belongs in the pill.
            val stepGoal = agentOutput.nextGoal?.takeIf { it.isNotBlank() } ?: "Working"
            if (!SessionBridge.sessionVisible.value) {
                overlay.showAgentStatus(
                    goal = initialTask,
                    activity = stepGoal,
                    step = state.nSteps,
                    maxSteps = maxSteps,
                    onStop = stopAgent
                )
            }
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
            // Thoughts are only shown as a floating toast when the assistant popup is
            // gone; while the session window is on screen the activity line already
            // carries this, and a toast on top of the popup is just a cover-up.
            if (sharedPrefs.getBoolean(SettingsActivity.KEY_SHOW_THOUGHTS, false) &&
                !SessionBridge.sessionVisible.value
            ) {
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

            // --- Deterministic completion for plain app-opening requests ---
            // For "open x" and nothing more, the request is complete the moment
            // the app is open. The model would normally call done() here, but on
            // apps with sparse accessibility trees (camera viewfinders, games,
            // maps) it can drift into "did it open?" loops that re-read the same
            // near-empty screen at fully charged LLM latency until maxSteps. Not
            // letting a one-line request burn the whole budget: complete on the
            // successful opener and let the user move on. Requests with follow-on
            // work ("open camera and take a picture") never match, so nothing
            // real is cut short.
            if (!state.stopped && isSimpleOpenRequest(initialTask)) {
                val opened = agentOutput.action.zip(actionResults)
                    .firstOrNull { (a, r) -> a is Action.OpenApp && r.error == null }
                    ?.first as? Action.OpenApp
                if (opened != null) {
                    Log.d(TAG, "✅ Plain app-open request; finishing after opening '${opened.appName}'.")
                    overlay.hideAgentStatus()
                    val completion = "Opened ${opened.appName}. Is there anything else you need?"
                    speechCoordinator.speakToUser(completion)
                    AssistantSessionState.reply(completion)
                    state.stopped = true
                    break
                }
            }

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
                val done = agentOutput.action.filterIsInstance<Action.Done>().firstOrNull()

                // A failed "done" gets exactly one forced second look before the
                // agent is allowed to give up. "I can't" is usually a belief, not
                // a fact: a tool the model dismissed may cover the request (battery,
                // notifications, files, an app the truncated list omitted, or an
                // intent). Deterministic, so a model that shies away never exits
                // without re-scanning the tool surface at least once.
                if (done?.success != true && !recheckAttempted) {
                    recheckAttempted = true
                    Log.d(TAG, "🔄 done(success=false) — forcing one careful tools re-check before giving up.")
                    memoryManager.addContextMessage(
                        GeminiMessage(
                            text = "System Note: You just tried to finish this task with success=false, " +
                                "but before giving up you MUST re-run the <tool_selection_protocol> from step 0 " +
                                "and carefully re-read the complete <available_actions> catalog, the " +
                                "<intents_catalog>, and <installed_apps>. A tool you dismissed may cover the " +
                                "request: device_state (battery, time, network, lock state), notifications " +
                                "(messages and alerts), reminders (scheduled reminders), list_files/read_file/ " +
                                "write_file (workspace files), open_app (installed apps - the listed apps may be " +
                                "truncated, so still try with the user's exact name), and launch_intent " +
                                "(SetTimer, SetAlarm, SetReminder, Dial, Share, OpenUrl, Email). If any tool can " +
                                "make progress, use it now instead of giving up. Only if you have genuinely " +
                                "re-checked and no tool fits or can make progress may you call done again with " +
                                "success=false."
                        )
                    )
                    state.nSteps++
                    delay(1000)
                    continue
                }

                Log.d(TAG,"✅ Agent finished the task.")
                overlay.hideAgentStatus()

                // The done action carries the model's final answer, and that
                // answer is what the user should hear -- not a canned "task
                // completed" line. A short follow-up keeps the turn feeling
                // like an assistant that stays available instead of ending flat.
                val doneText = done?.text?.trim().orEmpty()
                val completion = when {
                    doneText.isNotBlank() ->
                        "$doneText Is there anything else you need?"
                    done?.success == true ->
                        "Done. Is there anything else you need?"
                    else ->
                        "I couldn't finish that one. Is there anything else you need?"
                }
                speechCoordinator.speakToUser(completion)
                // The same words land in the popup's transcript, so the answer is
                // readable as well as audible -- part of carrying the status UI in
                // the assistant rather than in floating overlays.
                AssistantSessionState.reply(completion)
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

    /**
     * True when the user's request is only about opening a single app
     * ("open camera", "launch whatsapp", "take me to the settings app") with
     * nothing more to do after it is up.
     *
     * Used by the two deterministic completions above: such a request is complete
     * the instant the opener succeeds. Anything carrying follow-on work --
     * "open camera and take a picture", "open whatsapp and message dad" -- fails
     * the check (conjunctions, verbs, more than a few nouns) and stays on the
     * normal loop, so no real multi-step ask is cut short.
     */
    private fun isSimpleOpenRequest(request: String): Boolean {
        val name = simpleOpenAppName(request) ?: return false
        val words = name.split(" ")
        if (words.size > 3) return false
        val actionHints = setOf(
            "and", "then", "take", "send", "record", "capture", "snap", "show",
            "check", "find", "search", "play", "set", "list", "read", "write",
            "call", "message", "text", "with", "after", "before", "stop"
        )
        return words.none { it in actionHints }
    }

    /**
     * The app-name portion of a plain open request ("open the camera app" ->
     * "camera"), or null when the request does not start with an open verb.
     * Articles and the trailing "app"/"application" filler are dropped.
     */
    private fun simpleOpenAppName(request: String): String? {
        val lowered = request.trim().lowercase()
        val rest = Regex("^\\s*(?:open|launch|start|open up|take me to)\\s+(.+)$")
            .find(lowered)?.groupValues?.get(1)?.trim() ?: return null
        if (rest.isBlank()) return null
        val words = rest.split(Regex("\\s+")).filter {
            it.isNotBlank() && it != "the" && it != "app" && it != "application"
        }
        return if (words.isEmpty()) null else words.joinToString(" ")
    }
}