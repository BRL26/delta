package com.blurr.voice.sidekey

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Actions that need a *target* cannot be fixed entries in
 * [SideKeyActionRegistry.actions]: what the press should open is decided on the phone
 * by the user, not by the build. So a targeted action is stored as one id with its
 * target appended after a colon --
 *
 *     open_app:com.android.settings
 *     open_link:geo:0,0?q=coffee
 *
 * Keeping it inside the same id string is what lets the rest of the system stay as it
 * already is: the prefs still hold one string, [EssentialKeyMapper] still validates one
 * string, and the registry still degrades an id it does not recognise to "do nothing"
 * instead of throwing. A separate table keyed by package would be a second thing to keep
 * in sync for no benefit.
 *
 * Only the prefix is a contract. The remainder is opaque here and is handed to [Intent]
 * untouched, so a new link scheme needs no change to this file.
 */
const val OPEN_APP_PREFIX = "open_app:"
const val OPEN_LINK_PREFIX = "open_link:"

/** An action's chosen destination: an app to launch, or a link to open. */
sealed interface SideKeyTarget {

    /** The action id that stores this target, e.g. `open_app:com.example`. */
    val encoded: String

    /** Launch [packageName] at its own launcher entry. */
    data class OpenApp(val packageName: String) : SideKeyTarget {
        override val encoded: String get() = OPEN_APP_PREFIX + packageName
    }

    /** Open [uri], whatever scheme it carries, letting the system pick a handler. */
    data class OpenLink(val uri: String) : SideKeyTarget {
        override val encoded: String get() = OPEN_LINK_PREFIX + uri
    }
}

/**
 * Reads, writes and runs the target carried by a side-key action id.
 *
 * Parsing is deliberately strict about only two things -- the prefix, and the target
 * being non-blank -- so that a package or a URI with unusual characters in it survives
 * a round trip through prefs untouched. Everything after the prefix is validated by the
 * thing that actually uses it, at the moment it is used.
 */
object SideKeyTargets {

    /** @return the target encoded in [id], or null when [id] is not a targeted action. */
    fun parse(id: String?): SideKeyTarget? = when {
        id == null -> null
        id.startsWith(OPEN_APP_PREFIX) ->
            id.removePrefix(OPEN_APP_PREFIX).takeIf { it.isNotBlank() }
                ?.let { SideKeyTarget.OpenApp(it) }
        id.startsWith(OPEN_LINK_PREFIX) ->
            id.removePrefix(OPEN_LINK_PREFIX).takeIf { it.isNotBlank() }
                ?.let { SideKeyTarget.OpenLink(it) }
        else -> null
    }

    /** Whether [id] names a targeted action rather than a fixed catalogue entry. */
    fun isTargeted(id: String?): Boolean = parse(id) != null

    /**
     * Launches [target] off the main thread's back stack.
     *
     * @return true when the phone accepted it. False covers both "nothing is installed
     *   for this" and "the system refused the intent"; the caller has no useful way to
     *   tell those apart and neither does the user.
     */
    fun launch(context: Context, target: SideKeyTarget): Boolean = when (target) {
        is SideKeyTarget.OpenApp -> launchPackage(context, target.packageName)
        is SideKeyTarget.OpenLink -> launchLink(context, target.uri)
    }

    /** The name to show a press row for an app target, e.g. "Open Camera". */
    fun labelFor(context: Context, target: SideKeyTarget): String = when (target) {
        is SideKeyTarget.OpenApp -> "Open ${appLabel(context, target.packageName)}"
        is SideKeyTarget.OpenLink -> "Open link"
    }

    /**
     * The installed app's own name.
     *
     * Falls back to the package name, which is ugly but still identifies the app, and
     * which is also what happens if it is uninstalled after being mapped -- the press
     * then has somewhere to fail rather than a blank row.
     */
    fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun launchPackage(context: Context, packageName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        // Required: this runs from a service, which has no task of its own to start
        // into. Without it the launch throws rather than opening the app.
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launch) }.isSuccess
    }

    private fun launchLink(context: Context, uri: String): Boolean {
        // A schemeless string such as "example.com" parses fine but can never be
        // dispatched, so it is rejected here instead of as an ActivityNotFoundException.
        if (runCatching { Uri.parse(uri).scheme }.getOrNull().isNullOrBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}