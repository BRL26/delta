package com.blurr.voice.sidekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gesture classifier's timing logic, driven by a virtual clock so the
 * long-press and multi-tap windows are tested deterministically instead of
 * sleeping real time.
 */
class KeyGestureClassifierTest {

    private class FakeScheduler : GestureScheduler {
        var now = 0L
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        override fun schedule(delayMillis: Long, block: () -> Unit): Cancellable {
            val entry = (now + delayMillis) to block
            pending.add(entry)
            return Cancellable { pending.remove(entry) }
        }

        fun advance(ms: Long) {
            now += ms
            val due = pending.filter { it.first <= now }.sortedBy { it.first }
            pending.removeAll { it.first <= now }
            due.forEach { it.second() }
        }
    }

    private class Box<T>(var value: T? = null)

    private fun classifier(
        enabled: Set<KeyGesture>,
        scheduler: FakeScheduler,
        onGesture: Box<KeyGesture>
    ): KeyGestureClassifier = KeyGestureClassifier(
        enabledGestures = enabled,
        scheduler = scheduler,
        onGesture = { onGesture.value = it }
    )

    @Test
    fun `single press resolves immediately when multi-tap is disabled`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(setOf(KeyGesture.SINGLE_PRESS), clock, result)

        c.onKeyDown(0)
        c.onKeyUp(0, 50)
        // No window to wait out: the single press must already be out.
        assertEquals(KeyGesture.SINGLE_PRESS, result.value)
    }

    @Test
    fun `double press waits out the multi-tap window`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(
            setOf(KeyGesture.SINGLE_PRESS, KeyGesture.DOUBLE_PRESS), clock, result
        )

        c.onKeyDown(0)
        c.onKeyUp(0, 40)
        assertNull(result.value) // still could become a double

        c.onKeyDown(100)
        c.onKeyUp(100, 140)
        assertNull(result.value) // sequence not resolved until the window closes

        clock.advance(KeyGestureClassifier.MULTI_TAP_WINDOW_MS)
        assertEquals(KeyGesture.DOUBLE_PRESS, result.value)
    }

    @Test
    fun `triple press collapses tap counts above double`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(
            setOf(KeyGesture.SINGLE_PRESS, KeyGesture.DOUBLE_PRESS, KeyGesture.TRIPLE_PRESS),
            clock,
            result
        )

        var t = 0L
        repeat(3) {
            c.onKeyDown(t)
            c.onKeyUp(t, t + 40)
            t += 60
        }
        clock.advance(KeyGestureClassifier.MULTI_TAP_WINDOW_MS)
        assertEquals(KeyGesture.TRIPLE_PRESS, result.value)
    }

    @Test
    fun `long press fires while the key is still held`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(setOf(KeyGesture.SINGLE_PRESS), clock, result)

        c.onKeyDown(0)
        clock.advance(KeyGestureClassifier.LONG_PRESS_THRESHOLD_MS)
        assertEquals(KeyGesture.LONG_PRESS, result.value)

        // The release of the already-resolved long press is swallowed.
        c.onKeyUp(0, 800)
        assertEquals(KeyGesture.LONG_PRESS, result.value)
    }

    @Test
    fun `auto-repeat key downs are not new presses`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(setOf(KeyGesture.SINGLE_PRESS), clock, result)

        c.onKeyDown(10)
        c.onKeyDown(10) // repeat event, same down time
        c.onKeyUp(10, 60)
        assertEquals(KeyGesture.SINGLE_PRESS, result.value)
    }

    /**
     * Adaptive timing: a long press resolves from its own timer while the key is
     * down, so it cannot be confused with a released tap. Mapping one alongside
     * a single press must therefore not make the single press wait.
     */
    @Test
    fun `single press stays instant when a long press is also mapped`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(
            setOf(KeyGesture.SINGLE_PRESS, KeyGesture.LONG_PRESS), clock, result
        )

        c.onKeyDown(0)
        c.onKeyUp(0, 50)
        assertEquals(KeyGesture.SINGLE_PRESS, result.value)
    }

    @Test
    fun `a held press still resolves as a long press when a single press is mapped`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(
            setOf(KeyGesture.SINGLE_PRESS, KeyGesture.LONG_PRESS), clock, result
        )

        c.onKeyDown(0)
        clock.advance(KeyGestureClassifier.LONG_PRESS_THRESHOLD_MS)
        assertEquals(KeyGesture.LONG_PRESS, result.value)

        c.onKeyUp(0, 700)
        assertEquals(KeyGesture.LONG_PRESS, result.value) // the release adds nothing
    }

    /**
     * The classifier classifies the key, it does not decide what runs: with
     * nothing mapped it still resolves a single press immediately, and the
     * mapper is what turns that into a no-op. Waiting here would add latency
     * for every press to buy nothing.
     */
    @Test
    fun `an unmapped single press still classifies immediately`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(emptySet(), clock, result)

        c.onKeyDown(0)
        c.onKeyUp(0, 50)
        assertEquals(KeyGesture.SINGLE_PRESS, result.value)
    }

    @Test
    fun `reset clears a pending double`() {
        val clock = FakeScheduler()
        val result = Box<KeyGesture>()
        val c = classifier(
            setOf(KeyGesture.SINGLE_PRESS, KeyGesture.DOUBLE_PRESS), clock, result
        )
        c.onKeyDown(0)
        c.onKeyUp(0, 40)
        c.reset()
        clock.advance(KeyGestureClassifier.MULTI_TAP_WINDOW_MS)
        assertNull(result.value)
    }
}