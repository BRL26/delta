package com.blurr.voice.overlay

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.blurr.voice.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Compact status pill shown while the task agent runs.
 *
 * Layout, spacing and colour are ported from the MBTG assistant's collapsed
 * bubble: a fully-rounded elevated surface, a progress ring on the left with the
 * stop control nested *inside* it, a two-line activity label, and a close X on
 * the right. Built from Material components so it picks up theme attributes
 * rather than hard-coded colours.
 *
 * Purely additive - it does not touch any existing screen layout.
 *
 * @param onStop invoked when the user taps the stop control in the ring.
 */
class AgentStatusPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val progress: CircularProgressIndicator
    private val stopGlyph: ImageView
    private val labelView: TextView
    private val closeButton: MaterialButton

    private var onStop: (() -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null

    /**
     * The exit animation, if one is running.
     *
     * Held so a [show] arriving mid-exit can cancel it and reverse from wherever the
     * pill had got to, rather than snapping back to full opacity and then fading in
     * again -- the flicker that a new task starting as the old one finishes.
     */
    private var exitAnimation: ViewPropertyAnimator? = null

    init {
        // Starts fully transparent: the window is added before the first [show]
        // arrives, and a pill that is briefly opaque at full size before animating
        // in is exactly the pop the animation is meant to remove. [show] brings the
        // alpha up, [hide] takes it back down.
        alpha = 0f
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Asymmetric on purpose: the close button's own touch padding supplies
        // the trailing inset, so extra padding here would double it up.
        setPadding(dp(14f), dp(8f), dp(4f), dp(8f))

        val card = MaterialCardView(context).apply {
            // A radius past half the height is what makes this read as a pill
            // rather than a rounded rectangle; the draw path clamps it.
            radius = dp(60f).toFloat()
            cardElevation = dp(12f).toFloat()
            setCardBackgroundColor(R.color.assistant_surface)
            isClickable = false
            isFocusable = false
        }
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // The card is the pill; this row is its content.
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // --- stop control: ring with the X drawn on top of it ---
        // The 16dp visual is not a viable touch target on its own, so the
        // clickable box is padded out to 30dp around the same 16dp ring. The
        // pill's window is FLAG_NOT_TOUCH_MODAL, so the extra area is inside the
        // pill and does not steal taps from the app underneath.
        val stopBox = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onStop?.invoke() }
            contentDescription = context.getString(R.string.stop_agent)
        }
        row.addView(stopBox, LayoutParams(dp(30f).toInt(), dp(30f).toInt()))

        progress = CircularProgressIndicator(context).apply {
            isIndeterminate = true
            indicatorSize = dp(16f).toInt()
            // The widget force-sizes itself to indicatorSize + 2 * indicatorInset
            // and ignores its own LayoutParams, and the style's default 4dp inset
            // would inflate the 16dp ring to a 24dp view. Zeroing it makes the
            // ring exactly the size asked for.
            indicatorInset = 0
            trackThickness = dp(2f).toInt()
            setIndicatorColor(R.color.assistant_primary)
        }
        stopBox.addView(
            progress,
            FrameLayout.LayoutParams(dp(16f).toInt(), dp(16f).toInt()).apply {
                gravity = Gravity.CENTER
            }
        )

        stopGlyph = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close_rounded))
            setColorFilter(ContextCompat.getColor(context, R.color.assistant_primary))
        }
        stopBox.addView(
            stopGlyph,
            FrameLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply {
                gravity = Gravity.CENTER
            }
        )

        row.addView(View(context), LayoutParams(dp(10f).toInt(), 1))

        labelView = TextView(context).apply {
            setTextColor(R.color.assistant_on_surface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            // The overlay window is WRAP_CONTENT, so an uncapped label lets one
            // long goal stretch the pill most of the way across the display and
            // it stops reading as a status line. The label drives the width, so
            // capping it here is what caps the pill.
            maxWidth = dp(216f)
        }
        row.addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // The pill can be the only thing on screen, so it carries its own way
        // out. A separate target rather than a long-press, because a hidden
        // gesture on the one control that cannot be dismissed any other way is a
        // gesture nobody will find.
        closeButton = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialIconButtonStyle
        ).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_close_rounded)
            iconTint = ContextCompat.getColorStateList(context, R.color.assistant_on_surface_variant)
            iconSize = dp(15f).toInt()
            iconPadding = 0
            contentDescription = context.getString(R.string.dismiss_agent_status)
            // The M3 icon-button style carries 48dp minimums, insets and padding
            // that would bulk the pill out well past a status line.
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            isCheckable = false
        }
        row.addView(closeButton, LayoutParams(dp(34f).toInt(), dp(34f).toInt()))
    }

    /**
     * Brings the pill onto the screen, animated.
     *
     * The pill is the only thing on screen while a task runs, so it arrives from
     * slightly above and slightly small and settles into place, which reads as
     * "this is here now" rather than as a rectangle that simply exists. Reversible:
     * a [show] while an exit is still running picks up from the current values
     * instead of restarting.
     *
     * @param goal     the original request, shown when there is no activity text
     * @param activity the model's plain-language description of the current step
     * @param step     current step, rendered as "n/m" when [maxSteps] is given
     */
    fun show(
        goal: String,
        activity: String? = null,
        step: Int? = null,
        maxSteps: Int? = null,
        onStop: (() -> Unit)? = null
    ) {
        // The stop action is re-supplied on every step, but keep the previous
        // one if a later call omits it so a mid-run update cannot silently
        // disable the stop control.
        this.onStop = onStop ?: this.onStop

        labelView.text = buildString {
            append(activity?.takeIf { it.isNotBlank() } ?: goal)
            if (step != null && maxSteps != null) {
                append("  ·  ").append(step).append('/').append(maxSteps)
            }
        }
        if (!progress.isIndeterminate) progress.isIndeterminate = true

        exitAnimation?.cancel()
        exitAnimation = null
        if (visibility == View.VISIBLE && alpha >= 1f) return // already up, nothing to do

        visibility = View.VISIBLE
        // Continuing from the current values rather than resetting them is what makes
        // an interrupted exit resume instead of jumping back to the start position.
        alpha = alpha.coerceIn(0f, 1f)
        scaleX = scaleX.coerceIn(ENTER_SCALE, 1f)
        scaleY = scaleY.coerceIn(ENTER_SCALE, 1f)
        translationY = translationY.coerceIn(-dp(12f).toFloat(), 0f)

        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(ENTER_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(null)
            .start()
    }

    /**
     * Registers what the close X does. Set once when the pill is created and
     * deliberately *not* re-registered by [hide], so hiding and re-showing the
     * pill between tasks cannot leave the X wired to nothing.
     */
    fun setOnDismiss(callback: () -> Unit) {
        onDismiss = callback
        closeButton.setOnClickListener { onDismiss?.invoke() }
    }

    /**
     * Takes the pill off the screen, animated.
     *
     * Faded and shrunk rather than simply set GONE, because it is removed abruptly
     * often enough to notice: a task that opens an app collapses the whole assistant
     * at the exact moment the user is looking at the screen they asked for.
     *
     * The view is only set GONE when the animation actually ends, so a hide that is
     * interrupted by a show cannot leave the pill invisible. Callers still remove the
     * view from the window separately; this is about how it looks on the way out.
     */
    fun hide() {
        onStop = null
        if (visibility != View.VISIBLE) {
            visibility = View.GONE
            return
        }
        exitAnimation?.cancel()
        // Assigned before starting, and started separately, because start() returns
        // Unit and what needs holding on to is the animator itself.
        val exiting = animate()
            .alpha(0f)
            .scaleX(ENTER_SCALE)
            .scaleY(ENTER_SCALE)
            .translationY(-dp(12f).toFloat())
            .setDuration(EXIT_DURATION_MS)
            // Straight fade, not a decelerate: a short exit that keeps easing reads
            // as the pill hesitating on its way out.
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                exitAnimation = null
                visibility = View.GONE
            }
        exitAnimation = exiting
        exiting.start()
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private companion object {
        /** How long the pill takes to arrive. Long enough to see, short enough to ignore. */
        const val ENTER_DURATION_MS = 220L

        /** The exit is quicker than the entrance: the user is waiting for their screen. */
        const val EXIT_DURATION_MS = 160L

        /** The scale the pill grows up from, and shrinks back down to. */
        const val ENTER_SCALE = 0.9f
    }
}
