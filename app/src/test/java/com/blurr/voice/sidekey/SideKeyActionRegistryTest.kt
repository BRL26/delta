package com.blurr.voice.sidekey

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The action registry is the extension point for the press menu, so its guarantee
 * is pinned here: an id that is missing, or that belonged to an action which has
 * since been removed, degrades to "do nothing" instead of throwing or leaving a
 * press mapped to something with no implementation behind it.
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
}
