package com.blurr.voice.ui.sidekey

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blurr.voice.sidekey.SideKeyActionRegistry
import com.blurr.voice.sidekey.SideKeyTarget
import com.blurr.voice.sidekey.SideKeyTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picker rows that ask for a target rather than being an action in their own right. */
private const val CHOOSE_APP = "__choose_app"
private const val CHOOSE_LINK = "__choose_link"

/** Which step of the picker is showing. */
internal enum class Stage { ACTIONS, CHOOSE_APP, ENTER_LINK }

/**
 * The picker's current step, held *outside* the composition.
 *
 * The host window needs it because of where the system back key arrives: it reaches
 * the [android.app.Dialog] directly, whose own answer is to dismiss, and dismissing
 * from a sub-step throws away the half-made choice -- the app that was being picked,
 * the link that was being typed. Holding the step here lets the window ask "is there a
 * step to go back to?" instead of only "should I close?", which is a question the
 * composition alone could not answer in time.
 */
internal class SideKeyPickerNav {

    var stage by mutableStateOf(Stage.ACTIONS)
        internal set

    /**
     * Answers one press of the system back key.
     *
     * @return true when back moved to the previous step; false when there is no
     *   previous step, which is the caller's cue to close the whole dialog.
     */
    fun back(): Boolean {
        if (stage == Stage.ACTIONS) return false
        stage = Stage.ACTIONS
        return true
    }
}

/** One selectable line: the action's own title, or a request to choose a target. */
private data class Choice(val id: String, val label: String, val description: String)

/** An installed app the user can point a press at. */
private data class LaunchableApp(val label: String, val packageName: String)

/**
 * The press picker: what should this press type do?
 *
 * ## Why this is Compose and not another AlertDialog
 *
 * It was a [android.app.AlertDialog] over a [android.widget.ListView], which meant the
 * one screen a user opens to make the key useful was the one screen still wearing the
 * old look: a white list of single-line rows with no descriptions, no theme colours,
 * nothing shared with the rest of the app. Everything it draws now comes from
 * [MaterialTheme], like the assistant and the settings screens.
 *
 * ## The three steps
 *
 * [Stage.ACTIONS] is the catalogue, built from [SideKeyActionRegistry.actions]. The two
 * entries that need more than a choice end their own step rather than resolving
 * immediately, because neither can be told what it does until the user has said what it
 * should do: an app to open, or a link to follow.
 *
 * Both callbacks that leave this dialog carry the *stored* id rather than a resolved
 * [com.blurr.voice.sidekey.SideKeyActionSpec]: a row for a chosen app or link is only
 * itself when its target comes along, and a spec would reduce it to "open an app".
 */
