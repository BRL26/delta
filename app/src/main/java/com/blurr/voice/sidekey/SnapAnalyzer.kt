package com.blurr.voice.sidekey

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.blurr.voice.ScreenInteractionService
import com.blurr.voice.api.GeminiApi
import com.blurr.voice.reminders.ReminderScheduler
import com.blurr.voice.reminders.ReminderTimeParser
import com.blurr.voice.v2.llm.TextPart
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * The single-press side-key flow: snap the screen, save it, analyse it in the
 * background for anything the user would want to be reminded about, schedule
 * what is found, and notify with a summary of what was actually done.
 *
 * Nothing here takes over the screen: no popup, no pill, no speech. The phone
 * stays exactly where it was and the notification is the whole answer, which
 * is what makes the key usable while the user is busy doing the thing the
 * screen shows. Consecutive presses inside [THROTTLE_MS] are ignored, so a
 * stray double-press from the key's touch debounce cannot schedule twice.
 */
object SnapAnalyzer {

    private const val TAG = "SnapAnalyzer"
    private const val THROTTLE_MS = 4_000L

    private val lastSnapAt = AtomicLong(0L)

    private val snapshotFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val humanFormat = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US)

    private val ANALYSIS_PROMPT = """
        You are Delta's side-key screen analyst. A screenshot of the phone screen is attached.
        Reply ONLY with JSON Lines. Each line is one time-sensitive item the user would want
        a reminder for: appointments, events, deadlines, bills, deliveries, and any date or
        time shown on the screen.
        Line format, exactly:
        {"what": "Doctor appointment", "when": "Oct 9 at 2pm"}
        "when" is plain natural language: "in 2 hours", "tomorrow at 9am", "October 9 at 2pm".
        Only include items with an explicit or clearly implied date or time. If none, reply
        with a single {} line.
        No prose. No explanations. No markdown. JSON lines only.
    """.trimIndent()

    /** Short follow-up used when a model ignores the format and writes prose. */
    private val CORRECTION_PROMPT = """
        Your previous reply ignored the required format. Reply ONLY with JSON Lines for
        time-sensitive items on the attached screen, or a single {} line if there are none.
        No prose, no explanations, no markdown.
    """.trimIndent()

    /**
     * Runs the whole flow. Safe to call from any dispatcher; [screenshot] is a
     * suspend function on the accessibility service and the model call already
     * manages its own I/O.
     */
    suspend fun snapAndSchedule(context: Context, screenService: ScreenInteractionService) {
        val now = System.currentTimeMillis()
        val last = lastSnapAt.get()
        if (now - last < THROTTLE_MS) {
            Log.d(TAG, "Throttling: a snap happened ${now - last}ms ago.")
            return
        }
        if (!lastSnapAt.compareAndSet(last, now)) return

        // 1. Capture and save the screen.
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            screenService.captureScreenshot()
        } else {
            null
        }
        val savedFile = bitmap?.let { saveSnap(context, it) }
        if (savedFile.isNullOrEmpty()) {
            Log.w(TAG, "No screenshot captured; nothing to analyse.")
            SideKeyNotifications.notify(
                context,
                "Side Key snap",
                "Couldn't capture the screen — it may be locked or busy."
            )
            return
        }
        val fileName = savedFile.substringAfterLast('/')

        // 2. Analyse in the background with a vision-capable model. A null
        //    result means the analysis itself failed (no provider / no
        //    network); an empty list means it ran but found nothing.
        val items = analyse(context, bitmap)
        if (items == null) {
            SideKeyNotifications.notify(
                context,
                "Side Key snap",
                "Analysis couldn't run — no AI provider configured or no network. " +
                    "The snap was still saved."
            )
            SnapStore.add(
                context,
                SnapRecord(fileName, now, "failed", emptyList(), emptyList(), "Analysis couldn't run.")
            )
            return
        }

        // 3. Schedule what the analysis found.
        if (items.isEmpty()) {
            val body = "No time-sensitive details worth scheduling were found on that screen." +
                "\nSnap saved: $savedFile"
            SideKeyNotifications.notify(context, "Side Key snap", body)
            SnapStore.add(
                context,
                SnapRecord(fileName, now, "nothing", emptyList(), emptyList(), body)
            )
            return
        }

        val scheduled = mutableListOf<SnapItem>()
        val skipped = mutableListOf<String>()
        val nowMillis = System.currentTimeMillis()
        items.forEach { (what, whenPhrase) ->
            val triggerAt = ReminderTimeParser.parse(whenPhrase, nowMillis)
                ?: ReminderTimeParser.parse("$whenPhrase $what", nowMillis)
            if (triggerAt != null) {
                val id = ReminderScheduler.schedule(context, what, triggerAt)
                Log.d(TAG, "Scheduled reminder #$id '$what' at $triggerAt")
                scheduled.add(SnapItem(what, whenPhrase, id, triggerAt))
            } else {
                Log.d(TAG, "Could not pin a time for '$what' from '$whenPhrase'")
                skipped.add(what)
            }
        }

        // 4. Tell the user exactly what happened, and keep the same text in
        //    the snap history so the tab and the notification always agree.
        val body = buildSummary(
            scheduled.map { it.what to it.triggerAt }, skipped, savedFile
        )
        val title = if (scheduled.isEmpty()) {
            "Side Key snap — nothing set"
        } else {
            "Side Key snap — ${scheduled.size} reminder${if (scheduled.size == 1) "" else "s"} set"
        }
        SideKeyNotifications.notify(context, title, body)
        SnapStore.add(
            context,
            SnapRecord(
                fileName = fileName,
                timestampMs = now,
                outcome = if (scheduled.isEmpty()) "nothing" else "scheduled",
                items = scheduled,
                skipped = skipped,
                note = body
            )
        )
    }

    /**
     * Runs the vision analysis and returns the extracted items (what / when
     * phrase), or null when the model call itself failed. Small models are
     * prone to answering a screenshot with a prose description instead of the
     * requested JSON, so a response that parses to nothing is retried once
     * with a terse correction before giving up on the screen.
     */
    private suspend fun analyse(
        context: Context,
        bitmap: Bitmap
    ): List<Pair<String, String>>? {
        var chat = listOf("user" to listOf<Any>(TextPart(ANALYSIS_PROMPT)))
        repeat(3) { attempt ->
            val response = GeminiApi.generateContent(
                chat = chat,
                images = listOf(bitmap),
                context = context
            ) ?: return null

            if (response.trim() == "{}") {
                if (attempt > 0) Log.d(TAG, "Analysis cleanly found nothing ({}) after correction.")
                return emptyList()
            }
            val items = parseItems(response)
            if (items.isNotEmpty()) {
                Log.d(TAG, "Analysis returned ${items.size} item(s) on attempt ${attempt + 1}.")
                return items
            }
            if (attempt < 2 && response.isNotBlank()) {
                Log.w(TAG, "Model ignored the JSON format (${response.take(80)}…); retrying.")
                chat = chat + listOf("user" to listOf<Any>(TextPart(CORRECTION_PROMPT)))
            }
        }
        Log.w(TAG, "Analysis never produced structured items; treating the screen as empty.")
        return emptyList()
    }

    private fun saveSnap(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "snaps").apply { mkdirs() }
        val file = File(dir, "snap-${snapshotFormat.format(Date())}.png")
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snap", e)
            ""
        }
    }

    /**
     * The model emits one JSON object per line. Lenient parsing: find the first
     * '{' to the last '}' on each line and read "what"/"when", so an extra
     * sentence around the object does not lose the item.
     */
    private fun parseItems(response: String): List<Pair<String, String>> {
        val items = mutableListOf<Pair<String, String>>()
        response.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("{")) return@forEach
            try {
                val obj = JSONObject(line)
                val what = obj.optString("what").trim()
                val whenPhrase = obj.optString("when").trim()
                if (what.isNotEmpty() && whenPhrase.isNotEmpty()) {
                    items.add(what to whenPhrase)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Skipping unparseable item line: $line")
            }
        }
        return items
    }

    private fun buildSummary(
        scheduled: List<Pair<String, Long>>,
        skipped: List<String>,
        savedFile: String?
    ): String {
        val lines = mutableListOf<String>()
        scheduled.forEach { (what, at) ->
            lines.add("• $what — ${humanFormat.format(Date(at))}")
        }
        if (skipped.isNotEmpty()) {
            lines.add("Couldn't pin a time for: ${skipped.joinToString(", ")}")
        }
        if (lines.isEmpty()) {
            lines.add("No reminders were set.")
        }
        if (savedFile.isNullOrEmpty().not()) {
            lines.add("Snap saved: $savedFile")
        }
        return lines.joinToString("\n")
    }
}