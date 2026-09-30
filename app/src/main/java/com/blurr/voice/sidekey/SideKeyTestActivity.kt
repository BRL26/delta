package com.blurr.voice.sidekey

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.R
import com.blurr.voice.ScreenInteractionService
import kotlinx.coroutines.launch

/**
 * A live Essential Key test screen. While it is on screen the accessibility
 * service records raw key events and recognised gestures here instead of
 * executing the mapped action, so the user can verify both the hardware and
 * the classifier before relying on a mapping. A scanCode that differs from the
 * learned one is offered for re-learning, mirroring EssentialKeyTools' Key
 * Setup.
 */
class SideKeyTestActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var learnButton: TextView
    private lateinit var lastEventView: TextView
    private lateinit var logView: TextView

    private val gestureLog = ArrayDeque<String>()
    private var pendingScanCode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_side_key_test)

        statusView = findViewById(R.id.sideKeyStatus)
        learnButton = findViewById(R.id.sideKeyLearnButton)
        lastEventView = findViewById(R.id.sideKeyLastEvent)
        logView = findViewById(R.id.sideKeyGestureLog)

        // Armed from the moment the screen exists, and re-armed whenever it
        // comes back to the foreground. Disarmed on onStop: Android pauses
        // activities for overlays and the shade while the screen stays
        // visible, and a press during that window must not slip through as a
        // snap of the test screen. Once the screen is actually hidden (Home,
        // another app) snaps work normally again.
        SideKeyEventStream.testModeActive = true

        learnButton.setOnClickListener {
            if (pendingScanCode > 0) {
                EssentialKeyMapper.setLearnedScanCode(this, pendingScanCode)
                learnButton.visibility = View.GONE
                refreshStatus()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SideKeyEventStream.testModeActive = true
        refreshStatus()
        lifecycleScope.launch {
            SideKeyEventStream.events.collect { event -> onRawEvent(event) }
        }
        lifecycleScope.launch {
            SideKeyEventStream.gestures.collect { gesture -> onGesture(gesture) }
        }
    }

    override fun onStop() {
        SideKeyEventStream.testModeActive = false
        super.onStop()
    }

    private fun refreshStatus() {
        val connected = ScreenInteractionService.instance != null
        val learned = EssentialKeyMapper.learnedScanCode(this)
        statusView.text = buildString {
            append(if (connected) "Accessibility service: connected" else "Accessibility service: NOT connected — enable it in Settings")
            append("\nListening for the Essential Key (scanCode $learned)…")
        }
    }

    private fun onRawEvent(event: ObservedKeyEvent) {
        val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        lastEventView.text = "last event: $action keyCode=${event.keyCode} scanCode=${event.scanCode}"

        // Offer re-learning when a plausible, unlearned scanCode shows up.
        val learned = EssentialKeyMapper.learnedScanCode(this)
        if (event.scanCode != learned && event.scanCode != 0 &&
            SideKeyEventFilter.isLearnableCandidate(event.keyCode)
        ) {
            pendingScanCode = event.scanCode
            learnButton.text = "Remember scanCode ${event.scanCode} as the Essential Key"
            learnButton.visibility = View.VISIBLE
        }
    }

    private fun onGesture(gesture: KeyGesture) {
        gestureLog.addFirst("${System.currentTimeMillis() % 100_000}  ${gesture.name}")
        while (gestureLog.size > 12) gestureLog.removeLast()
        logView.text = gestureLog.joinToString("\n")
        Log.d("SideKeyTest", "Gesture detected: ${gesture.name}")
    }
}