@Composable
internal fun SideKeyActionDialog(
    gestureLabel: String,
    currentId: String?,
    nav: SideKeyPickerNav,
    onPick: (String) -> Unit,
    onTest: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf(currentId ?: SideKeyActionRegistry.NONE_ID) }
    var linkText by remember {
        mutableStateOf(
            (SideKeyTargets.parse(currentId) as? SideKeyTarget.OpenLink)?.uri.orEmpty()
        )
    }

    // null means "not asked for yet" rather than "there are none": an empty list is
    // a real answer, and until it arrives the step has to say it is still looking.
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }

    // Loading every installed app's label is the slow part, and it only matters once the
    // user actually gets to that step.
    LaunchedEffect(nav.stage) {
        if (nav.stage == Stage.CHOOSE_APP && apps == null) {
            apps = withContext(Dispatchers.IO) { launchableApps(context) }
        }
    }

    when (nav.stage) {
        Stage.ACTIONS -> {
            val choices = remember(currentId, context) { choicesFor(currentId, context) }
            val chosen = choices.firstOrNull { it.id == selectedId }
            val testable = chosen != null &&
                chosen.id != CHOOSE_APP && chosen.id != CHOOSE_LINK

            Sheet(
                title = "$gestureLabel does what?",
            ) {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(choices, key = { it.id }) { choice ->
                        ChoiceRow(
                            label = choice.label,
                            description = choice.description,
                            selected = choice.id == selectedId,
                            onClick = { selectedId = choice.id },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    // Runs the highlighted action without waiting for the key, because
                    // what an action does often depends on the screen underneath it and
                    // this dialog is the only thing the user can currently see.
                    TextButton(
                        enabled = testable,
                        onClick = { onTest(selectedId) },
                    ) {
                        Text("Test it")
                    }
                    TextButton(
                        onClick = {
                            when (chosen?.id) {
                                CHOOSE_APP -> nav.stage = Stage.CHOOSE_APP
                                CHOOSE_LINK -> nav.stage = Stage.ENTER_LINK
                                else -> {
                                    onPick(chosen?.id ?: SideKeyActionRegistry.NONE_ID)
                                    onDismiss()
                                }
                            }
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }

        Stage.CHOOSE_APP -> {
            Sheet(
                title = "Which app?",
            ) {
                // The list is null until it has been fetched, so "still looking" cannot
                // be confused with "this phone has no apps at all".
                val appList = apps
                if (appList == null) {
                    Text(
                        text = "Looking for apps…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(appList, key = { it.packageName }) { app ->
                            ChoiceRow(
                                label = app.label,
                                description = app.packageName,
                                selected = false,
                                onClick = {
                                    onPick(SideKeyTarget.OpenApp(app.packageName).encoded)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { nav.stage = Stage.ACTIONS }) { Text("Back") }
                }
            }
        }

        Stage.ENTER_LINK -> {
            Sheet(
                title = "Which link?",
            ) {
                Text(
                    text = "Anything the phone can open. A place, a search, a thread.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    label = { Text("Link") },
                    placeholder = { Text("geo:0,0?q=coffee") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { nav.stage = Stage.ACTIONS }) { Text("Back") }
                    TextButton(
                        enabled = linkText.isNotBlank(),
                        onClick = {
                            onPick(SideKeyTarget.OpenLink(linkText.trim()).encoded)
                            onDismiss()
                        },
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

/**
 * The catalogue, plus the two target-choosing entries, plus the press's existing target
 * mapping when it has one.
 *
 * That last part is why an existing choice stays visible instead of the row reading as
 * unselected: a press pointed at one app has no row in the catalogue to match, and
 * without this the user would have to pick a new app to keep the one they already had.
 */
private fun choicesFor(currentId: String?, context: Context): List<Choice> = buildList {
    addAll(SideKeyActionRegistry.actions.map { Choice(it.id, it.label, it.description) })
    SideKeyTargets.parse(currentId)?.let {
        add(
            Choice(
                id = currentId!!,
                label = SideKeyActionRegistry.labelFor(context, currentId),
                description = "The one you picked before. Save to keep it.",
            )
        )
    }
    add(
        Choice(
            id = CHOOSE_APP,
            label = "Open an app…",
            description = "Any installed app, opened at its own home screen.",
        )
    )
    add(
        Choice(
            id = CHOOSE_LINK,
            label = "Open a link…",
            description = "A place, a search, a thread, whatever the link points at.",
        )
    )
}

/**
 * The sheet the three steps share.
 *
 * A plain [Surface] rather than an [androidx.compose.material3.AlertDialog], because the
 * dialog already lives in a host window and a Material alert dialog would open a second
 * one inside it.
 */
@Composable
private fun Sheet(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** One selectable line: a radio, the action's title, and what it actually does. */
@Composable
private fun ChoiceRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Every app with a launcher entry, by its own name.
 *
 * Sorted by name because the list is scanned, not searched: a phone has a hundred of
 * these and the one being looked for is usually remembered by what it is called.
 */
private fun launchableApps(context: Context): List<LaunchableApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .mapNotNull { resolved ->
            resolved.activityInfo?.packageName?.let { pkg ->
                LaunchableApp(SideKeyTargets.appLabel(context, pkg), pkg)
            }
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}