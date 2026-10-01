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
 * [actions] holds the actions that need no further choice, plus the "do nothing"
 * entry every press needs, and it is the single place to add to when the next
 * action is decided. The two actions that *do* need a further choice -- opening a
 * chosen app, opening a chosen link -- are not in it, because what they open is
 * decided on the phone; they live as ids encoded by [SideKeyTargets] and are
 * resolved by [specFor].
 */
object SideKeyActionRegistry {

    /** The stored id that means "this press does nothing". */
    const val NONE_ID = "none"

    /** Stored id for [CIRCLE_TO_SEARCH]. */
    const val CIRCLE_TO_SEARCH_ID = "circle_to_search"

    /** Stored id for [GOOGLE_LENS_SCREEN]. */
    const val GOOGLE_LENS_SCREEN_ID = "google_lens_screen"

    /** Stored id for [TOGGLE_FLASHLIGHT]. */
    const val TOGGLE_FLASHLIGHT_ID = "toggle_flashlight"

    /** Stored id for [OPEN_QUICK_SETTINGS]. */
    const val OPEN_QUICK_SETTINGS_ID = "open_quick_settings"

    /** Stored id for [MEDIA_PLAY_PAUSE]. */
    const val MEDIA_PLAY_PAUSE_ID = "media_play_pause"

    /** Stored id for [MEDIA_NEXT]. */
    const val MEDIA_NEXT_ID = "media_next"

    /** Stored id for [MEDIA_PREVIOUS]. */
    const val MEDIA_PREVIOUS_ID = "media_previous"

    /** The action every unmapped press resolves to. */
    val NONE = SideKeyActionSpec(
        id = NONE_ID,
        label = "Do nothing",
        description = "Leave this press type alone. The key still wakes the phone if Nothing OS wants it to."
    )

    /**
     * AKS-Labs Circle to Search on whatever is on screen, with the circle.
     *
     * Not a shortcut into AKS' app -- [AksCircleToSearch] taps AKS' own trigger so
     * that AKS captures the screen and draws its own circle. Needs the AKS app
     * installed and its accessibility service turned on.
     */
    val CIRCLE_TO_SEARCH = SideKeyActionSpec(
        id = CIRCLE_TO_SEARCH_ID,
        label = "Circle to Search",
        description = "Opens AKS Circle to Search on this screen, so you can drag a circle around anything on it."
    )

    /**
     * Google Lens on whatever is on screen: the frame is captured and handed to
     * Lens, which searches it visually.
     *
     * [CIRCLE_TO_SEARCH] is the better pick and is first in the list. This one is
     * kept because it works with nothing but the Google app, so it is the fallback
     * for anyone without AKS installed. See [LensScreenSearch] for what it does
     * and does not do.
     */
    val GOOGLE_LENS_SCREEN = SideKeyActionSpec(
        id = GOOGLE_LENS_SCREEN_ID,
        label = "Google Lens",
        description = "Searches the screen you are on with Google Lens: text, objects, products, places."
    )

    /**
     * Flips the torch, the way the quick-settings tile does.
     *
     * A toggle rather than "turn on", because a press that cannot be pressed twice
     * is a light that cannot be turned off.
     */
    val TOGGLE_FLASHLIGHT = SideKeyActionSpec(
        id = TOGGLE_FLASHLIGHT_ID,
        label = "Flashlight",
        description = "Turns the torch on, or off if it is already on."
    )

    /** Pulls down the system's own quick settings shade. */
    val OPEN_QUICK_SETTINGS = SideKeyActionSpec(
        id = OPEN_QUICK_SETTINGS_ID,
        label = "Quick settings",
        description = "Pulls down the quick settings panel, the one with wifi, bluetooth and the torch."
    )

    /**
     * Play/pause for whatever is playing.
     *
     * One action rather than two, because the same press has to mean both: which of
     * them a given moment wants is the system's business, not the user's.
     */
    val MEDIA_PLAY_PAUSE = SideKeyActionSpec(
        id = MEDIA_PLAY_PAUSE_ID,
        label = "Play / pause",
        description = "Plays or pauses the current song, podcast or video."
    )

    /** Skips forward. */
    val MEDIA_NEXT = SideKeyActionSpec(
        id = MEDIA_NEXT_ID,
        label = "Next track",
        description = "Skips forward to the next song or video."
    )

    /** Goes back. */
    val MEDIA_PREVIOUS = SideKeyActionSpec(
        id = MEDIA_PREVIOUS_ID,
        label = "Previous track",
        description = "Goes back to the previous song or video."
    )

    /**
     * Picker order: nothing first, then the real actions.
     *
     * Grouped the way a hand reaches for them -- what is on screen, then what the
     * phone itself does, then media -- rather than by implementation.
     */
    val actions: List<SideKeyActionSpec> = listOf(
        NONE,
        CIRCLE_TO_SEARCH,
        GOOGLE_LENS_SCREEN,
        TOGGLE_FLASHLIGHT,
        OPEN_QUICK_SETTINGS,
        MEDIA_PLAY_PAUSE,
        MEDIA_NEXT,
        MEDIA_PREVIOUS,
    )

    /**
     * @return the spec for [id], or [NONE] when it is missing or no longer known.
     *
     * Also resolves the targeted ids, because a press mapped to an app is a real
     * mapping: resolving it here rather than letting it fall through to [NONE] is
     * what keeps it firing after the catalogue changes underneath it.
     */
    fun specFor(id: String?): SideKeyActionSpec {
        if (id == null) return NONE
        actions.firstOrNull { it.id == id }?.let { return it }
        return when (SideKeyTargets.parse(id)) {
            is SideKeyTarget.OpenApp -> SideKeyActionSpec(
                id = id,
                label = "Open app",
                description = "Opens one app you chose, at its own home screen.",
            )

            is SideKeyTarget.OpenLink -> SideKeyActionSpec(
                id = id,
                label = "Open link",
                description = "Opens a link you chose: a place, a search, a message thread.",
            )

            null -> NONE
        }
    }

    /**
     * Whether [id] is something a press may be mapped to: a catalogue entry -- "do
     * nothing" included, since it is the first one -- or a targeted id that still
     * carries a target.
     *
     * Read off the catalogue rather than off [specFor]'s result, because [specFor]
     * answers "what is this" with [NONE] for anything unknown, which would make the
     * question answer itself.
     */
    fun isKnown(id: String?): Boolean =
        id != null && (actions.any { it.id == id } || SideKeyTargets.isTargeted(id))

    /**
     * The label for whatever a press is currently mapped to, for the settings
     * row. @param id the stored action id.
     */
    fun labelFor(context: Context, id: String?): String =
        SideKeyTargets.parse(id)?.let { SideKeyTargets.labelFor(context, it) }
            ?: specFor(id).label
}
