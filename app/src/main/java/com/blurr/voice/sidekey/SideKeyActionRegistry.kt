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

    /** Stored id for [CIRCLE_TO_SEARCH]. */
    const val CIRCLE_TO_SEARCH_ID = "circle_to_search"

    /** The action every unmapped press resolves to. */
    val NONE = SideKeyActionSpec(
        id = NONE_ID,
        label = "Do nothing",
        description = "Leave this press type alone. The key still wakes the phone if Nothing OS wants it to."
    )

    /**
     * Google's Circle to Search on whatever is on screen, brought up by the
     * same home-indicator long-press the user would do by hand.
     */
    val CIRCLE_TO_SEARCH = SideKeyActionSpec(
        id = CIRCLE_TO_SEARCH_ID,
        label = "Circle to Search",
        description = "Opens Google's circle-to-search on this screen so you can drag a circle around anything on it."
    )

    /** Picker order: nothing first, then the real actions. */
    val actions: List<SideKeyActionSpec> = listOf(NONE, CIRCLE_TO_SEARCH)

    /** @return the spec for [id], or [NONE] when it is missing or no longer known. */
    fun specFor(id: String?): SideKeyActionSpec =
        actions.firstOrNull { it.id == id } ?: NONE

    /**
     * The label for whatever a press is currently mapped to, for the settings
     * row. @param id the stored action id.
     */
    fun labelFor(context: Context, id: String?): String = specFor(id).label
}
