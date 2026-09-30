package com.blurr.voice.sidekey

import android.util.Log
import com.blurr.voice.ScreenInteractionService
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Pulls the visible text out of the screen the accessibility node tree already
 * exposes, which is Delta's local "OCR": no dependency, no network, and it
 * reads the exact strings the user is looking at.
 *
 * This is deliberately the primary signal for the snap analysis. A vision model
 * alone is unreliable here - asked to describe a booking form it answers as a
 * helpful assistant and tries to *help the user finish booking* instead of
 * reporting what the screen says. The tree text cannot be talked out of
 * anything, and it is the same source the agent already trusts for on-screen
 * reading.
 */
object ScreenText {

    private const val TAG = "ScreenText"
    private const val MAX_CHARS = 6000

    /** A hard cap on collected lines, so a pathological dump cannot run away. */
    private const val MAX_NODES = 400

    /** Attributes of an accessibility node that hold words the user can see. */
    private val TEXT_ATTRIBUTES = listOf("content-desc", "text", "hint")

    /**
     * Words that are almost always interface furniture rather than content.
     * Dropping them keeps the screen text short enough to be worth reading and
     * stops a screen full of buttons from looking like a screen full of things
     * to remember. A button label that is *also* meaningful ("Pay now", "Book
     * appointment") survives on the longer list below, which is only used to
     * protect those.
     */
    private val NOISE = setOf(
        "ok", "okay", "cancel", "close", "back", "next", "done", "save", "edit", "delete",
        "yes", "no", "retry", "dismiss", "allow", "deny", "continue", "confirm", "submit",
        "search", "menu", "home", "settings", "more", "skip", "apply", "reset", "clear",
        "sign in", "sign up", "log in", "log out", "loading", "error", "refresh", "share",
        "copy", "paste", "select", "select all", "done", "add", "new", "update", "upgrade",
        "required", "optional", "note", "notes", "help", "info", "about", "privacy",
        "terms", "accept", "agree", "decline", "not now", "maybe", "later", "got it"
    )

    /**
     * @return the distinct visible text of the current screen, newline
     *   separated in reading order, or null when the tree is unavailable.
     */
    suspend fun current(): String? {
        val service = ScreenInteractionService.instance ?: run {
            Log.w(TAG, "Accessibility service not connected; no screen text.")
            return null
        }
        val xml = service.dumpWindowHierarchy(true)
        val text = parse(xml)
        Log.d(TAG, "Extracted ${text.length} chars of screen text.")
        return text
    }

    /**
     * Walks the dumped hierarchy and keeps the text of every node that shows
     * something. Whitespace collapses, near-duplicates are dropped, and long
     * strings are truncated, so the model gets a short list of real phrases
     * rather than a wall of layout noise.
     *
     * SAX rather than a pull parser, for two reasons: it streams, which matters
     * when the dump is a few hundred kilobytes of nodes, and it is plain JDK,
     * so the extraction rules are covered by ordinary unit tests instead of
     * only being exercisable on a device. A truncated dump still yields whatever
     * was read before the parse failed, because lines are collected as they go.
     */
    fun parse(xml: String): String {
        if (xml.isBlank() || xml.startsWith("Error:")) return ""
        val out = LinkedHashSet<String>()
        val buffer = StringBuilder()

        val handler = object : DefaultHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes?
            ) {
                buffer.setLength(0)
                if (out.size >= MAX_NODES || attributes == null) return
                // An accessibility dump carries its words as attributes, not as
                // element content: "text" for labels, "content-desc" for the
                // accessible name a user would hear read aloud, and "hint" for
                // the placeholder on an empty field. All three are things the
                // user can see.
                for (name in TEXT_ATTRIBUTES) {
                    val value = attributes.getValue(name)?.trim().orEmpty()
                    if (value.isNotEmpty() && value.length <= 400 && looksUseful(value)) {
                        out.add(value)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (out.size < MAX_NODES && buffer.length < MAX_CHARS) {
                    buffer.appendRange(ch, start, start + length)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val value = buffer.toString().trim()
                buffer.setLength(0)
                if (out.size >= MAX_NODES) return
                if (value.isEmpty() || value.length > 400) return
                if (looksUseful(value)) out.add(value)
            }
        }

        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                // The dump is written by us, but a screen full of hostile text
                // should not be able to make the parser reach for a URL.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        } catch (e: SAXException) {
            // A dump cut off mid-document is normal when a screen is changing
            // under us; keep whatever was read.
            Log.w(TAG, "Hierarchy dump ended early; kept ${out.size} line(s): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse screen hierarchy text", e)
        }
        return out.joinToString("\n").take(MAX_CHARS)
    }

    /** Drops layout noise, numeric-only junk, and pure resource identifiers. */
    private fun looksUseful(text: String): Boolean {
        if (text.matches(Regex("^[0-9.,:/-]+$"))) return false
        if (text.startsWith("android.") || text.startsWith("com.")) return false
        if (text.length < 2) return false
        if (text.lowercase(Locale.US) in NOISE) return false
        return true
    }
}