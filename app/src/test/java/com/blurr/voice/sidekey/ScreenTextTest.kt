package com.blurr.voice.sidekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text that reaches the model comes from a real accessibility dump, so
 * these cases use the same node shapes the service emits: content-desc on
 * buttons, text on labels, and a fair amount of resource-id noise.
 */
class ScreenTextTest {

    private fun dump(body: String) = "<?xml version='1.0' encoding='UTF-8'?><hierarchy rotation=\"0\">$body</hierarchy>"

    @Test
    fun `extracts text and content descriptions in order`() {
        val xml = dump(
            "<node text=\"Book appointment\" resource-id=\"id/title\" />" +
                "<node content-desc=\"Next step, tap to continue\" resource-id=\"id/btn\" />"
        )
        val text = ScreenText.parse(xml)
        assertTrue(text.contains("Book appointment"))
        assertTrue(text.contains("Next step, tap to continue"))
    }

    @Test
    fun `drops resource identifiers and pure numbers`() {
        val xml = dump(
            "<node text=\"com.example.app.booking\" />" +
                "<node text=\"android.widget.Button\" />" +
                "<node text=\"42\" />" +
                "<node text=\"October 9\" />"
        )
        val text = ScreenText.parse(xml)
        assertFalse(text.contains("com.example"))
        assertFalse(text.contains("android.widget"))
        assertTrue(text.contains("October 9"))
    }

    @Test
    fun `drops interface furniture`() {
        val xml = dump(
            "<node text=\"Cancel\" /><node text=\"Continue\" /><node text=\"Sign in\" />" +
                "<node text=\"Dr Okafor, 2:30 PM\" />"
        )
        val text = ScreenText.parse(xml)
        assertFalse(text.contains("Cancel"))
        assertFalse(text.contains("Continue"))
        assertFalse(text.contains("Sign in"))
        assertTrue(text.contains("Dr Okafor, 2:30 PM"))
    }

    @Test
    fun `keeps a meaningful button label`() {
        val xml = dump("<node text=\"Pay now\" /><node text=\"Book appointment\" />")
        val text = ScreenText.parse(xml)
        assertTrue(text.contains("Pay now"))
        assertTrue(text.contains("Book appointment"))
    }

    @Test
    fun `collapses repeated identical text`() {
        val xml = dump("<node text=\"October 9\" /><node text=\"October 9\" />")
        assertEquals(1, ScreenText.parse(xml).lines().count { it == "October 9" })
    }

    @Test
    fun `error and empty dumps are handled`() {
        assertEquals("", ScreenText.parse("Error: UI hierarchy is not available."))
        assertEquals("", ScreenText.parse(""))
        assertEquals("", ScreenText.parse("not xml at all <<<"))
    }

    @Test
    fun `reading order is preserved`() {
        val xml = dump(
            "<node text=\"First\" /><node text=\"Second\" /><node text=\"Third\" />"
        )
        assertEquals(listOf("First", "Second", "Third"), ScreenText.parse(xml).lines())
    }
}
