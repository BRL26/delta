package com.blurr.voice.ui.chat

/**
 * Strips the markdown an assistant reply tends to pick up, leaving readable text.
 *
 * The chat renders through a plain Compose [androidx.compose.material3.Text], which
 * has no notion of markup, so anything the model emphasises comes out as the literal
 * punctuation it used. A real reply from the phone looked like this:
 *
 *     The first three items on your screen are:
 *
 *     1. **Nothing Account** (button)
 *
 * with the asterisks visible on screen, which reads as a bug even though the content
 * is correct.
 *
 * The obvious alternative is to ask the model not to do this. That is not enough on
 * its own, for two reasons: the system prompt is not ours alone, and a formatting rule
 * in the prompt is a request, not a guarantee, so a long or listy answer would
 * eventually ignore it. Stripping at the point of display is the only version that
 * cannot fail, and it costs nothing.
 *
 * Deliberately narrow. It removes emphasis, headings, code fences and backticks, and
 * leaves links alone -- a bare URL is legible, and rewriting them risks mangling
 * answer text for no visible gain. Anything not matched is returned untouched.
 *
 * The italic rule is the only fiddly one, because a lone asterisk is also ordinary
 * punctuation. It insists the marker is not glued to a neighbouring word and not
 * followed by a space, so `2 * 3 * 4` and `snake*case*name` survive intact while
 * `use *this* here` does not. Underscores are handled only in pairs for the same
 * reason: `_italic_` would eat half of every snake_case identifier in a reply.
 *
 * Ported from the MBTG assistant along with its tests, which are the reason the
 * counter-cases are as fussy as they are.
 */
internal fun plainText(raw: String): String {
    if (!raw.any { it in MARKUP_CHARS }) return raw

    var text = CODE_FENCE.replace(raw, "")
    // Bullets before emphasis, so a leading "* item" is read as a list marker and
    // never as the start of an italic run.
    text = STAR_BULLET.replace(text, "- ")
    text = HEADING.replace(text, "")
    text = BOLD_STAR.replace(text) { it.groupValues[1] }
    text = BOLD_UNDERSCORE.replace(text) { it.groupValues[1] }
    text = CODE_SPAN.replace(text) { it.groupValues[1] }
    text = ITALIC_STAR.replace(text) { it.groupValues[1] }
    return text.trim()
}

/**
 * The punctuation this function acts on.
 *
 * Checking for these first means ordinary replies -- the overwhelming majority -- skip
 * seven regex passes entirely.
 */
private const val MARKUP_CHARS = "*_`#"

private val CODE_FENCE = Regex("(?m)^[ \t]*```[a-zA-Z0-9]*[ \t]*$")
private val STAR_BULLET = Regex("(?m)^[ \t]*\\*[ \t]+")
private val HEADING = Regex("(?m)^#{1,6}[ \t]+")
private val BOLD_STAR = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)
private val BOLD_UNDERSCORE = Regex("__(.+?)__", RegexOption.DOT_MATCHES_ALL)
private val CODE_SPAN = Regex("`([^`\n]+)`")

/**
 * Single-asterisk emphasis, narrowed to the unambiguous cases.
 *
 * The lookarounds are the whole point: `(?<![*\w])` and `(?![*\w])` keep the marker
 * from matching inside a word, and `(?!\s)`/`(?<!\s)` keep it from swallowing the
 * space on either side of an ordinary multiplication sign.
 */
private val ITALIC_STAR =
    Regex("(?<![*\\w])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![*\\w])")
