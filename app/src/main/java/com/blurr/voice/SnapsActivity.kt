package com.blurr.voice

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.blurr.voice.sidekey.SnapRecord
import com.blurr.voice.sidekey.SnapStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Snaps tab: every side-key screenshot with the one-line history of what
 * the background analysis did with it. Tapping a row opens [SnapsDetailActivity]
 * for the full screenshot and analysis. The list is built from the JSON index
 * written by the snap pipeline, so it shows exactly what was snapped -- which
 * also settles any "did it snap the right screen?" doubt at a glance.
 */
class SnapsActivity : BaseNavigationActivity() {

    private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.US)

    override fun getContentLayoutId(): Int = R.layout.activity_snaps_content

    override fun getCurrentNavItem(): BaseNavigationActivity.NavItem = BaseNavigationActivity.NavItem.SNAPS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(getContentLayoutId())

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // Reflect new snaps taken while the tab sat open in the back stack.
        render()
    }

    private fun render() {
        val records = SnapStore.all(this)
        val container = findViewById<LinearLayout>(R.id.snapsList)
        container.removeAllViews()

        findViewById<TextView>(R.id.snapsEmpty).visibility =
            if (records.isEmpty()) View.VISIBLE else View.GONE

        records.forEach { record ->
            container.addView(buildRow(record))
        }
    }

    private fun buildRow(record: SnapRecord): View {
        val context = this
        val list = findViewById<LinearLayout>(R.id.snapsList)
        val row = LayoutInflater.from(context).inflate(R.layout.item_snap, list, false)

        row.findViewById<ImageView>(R.id.snapThumb).setImageBitmap(loadThumbnail(record.fileName))
        row.findViewById<TextView>(R.id.snapTime).text = timeFormat.format(Date(record.timestampMs))
        row.findViewById<TextView>(R.id.snapStatus).text = statusText(record)
        row.findViewById<TextView>(R.id.snapNote).text = record.note

        row.setOnClickListener {
            startActivity(
                Intent(context, SnapsDetailActivity::class.java)
                    .putExtra(SnapsDetailActivity.EXTRA_FILE_NAME, record.fileName)
            )
        }
        return row
    }

    private fun statusText(record: SnapRecord): String = when (record.outcome) {
        "scheduled" -> "Set ${record.items.size} reminder${if (record.items.size == 1) "" else "s"}"
        "failed" -> "Analysis failed to run"
        else -> "Nothing worth scheduling"
    }

    /** A ~100px decode so the list never holds five full PNGs in memory. */
    private fun loadThumbnail(fileName: String): android.graphics.Bitmap? {
        val file = File(File(filesDir, "snaps"), fileName)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val size = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = when {
                size > 1600 -> 8
                size > 800 -> 4
                size > 400 -> 2
                else -> 1
            }
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        } catch (e: Exception) {
            null
        }
    }
}