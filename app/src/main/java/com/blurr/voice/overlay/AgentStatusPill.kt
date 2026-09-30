package com.blurr.voice.overlay

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

    init {
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
        visibility = View.VISIBLE
        if (!progress.isIndeterminate) progress.isIndeterminate = true
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

    /** Drops the pill off the screen. */
    fun hide() {
        onStop = null
        visibility = View.GONE
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()
}
