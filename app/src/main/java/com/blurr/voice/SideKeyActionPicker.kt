package com.blurr.voice

import android.app.Activity
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blurr.voice.sidekey.EssentialKeyMapper
import com.blurr.voice.sidekey.LensScreenSearch
import com.blurr.voice.sidekey.LensScreenSearchOutcome
import com.blurr.voice.sidekey.KeyGesture
import com.blurr.voice.sidekey.SideKeyActionRegistry
import com.blurr.voice.sidekey.SideKeyActionSpec
import com.blurr.voice.sidekey.SideKeyNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single-choice action picker for one Essential Key press type.
 *
 * The list is rendered straight from [SideKeyActionRegistry.actions], so adding
 * an action later needs no change here: the new entry appears in the dialog and
 * in the settings row on its own. A neutral "Test it" button runs the highlighted
 * action immediately -- which matters most for the screen-reading actions, since
 * the thing they read is whatever happens to be on the display at the time, and
 * that is usually not the settings screen.
 */
object SideKeyActionPicker {

    /** Human-readable press names, in the order the settings rows show them. */
    val pressOrder: List<Pair<KeyGesture, String>> = listOf(
        KeyGesture.SINGLE_PRESS to "Single press",
        KeyGesture.DOUBLE_PRESS to "Double press",
        KeyGesture.TRIPLE_PRESS to "Triple press",
        KeyGesture.LONG_PRESS to "Long press"
    )

    /** @return the display name of [gesture], for the settings rows. */
    fun pressLabel(gesture: KeyGesture): String =
        pressOrder.firstOrNull { it.first == gesture }?.second ?: gesture.name

    /**
     * Shows the picker for [gesture] and stores the choice.
     *
     * @param onChanged invoked after a selection is written, so the caller can
     *   refresh the press row's current-action label.
     */
    fun show(activity: Activity, gesture: KeyGesture, onChanged: () -> Unit) {
        val actions = SideKeyActionRegistry.actions
        val currentId = EssentialKeyMapper.actionIdFor(activity, gesture)
        val checked = actions.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val labels = actions.map { "${it.label}\n${it.description}" }

        val list = ListView(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_single_choice, labels)
            choiceMode = ListView.CHOICE_MODE_SINGLE
            setItemChecked(checked, true)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("${pressLabel(gesture)} does what?")
            .setView(list)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test it", null)
            .setPositiveButton("Save") { _, _ ->
                val chosen = actions.getOrNull(list.checkedItemPosition) ?: SideKeyActionRegistry.NONE
                EssentialKeyMapper.setActionIdFor(activity, gesture, chosen.id)
                onChanged()
            }
            .create()

        dialog.setOnShowListener {
            // Neutral buttons dismiss by default; override so the dialog can
            // stay open and a choice can be tried before it is saved.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val chosen = actions.getOrNull(list.checkedItemPosition) ?: SideKeyActionRegistry.NONE
                EssentialKeyMapper.setActionIdFor(activity, gesture, chosen.id)
                onChanged()
                testAction(activity, chosen)
            }
        }
        dialog.show()
    }

    /**
     * Runs [action] once without pressing the key, so the user can see what it
     * actually does before mapping it to a press.
     */
    private fun testAction(activity: Activity, action: SideKeyActionSpec) {
        val service = ScreenInteractionService.instance
        if (service == null) {
            SideKeyNotifications.notify(
                activity,
                "Cannot test right now",
                "Delta's accessibility service is not connected, so it cannot dispatch actions."
            )
            return
        }
        when (action.id) {
            SideKeyActionRegistry.NONE_ID ->
                Toast.makeText(activity, "Do nothing has nothing to test.", Toast.LENGTH_SHORT).show()

            SideKeyActionRegistry.GOOGLE_LENS_SCREEN_ID -> {
                Toast.makeText(activity, "Sending this screen to Google Lens...", Toast.LENGTH_SHORT).show()
                CoroutineScope(Dispatchers.Main).launch {
                    val outcome = LensScreenSearch.searchScreen(service)
                    if (outcome == LensScreenSearchOutcome.FAILED) {
                        SideKeyNotifications.notify(
                            activity,
                            "Google Lens did not open",
                            "The screen could not be captured, or Google Lens refused it."
                        )
                    }
                }
            }

            else -> Toast.makeText(
                activity,
                "No test available for ${action.label}.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
