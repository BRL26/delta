package com.blurr.voice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.api.GoogleTts
import com.blurr.voice.api.TTSVoice
import com.blurr.voice.sidekey.EssentialKeyMapper
import com.blurr.voice.sidekey.SideKeyActionRegistry
import com.blurr.voice.ui.app.DeltaDestination
import com.blurr.voice.ui.app.DeltaNav
import com.blurr.voice.ui.settings.PressMapping
import com.blurr.voice.ui.settings.SettingsScreen
import com.blurr.voice.ui.settings.SettingsUiState
import com.blurr.voice.ui.settings.VoicePickerDialog
import com.blurr.voice.ui.theme.BlurrTheme
import com.blurr.voice.utilities.AuthGate
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.VoicePreferenceManager
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings, hosted in Compose.
 *
 * The screen itself is [SettingsScreen]; this class owns only the things a
 * composable cannot own: reading and writing preferences, playing a voice sample,
 * and starting the Activities the rows point at. Everything visible is state, and
 * every state change goes through [refresh].
 *
 * The old version of this screen was a `ScrollView` of `LinearLayout`s whose cards
 * were drawn in `@color/background` on a `@color/panel_background` page -- the same
 * value in night mode, so every card was invisible -- and whose title used one of
 * six different type sizes in the app. None of that is expressible here: the colours
 * are [MaterialTheme]'s and the sections come from [com.blurr.voice.ui.app.DeltaCard].
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sc: SpeechCoordinator
    private lateinit var availableVoices: List<TTSVoice>
    private var voiceTestJob: Job? = null

    /**
     * The screen's entire model. Compose reads [ui]`?.value`; this class is the only
     * writer, through [refresh] and the copies below, so the tree and the prefs
     * cannot drift apart.
     */
    private lateinit var ui: MutableState<SettingsUiState>

    private val voicePickerOpen = mutableStateOf(false)
    private val signOutConfirmOpen = mutableStateOf(false)
    private val batteryHelpOpen = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sc = SpeechCoordinator.getInstance(this)
        availableVoices = GoogleTts.getAvailableVoices()
        ui = mutableStateOf(buildState())

        // The Scaffold in DeltaScaffold consumes the window insets and hands them
        // back to the content; that only means anything if the window is actually
        // drawn behind the bars.
        enableEdgeToEdge()

        setContent {
            BlurrTheme {
                SettingsContent()
            }
        }

        cacheVoiceSamples()
    }

    override fun onStop() {
        super.onStop()
        // A voice sample outliving the screen it was previewed from is a noise the
        // user cannot attribute to anything.
        sc.stop()
        voiceTestJob?.cancel()
    }

    /** The whole Compose tree, so it reads [ui] in one place and dialogs stay siblings. */
    @Composable
    private fun SettingsContent() {
        val state = ui.value

        SettingsScreen(
            state = state,
            onNavigate = { DeltaNav.navigate(this, it) },
            onVoiceClick = { voicePickerOpen.value = true },
            onAiProvidersClick = { startActivity(Intent(this, AiProvidersActivity::class.java)) },
            onTaskLogsClick = { startActivity(Intent(this, TaskLogsListActivity::class.java)) },
            onSideKeyEnabledChange = { checked ->
                EssentialKeyMapper.setEnabled(this, checked)
                // The status line explains *why* the key may still do nothing when
                // it is on, so it has to be recomputed with the switch, not once.
                refresh()
            },
            onPressClick = { index -> openPressPicker(index) },
            onKeyTestClick = {
                startActivity(Intent(this, com.blurr.voice.sidekey.SideKeyTestActivity::class.java))
            },
            onPermissionsClick = { startActivity(Intent(this, PermissionsActivity::class.java)) },
            onBatteryHelpClick = { batteryHelpOpen.value = true },
            onSignOutClick = { signOutConfirmOpen.value = true },
        )

        if (voicePickerOpen.value) {
            VoicePickerDialog(
                voices = availableVoices.map { it.displayName },
                selectedIndex = selectedVoiceIndex(state.voiceName),
                onSelect = { index ->
                    voicePickerOpen.value = false
                    onVoiceChosen(availableVoices[index])
                },
                onDismiss = { voicePickerOpen.value = false },
            )
        }

        if (batteryHelpOpen.value) {
            InfoDialog(
                title = getString(R.string.battery_optimization_title),
                message = getString(R.string.battery_optimization_message),
                confirmLabel = getString(R.string.learn_how),
                onConfirm = {
                    batteryHelpOpen.value = false
                    openBatteryOptimizationHelp()
                },
                onDismiss = { batteryHelpOpen.value = false },
            )
        }

        if (signOutConfirmOpen.value) {
            InfoDialog(
                title = "Sign out?",
                message = "This clears your profile and every setting in the app.",
                confirmLabel = "Sign out",
                onConfirm = {
                    signOutConfirmOpen.value = false
                    signOut()
                },
                onDismiss = { signOutConfirmOpen.value = false },
            )
        }
    }

    /** A two-button dialog for the screen's confirmations. */
    @Composable
    private fun InfoDialog(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }

    /** Re-reads every stored value into the screen's model. */
    private fun refresh() {
        ui.value = buildState()
    }

    /** @return the model as the prefs currently describe it. */
    private fun buildState(): SettingsUiState {
        val profile = UserProfileManager(this)
        return SettingsUiState(
            voiceName = VoicePreferenceManager.getSelectedVoice(this).displayName,
            sideKeyEnabled = EssentialKeyMapper.isEnabled(this),
            sideKeyStatus = sideKeyStatus(),
            presses = pressMappings(),
            userName = profile.getName().orEmpty(),
            userEmail = profile.getEmail().orEmpty(),
            version = "Version ${BuildConfig.VERSION_NAME}",
        )
    }

    /** The index of the voice named [displayName], for the picker's initial mark. */
    private fun selectedVoiceIndex(displayName: String): Int =
        availableVoices.indexOfFirst { it.displayName == displayName }.coerceAtLeast(0)

    /**
     * What each press currently does, for the four rows.
     *
     * Read through [SideKeyActionRegistry.labelFor] rather than off the action spec,
     * because a press mapped to a chosen app or link resolves to a generic "Open app"
     * spec: the name the user actually recognises -- "Open Camera" -- comes from the
     * target, and only the label lookup knows to go and read it.
     */
    private fun pressMappings(): List<PressMapping> =
        SideKeyActionPicker.pressOrder.map { (gesture, label) ->
            PressMapping(
                label,
                SideKeyActionRegistry.labelFor(this, EssentialKeyMapper.actionIdFor(this, gesture)),
            )
        }

    /**
     * Explains what the key will do, given whether it is on and whether the
     * accessibility service is actually connected: without the service, key events
     * never reach Delta, and the switch alone would be telling only half the story.
     */
    private fun sideKeyStatus(): String {
        val enabled = EssentialKeyMapper.isEnabled(this)
        val connected = ScreenInteractionService.instance != null
        return when {
            !enabled -> "Off. Turn it on to use your mapped presses."
            connected -> "On. Each press runs what you mapped it to."
            else -> "On, but Delta's accessibility service is not connected, so no press " +
                "reaches it yet."
        }
    }

    /** Opens the action picker for press number [index] in [SideKeyActionPicker.pressOrder]. */
    private fun openPressPicker(index: Int) {
        val gesture = SideKeyActionPicker.pressOrder.getOrNull(index)?.first ?: return
        SideKeyActionPicker.show(this, gesture) { refresh() }
    }

    /** Saves [voice], plays it, and re-renders the row that names it. */
    private fun onVoiceChosen(voice: TTSVoice) {
        VoicePreferenceManager.saveSelectedVoice(this, voice)
        refresh()

        voiceTestJob?.cancel()
        voiceTestJob = lifecycleScope.launch {
            delay(400L)
            sc.stop()
            playVoiceSample(voice)
        }
    }

    private fun playVoiceSample(voice: TTSVoice) {
        lifecycleScope.launch {
            val voiceFile = File(File(cacheDir, "voice_samples"), "${voice.name}.wav")
            try {
                // The samples are pre-synthesised by cacheVoiceSamples, so the
                // preview is instant in the normal case and only falls back to a
                // live request for a voice that has not been cached yet.
                if (voiceFile.exists()) {
                    sc.playAudioData(voiceFile.readBytes())
                    Log.d(TAG, "Playing cached sample for ${voice.displayName}")
                } else {
                    sc.testVoice(TEST_TEXT, voice)
                    Log.d(TAG, "Synthesizing test for ${voice.displayName}")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error playing voice sample", e)
                    Toast.makeText(this@SettingsActivity, "Error playing voice", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    /**
     * Synthesises every voice's sample once, in the background.
     *
     * This is what makes the picker feel local: the samples are written to the
     * cache on first open, and choosing a voice afterwards is playback rather than
     * a round trip. It is deliberately not on the critical path -- the screen is
     * usable before it finishes, and a voice that is not cached yet is synthesised
     * on demand by [playVoiceSample].
     */
    private fun cacheVoiceSamples() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheDir = File(cacheDir, "voice_samples")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            var downloaded = 0
            for (voice in availableVoices) {
                val voiceFile = File(cacheDir, "${voice.name}.wav")
                if (!voiceFile.exists()) {
                    try {
                        voiceFile.writeBytes(GoogleTts.synthesize(TEST_TEXT, voice))
                        downloaded++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cache voice ${voice.name}", e)
                    }
                }
            }
            if (downloaded > 0) {
                runOnUiThread {
                    Toast.makeText(
                        this@SettingsActivity,
                        "$downloaded voice samples prepared.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun openBatteryOptimizationHelp() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BATTERY_FAQ_URL))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No browser to open that with.", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Failed to open battery optimization link", e)
        }
    }

    private fun signOut() {
        UserProfileManager(this).clearProfile()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()

        // In local mode there is no session to end, so going back to LoginActivity
        // would bounce straight through AuthGate; send the user home instead, after
        // re-provisioning the placeholder profile.
        val destination = if (AuthGate.isLocalMode(this)) {
            UserProfileManager(this).saveProfile("Local User", "local@blurr.invalid")
            MainActivity::class.java
        } else {
            LoginActivity::class.java
        }

        startActivity(
            Intent(this, destination).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }

    companion object {
        private const val TAG = "SettingsActivity"
        private const val PREFS_NAME = "BlurrSettings"
        private const val TEST_TEXT = "Hello, I'm Delta, and this is a test of the selected voice."
        private const val BATTERY_FAQ_URL =
            "https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html#00"
    }
}
