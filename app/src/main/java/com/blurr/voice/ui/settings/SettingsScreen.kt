package com.blurr.voice.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blurr.voice.ui.app.DeltaBottomSpacer
import com.blurr.voice.ui.app.DeltaCard
import com.blurr.voice.ui.app.DeltaDestination
import com.blurr.voice.ui.app.DeltaRow
import com.blurr.voice.ui.app.DeltaRowDivider
import com.blurr.voice.ui.app.DeltaScaffold
import com.blurr.voice.ui.app.DeltaSection
import com.blurr.voice.ui.app.DeltaSwitchRow
import com.blurr.voice.ui.app.RowChevron

/**
 * One press type and the action it currently runs, as the settings row shows it.
 *
 * @param label the press itself: "Single press", "Long press".
 * @param actionLabel the action's [com.blurr.voice.sidekey.SideKeyActionSpec.label].
 */
data class PressMapping(val label: String, val actionLabel: String)

/**
 * Everything the Settings screen draws, gathered so the screen itself is pure.
 *
 * The Activity owns the reading and writing of preferences and hands the result
 * down; the composable has no access to storage and therefore no way to disagree
 * with the Activity about what is stored.
 */
data class SettingsUiState(
    val voiceName: String,
    val sideKeyEnabled: Boolean,
    val sideKeyStatus: String,
    val presses: List<PressMapping>,
    val userName: String,
    val userEmail: String,
    val version: String,
)

/**
 * Settings, drawn in the assistant's own design language.
 *
 * Sections are groups of rows in a [DeltaCard]; every row is a [DeltaRow] or a
 * [DeltaSwitchRow], and every colour and shape comes from [MaterialTheme]. That is
 * the whole of the "matching the assistant" requirement: the assistant renders
 * inside the same theme, so on a device with dynamic colour the two agree by
 * construction rather than by anyone copying hex values across.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onNavigate: (DeltaDestination) -> Unit,
    onVoiceClick: () -> Unit,
    onAiProvidersClick: () -> Unit,
    onTaskLogsClick: () -> Unit,
    onSideKeyEnabledChange: (Boolean) -> Unit,
    onPressClick: (Int) -> Unit,
    onKeyTestClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    onBatteryHelpClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    DeltaScaffold(
        title = "Settings",
        current = DeltaDestination.SETTINGS,
        onNavigate = onNavigate,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(padding),
        ) {
            DeltaSection("Voice")
            DeltaCard {
                DeltaRow(
                    title = "Voice",
                    subtitle = "The voice Delta replies in",
                    value = state.voiceName,
                    onClick = onVoiceClick,
                    trailing = { RowChevron() },
                )
            }

            DeltaSection("Assistant")
            DeltaCard {
                DeltaRow(
                    title = "AI providers",
                    subtitle = "Choose which model answers you",
                    onClick = onAiProvidersClick,
                    trailing = { RowChevron() },
                )
                DeltaRowDivider()
                DeltaRow(
                    title = "Task logs",
                    subtitle = "Everything Delta has run for you",
                    onClick = onTaskLogsClick,
                    trailing = { RowChevron() },
                )
            }

            DeltaSection("Essential Key")
            DeltaCard {
                DeltaSwitchRow(
                    title = "Use the Essential Key",
                    subtitle = state.sideKeyStatus,
                    checked = state.sideKeyEnabled,
                    onCheckedChange = onSideKeyEnabledChange,
                )
                DeltaRowDivider()
                // One row per press type, built from the state the picker handed
                // back: the list comes from SideKeyActionPicker.pressOrder, so a
                // new press type shows up here without this file changing.
                state.presses.forEachIndexed { index, press ->
                    DeltaRow(
                        title = press.label,
                        value = press.actionLabel,
                        onClick = { onPressClick(index) },
                        trailing = { RowChevron() },
                    )
                    if (index != state.presses.lastIndex) DeltaRowDivider()
                }
                DeltaRowDivider()
                DeltaRow(
                    title = "Test the key",
                    subtitle = "Check Delta is seeing the key at all",
                    onClick = onKeyTestClick,
                    trailing = { RowChevron() },
                )
            }

            DeltaSection("Profile")
            DeltaCard {
                DeltaRow(
                    title = "Name",
                    value = state.userName.ifBlank { "Not set" },
                )
                DeltaRowDivider()
                DeltaRow(
                    title = "Email",
                    value = state.userEmail.ifBlank { "Not set" },
                )
                DeltaRowDivider()
                Text(
                    text = "We treat different emails as different people, and do not " +
                        "verify or store them. Use an address you actually own so you " +
                        "are not charged twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            DeltaSection("Support")
            DeltaCard {
                DeltaRow(
                    title = "Why Delta needs permissions",
                    onClick = onPermissionsClick,
                    trailing = { RowChevron() },
                )
                DeltaRowDivider()
                DeltaRow(
                    title = "Delta is not working?",
                    subtitle = "Battery optimisation is the usual cause",
                    onClick = onBatteryHelpClick,
                    trailing = { RowChevron() },
                )
            }

            DeltaSection("Account")
            DeltaCard {
                DeltaRow(
                    title = "Sign out",
                    subtitle = "Clears your settings and profile",
                    onClick = onSignOutClick,
                    destructive = true,
                )
            }

            Text(
                text = state.version,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            )
            DeltaBottomSpacer()
        }
    }
}

/**
 * The voice list, as a single-choice dialog.
 *
 * Replaces the `NumberPicker` the screen used to carry. The picker was a wheel
 * that had to be scrolled and whose selection was only legible through a
 * one-line slot, against a list that is 30 voices long; a scrollable list with
 * the current choice marked is both shorter to read and narrower to get wrong.
 */
@Composable
fun VoicePickerDialog(
    voices: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                voices.forEachIndexed { index, name ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .selectable(
                                selected = index == selectedIndex,
                                onClick = { onSelect(index) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = null,
                        )
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
