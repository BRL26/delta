package com.blurr.voice.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The markdown the model reaches for, taken back out before it is shown.
 *
 * The risk in this file is not leaving asterisks in, it is taking too much out. A
 * stripper that is merely eager is worse than none: it silently rewrites the user's
 * answer, and a reply containing `2 * 3` or `snake_case_name` or a bare URL is
 * ordinary, not edge-case. So roughly half of these tests are the "leave it alone"
 * cases, and the emphasis rule is pinned by its counter-examples rather than only by its
 * happy path.
 *
 * Carried over with the function it covers, from the MBTG assistant, where the reply it
 * had to cope with was a real one from a phone: a screen listing rendered with its
 * emphasis markers still on screen, which reads as a bug even though the content is
 * right.
 */
class PlainTextTest {

    // ---- what it removes ---------------------------------------------------

    @Test
    fun `bold from a real reply loses its asterisks`() {
        assertEquals(
            "The first three items on your screen are:\n\n" +
                "1. Nothing Account (button)\n" +
                "2. Settings (text view)\n" +
                "3. Search Settings (text view)",
            plainText(
                "The first three items on your screen are:\n\n" +
                    "1. **Nothing Account** (button)\n" +
                    "2. **Settings** (text view)\n" +
                    "3. **Search Settings** (text view)"
            ),
        )
    }

    @Test
    fun `every bold run in a line is unwrapped, not just the first`() {
        assertEquals("turn on Wi-Fi and Bluetooth", plainText("turn on **Wi-Fi** and **Bluetooth**"))
    }

    @Test
    fun `underscore bold is unwrapped`() {
        assertEquals("loud", plainText("__loud__"))
    }

    @Test
    fun `emphasis is unwrapped when it is clearly emphasis`() {
        assertEquals("use this one here", plainText("use *this one* here"))
    }

    @Test
    fun `a heading loses its hashes`() {
        assertEquals("Battery", plainText("## Battery"))
    }

    @Test
    fun `only the hashes go, the rest of the line stays`() {
        assertEquals("Battery saver is on", plainText("### Battery saver is on"))
    }

    @Test
    fun `code spans keep their contents`() {
        assertEquals("run adb devices first", plainText("run `adb devices` first"))
    }

    @Test
    fun `a fenced block keeps its contents and loses the fence`() {
        assertEquals(
            "line one\nline two",
            plainText("```\nline one\nline two\n```"),
        )
    }

    @Test
    fun `a fence with a language tag goes too`() {
        assertEquals("SELECT 1", plainText("```sql\nSELECT 1\n```"))
    }

    @Test
    fun `asterisk bullets become dash bullets`() {
        assertEquals("- first\n- second", plainText("* first\n* second"))
    }

    // ---- what it must not touch --------------------------------------------

    @Test
    fun `multiplication survives, because asterisks are ordinary punctuation too`() {
        assertEquals("2 * 3 * 4 = 24", plainText("2 * 3 * 4 = 24"))
    }

    @Test
    fun `a star glued inside a word is not emphasis`() {
        assertEquals("snake*case*name", plainText("snake*case*name"))
    }

    @Test
    fun `a single underscore pair is left alone, because of snake case`() {
        assertEquals("set user_name in prefs", plainText("set user_name in prefs"))
    }

    @Test
    fun `a bare url is left readable`() {
        assertEquals("see https://example.com/a_b_c", plainText("see https://example.com/a_b_c"))
    }

    @Test
    fun `a numbered list keeps its numbers`() {
        assertEquals("1. open Settings\n2. tap Network", plainText("1. open Settings\n2. tap Network"))
    }

    @Test
    fun `a hash that is not a heading is left alone`() {
        assertEquals("model #1 of 3", plainText("model #1 of 3"))
    }

    @Test
    fun `ordinary text comes back byte for byte`() {
        val plain = "Opened Settings and tapped Network & internet, then Wi-Fi."
        assertEquals(plain, plainText(plain))
    }

    @Test
    fun `an empty reply stays empty`() {
        assertEquals("", plainText(""))
    }

    // ---- composition --------------------------------------------------------

    @Test
    fun `bold inside a list item is unwrapped and the marker kept`() {
        assertEquals("1. Done", plainText("1. **Done**"))
    }

    @Test
    fun `an italic run at the start of a line is emphasis, not a bullet`() {
        // No space after the opening star, so the bullet rule must not claim it --
        // the two rules are told apart by that space and by nothing else.
        assertEquals("emphasis first", plainText("*emphasis* first"))
    }
}
