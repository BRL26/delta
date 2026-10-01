package com.blurr.voice.sidekey

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import com.blurr.voice.ScreenInteractionService
import kotlinx.coroutines.delay

/** How a Circle to Search attempt ended, for logs, tests, and user feedback. */
enum class CircleToSearchOutcome {

    /** Circle to Search is up and searching the screen. */
    OPENED,

    /** The trigger surface was not there, or Circle to Search did not come up. */
    FAILED
}

/**
 * Opens Circle to Search by way of AKS-Labs CircleToSearch, without launching it.
 *
 * ## Why not just start it
 *
 * AKS-Labs Circle to Search (GPL, `github.com/AKS-Labs/CircleToSearch`) is the only
 * Circle to Search on this device that a third-party app can actually reach -- the
 * real Google one cannot be started at all, and [LensScreenSearch] documents that
 * dead end. Its own manifest is, however, closed to us:
 *
 *  * `OverlayActivity` and `TileTriggerActivity`, which is the real deal, are
 *    `android:exported="false"`.
 *  * `CircleToSearchTileService` and `AssistSessionService` are exported but
 *    guarded by `BIND_QUICK_SETTINGS_TILE` / `BIND_VOICE_INTERACTION`, which are
 *    signature permissions only the platform holds.
 *  * `MainActivity` is exported and startable, and is a setup screen.
 *  * The app registers no broadcast receivers at all, so there is nothing to send.
 *
 * ## What is reachable
 *
 * The app's *accessibility service* keeps its own touch-triggered overlay window on
 * screen, and that window is the door. AKS' accessibility service is a plain
 * `TYPE_ACCESSIBILITY_OVERLAY` window, which the platform makes visible to every
 * accessibility service rather than only to its owner -- so this service can find
 * it, learn where it is, and tap it. The tap is handled inside AKS, which does its
 * own `takeScreenshot`, crops to its own overlay segments, and starts its own
 * `OverlayActivity`.
 *
 * So the capture, the circle, the OCR and the search engines all stay AKS'. Delta
 * contributes a double tap and then gets out of the way. Nothing is reimplemented
 * and no screenshot passes through here.
 *
 * ## Locating the trigger
 *
 * The window's position comes from [Service.windows] rather than being assumed,
 * because AKS lets the user move and resize its trigger segments. If the window
 * cannot be read -- a stripped build, a platform that withholds overlay windows --
 * the tap falls back to [DEFAULT_TRIGGER_HEIGHT_PX], which is the size AKS ships
 * with, so the common case still works.
 */
object AksCircleToSearch {

    private const val TAG = "AksCircleToSearch"

    /** Package of the AKS app, `github.com/AKS-Labs/CircleToSearch`, GPL-3.0. */
    const val PACKAGE = "com.akslabs.circletosearch"

    /**
     * Height of AKS' out-of-the-box trigger strip, in pixels.
     *
     * Its shipped default overlay segment is `OverlaySegment(width = 1080)`, and
     * [OverlaySegment]'s own defaults put it at the top-left with a height of 60.
     * Only used when the real window cannot be found.
     */
    private const val DEFAULT_TRIGGER_HEIGHT_PX = 60

    /** How long each tap of the double tap is held. Long enough to register as a tap. */
    private const val TAP_MS = 40L

    /**
     * Gap between the two taps.
     *
     * Comfortably inside the 300ms double-tap timeout of the framework's own
     * `GestureDetector`, which is what AKS listens with, while still being far
     * enough apart that neither tap can be read as a triple tap -- AKS also counts
     * raw `DOWN` events and fires at three.
     */
    private const val TAP_GAP_MS = 120L

    /**
     * How long to wait before deciding whether it worked.
     *
     * AKS has to capture the screen through the accessibility API, crop it, and
     * start its own activity before a window of its own appears, which takes
     * longer than an ordinary activity launch.
     */
    private const val SETTLE_MS = 1400L

