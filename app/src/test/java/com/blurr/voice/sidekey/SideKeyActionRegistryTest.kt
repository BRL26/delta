package com.blurr.voice.sidekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The action registry is the extension point for the press menu, so its two
 * guarantees are pinned here: an unknown id degrades to "do nothing" instead of
 * throwing, and the Circle to Search overlay check only accepts Google search
 * windows.
 */
class SideKeyActionRegistryTest {

    @Test
    fun `unknown id resolves to do nothing`() {
        assertEquals(SideKeyActionRegistry.NONE, SideKeyActionRegistry.specFor("no_such_action"))
    }

    @Test
    fun `null id resolves to do nothing`() {
        assertEquals(SideKeyActionRegistry.NONE, SideKeyActionRegistry.specFor(null))
    }

    @Test
    fun `every action id is unique and none is first`() {
        val ids = SideKeyActionRegistry.actions.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(SideKeyActionRegistry.NONE, SideKeyActionRegistry.actions.first())
    }

    @Test
    fun `known ids round-trip`() {
        for (action in SideKeyActionRegistry.actions) {
            assertEquals(action, SideKeyActionRegistry.specFor(action.id))
        }
    }

    @Test
    fun `a google search window is recognised as the overlay`() {
        assertTrue(CircleToSearch.looksLikeSearchOverlay(listOf("com.google.android.googlequicksearchbox")))
        assertTrue(CircleToSearch.looksLikeSearchOverlay(listOf("com.android.systemui", "com.google.android.apps.lens")))
    }

    @Test
    fun `an ordinary window is not the overlay`() {
        assertFalse(CircleToSearch.looksLikeSearchOverlay(listOf("com.brl.blurrmbtg")))
        assertFalse(CircleToSearch.looksLikeSearchOverlay(emptyList()))
    }
}
