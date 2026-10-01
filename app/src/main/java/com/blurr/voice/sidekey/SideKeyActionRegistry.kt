package com.blurr.voice.sidekey

import android.content.Context

/**
 * One thing the Essential Key can be told to do, as the Settings picker shows
 * it.
 *
 * Actions are data rather than an enum so the action list can grow without the
 * settings screen, the prefs format, or the key pipeline changing: a new action
 * is a single [SideKeyActionRegistry.actions] entry, and the four press rows
 * pick it up on their own. Only [id] is ever persisted, so an action that is
 * later removed or renamed degrades to [SideKeyActionRegistry.NONE_ID] rather
 * than breaking the stored mapping.
 *
 * @param id stable string written to prefs; never localise or change it.
 * @param label the row title in the picker.
 * @param description one line explaining what actually happens, shown under the
 *   label in the picker and in the press row's subtitle.
 */
data class SideKeyActionSpec(
    val id: String,
    val label: String,
    val description: String
)

/**
 * Every action a press type can be assigned to, in picker order.
 *
 * The list is intentionally short today: it holds what is actually wired up
 * plus the "do nothing" entry every press needs, and it is the single place to
 * add to when the next action is decided.
 */
object SideKeyActionRegistry {

    /** The stored id that means "this press does nothing". */
    const val NONE_ID = "none"

    /** Stored id for [GOOGLE_LENS_SCREEN]. */
    const val GOOGLE_LENS_SCREEN_ID = "google_lens_screen"

    /** The action every unmapped press resolves to. */
    val NONE = SideKeyActionSpec(
        id = NONE_ID,
        label = "Do nothing",
        description = "Leave this press type alone. The key still wakes the phone if Nothing OS wants it to."
    )

    /**
     * Google Lens on whatever is on screen: the frame is captured and handed to
     * Lens, which searches it visually.
     *
     * Named for what it does rather than for Circle to Search, which it is not.
     * The Circle to Search overlay cannot be started by another app and is bound to
     * a launcher gesture this device's launcher does not implement; see
     * [LensScreenSearch] for the whole story. Same search engine, different door.
     */
    val GOOGLE_LENS_SCREEN = SideKeyActionSpec(
        id = GOOGLE_LENS_SCREEN_ID,
        label = "Google Lens",
        description = "Searches the screen you are on with Google Lens: text, objects, products, places."
    )

    /** Picker order: nothing first, then the real actions. */
    val actions: List<SideKeyActionSpec> = listOf(NONE, GOOGLE_LENS_SCREEN)

    /** @return the spec for [id], or [NONE] when it is missing or no longer known. */
    fun specFor(id: String?): SideKeyActionSpec =
        actions.firstOrNull { it.id == id } ?: NONE

    /**
     * The label for whatever a press is currently mapped to, for the settings
     * row. @param id the stored action id.
     */
    fun labelFor(context: Context, id: String?): String = specFor(id).label
}
