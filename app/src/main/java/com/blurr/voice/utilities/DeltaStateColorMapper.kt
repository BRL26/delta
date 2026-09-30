package com.blurr.voice.utilities

import android.content.Context
import androidx.core.content.ContextCompat
import com.blurr.voice.R

/**
 * Utility class for mapping DeltaState values to their corresponding colors
 * and providing state-related information for the delta symbol.
 */
object DeltaStateColorMapper {

    /**
     * Data class representing the visual state of the delta symbol
     */
    data class DeltaVisualState(
        val state: DeltaState,
        val color: Int,
        val statusText: String,
        val colorHex: String
    )

    /**
     * Get the color resource ID for a given DeltaState
     */
    fun getColorResourceId(state: DeltaState): Int {
        return when (state) {
            DeltaState.IDLE -> R.color.delta_idle
            DeltaState.LISTENING -> R.color.delta_listening
            DeltaState.PROCESSING -> R.color.delta_processing
            DeltaState.SPEAKING -> R.color.delta_speaking
            DeltaState.ERROR -> R.color.delta_error
        }
    }

    /**
     * Get the resolved color value for a given DeltaState
     */
    fun getColor(context: Context, state: DeltaState): Int {
        val colorResId = getColorResourceId(state)
        return ContextCompat.getColor(context, colorResId)
    }

    /**
     * Get the status text for a given DeltaState
     */
    fun getStatusText(state: DeltaState): String {
        return when (state) {
            DeltaState.IDLE -> "Ready, tap delta to wake me up!"
            DeltaState.LISTENING -> "Listening..."
            DeltaState.PROCESSING -> "Processing..."
            DeltaState.SPEAKING -> "Speaking..."
            DeltaState.ERROR -> "Error"
        }
    }

    /**
     * Get the hex color string for a given DeltaState (for debugging/logging)
     */
    fun getColorHex(context: Context, state: DeltaState): String {
        val color = getColor(context, state)
        return String.format("#%08X", color)
    }

    /**
     * Get complete visual state information for a given DeltaState
     */
    fun getDeltaVisualState(context: Context, state: DeltaState): DeltaVisualState {
        return DeltaVisualState(
            state = state,
            color = getColor(context, state),
            statusText = getStatusText(state),
            colorHex = getColorHex(context, state)
        )
    }

    /**
     * Get all available states with their visual information
     */
    fun getAllStates(context: Context): List<DeltaVisualState> {
        return DeltaState.values().map { state ->
            getDeltaVisualState(context, state)
        }
    }

    /**
     * Check if a state represents an active operation (not idle or error)
     */
    fun isActiveState(state: DeltaState): Boolean {
        return when (state) {
            DeltaState.LISTENING, DeltaState.PROCESSING, DeltaState.SPEAKING -> true
            DeltaState.IDLE, DeltaState.ERROR -> false
        }
    }

    /**
     * Check if a state represents an error condition
     */
    fun isErrorState(state: DeltaState): Boolean {
        return state == DeltaState.ERROR
    }

    /**
     * Get the priority of a state for determining which state to display
     * when multiple conditions might be true. Higher numbers = higher priority.
     */
    fun getStatePriority(state: DeltaState): Int {
        return when (state) {
            DeltaState.ERROR -> 5      // Highest priority
            DeltaState.SPEAKING -> 4   // High priority
            DeltaState.LISTENING -> 3  // Medium-high priority
            DeltaState.PROCESSING -> 2 // Medium priority
            DeltaState.IDLE -> 1       // Lowest priority
        }
    }
}