package com.blurr.voice.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.blurr.voice.ui.app.DeltaBottomSpacer
import com.blurr.voice.ui.app.DeltaDestination
import com.blurr.voice.ui.app.DeltaScaffold
import com.blurr.voice.ui.app.DeltaSection
import com.blurr.voice.utilities.DeltaState
import com.blurr.voice.utilities.DeltaStateColorMapper
import com.blurr.voice.views.DeltaSymbolView

/**
 * What the Delta screen draws.
 *
 * @param deltaState the assistant's current state, which colours the mark and
 *   drives its glow.
 * @param statusText the one-line explanation of that state.
 * @param loading true while the subscription check is running.
 * @param permissionsGranted whether every runtime permission Delta needs is held.
 * @param isDefaultAssistant whether the platform currently gives Delta the
 *   assistant role; the "set as default" button is hidden when it does.
 * @param tasksLeft the free tasks remaining, or null when the user is subscribed
 *   or the count is not known yet, in which case nothing is shown.
 * @param developerMessage a message from Remote Config to show once, if any.
 * @param disclaimerOpen whether the disclaimer dialog is up.
 * @param examplesOpen whether the example-commands dialog is up.
 */
data class HomeUiState(
    val deltaState: DeltaState,
    val statusText: String,
    val loading: Boolean,
    val permissionsGranted: Boolean,
    val isDefaultAssistant: Boolean,
    val tasksLeft: Long?,
    val developerMessage: String?,
    val disclaimerOpen: Boolean = false,
    val examplesOpen: Boolean = false,
)

/**
 * The app's home screen: the Delta mark, what it is doing, and the few things you
 * can do from here.
 *
 * The mark is the same [DeltaSymbolView] the old screen drew, hosted through
 * [AndroidView] because it is a hand-drawn `View`. Everything around it is the
 * shared chrome, so this screen is the same material as Settings: the mark gets
 * its colour from [MaterialTheme] rather than from the four legacy state colours,
 * which is what made this page look like a different app from the one it opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onNavigate: (DeltaDestination) -> Unit,
    onDeltaTap: () -> Unit,
    onSetAssistantClick: () -> Unit,
    onManagePermissionsClick: () -> Unit,
    onEmailDeveloperClick: () -> Unit,
    onDisclaimerClick: () -> Unit,
    onExamplesClick: () -> Unit,
    onDeveloperMessageDismiss: () -> Unit,
    onDisclaimerDismiss: () -> Unit,
    onExamplesDismiss: () -> Unit,
    onExampleChosen: (String) -> Unit,
) {
    DeltaScaffold(
        title = "Delta",
        current = DeltaDestination.HOME,
        onNavigate = onNavigate,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                DeltaMark(
                    state = state.deltaState,
                    // The mark is only a button when it has something to do:
                    // waking the assistant mid-task would start a second one.
                    enabled = state.deltaState == DeltaState.IDLE ||
                        state.deltaState == DeltaState.ERROR,
                    onClick = onDeltaTap,
                )

                Spacer(Modifier.height(24.dp))
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(32.dp))

                if (!state.isDefaultAssistant) {
                    Button(
                        onClick = onSetAssistantClick,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Set as default assistant")
                    }
                    Spacer(Modifier.height(12.dp))
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onExamplesClick) { Text("Make Delta do something") }
                    OutlinedButton(onClick = onDisclaimerClick) { Text("Disclaimer") }
                    OutlinedButton(onClick = onEmailDeveloperClick) { Text("Email developer") }
                }

                if (!state.permissionsGranted) {
                    Spacer(Modifier.height(32.dp))
                    DeltaSection("Permissions")
                    Text(
                        text = "Delta needs a few permissions before it can act on your screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onManagePermissionsClick) { Text("Manage permissions") }
                }

                state.tasksLeft?.let { left ->
                    Spacer(Modifier.height(32.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$left free tasks left",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                DeltaBottomSpacer(48.dp)
            }

            if (state.loading) {
                // A scrim rather than the old opaque panel: the screen underneath
                // is not wrong, it is just not final yet.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    state.developerMessage?.let { message ->
        AlertDialog(
            onDismissRequest = onDeveloperMessageDismiss,
            title = { Text("From the developer") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDeveloperMessageDismiss) { Text("OK") }
            },
        )
    }

    if (state.disclaimerOpen) {
        AlertDialog(
            onDismissRequest = onDisclaimerDismiss,
            title = { Text("Disclaimer") },
            text = { Text(DISCLAIMER) },
            confirmButton = {
                TextButton(onClick = onDisclaimerDismiss) { Text("Okay") }
            },
        )
    }

    if (state.examplesOpen) {
        AlertDialog(
            onDismissRequest = onExamplesDismiss,
            title = { Text("Example commands") },
            // Each example runs the agent as soon as it is tapped: the dialog is a
            // launcher, not a form, and the old version's extra step of tapping an
            // item and then confirming was a step with nothing in it.
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EXAMPLES.forEach { example ->
                        Text(
                            text = example,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onExampleChosen(example) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onExamplesDismiss) { Text("Cancel") }
            },
        )
    }
}

/** Paraphrased from the old dialog, whose wording was kept. */
private const val DISCLAIMER =
    "Delta is an experimental AI assistant and is still in development. It may not " +
        "always be accurate or do what you expect, though it is better at small tasks. " +
        "Thanks for bearing with it."

/** The prompts the examples dialog offers; "Surprise me" is handled by the caller. */
val EXAMPLES = listOf(
    "Open YouTube and play music",
    "Send a text message",
    "Set an alarm for 30 minutes",
    "Open camera app",
    "Check weather forecast",
    "Open calculator",
    "Surprise me",
)

/**
 * The mark, coloured by state.
 *
 * The colour comes from [MaterialTheme] for every state rather than from
 * [com.blurr.voice.utilities.DeltaStateColorMapper]: the mapper's palette is the
 * app's old one, and mixing it with the theme is exactly what made this page the
 * only orange screen in a purple app.
 */
@Composable
private fun DeltaMark(
    state: DeltaState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = when (state) {
        DeltaState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        DeltaState.LISTENING -> MaterialTheme.colorScheme.primary
        DeltaState.PROCESSING -> MaterialTheme.colorScheme.tertiary
        DeltaState.SPEAKING -> MaterialTheme.colorScheme.primary
        DeltaState.ERROR -> MaterialTheme.colorScheme.error
    }

    AndroidView(
        factory = { ctx -> DeltaSymbolView(ctx) },
        // `update` rather than a LaunchedEffect: the view does not exist until the
        // factory has run, and an effect keyed on the state can execute before it
        // does, leaving the mark its default colour until something else changes.
        update = { view ->
            view.setColor(color.toArgb())
            if (DeltaStateColorMapper.isActiveState(state)) view.startGlow() else view.stopGlow()
        },
        modifier = Modifier
            .size(220.dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}
