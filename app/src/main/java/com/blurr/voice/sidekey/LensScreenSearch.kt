package com.blurr.voice.sidekey

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.blurr.voice.ScreenInteractionService
import java.io.File
import java.io.FileOutputStream

/** How a "search this screen" attempt ended, for logs, tests, and user feedback. */
enum class LensScreenSearchOutcome {
    /** Google's Lens took the screen and is searching it. */
    LENS_OPENED,

    /** The screen could not be captured, or Lens refused the image. */
    FAILED
}

/**
 * Hands whatever is on screen to Google Lens.
 *
 * The obvious thing to reach for here is Circle to Search, and the reason this does
 * not do that is worth recording, because it was tried:
 *
 *  * The Circle to Search overlay has no startable entry point. The Google app
 *    answers `android.intent.action.ASSIST` from a non-exported internal gateway and
 *    draws the overlay in a window of its own, so no third-party app can start it.
 *  * It is brought up by a long press on the home indicator, which an accessibility
 *    service can synthesise -- and doing so was tried on the target device. The
 *    launcher in use (mur) has no assistant gesture bound to the bar, so the
 *    synthesised press was delivered as an ordinary long press to whatever was
 *    underneath, which on the home screen opened the launcher's own long-press menu.
 *    A shortcut that long-presses the user's screen at a fixed point is worse than no
 *    shortcut, so the gesture is gone rather than tuned.
 *
 * What is left is the part of Google's own search stack that *is* reachable: Lens
 * with an image. Same engine, same visual search, same results; the difference is
 * that the region is chosen by handing over the frame rather than by dragging a
 * circle over a live surface. The screenshot is captured by the accessibility
 * service's own API and staged through a FileProvider, so no other app's permission
 * is involved and the image never leaves the device except inside the Intent.
 */
object LensScreenSearch {

    private const val TAG = "LensScreenSearch"

    /** Lens's share entry point: the Google app's own "search this image" activity. */
    private const val LENS_ACTIVITY = "com.google.android.apps.search.lens.LensShareEntryPointActivity"

    private const val LENS_PACKAGE = "com.google.android.googlequicksearchbox"

    /** Where the screenshot is written before being shared. */
    private const val SHARE_DIR = "lens_shares"

    private const val SHARE_NAME = "screen.png"

    /**
     * Captures the screen and starts Lens on it.
     *
     * @param service supplies the screenshot; the service is the only component
     *   here with the privilege to take one.
     * @return what actually happened, so the caller can tell the user rather than
     *   leaving them looking at an unchanged screen.
     */
    suspend fun searchScreen(service: ScreenInteractionService): LensScreenSearchOutcome {
        val bitmap = capture(service) ?: return LensScreenSearchOutcome.FAILED
        val uri = writeShareablePng(service, bitmap) ?: return LensScreenSearchOutcome.FAILED

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The service is not an Activity, so without this the platform throws
            // instead of starting anything: an Intent from a non-Activity context has
            // to name the task it wants.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            component = ComponentName(LENS_PACKAGE, LENS_ACTIVITY)
        }
        return try {
            service.startActivity(intent)
            Log.i(TAG, "Lens started on the current screen")
            LensScreenSearchOutcome.LENS_OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "Lens refused the share intent", t)
            LensScreenSearchOutcome.FAILED
        }
    }

    /** @return the current screen, or null when the platform would not hand one over. */
    private suspend fun capture(service: ScreenInteractionService): Bitmap? = try {
        service.captureScreenshot()
    } catch (t: Throwable) {
        Log.w(TAG, "Could not capture the screen", t)
        null
    }

    /**
     * Writes [bitmap] into the cache and hands back a content:// URI another app may
     * read. A file:// path is rejected by the receiving app on modern Android, so the
     * FileProvider URI is what makes the share work at all.
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
}
