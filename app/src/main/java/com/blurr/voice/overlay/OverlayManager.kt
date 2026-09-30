package com.blurr.voice.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.ContextThemeWrapper
import android.widget.TextView
import com.blurr.voice.R
import com.blurr.voice.assistant.SessionBridge
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class OverlayManager private constructor(context: Context) {

    private val applicationContext = context.applicationContext
    private val windowManager by lazy { applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val clientCount = AtomicInteger(0)
    private var bottomOverlayView: View? = null
    private var topOverlayView: View? = null
    private var observeJob: Job? = null
    private var autoDismissRunnable: Runnable? = null

    // The agent status pill is deliberately kept out of the position-keyed
    // overlay map so it never displaces the TTS caption or task overlays.
    private var statusPillView: AgentStatusPill? = null
    private var statusPillPinned = false

    companion object {
        @Volatile private var INSTANCE: OverlayManager? = null
        fun getInstance(context: Context): OverlayManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OverlayManager(context).also { INSTANCE = it }
            }
        }
    }
    /**
     * Called by anyone who wants the overlay to be alive.
     * Increments the user count.
     */
    @Synchronized // prevent race conditions
    fun startObserving() {
        val currentCount = clientCount.incrementAndGet()
        Log.d("OverlayManager", "Client added. Total clients: $currentCount")

        // Only start the job if it's not already running
        if (observeJob?.isActive == true) return

        observeJob = scope.launch {
            try {
                OverlayDispatcher.activeContent.collect { contentMap ->
                    try {
                        // Update or remove views based on the map content
                        for (position in OverlayPosition.values()) {
                            val content = contentMap[position]
                            if (content != null) {
                                updateOverlayView(content)
                            } else {
                                removeOverlayView(position)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("OverlayManager", "UI Update Error", e)
                    }
                }
            } catch (e: CancellationException) {
                Log.d("OverlayManager", "Observer cancelled normally.")
            } catch (e: Exception) {
                Log.e("OverlayManager", "Fatal Observer Error", e)
            }
        }
    }

    /**
     * Called when a component is done with the overlay.
     * Decrements the user count. Only kills the overlay if count reaches 0.
     */
    @Synchronized
    fun stopObserving() {
        val remainingClients = clientCount.decrementAndGet()
        Log.d("OverlayManager", "Client removed. Remaining clients: $remainingClients")

        if (remainingClients <= 0) {
            // Only actually stop if NOBODY needs it anymore
            Log.d("OverlayManager", "No clients left. Stopping observer.")
            clientCount.set(0) // Safety reset
            observeJob?.cancel()
            observeJob = null // Clear the reference so it can restart later
            removeOverlayInternal()
        } else {
            Log.d("OverlayManager", "Observer kept alive for other clients.")
        }
    }
    private fun updateOverlayView(content: OverlayContent) {
        // Stop any pending auto-dismiss (This logic might need refinement for concurrent overlays if IDs overlap, but for now it's okay)
        // Ideally we should map runnables to IDs.
        Log.d("OverlayManager", "Updating overlay: ${content.text} at ${content.position}")
        // autoDismissRunnable?.let { mainHandler.removeCallbacks(it) } // TODO: Handle per-overlay auto-dismiss

        if (content.position == OverlayPosition.TOP) {
            if (topOverlayView == null) createView(OverlayPosition.TOP)
            topOverlayView?.setCaptionText(content.text)
        } else {
            if (bottomOverlayView == null) createView(OverlayPosition.BOTTOM)
            bottomOverlayView?.setCaptionText(content.text)
        }

        // Handle Auto-dismiss (e.g., for system info toasts)
        if (content.duration > 0) {
            val runnable = Runnable {
                OverlayDispatcher.dismiss(content.id)
            }
            mainHandler.postDelayed(runnable, content.duration)
        }
    }

    private fun removeOverlayView(position: OverlayPosition) {
        if (position == OverlayPosition.TOP) {
            removeView(topOverlayView)
            topOverlayView = null
        } else {
            removeView(bottomOverlayView)
            bottomOverlayView = null
        }
    }

    /**
     * Shows (or updates) the agent status pill. Safe to call every step.
     *
     * @param onStop invoked when the user taps the pill's X. Omit on later
     *   calls to keep the one already registered.
     *
     * Skipped while the assistant popup is on screen, because then the popup *is* the
     * status indicator: it collapses to a bubble showing the current step, and a second
     * pill in the same corner is two answers to the same question. The popup's bubble
     * carries the same stop control, so nothing is lost by letting the session own it.
     */
    fun showAgentStatus(
        goal: String,
        activity: String?,
        step: Int?,
        maxSteps: Int?,
        onStop: (() -> Unit)? = null
    ) {
        if (SessionBridge.sessionVisible.value) return
        mainHandler.post {
            try {
                statusPillPinned = true
                val pill = statusPillView ?: createStatusPill()
                pill?.show(goal, activity, step, maxSteps, onStop)
            } catch (e: Exception) {
                Log.e("OverlayManager", "Failed to show agent status pill", e)
            }
        }
    }

    fun hideAgentStatus() {
        mainHandler.post {
            try {
                statusPillPinned = false
                statusPillView?.hide()
                // Left attached but hidden: re-showing is far cheaper than a
                // window add/remove on every step.
            } catch (e: Exception) {
                Log.e("OverlayManager", "Failed to hide agent status pill", e)
            }
        }
    }

    private fun createStatusPill(): AgentStatusPill? {
        return try {
            if (!Settings.canDrawOverlays(applicationContext)) {
                Log.w("OverlayManager", "Overlay permission missing; status pill disabled.")
                return null
            }

            // Material components resolve their styling from theme attributes,
            // and the application context carries no theme - inflating a
            // MaterialCardView against it throws. Wrapping in the app theme is
            // what makes the pill render as Material rather than crashing.
            val themed = ContextThemeWrapper(applicationContext, R.style.Theme_Delta)
            val pill = AgentStatusPill(themed)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_TOUCH_MODAL rather than NOT_TOUCHABLE: the pill itself is
                // interactive (it has a stop button) but touches that land
                // outside its bounds still reach the app underneath. Plain
                // NOT_TOUCHABLE would make the X dead.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Top-start with a 12dp inset, matching the assistant bubble.
                // y is offset past the status bar so the pill is not drawn
                // underneath it.
                gravity = Gravity.TOP or Gravity.START
                x = dp(12f)
                y = statusBarHeight() + dp(12f)
            }

            windowManager.addView(pill, params)
            statusPillView = pill
            // Tapping the X drops the pin, so the next removeOverlayInternal()
            // reclaims the window instead of leaving it pinned forever.
            pill.setOnDismiss { statusPillPinned = false }
            pill
        } catch (e: Exception) {
            Log.e("OverlayManager", "Could not add status pill view", e)
            null
        }
    }

    private fun createView(position: OverlayPosition) {
        val themed = ContextThemeWrapper(applicationContext, R.style.Theme_Delta)

        val caption = TextView(themed).apply {
            id = R.id.overlay_caption_text
            setTextColor(R.color.assistant_on_surface)
            textSize = 16f
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }

        // Same dark rounded surface as before, but expressed as a Material card
        // so elevation, corner radius and colours come from the theme rather
        // than a hand-built GradientDrawable.
        val card = MaterialCardView(themed).apply {
            radius = dp(24f).toFloat()
            cardElevation = dp(12f).toFloat()
            setCardBackgroundColor(R.color.assistant_surface)
            addView(caption)
        }

        val gravity = if (position == OverlayPosition.TOP) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val yPos = if (position == OverlayPosition.TOP) 150 else 250

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            y = yPos
        }

        try {
            windowManager.addView(card, params)
            if (position == OverlayPosition.TOP) {
                topOverlayView = card
            } else {
                bottomOverlayView = card
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, applicationContext.resources.displayMetrics
    ).toInt()

    /** Height of the status bar, so the pill can sit clear of it. */
    private fun statusBarHeight(): Int {
        val id = applicationContext.resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
        return if (id > 0) applicationContext.resources.getDimensionPixelSize(id) else 0
    }

    /**
     * The caption root is a MaterialCardView, not a TextView, so the label has
     * to be reached by id rather than by casting the root.
     */
    private fun View.setCaptionText(text: String) {
        findViewById<TextView>(R.id.overlay_caption_text)?.text = text
    }

    private fun removeOverlayInternal() {
        removeView(bottomOverlayView)
        bottomOverlayView = null
        removeView(topOverlayView)
        topOverlayView = null
        // The pill has its own lifetime. clientCount is a global refcount
        // across every overlay client, so an unrelated component calling
        // stopObserving() would otherwise tear the pill off the screen in the
        // middle of a running task. Only a genuine teardown may remove it.
        if (!statusPillPinned) {
            removeView(statusPillView)
            statusPillView = null
        }
    }

    private fun removeView(view: View?) {
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore if view is not attached or already removed
            }
        }
    }
}