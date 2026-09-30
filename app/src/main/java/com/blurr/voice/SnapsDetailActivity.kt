package com.blurr.voice

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import com.blurr.voice.sidekey.SnapStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen view of one snap: the screenshot itself, and the analysis text
 * for it (what was extracted and scheduled, or why nothing was). The same
 * note text the summary notification displayed, so the two never diverge.
 */
class SnapsDetailActivity : BaseNavigationActivity() {

    companion object {
        const val EXTRA_FILE_NAME = "snap_file_name"
    }

    private val timeFormat = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US)
    private val reminderFormat = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US)

    override fun getContentLayoutId(): Int = R.layout.activity_snaps_detail

    override fun getCurrentNavItem(): BaseNavigationActivity.NavItem = BaseNavigationActivity.NavItem.SNAPS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getContentLayoutId())

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: run { finish(); return }
        render(fileName)
    }

    private fun render(fileName: String) {
        val record = SnapStore.all(this).firstOrNull { it.fileName == fileName }
        if (record == null) {
            findViewById<TextView>(R.id.snapNote).text =
                "This snap's record is missing (the file may have been cleared)."
            return
        }

        findViewById<ImageView>(R.id.snapFull).setImageBitmap(loadScreenshot(fileName))
        findViewById<TextView>(R.id.snapTime).text = timeFormat.format(Date(record.timestampMs))
        findViewById<TextView>(R.id.snapStatus).text = statusText(record)

        val itemsText = buildString {
            if (record.items.isEmpty()) {
                append("Nothing was set from this screen.")
            }
            record.items.forEach { item ->
                append("• ${item.what}")
                if (item.whenPhrase.isNotBlank()) append(" — ${item.whenPhrase}")
                append("\n  Reminder #${item.scheduledId} for ${reminderFormat.format(Date(item.triggerAt))}")
            }
            if (record.skipped.isNotEmpty()) {
                append("\nCouldn't pin a time for: ${record.skipped.joinToString(", ")}")
            }
        }
        findViewById<TextView>(R.id.snapItems).text = itemsText
        findViewById<TextView>(R.id.snapNote).text = record.note
    }

    private fun statusText(record: com.blurr.voice.sidekey.SnapRecord): String = when (record.outcome) {
        "scheduled" -> "Set ${record.items.size} reminder${if (record.items.size == 1) "" else "s"}"
        "failed" -> "Analysis failed to run"
        else -> "Nothing worth scheduling"
    }

    /** A half-size decode is plenty for the screen and avoids OOM on tall shots. */
    private fun loadScreenshot(fileName: String): android.graphics.Bitmap? {
        val file = File(File(filesDir, "snaps"), fileName)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = 2
            })
        } catch (e: Exception) {
            null
        }
    }
}