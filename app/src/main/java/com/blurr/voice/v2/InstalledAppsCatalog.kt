package com.blurr.voice.v2

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * Enumerates the apps that can actually be launched on this device.
 *
 * The agent's prompt previously contained only the *current* screen, so it had
 * no idea which apps were installed. If the user asked for an app that was not
 * visible in the current accessibility tree, the model would guess - which is
 * how "open ChatGPT" ended up opening Claude.
 *
 * This catalog is injected into the system prompt so the model always knows
 * the real, launchable app list and can call `open_app` with an exact name.
 */
object InstalledAppsCatalog {

    private const val TAG = "InstalledAppsCatalog"

    /** Apps we never want to suggest opening. */
    private val BLOCKED_PACKAGES = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.shell",
        "com.android.providers.settings",
        "android"
    )

    data class LaunchableApp(
        val label: String,
        val packageName: String
    )

    /**
     * Returns the launchable apps, sorted by label. Cached because package
     * enumeration is slow and the list rarely changes mid-session.
     */
    fun getLaunchableApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager

        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved: List<ResolveInfo> = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launchIntent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launchIntent, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query launchable apps: ${e.message}")
            emptyList()
        }

        val seen = HashSet<String>(resolved.size)
        val out = ArrayList<LaunchableApp>(resolved.size)

        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg in BLOCKED_PACKAGES) continue
            if (!seen.add(pkg)) continue

            val label = try {
                info.loadLabel(pm)?.toString()?.trim().orEmpty()
            } catch (e: Exception) {
                ""
            }

            // A blank label is useless as a target for open_app; fall back to
            // the last package segment so the entry is still actionable.
            val finalLabel = label.ifBlank {
                pkg.substringAfterLast('.')
            }

            out.add(LaunchableApp(finalLabel, pkg))
        }

        return out.sortedBy { it.label.lowercase() }
    }

    /**
     * Renders the catalog for the system prompt. Kept compact - the prompt is
     * resent on every step, so a huge list would burn tokens for no benefit.
     */
    fun describeForPrompt(context: Context, maxEntries: Int = 120): String {
        val apps = getLaunchableApps(context)
        if (apps.isEmpty()) return ""

        val shown = apps.take(maxEntries)
        val body = buildString {
            append("<installed_apps>\n")
            append("These apps are installed and can be opened at any time with the ")
            append("\"open_app\" action, regardless of whether they are visible on the ")
            append("current screen. Use the exact label shown here.\n")
            shown.forEach { app ->
                append("- ${app.label} (${app.packageName})\n")
            }
            if (apps.size > shown.size) {
                append("... and ${apps.size - shown.size} more.\n")
            }
            append("</installed_apps>")
        }

        return body
    }
}
