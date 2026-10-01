package com.blurr.voice.v2.actions

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.blurr.voice.KeyguardDismissActivity
import com.blurr.voice.ScreenInteractionService
import com.blurr.voice.api.Finger
import com.blurr.voice.assistant.SessionBridge
import com.blurr.voice.triggers.DeltaNotificationListenerService
import com.blurr.voice.reminders.ReminderScheduler
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.utilities.UserInputManager
import com.blurr.voice.v2.ActionResult
import com.blurr.voice.v2.InstalledAppsCatalog
import com.blurr.voice.v2.fs.FileSystem
import com.blurr.voice.v2.perception.ScreenAnalysis
import com.blurr.voice.intents.IntentRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.measureTimeMillis
import kotlin.text.removePrefix

/**
 * Executes a pre-validated, type-safe Action command.
 * The 'when' block is exhaustive, ensuring every action is handled.
 */
class ActionExecutor(private val finger: Finger) {

    private val TAG = "ActionExecutor"

    // Add this function inside ActionExecutor.kt, outside the class, or as a private fun.
    private fun getExtraInfo(node: AccessibilityNodeInfo): String {
        val infoParts = mutableListOf<String>()
        if (node.isCheckable) infoParts.add("checkable")
        if (node.isChecked) infoParts.add("checked")
        if (node.isClickable) infoParts.add("clickable")
        if (node.isEnabled) infoParts.add("enabled")
        if (node.isFocusable) infoParts.add("focusable")
        if (node.isFocused) infoParts.add("focused")
        if (node.isScrollable) infoParts.add("scrollable")
        if (node.isLongClickable) infoParts.add("long clickable")
        if (node.isSelected) infoParts.add("selected")

        return if (infoParts.isNotEmpty()) {
            "This element is ${infoParts.joinToString(", ")}."
        } else {
            ""
        }
    }

    private fun findPackageNameFromAppName(appName: String, context: Context): String? =
        resolvePackage(appName, context)

    /**
     * Resolve a user-typed app name to a launchable package, deterministically.
     *
     * Lives in the companion so the deterministic "open X" fast path in the
     * agent can open an app without waiting for the model's first response.
     * The matching rules deliberately mirror what the model is told to send
     * (exact label first), and an ambiguous name resolves to nothing so the
     * caller falls back to the model rather than opening the wrong app.
     */
    companion object {
        fun resolvePackage(appName: String, context: Context): String? {
            val pm = context.packageManager

            val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launchIntent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launchIntent, 0)
            }

