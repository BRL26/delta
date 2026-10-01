package com.blurr.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.blurr.voice.api.LlmProviderStore
import com.blurr.voice.overlay.OverlayManager
import com.blurr.voice.reminders.ReminderScheduler
import com.blurr.voice.v2.AgentService

/**
 * Debug-build-only harness for exercising things that are otherwise only
 * reachable by talking to the app.
 *
 * Lives in the debug source set, so it is never compiled into a release build.
 * Exists because the agent executor, the status pill and the reminder
 * scheduler have no UI to click: the only other way in is to speak to the
 * device, which is not something a build can verify.
 */
class DevHarnessActivity : Activity() {

    companion object {
        private const val TAG = "DevHarness"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = LlmProviderStore.getInstance(this).getActiveConfig()
        val header = TextView(this).apply {
            text = buildString {
                appendLine("Dev harness")
                appendLine("Provider: ${config?.provider?.displayName ?: "none set"}")
                appendLine("Model: ${config?.model ?: "-"}")
                appendLine("Reminders: ${ReminderScheduler.all(this@DevHarnessActivity).size}")
                appendLine()
                appendLine("Logcat tag: $TAG")
            }
            textSize = 14f
            setPadding(32, 48, 32, 24)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
        }

        fun button(label: String, onClick: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        button("Start task: open ChatGPT") {
            Log.d(TAG, "--> starting task")
            AgentService.start(this, "Open the ChatGPT app")
        }

        button("Start task: slower one (pill shows steps)") {
            Log.d(TAG, "--> starting slow task")
            AgentService.start(this, "Open the Settings app and scroll down the list")
        }

        button("Show pill only (no agent)") {
            val overlay = OverlayManager.getInstance(this)
            overlay.startObserving()
            overlay.showAgentStatus("Demo goal", "Looking at the screen", 7, 150)
        }

        button("Hide pill") {
            OverlayManager.getInstance(this).hideAgentStatus()
        }

        button("Schedule reminder in 8s") {
            val id = ReminderScheduler.schedule(
                context = this,
                label = "your test reminder",
                triggerAtMillis = System.currentTimeMillis() + 8_000L
            )
            Log.d(TAG, "--> scheduled reminder #$id")
            header.text = header.text.toString() + "\nScheduled #$id\n" +
                "Reminders: ${ReminderScheduler.all(this).size}"
        }

        button("Show pending reminders") {
            Log.d(
                TAG,
                "pending=${ReminderScheduler.all(this).map { "#${it.id} ${it.label} @${it.triggerAtMillis}" }}"
            )
            header.text = header.text.toString() + "\n" +
                ReminderScheduler.all(this).joinToString("\n") { "#${it.id} ${it.label}" }
        }

        button("Fire reminder broadcast now") {
            val id = ReminderScheduler.all(this).firstOrNull()?.id ?: run {
                Log.w(TAG, "no reminders to fire")
                return@button
            }
            sendBroadcast(
                Intent(this, com.blurr.voice.reminders.ReminderReceiver::class.java).apply {
                    action = com.blurr.voice.reminders.ReminderReceiver.ACTION_FIRE
                    putExtra(com.blurr.voice.reminders.ReminderReceiver.EXTRA_ID, id)
                }
            )
            Log.d(TAG, "--> fired reminder #$id")
        }

        setContentView(column)

        // Lets a build verify the overlays without anyone touching the screen,
        // which matters when the test device is locked:
        //   am start -n .../DevHarnessActivity --ez autopill true
        if (intent?.getBooleanExtra("autopill", false) == true) {
            val overlay = OverlayManager.getInstance(this)
            overlay.startObserving()
            overlay.showAgentStatus(
                "Search for the best pizza places near me and tell me the top result",
                "Looking at the screen and thinking about what to do next",
                7,
                150
            ) {
                Log.d(TAG, "STOP BUTTON PRESSED")
            }
            Log.d(TAG, "autopill: requested")
        }

        // The "autoinput" harness exercised the service's own overlay text composer.
        // That composer is gone: text input now happens in the assistant popup, and
        // there is nothing left in the app that inflates a TextInputLayout from a
        // Service context, so there is nothing here for the harness to exercise.

        if (intent?.getBooleanExtra("dumptree", false) == true) {
            // Measures the pill off-screen and logs its view tree. The pill is
            // a TYPE_APPLICATION_OVERLAY, so it does not show up in
            // `uiautomator dump` - this is the only way to check that the stop
            // control and the close X actually got laid out.
            val probe = com.blurr.voice.overlay.AgentStatusPill(this)
            probe.show(
                goal = "Open the ChatGPT app",
                activity = "Looking at the screen",
                step = 7,
                maxSteps = 150,
                onStop = { Log.d(TAG, "STOP TAPPED") }
            )
            probe.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2392, View.MeasureSpec.AT_MOST)
            )
            probe.layout(0, 0, probe.measuredWidth, probe.measuredHeight)
            Log.d(TAG, "pill ${probe.measuredWidth}x${probe.measuredHeight}px")
            dumpTree(probe, 0)
        }
    }

    /** Logs each node's class, pixel size and clickability. */
    private fun dumpTree(view: View, depth: Int) {
        val pad = "  ".repeat(depth)
        val label = when (view) {
            is TextView -> "\"${view.text}\""
            else -> ""
        }.let { if (it.isEmpty()) "" else " $it" }
        Log.d(
            TAG,
            "$pad${view.javaClass.simpleName} ${view.width}x${view.height}" +
                " clickable=${view.isClickable} visible=${view.visibility}$label"
        )
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) dumpTree(view.getChildAt(i), depth + 1)
        }
    }
}
