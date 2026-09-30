package com.blurr.voice.sidekey

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.blurr.voice.ScreenInteractionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** How a Circle to Search attempt ended, for logs, tests, and user feedback. */
enum class CircleToSearchOutcome {
    /** The gesture opened Google's overlay. */
    OVERLAY_OPENED,

    /** The gesture did nothing, so the screenshot was handed to real Lens. */
    LENS_FALLBACK,

    /** Neither route could run (no screenshot, no Lens installed, no permission). */
    FAILED
}

/**
 * Brings up Google's own Circle to Search on whatever is on screen.
 *
 * Circle to Search has no launchable entry point on Android: the Google app
 * answers `android.intent.action.ASSIST` through a non-exported internal gateway
 * and hosts the overlay in a window of its own, so no third-party app can start
 * it with an intent. What the system itself does is a long press on the home
 * indicator, and an accessibility service can synthesise exactly that gesture --
 * so that is the primary route here, and it is the real overlay, not a
 * reimplementation of it.
 *
 * Two things can stop it. The launcher may not implement the search gesture at
 * all, and the gesture may be claimed by whoever holds the assistant role. So
 * the attempt is verified instead of assumed: after the gesture, the service's
 * window list is checked for a Google search window, and if there is none the
 * captured screen is handed to real Lens (`LensShareEntryPointActivity`), which
 * is an image search over the same pixels rather than a live circle overlay but
 * is still Google's own search stack.
 */
object CircleToSearch {

    private const val TAG = "CircleToSearch"

    /** How long the synthesised press is held; the system gesture lives around 500ms. */
    const val GESTURE_HOLD_MS = 700L

    /** How long to wait after the press before deciding whether the overlay opened. */
    const val VERIFY_DELAY_MS = 800L

    /** Fragments that identify a Google search / Lens window. */
    private val OVERLAY_MARKERS = listOf("googlequicksearchbox", "lens")

    /** Lens's share entry point, the fallback's target. */
    private const val LENS_ACTIVITY = "com.google.android.apps.search.lens.LensShareEntryPointActivity"

    private const val LENS_PACKAGE = "com.google.android.googlequicksearchbox"

    /** Where the fallback image is written before being shared. */
    private const val SHARE_DIR = "lens_shares"

    private const val SHARE_NAME = "screen.png"

    /**
     * @return true when any of [packages] belongs to Google's search or Lens
     *   surfaces. Pure, so the "did it work" rule is unit-tested rather than
     *   inferred on a phone.
     */
    fun looksLikeSearchOverlay(packages: List<String>): Boolean =
        packages.any { candidate -> OVERLAY_MARKERS.any { candidate.contains(it, ignoreCase = true) } }

    /**
     * Runs the whole attempt: press, verify, and fall back to Lens if needed.
     *
     * @param service the running accessibility service, which supplies both the
     *   gesture dispatcher and the window list.
     */
    suspend fun trigger(service: ScreenInteractionService): CircleToSearchOutcome {
        val dispatched = service.dispatchGestureBarLongPress(GESTURE_HOLD_MS)
        Log.d(TAG, "Gesture-bar long press dispatched=$dispatched")

        if (dispatched) {
            // The overlay animates in; give it a moment, then look for it. A
            // delay is the only honest way to see whether the system answered.
            delay(VERIFY_DELAY_MS)
            if (looksLikeSearchOverlay(service.visibleWindowPackages())) {
                Log.d(TAG, "Circle to Search overlay is up")
                return CircleToSearchOutcome.OVERLAY_OPENED
            }
            Log.i(TAG, "No Google search window after the gesture; falling back to Lens")
        }

        return withContext(Dispatchers.Main) {
            launchLensOnScreen(service)
        }
    }

    /**
     * Screenshots what is on screen and hands the image to real Google Lens.
     *
     * @return [CircleToSearchOutcome.LENS_FALLBACK] once Lens was started,
     *   [CircleToSearchOutcome.FAILED] when the image could not be produced or
     *   Lens refused the intent.
     */
    private suspend fun launchLensOnScreen(service: ScreenInteractionService): CircleToSearchOutcome {
        val bitmap: Bitmap? = try {
            service.captureScreenshot()
        } catch (t: Throwable) {
            Log.w(TAG, "Screenshot for the Lens fallback failed", t)
            null
        }
        if (bitmap == null) {
            Log.w(TAG, "No screenshot; Lens fallback cannot run")
            return CircleToSearchOutcome.FAILED
        }
        val uri = writeShareablePng(service, bitmap)
        if (uri == null) return CircleToSearchOutcome.FAILED

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            component = android.content.ComponentName(LENS_PACKAGE, LENS_ACTIVITY)
        }
        return try {
            service.startActivity(intent)
            Log.i(TAG, "Lens fallback started")
            CircleToSearchOutcome.LENS_FALLBACK
        } catch (t: Throwable) {
            Log.w(TAG, "Lens refused the share intent", t)
            CircleToSearchOutcome.FAILED
        }
    }

    /**
     * Writes [bitmap] into the cache and hands back a content:// URI another app
     * may read. A file:// path would be rejected by the receiving app on modern
     * Android, so the FileProvider URI is what makes the share work at all.
     */
    private fun writeShareablePng(context: Context, bitmap: Bitmap): Uri? = try {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val file = File(dir, SHARE_NAME)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (t: Throwable) {
        Log.w(TAG, "Could not stage the screenshot for Lens", t)
        null
    }

    /** Posts [block] on the main looper; used by the settings screen's test button. */
    fun onMain(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)
}
