package com.blurr.voice.sidekey

import android.os.Handler

/** A scheduled callback that can still be cancelled. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Abstraction over "run this after a delay" so the gesture classifier stays
 * free of Android timers and its timing logic can be unit-tested with a
 * virtual clock. The production implementation posts to the main handler,
 * because the classifier runs on the accessibility service's main thread.
 */
interface GestureScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): Cancellable
}

/** Posts delayed callbacks on [handler] and cancels them by removing the post. */
class HandlerGestureScheduler(private val handler: Handler) : GestureScheduler {

    override fun schedule(delayMillis: Long, block: () -> Unit): Cancellable {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMillis)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}