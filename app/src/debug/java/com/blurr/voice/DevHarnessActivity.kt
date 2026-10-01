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
import com.blurr.voice.reminders.ReminderScheduler
import com.blurr.voice.v2.AgentService

/**
 * Debug-build-only harness for exercising things that are otherwise only
 * reachable by talking to the app.
 *
 * Lives in the debug source set, so it is never compiled into a release build.
 * Exists because the agent executor and the reminder scheduler have no UI to
 * click: the only other way in is to speak to the device, which is not
 * something a build can verify.
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

        // The "autoinput" harness exercised the service's own overlay text composer.
        // That composer is gone: text input now happens in the assistant popup, and
        // there is nothing left in the app that inflates a TextInputLayout from a
        // Service context, so there is nothing here for the harness to exercise.
    }
}
