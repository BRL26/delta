package com.blurr.voice.sidekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A targeted action is one string with its target appended after a colon, and three
 * separate pieces read it back: the prefs that store it, the registry that describes it
 * to a settings row, and the executor that runs it. If the shape drifts, a press mapped
 * to an app silently becomes a press that does nothing -- which is exactly what these
 * pin down.
 */
class SideKeyTargetsTest {

    @Test
    fun `app target round-trips through its encoded id`() {
        val target = SideKeyTarget.OpenApp("com.example.some.app")
        assertEquals(target, SideKeyTargets.parse(target.encoded))
    }

    @Test
    fun `link target round-trips even with colons and spaces in it`() {
        val target = SideKeyTarget.OpenLink("geo:0,0?q=a coffee shop")
        assertEquals(target, SideKeyTargets.parse(target.encoded))
    }

    @Test
    fun `plain action ids are not targeted`() {
        assertNull(SideKeyTargets.parse(SideKeyActionRegistry.NONE_ID))
        assertNull(SideKeyTargets.parse(SideKeyActionRegistry.TOGGLE_FLASHLIGHT_ID))
    }

    @Test
    fun `a prefix with nothing after it is not a target`() {
        // Storing this would map the press to an app that is not named, and the
        // executor would have nothing to launch.
        assertNull(SideKeyTargets.parse(OPEN_APP_PREFIX))
        assertNull(SideKeyTargets.parse(OPEN_LINK_PREFIX))
        assertNull(SideKeyTargets.parse(null))
    }

    @Test
    fun `a targeted id resolves to its own spec rather than do nothing`() {
        val id = SideKeyTarget.OpenApp("com.android.camera").encoded
        val spec = SideKeyActionRegistry.specFor(id)
        assertEquals(id, spec.id)
        assertEquals("Open app", spec.label)
    }

    @Test
    fun `a link id resolves to its own spec rather than do nothing`() {
        val id = SideKeyTarget.OpenLink("https://example.com/x").encoded
        assertEquals(id, SideKeyActionRegistry.specFor(id).id)
    }

    @Test
    fun `isKnown accepts catalogue and targeted ids and rejects the rest`() {
        assertTrue(SideKeyActionRegistry.isKnown(SideKeyActionRegistry.NONE_ID))
        assertTrue(SideKeyActionRegistry.isKnown(SideKeyTarget.OpenApp("com.foo").encoded))
        assertFalse(SideKeyActionRegistry.isKnown("an_action_that_was_removed"))
        assertFalse(SideKeyActionRegistry.isKnown(OPEN_APP_PREFIX))
        assertFalse(SideKeyActionRegistry.isKnown(null))
    }
}