            // Build (label -> package) pairs. Only launchable apps are considered,
            // since those are the only ones open_app can actually start.
            val candidates = packages.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = try {
                    info.loadLabel(pm)?.toString()?.trim().orEmpty()
                } catch (e: Exception) {
                    ""
                }
                label to pkg
            }

            val target = appName.trim()
            if (target.isEmpty()) return null

            // 1. Exact label match (case-insensitive). This is what the model is
            //    told to send, and it disambiguates things like ChatGPT vs Claude.
            candidates.firstOrNull { it.first.equals(target, ignoreCase = true) }?.let {
                return it.second
            }

            // 2. Exact package name match.
            candidates.firstOrNull { it.second.equals(target, ignoreCase = true) }?.let {
                return it.second
            }

            // 3. Partial label match, but only accept an UNAMBIGUOUS one. The old
            //    code returned the first hit in arbitrary package order, which is
            //    how a request for one app could open a different one.
            val partials = candidates.filter { it.first.contains(target, ignoreCase = true) }
            if (partials.size == 1) return partials.first().second

            // 4. Partial package-name match.
            val pkgPartials = candidates.filter { it.second.contains(target, ignoreCase = true) }
            if (pkgPartials.size == 1) return pkgPartials.first().second

            // 5. Nothing conclusive. Log the realistic options so the model can be
            //    told to retry with a real name instead of the request failing
            //    silently and the agent picking some other app.
            if (partials.size > 1 || pkgPartials.size > 1) {
                val options = (partials + pkgPartials).map { it.first }.distinct().sorted()
                Log.w("ActionExecutor", "Ambiguous app name '$appName'. Candidates: $options")
            } else {
                Log.w("ActionExecutor", "No installed app matches '$appName'.")
            }

            return null
        }
    }

    private fun getVisibleText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        // Prefer text, fall back to content description
        return (if (text.isNotBlank()) text else contentDesc).replace("\n", " ")
    }
    private fun getCenterFromNode(node: AccessibilityNodeInfo): Pair<Int, Int>? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            return null // Node is not on screen or has no bounds
        }
        return Pair(bounds.centerX(), bounds.centerY())
    }
    /**
     * Executes a single action and returns the result.
     * @return An ActionResult detailing the outcome of the action.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun execute(
        action: Action,
        screenAnalysis: ScreenAnalysis,
        context: Context,
        fileSystem: FileSystem
    ): ActionResult {
        // This 'when' block now returns an ActionResult for every case.
        return when (action) {
            is Action.TapElement -> {
                val elementNode = screenAnalysis.elementMap[action.elementId]
                if (elementNode != null) {
                    val text = getVisibleText(elementNode)
                    val service = ScreenInteractionService.instance

                    var signatureBefore = ""
                    var signatureAfter = ""
                    var screenChanged = false

                    // --- START: Time Measurement ---
                    val diffTime = measureTimeMillis {
                        // 1. GET SIGNATURE (The entire XML tree)
                        signatureBefore = service?.getWindowHierarchySignature() ?: ""

                        // 2. ATTEMPT 1: Polite Accessibility Action
                        elementNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                        // 3. WAIT & VERIFY
                        // We wait for the app to process the click and update the UI
                        delay(100)

                        signatureAfter = service?.getWindowHierarchySignature() ?: ""

                        // If the XML strings are different, the screen changed.
                        screenChanged = signatureBefore != signatureAfter
                    }

                    // --- LOG THE RESULT ---
                    Log.d("ActionExecutor", "Signature diff + 100ms delay took ${diffTime}ms. Screen changed: $screenChanged")

                    if (screenChanged) {
                        ActionResult(longTermMemory = "Clicked element '$text'. Screen updated successfully.")
                    } else {
                        // 4. ESCALATE: BRUTE FORCE TAP
                        // The XML is identical, so the app ignored the click.
                        val center = getCenterFromNode(elementNode)
                        if (center != null) {
                            finger.tap(center.first, center.second)
                            delay(500) // Wait for the physical tap to register
                            ActionResult(longTermMemory = "Accessibility click failed (screen didn't change). Escalated to physical tap at ${center.first},${center.second} on '$text'.")
                        } else {
                            ActionResult(error = "Click sent to '$text' but screen did not change, and cannot find coordinates for physical retry.")
                        }
                    }
                } else {
                    ActionResult(error = "Element with ID ${action.elementId} not found.")
                }
            }
//            is Action.TapElement -> {
//                val elementNode = screenAnalysis.elementMap[action.elementId]
//                if (elementNode != null) {
//                    val text = getVisibleText(elementNode)
//                    val service = ScreenInteractionService.instance
//
//                    // 1. GET SIGNATURE (The entire XML tree)
//                    val signatureBefore = service?.getWindowHierarchySignature() ?: ""
//
//                    // 2. ATTEMPT 1: Polite Accessibility Action
//                    elementNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//
//                    // 3. WAIT & VERIFY
//                    // We wait for the app to process the click and update the UI
//                    delay(600)
//
//                    val signatureAfter = service?.getWindowHierarchySignature() ?: ""
//
//                    // If the XML strings are different, the screen changed.
//                    val screenChanged = signatureBefore != signatureAfter
//
//                    if (screenChanged) {
//                        ActionResult(longTermMemory = "Clicked element '$text'. Screen updated successfully.")
//                    } else {
//                        // 4. ESCALATE: BRUTE FORCE TAP
//                        // The XML is identical, so the app ignored the click.
//                        val center = getCenterFromNode(elementNode)
//                        if (center != null) {
//                            finger.tap(center.first, center.second)
//                            delay(500) // Wait for the physical tap to register
//                            ActionResult(longTermMemory = "Accessibility click failed (screen didn't change). Escalated to physical tap at ${center.first},${center.second} on '$text'.")
//                        } else {
//                            ActionResult(error = "Click sent to '$text' but screen did not change, and cannot find coordinates for physical retry.")
//                        }
//                    }
//                } else {
//                    ActionResult(error = "Element with ID ${action.elementId} not found.")
//                }
//            }
//            is Action.TapElement -> {
//                // MODIFIED: 'elementNode' is now AccessibilityNodeInfo
//                val elementNode = screenAnalysis.elementMap[action.elementId]
//                if (elementNode != null) {
//                    // MODIFIED: Use new helpers
//                    val text = getVisibleText(elementNode)
//                    val resourceId = elementNode.viewIdResourceName ?: ""
//                    val extraInfo = getExtraInfo(elementNode)
//                    val className = (elementNode.className ?: "").removePrefix("android.")
//
//                    val center = getCenterFromNode(elementNode)
//                    if (center != null) {
//                        elementNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
////                        finger.tap(center.first, center.second)
//                        val si = ScreenInteractionService.instance
//                        si?.showDebugTap(center.first.toFloat(), center.second.toFloat())
//                        ActionResult(longTermMemory = "Tapped element text:$text <$resourceId> <$extraInfo> <$className>")
//                    } else {
//                        ActionResult(error = "Element with ID ${action.elementId} has no visible bounds.")
//                    }
//                } else {
//                    ActionResult(error = "Element with ID ${action.elementId} not found in the current screen state.")
//                }
//            }
            is Action.Speak -> {
                // The message is taken directly from the type-safe action class.
                val message = action.message
                runBlocking {
                    SpeechCoordinator.getInstance(context).speakToUser(message)
                }
                ActionResult(longTermMemory = "Spoke the message: \"${message.take(50)}...\"")
            }
            is Action.Ask -> {
                val question = action.question
                val userResponse = withContext(Dispatchers.IO) { // User input is blocking
                    val userInputManager = UserInputManager(context)
                    userInputManager.askQuestion(question) // This internally speaks and listens
                }

                val memory = "Asked user: '$question'. User responded: '$userResponse'."
                ActionResult(
                    longTermMemory = memory,
                    extractedContent = userResponse, // The user's answer is the result
                    includeExtractedContentOnlyOnce = true
                )
            }
            is Action.LongPressElement -> {
                // MODIFIED: 'elementNode' is now AccessibilityNodeInfo
                val elementNode = screenAnalysis.elementMap[action.elementId]
                if (elementNode != null) {
                    // MODIFIED: Use new helpers
                    val text = getVisibleText(elementNode)
                    val resourceId = elementNode.viewIdResourceName ?: ""
                    val extraInfo = getExtraInfo(elementNode)
                    val className = (elementNode.className ?: "").removePrefix("android.")

                    val center = getCenterFromNode(elementNode)
                    if (center != null) {
//                        finger.longPress(center.first, center.second)
                        elementNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                        ActionResult(longTermMemory = "Long-pressed element text:$text <$resourceId> <$extraInfo> <$className>")
                    } else {
                        ActionResult(error = "Element with ID ${action.elementId} has no visible bounds.")
                    }
                } else {
                    ActionResult(error = "Element with ID ${action.elementId} not found in the current screen state.")
                }
            }
            is Action.OpenApp -> {
                val packageName = findPackageNameFromAppName(action.appName, context)
                if (packageName != null) {
                    val success = finger.openApp(packageName)
                    if (success) {
                        // Marks the turn as one the user came for the app rather than the
                        // answer, which is what makes the assistant popup stay a bubble at
                        // the end of it. Opening an app is the one move where the thing on
                        // the screen is the point: bringing the full sheet back over it
                        // would cover what they just asked to see. A screen read is
                        // deliberately not marked, because there the answer is the point.
                        SessionBridge.noteAppLaunched()
                        ActionResult(longTermMemory = "Opened app '${action.appName}'.")
                    } else {
                        ActionResult(error = "Failed to launch '${action.appName}' (package: $packageName) even though it was resolved. It may have no launcher activity. Try a similar app or a different approach.")
                    }
                } else {
                    // Include real candidates so the model can immediately retry
                    // with a valid name instead of wandering off to another app.
                    val suggestions = InstalledAppsCatalog.getLaunchableApps(context)
                        .filter { it.label.contains(action.appName, ignoreCase = true) ||
                                  it.packageName.contains(action.appName, ignoreCase = true) }
                        .take(8)
                        .joinToString(", ") { it.label }
                        .ifBlank { "none" }

                    ActionResult(
                        error = "App '${action.appName}' is not installed. Closest matches: [$suggestions]. " +
                            "Retry open_app with one of those exact names, or check the installed_apps list."
                    )
                }
            }
            Action.Back -> {
                finger.back()
                ActionResult(longTermMemory = "Pressed the back button.")
            }
            Action.Home -> {
                finger.home()
                ActionResult(longTermMemory = "Pressed the home button.")
            }
            Action.SwitchApp -> {
                finger.switchApp()
                ActionResult(longTermMemory = "Opened the app switcher.")
            }
            Action.Wait -> {
                // Use delay in a coroutine instead of Thread.sleep
                delay(5_000)
                ActionResult(longTermMemory = "Waited for 5 seconds.")
            }
            is Action.ScrollDown -> {
                finger.scrollDown(action.amount)
                ActionResult(longTermMemory = "Scrolled down by ${action.amount} pixels.")
            }
            is Action.ScrollUp -> {
                finger.scrollUp(action.amount)
                ActionResult(longTermMemory = "Scrolled up by ${action.amount} pixels.")
            }
            is Action.SearchGoogle -> {
                // This is a multi-step conceptual action. The executor should handle the concrete steps.
                finger.openApp("com.android.chrome") // More reliable to use package name
                // The next steps (typing, pressing enter) should be decided by the agent in the next turn.
                ActionResult(longTermMemory = "Opened Chrome to search Google.")
            }
            is Action.Done -> {
                // This action doesn't *do* anything. It's a signal to the main loop.
                // We just construct the final ActionResult.
                ActionResult(
                    isDone = true,
                    success = action.success,
                    longTermMemory = "Task finished: ${action.text}",
                    attachments = action.filesToDisplay
                )
            }
//            is Action.ExtractStructuredData -> {
//                // This is a placeholder for a complex action.
//                // A full implementation would require another LLM call with the screen content.
//                // For now, we return an error indicating it's not yet implemented.
//                ActionResult(error = "Action 'ExtractStructuredData' is not yet implemented.")
//            }
            is Action.InputText -> {
                finger.type(action.text)
                ActionResult(longTermMemory = "Input text ${action.text}.")
            }
//            is Action.ScrollToText -> {
//                // As requested, skipping implementation.
//                ActionResult(error = "Action 'ScrollToText' is not implemented.")
//            }
            is Action.AppendFile -> {
                val success = fileSystem.appendFile(action.fileName, action.content)
                if (success) {
                    ActionResult(longTermMemory = "Appended content to '${action.fileName}'.")
                } else {
                    ActionResult(error = "Failed to append to file '${action.fileName}'.")
                }
            }
            is Action.ReadFile -> {
                val content = fileSystem.readFile(action.fileName)
                if (content.startsWith("Error:")) {
                    ActionResult(error = content)
                } else {
                    ActionResult(
                        longTermMemory = "Read content from '${action.fileName}'.",
                        extractedContent = content,
                        includeExtractedContentOnlyOnce = true
                    )
                }
            }
            is Action.WriteFile -> {
                val success = fileSystem.writeFile(action.fileName, action.content)
                if (success) {
                    Log.d("ActionExecutor", "Wrote content to '${action.fileName} ${action.content}'.")
                    ActionResult(longTermMemory = "Wrote content to '${action.fileName}'.")
                } else {
                    ActionResult(error = "Failed to write to file '${action.fileName}'.")
                }
            }

//            is Action.ScrollToText -> TODO()
            is Action.TapElementInputTextPressEnter -> {
                val elementNode = screenAnalysis.elementMap[action.index]
                if (elementNode != null) {

                    val text = getVisibleText(elementNode)
                    val resourceId = elementNode.viewIdResourceName ?: ""
                    val extraInfo = getExtraInfo(elementNode)
                    val className = (elementNode.className ?: "").removePrefix("android.")

                    val center = getCenterFromNode(elementNode)
                    if (center != null) {
                        elementNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(200)
                        finger.type(action.text)
                        delay(100)
                        finger.enter()
                        ActionResult(longTermMemory = "Tapped, typed '${action.text}', and pressed Enter on element: text:$text <$resourceId> <$extraInfo> <$className>.")
                    } else {
                        ActionResult(error = "Element with ID ${action.index} has no visible bounds.")
                    }
                } else {
                    ActionResult(error = "Element with ID ${action.index} for input not found.")
                }
            }
            is Action.LaunchIntent -> {
                val name = action.intentName
                val params = action.parameters
                val appIntent = IntentRegistry.findByName(context, name)
                if (appIntent == null) {
                    return ActionResult(error = "Intent '$name' not found. Check intents catalog for valid names.")
                }

                // Intents that act rather than display (scheduling a reminder,
                // for example) handle themselves here and return a result
                // string, so nothing gets launched for them.
                val performed = try {
                    appIntent.perform(context, params)
                } catch (t: Throwable) {
                    Log.e(TAG, "Intent '$name' failed while performing", t)
                    return ActionResult(error = "Intent '$name' failed: ${t.message}")
                }
                if (performed != null) {
                    return ActionResult(longTermMemory = performed)
                }

                val intent = appIntent.buildIntent(context, params)
                return if (intent == null) {
                    ActionResult(error = "Intent '$name' missing or invalid parameters: ${params}")
                } else {
                    try {
                        val launchSuccess = finger.launchIntent(intent)
                        if (launchSuccess) {
                            ActionResult(longTermMemory = "Launched intent '$name' with params ${params}")
                        } else {
                            ActionResult(error = "Failed to launch intent '$name' with params ${params}")
                        }
                    } catch (t: Throwable) {
                        ActionResult(error = "Failed to launch intent '$name': ${t.message}")
                    }
                }
            }
            // --- Tool-style actions: read and report, never touch the screen ---
            is Action.Notifications -> {
                val snapshot = DeltaNotificationListenerService.current
                if (snapshot.isEmpty()) {
                    ActionResult(
                        error = "No notifications are currently active, or notification access " +
                            "is not granted. If the listener is not enabled, the notifications " +
                            "tool cannot read other apps' alerts."
                    )
                } else {
                    val formatted = snapshot.take(15).joinToString("\n") { n ->
                        "#${n.packageName}: ${n.title}. ${n.text}"
                    }
                    ActionResult(
                        longTermMemory = "Read ${snapshot.size} active notification(s).",
                        extractedContent = formatted,
                        includeExtractedContentOnlyOnce = true
                    )
                }
            }
            is Action.ListFiles -> {
                val listing = fileSystem.describe()
                ActionResult(
                    longTermMemory = "Listed the workspace files.",
                    extractedContent = listing,
                    includeExtractedContentOnlyOnce = true
                )
            }
            is Action.DeviceState -> {
                ActionResult(
                    longTermMemory = "Reported the device state.",
                    extractedContent = describeDeviceState(context),
                    includeExtractedContentOnlyOnce = true
                )
            }
            is Action.Reminders -> {
                val reminders = ReminderScheduler.all(context)
                    .sortedBy { it.triggerAtMillis }
                if (reminders.isEmpty()) {
                    ActionResult(
                        error = "No reminders are scheduled. Only reminders set through " +
                            "this assistant are tracked; countdown timers run in the clock " +
                            "app and cannot be read back."
                    )
                } else {
                    val formatter = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
                    val formatted = reminders.take(10).joinToString("\n") { r ->
                        val due = formatter.format(Date(r.triggerAtMillis))
                        "#${r.id} ${r.label.ifBlank { "Reminder" }} - $due" +
                            if (r.repeats) " (repeats)" else ""
                    }
                    ActionResult(
                        longTermMemory = "Read ${reminders.size} scheduled reminder(s).",
                        extractedContent = formatted,
                        includeExtractedContentOnlyOnce = true
                    )
                }
            }
            is Action.RequestUnlock -> {
                val keyguardManager =
                    context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                if (keyguardManager == null || !keyguardManager.isKeyguardLocked) {
                    ActionResult(longTermMemory = "The phone is already unlocked.")
                } else {
                    // Ask out loud, then surface the real lock screen: the speech says
                    // why, and requestDismissKeyguard shows the actual PIN / password /
                    // fingerprint prompt on top. No floating banner -- the assistant's
                    // status lives in its own popup, not in a second window.
                    Log.d(TAG, "🔓 Keyguard is locked - asking the user to unlock.")
                    runBlocking {
                        SpeechCoordinator.getInstance(context)
                            .speakToUser("I need your screen unlocked to do that. Please unlock your phone.")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            val intent = Intent(context, KeyguardDismissActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                )
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not start the keyguard dismissal activity", e)
                        }
                    }

                    // Block this step until the user unlocks (or gives up / a
                    // timeout passes). Requesting the keyguard dismiss is exactly
                    // this app's right: holding the overlay permission exempts it
                    // from background-activity-start restrictions.
                    val deadline = SystemClock.elapsedRealtime() + 45_000L
                    while (keyguardManager.isKeyguardLocked &&
                        SystemClock.elapsedRealtime() < deadline
                    ) {
                        delay(500)
                    }

                    if (keyguardManager.isKeyguardLocked) {
                        ActionResult(
                            error = "The phone is still locked - the user did not unlock " +
                                "within 45 seconds."
                        )
                    } else {
                        ActionResult(
                            longTermMemory = "User unlocked the phone; continuing the task."
                        )
                    }
                }
            }
        }
    }

    /**
     * Builds a short, human-readable summary of the device: current time,
     * battery, screen, keyguard (lock) state and network. Everything here is
     * read through public system services -- no screen, no accessibility -- so
     * this stays a real tool rather than another excuse to look at the UI, and
     * it works while the phone is locked or the screen is off.
     */
    private fun describeDeviceState(context: Context): String {
        val batteryIntent = context.registerReceiver(
            /* receiver = */ null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = powerManager.isInteractive

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val locked = keyguardManager?.isKeyguardLocked ?: false

        val networkDescription = try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            when {
                network == null -> "no connection"
                capabilities != null &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities != null &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular data"
                else -> "connected"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read network state", e)
            "unknown"
        }

        return buildString {
            appendLine("Time: ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}")
            if (percent >= 0) appendLine("Battery: $percent% ${if (charging) "(charging)" else "(not charging)"}")
            appendLine("Screen: ${if (screenOn) "on" else "off"}")
            appendLine("Locked: ${if (locked) "yes (lock screen showing)" else "no"}")
            appendLine("Network: $networkDescription")
        }.trim()
    }
}
