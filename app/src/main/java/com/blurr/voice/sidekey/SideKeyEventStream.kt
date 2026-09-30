package com.blurr.voice.sidekey

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A single raw key event as observed by the accessibility service. */
data class ObservedKeyEvent(
    val keyCode: Int,
    val scanCode: Int,
    val action: Int,
    val timestampMs: Long
)

/**
 * The live channel between the accessibility service and the Key Test screen.
 *
 * While [testModeActive] is true the service publishes every candidate key
 * event and every recognised gesture here instead of executing mapped actions,
 * so the user can verify the hardware and the gesture classifier before relying
 * on a mapping. Holds no Android state, so the test screen and the settings
 * status row can read it without touching the service.
 */
object SideKeyEventStream {

    /** While true, the service records instead of acting. */
    @Volatile
    var testModeActive: Boolean = false

    private val _events = MutableSharedFlow<ObservedKeyEvent>(extraBufferCapacity = 64)
    private val _gestures = MutableSharedFlow<KeyGesture>(extraBufferCapacity = 16)

    /** Raw candidate key events, most recent first on collection. */
    val events: SharedFlow<ObservedKeyEvent> = _events.asSharedFlow()

    /** Fully classified gestures (single/double/triple/long). */
    val gestures: SharedFlow<KeyGesture> = _gestures.asSharedFlow()

    fun publishEvent(event: ObservedKeyEvent) {
        _events.tryEmit(event)
    }

    fun publishGesture(gesture: KeyGesture) {
        _gestures.tryEmit(gesture)
    }
}