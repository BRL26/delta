package com.blurr.voice.ui.app

import android.app.Activity
import android.content.Intent
import com.blurr.voice.MainActivity
import com.blurr.voice.MomentsActivity
import com.blurr.voice.SettingsActivity
import com.blurr.voice.triggers.ui.TriggersActivity

/**
 * Where each bottom-bar destination lives.
 *
 * A screen's Activity is named here and nowhere else, so the bar in
 * [DeltaScaffold] and the Activities themselves cannot disagree about where a tab
 * goes. The old bar wired four click listeners by hand, each with its own alpha,
 * and finished whichever screen you were on to get rid of it.
 */
object DeltaNav {

    /** The Activity that owns [destination]'s screen. */
    fun activityFor(destination: DeltaDestination): Class<out Activity> = when (destination) {
        DeltaDestination.TRIGGERS -> TriggersActivity::class.java
        DeltaDestination.MOMENTS -> MomentsActivity::class.java
        DeltaDestination.HOME -> MainActivity::class.java
        DeltaDestination.SETTINGS -> SettingsActivity::class.java
    }

    /**
     * Opens [destination] from [from].
     *
     * `REORDER_TO_FRONT` rather than the old `startActivity` + `finish`. Finishing
     * the caller is what made the tab bar a one-way trip: there was nothing
     * underneath to go back to, so the back gesture exited the app from any tab.
     * Reordering an existing instance to the front keeps the tabs you have already
     * visited in the back stack, so back walks the tabs you came through, and it
     * reuses the instance you already had rather than recreating it.
     */
    fun navigate(from: Activity, destination: DeltaDestination) {
        from.startActivity(
            Intent(from, activityFor(destination)).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
        )
    }
}
