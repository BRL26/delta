package com.blurr.voice.sidekey

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A single time-sensitive item extracted from a snapped screen. */
data class SnapItem(
    val what: String,
    val whenPhrase: String,
    val scheduledId: Int,
    val triggerAt: Long
)

/**
 * One side-key snap and what the background analysis did with it. [outcome] is
 * one of "scheduled" (at least one reminder was set), "nothing" (the analysis
 * ran but found no time-sensitive details), or "failed" (the screen could not
 * be captured or analysed). [note] is the same text the summary notification
 * showed, so the tab and the notification always agree.
 */
data class SnapRecord(
    val fileName: String,
    val timestampMs: Long,
    val outcome: String,
    val items: List<SnapItem>,
    val skipped: List<String>,
    val note: String
)

/**
 * Persists the side-key snap history as a small JSON index next to the PNGs in
 * `files/snaps/`, newest first. The index and the images live in the same
 * private app directory, so a screenshot never leaves the device except for
 * the one-draw vision analysis the snap is built on.
 */
object SnapStore {

    private const val TAG = "SnapStore"
    private val lock = Any()

    private fun indexFile(context: Context): File =
        File(File(context.filesDir, "snaps"), "index.json")

    fun all(context: Context): List<SnapRecord> {
        synchronized(lock) {
            val file = indexFile(context)
            if (!file.exists()) return emptyList()
            return try {
                val array = JSONArray(file.readText())
                buildList {
                    for (i in 0 until array.length()) {
                        runCatching { add(parse(array.getJSONObject(i))) }
                            .onFailure { Log.e(TAG, "Skipping corrupt snap record", it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read snap index", e)
                emptyList()
            }
        }
    }

    fun add(context: Context, record: SnapRecord) {
        synchronized(lock) {
            val current = all(context)
            val array = JSONArray()
            current.take(200).forEach { array.put(toJson(it)) }
            val next = JSONArray().apply { put(toJson(record)) }
            repeat(array.length()) { next.put(array.get(it)) }
            try {
                indexFile(context).writeText(next.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write snap index", e)
            }
        }
    }

    fun delete(context: Context, fileName: String) {
        synchronized(lock) {
            val kept = all(context).filterNot { it.fileName == fileName }
            val array = JSONArray()
            kept.forEach { array.put(toJson(it)) }
            try {
                indexFile(context).writeText(array.toString())
                File(File(context.filesDir, "snaps"), fileName).delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete snap $fileName", e)
            }
        }
    }

    private fun parse(obj: JSONObject): SnapRecord {
        val itemsArray = obj.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                add(
                    SnapItem(
                        what = item.optString("what"),
                        whenPhrase = item.optString("when"),
                        scheduledId = item.optInt("scheduledId"),
                        triggerAt = item.optLong("triggerAt")
                    )
                )
            }
        }
        val skippedArray = obj.optJSONArray("skipped") ?: JSONArray()
        val skipped = buildList { for (i in 0 until skippedArray.length()) add(skippedArray.getString(i)) }
        return SnapRecord(
            fileName = obj.getString("file"),
            timestampMs = obj.getLong("timestampMs"),
            outcome = obj.optString("outcome", "nothing"),
            items = items,
            skipped = skipped,
            note = obj.optString("note", "")
        )
    }

    private fun toJson(record: SnapRecord): JSONObject = JSONObject()
        .put("file", record.fileName)
        .put("timestampMs", record.timestampMs)
        .put("outcome", record.outcome)
        .put(
            "items", JSONArray().apply {
                record.items.forEach {
                    put(
                        JSONObject()
                            .put("what", it.what)
                            .put("when", it.whenPhrase)
                            .put("scheduledId", it.scheduledId)
                            .put("triggerAt", it.triggerAt)
                    )
                }
            }
        )
        .put(
            "skipped", JSONArray().apply { record.skipped.forEach { put(it) } }
        )
        .put("note", record.note)
}