    /**
     * Taps Circle to Search's trigger so it captures the screen and opens its own
     * circle UI.
     *
     * @param service supplies the window list and the gesture dispatch.
     * @return whether Circle to Search actually came up. Reporting honestly matters
     *   here: the press is silent, so without this the user cannot tell a working
     *   key from one that did nothing.
     */
    suspend fun trigger(service: ScreenInteractionService): CircleToSearchOutcome {
        val target = triggerPoint(service)

        if (!doubleTap(service, target[0], target[1])) {
            Log.w(TAG, "The platform refused the double tap")
            return CircleToSearchOutcome.FAILED
        }

        delay(SETTLE_MS)
        if (isShowing(service)) {
            Log.i(TAG, "Circle to Search is up")
            return CircleToSearchOutcome.OPENED
        }
        Log.w(TAG, "Tapped the trigger but Circle to Search never came up")
        return CircleToSearchOutcome.FAILED
    }

    /**
     * @return the point to double tap, as x/y. Prefers the real window so a moved
     *   or resized trigger still works, and falls back to the position AKS ships
     *   with, so there is always somewhere to aim.
     */
    private fun triggerPoint(service: ScreenInteractionService): FloatArray {
        val window = triggerWindow(service)
        if (window != null) {
            val bounds = safeBounds(window)
            if (bounds != null && !bounds.isEmpty) {
                Log.i(TAG, "Tapping the trigger window at $bounds")
                return floatArrayOf(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }

        // No window to aim at, so aim at where AKS puts one by default. Only the
        // strip's height is assumed; the width is always the whole display, so
        // centring horizontally needs no assumption at all.
        val metrics = service.resources.displayMetrics
        val fallback = Rect(0, 0, metrics.widthPixels, DEFAULT_TRIGGER_HEIGHT_PX)
        Log.i(TAG, "No trigger window found; falling back to the default strip $fallback")
        return floatArrayOf(fallback.exactCenterX(), fallback.exactCenterY())
    }

    /**
     * @return AKS' touch-triggered overlay window, or null.
     *
     * Filtered on the window *type* first because an accessibility overlay carries
     * no activity and therefore no reliable package on the window itself; the
     * package is read off the node tree AKS' service publishes for it. That read is
     * the fragile part, which is why [triggerPoint] can do without it.
     */
    private fun triggerWindow(service: ScreenInteractionService): AccessibilityWindowInfo? {
        val overlays = try {
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the window list", t)
            return null
        }
        if (overlays.isEmpty()) return null

        // Log what was actually on offer. When this cannot find AKS the reason is
        // almost always here, and it is otherwise invisible from outside the app.
        for (candidate in overlays) {
            val owner = try {
                candidate.root?.packageName?.toString()
            } catch (t: Throwable) {
                "unreadable: ${t.message}"
            }
            Log.i(TAG, "Overlay window ${safeBounds(candidate)} owned by $owner")
        }

        return overlays.firstOrNull { candidate ->
            try {
                candidate.root?.packageName?.toString() == PACKAGE
            } catch (t: Throwable) {
                false
            }
        }
    }

    /**
     * @return [window]'s on-screen bounds, or null if the platform would not hand
     *   them over. An accessibility overlay that has been torn down between being
     *   listed and being asked throws rather than answering, so this cannot be
     *   assumed safe. Written to through an out-parameter because that is the only
     *   shape the API has.
     */
    private fun safeBounds(window: AccessibilityWindowInfo): Rect? = try {
        Rect().also { window.getBoundsInScreen(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not read a window's bounds", t)
        null
    }

    /**
     * @return whether Circle to Search has put an activity of its own on screen
     *   yet, which is the only way to know the tap was acted on rather than
     *   swallowed.
     */
    private fun isShowing(service: ScreenInteractionService): Boolean = try {
        service.windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                try {
                    window.root?.packageName?.toString() == PACKAGE
                } catch (t: Throwable) {
                    false
                }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not check whether Circle to Search opened", t)
        false
    }

    /**
     * Dispatches both taps as one gesture.
     *
     * Two strokes in a single dispatch rather than two dispatches with a sleep
     * between them: the timing is then exact and unaffected by whatever else the
     * main thread is doing, and the second tap cannot be delayed past the double-tap
     * timeout by a busy frame.
     */
    private fun doubleTap(service: ScreenInteractionService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS))
            .addStroke(GestureDescription.StrokeDescription(path, TAP_GAP_MS, TAP_MS))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
}