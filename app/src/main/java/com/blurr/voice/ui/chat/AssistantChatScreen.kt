package com.blurr.voice.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blurr.voice.R
import com.blurr.voice.assistant.AssistantInput
import com.blurr.voice.assistant.AssistantLine
import com.blurr.voice.assistant.AssistantLineRole
import com.blurr.voice.assistant.AssistantSessionState
import com.blurr.voice.assistant.SessionBridge
import com.blurr.voice.ui.voice.VoiceInputController
import com.blurr.voice.ui.voice.VoiceInputState
import com.blurr.voice.utilities.SpeechCoordinator
import kotlinx.coroutines.delay

/** Which composer the user is typing/speaking into. */
private enum class InputMode { Mic, Text }

/**
 * The assistant popup.
 *
 * Rendered inside a VoiceInteractionSession window, i.e. not in an Activity, so there
 * is no Scaffold and no top app bar: the platform window is already full-screen and
 * dimmed, and this composable paints the sheet-like surface the user actually
 * interacts with.
 *
 * Speech is the default input, with a text composer one tap away, which is also why
 * the keyboard is never raised on open: an unsolicited IME in a full-screen system
 * window is what used to shove the sheet off the display.
 *
 * The conversation itself is *not* held here. [AssistantSessionState] mirrors what
 * [com.blurr.voice.ConversationalAgentService] is doing, which keeps running whether
 * or not this window exists -- so a task outlives a dismissal, and the answer is still
 * here when the pill is tapped.
 *
 * Ported from the MBTG assistant's popup, which is the same design running on a device
 * with a real voice interaction session behind it.
 */
@Composable
fun AssistantChatScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by AssistantSessionState.state.collectAsState()

    val context = LocalContext.current
    val voice = remember { VoiceInputController(context) }
    val voiceState by voice.state.collectAsState()
    DisposableEffect(voice) { onDispose { voice.release() } }

    // The conversational service's own speaker, read only to decide whether a
    // transcript was the user or the assistant. Not a state to observe: the question is
    // asked once per finished utterance, and a flow here would mean subscribing to TTS
    // for a boolean that is checked a handful of times a minute.
    val speechCoordinator = remember(context) {
        SpeechCoordinator.getInstance(context)
    }

    // Mic first: this is a long-press-power assistant, and a keyboard is the fallback,
    // not the default.
    var inputMode by rememberSaveable { mutableStateOf(InputMode.Mic) }
    var draft by rememberSaveable { mutableStateOf("") }

    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }

    // True while the popup is a small bubble rather than the full sheet, which is what
    // the agent loop does when it is about to work on the screen. Collected here rather
    // than passed down so the whole screen can switch layout from one branch, keeping
    // the sheet's own composition untouched and making a collapse a pure re-render.
    val collapsed by SessionBridge.collapsed.collectAsState()
    val stayCollapsed by SessionBridge.stayCollapsed.collectAsState()

    // The recogniser is released while collapsed. Holding the microphone through a
    // screen task would be pointless, and on a phone that is being tapped it is a
    // reliable source of stray transcripts arriving mid-task. Whether to resume is
    // remembered rather than assumed, so expanding the bubble does not switch the
    // microphone back on for someone who had deliberately turned it off.
    var resumeVoiceOnExpand by remember { mutableStateOf(false) }
    LaunchedEffect(collapsed) {
        if (collapsed) {
            resumeVoiceOnExpand = voiceState.listening
            if (voiceState.listening) voice.stop()
        } else if (resumeVoiceOnExpand) {
            resumeVoiceOnExpand = false
            voice.start()
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        AssistantInput.submit(context, text.trim())
        draft = ""
        // Put the keyboard away as soon as the request is sent rather than when the
        // answer arrives. The user has nothing left to type while the assistant works,
        // and leaving the IME up holds the window short, which is the state the popup
        // has to collapse out of before the agent can read the screen.
        keyboard?.hide()
    }

    // A finished utterance is submitted straight away, and the controller keeps the
    // microphone open by itself, so a voice conversation is a series of utterances with
    // nothing to tap between turns.
    //
    // What is dropped rather than submitted is anything heard while the assistant is
    // talking. The microphone is deliberately left open through the reply -- reopening
    // and closing it around every turn is the flicker this design exists to avoid -- and
    // an open microphone hears the speaker. A half-transcribed echo of "the message has
    // been sent" arriving as a user request is worse than a missed word, so it is
    // discarded here, at the one place where "who said this" is still known.
    LaunchedEffect(voice) {
        voice.transcripts.collect { transcript ->
            if (speechCoordinator.isCurrentlySpeaking()) return@collect
            submit(transcript)
        }
    }

    // Three silent sessions in a row means the microphone is open and nobody is behind
    // it. Left alone, the session window and the service notification would sit there
    // indefinitely, so the whole thing is torn down instead.
    //
    // Routed through AssistantInput for the stop half, which is the same single route the
    // bubble's Stop button uses, so there is one definition of what stopping means rather
    // than two that can drift. requestClose then ends the session window itself, which a
    // service Intent cannot do -- the two halves are genuinely different jobs.
    LaunchedEffect(voice) {
        voice.gaveUp.collect {
            AssistantInput.stop(context)
            SessionBridge.requestClose()
        }
    }

    // On open this runs with Mic already selected, so the assistant starts listening the
    // moment the popup appears: long-press power and talk, with no tap on the mic.
    // Switching to the text composer stops the recogniser so the microphone is genuinely
    // released while the keyboard is up, and switching back resumes listening.
    LaunchedEffect(inputMode) {
        if (inputMode == InputMode.Mic) {
            keyboard?.hide()
            voice.refreshPermission()
            voice.start()
        } else {
            voice.cancel()
            delay(120)
            runCatching { inputFocus.requestFocus() }
                .onSuccess { keyboard?.show() }
        }
    }

    // Declared after the voice effects above so that collapsing does not dispose them:
    // the controller and the conversation survive the round trip through the bubble,
    // which is what makes expanding feel like resuming rather than restarting.
    // Everything below this line belongs to the full sheet only.
    if (collapsed) {
        AssistantBubble(
            activity = state.activity,
            isThinking = state.isThinking,
            // Not SessionBridge::expand: a tap is the user asking, which outranks the
            // keep-as-a-bubble mode, so it goes through the path that says so.
            onExpand = SessionBridge::expandOnUserRequest,
            // Goes through AssistantInput rather than reaching into either service. The
            // bubble outlives the window's owner and has no business knowing that "stop"
            // means two different Intent actions in two different services.
            onStop = { AssistantInput.stop(context) },
            onClose = SessionBridge::requestClose,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim: tapping outside the sheet always gets the assistant out of the way as a
        // pill, never as a dismissal.
        //
        // Unconditional on purpose. A tap here is a gesture of "not now" -- the user
        // wants their screen back -- and not "throw this conversation away". Dismissing
        // answered the second question, so the answer was gone with no way to read it
        // and no trace of what had been said, which is the worst possible outcome from a
        // tap that was meant to do nothing at all.
        //
        // The pill's own X is the only way to end the session, which is the right shape:
        // one obvious control that does the destructive thing, rather than the
        // destructive thing being the default for the most natural gesture.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = ScrimAlpha))
                .clickable(
                    onClickLabel = stringResource(R.string.assistant_collapse_away),
                    onClick = SessionBridge::dismissToPill,
                ),
        )

        // The sheet is sized against the space *left above the keyboard*, not against
        // the window. The session window is ADJUST_NOTHING, so the platform will not
        // resize or pan it and the keyboard arrives as an ime inset instead; the sheet
        // container is therefore the only thing that moves. union(navigationBars) keeps
        // the resting state clear of the nav bar and lets the keyboard inset win while
        // it is up.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AssistantSheet(
                state = state,
                voiceState = voiceState,
                inputMode = inputMode,
                stayCollapsed = stayCollapsed,
                onToggleStayCollapsed = { SessionBridge.setStayCollapsed(!stayCollapsed) },
                draft = draft,
                onDraftChange = { draft = it },
                onInputModeChange = { inputMode = it },
                onSubmit = ::submit,
                onMicClick = {
                    if (voiceState.listening) voice.stop() else voice.start()
                },
                onClear = AssistantSessionState::clear,
                onOpenSettings = onOpenSettings,
                onGrantMicPermission = onOpenSettings,
                focusRequester = inputFocus,
            )
        }
    }
}

private const val ScrimAlpha = 0.55f

/**
 * The collapsed assistant: one line saying what it is doing, and nothing else.
 *
 * Deliberately the smallest thing that can be honest about what is happening. The
 * reason the popup is out of the way is that the user's app needs the screen, so every
 * pixel the bubble keeps is a pixel the assistant might be about to be asked to tap.
 *
 * Tappable, because the window is configured with FLAG_NOT_TOUCH_MODAL rather than
 * FLAG_NOT_TOUCHABLE: touches outside the bubble fall through to the app underneath,
 * while the bubble itself stays interactive, which is the user's way to get their
 * transcript back without waiting for the answer.
 */
@Composable
private fun AssistantBubble(
    activity: String?,
    isThinking: Boolean,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    // Spinner while working, tick once done. A bubble left spinning after the turn has
    // ended reads as a hang rather than as a finished job waiting to be read.
    val working = isThinking || activity != null

    Surface(
        modifier = Modifier
            // The window is WRAP_CONTENT, so this is the only thing keeping a long
            // activity line from stretching the bubble across the display.
            .widthIn(max = BubbleMaxWidth)
            .clickable(
                onClickLabel = stringResource(R.string.assistant_expand),
                onClick = onExpand,
            ),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            // Tight, because the first version of this read as "a massive pill" rather
            // than as a status line. Eighteen dp of horizontal padding either side of a
            // 16 dp icon is 52 dp of chrome around one glyph, and the corner radius is a
            // percentage so it grows with the row.
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    working -> activity ?: stringResource(R.string.assistant_working)
                    // Says what to do next, because the resting pill is the only thing on
                    // screen advertising that the answer is one tap away.
                    else -> stringResource(R.string.assistant_done_tap_to_read)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Announced as it changes, so a TalkBack user is told the task is
                // progressing rather than having to poll the bubble.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            // Stop, but only while there is something to stop.
            //
            // The bubble is where the user is looking while a long task runs, and it is
            // the one moment a cancellation is genuinely wanted: the assistant is
            // tapping through somebody else's app and the only way to interrupt it is to
            // say so. Gated on thinking so a finished answer does not carry a button that
            // would do nothing, and separate from the X because stopping keeps the
            // conversation and throwing it away does not.
            if (isThinking) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.assistant_stop),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // The pill is the only thing on screen once it has collapsed, so it has to
            // carry its own way out. A separate target rather than a long-press because a
            // hidden gesture on the one control that cannot be dismissed any other way is
            // a gesture nobody will find.
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.assistant_close),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val BubbleMaxWidth = 216.dp

@Composable
private fun AssistantSheet(
    state: com.blurr.voice.assistant.AssistantChatState,
    voiceState: VoiceInputState,
    inputMode: InputMode,
    stayCollapsed: Boolean,
    onToggleStayCollapsed: () -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onInputModeChange: (InputMode) -> Unit,
    onSubmit: (String) -> Unit,
    onMicClick: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onGrantMicPermission: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(SheetHeightFraction),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SheetHeader(
                hasMessages = state.lines.isNotEmpty(),
                inputMode = inputMode,
                stayCollapsed = stayCollapsed,
                onToggleStayCollapsed = onToggleStayCollapsed,
                onToggleInputMode = {
                    onInputModeChange(
                        if (inputMode == InputMode.Mic) InputMode.Text else InputMode.Mic,
                    )
                },
                onClear = onClear,
                onOpenSettings = onOpenSettings,
            )

            Box(modifier = Modifier.weight(1f)) {
                if (state.lines.isEmpty()) {
                    EmptyState()
                } else {
                    MessageList(
                        lines = state.lines,
                        isThinking = state.isThinking,
                        activity = state.activity,
                    )
                }
            }

            when (inputMode) {
                InputMode.Mic -> MicBar(
                    voiceState = voiceState,
                    onMicClick = onMicClick,
                    onGrantMicPermission = onGrantMicPermission,
                )

                InputMode.Text -> TextBar(
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onSubmit = { onSubmit(draft) },
                    thinking = state.isThinking,
                    focusRequester = focusRequester,
                )
            }
        }
    }
}

private const val SheetHeightFraction = 0.82f

@Composable
private fun SheetHeader(
    hasMessages: Boolean,
    inputMode: InputMode,
    stayCollapsed: Boolean,
    onToggleStayCollapsed: () -> Unit,
    onToggleInputMode: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.assistant_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        AnimatedVisibility(visible = hasMessages) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = stringResource(R.string.assistant_new_chat),
                )
            }
        }

        // Sits beside new-chat because it is about the same thing in a sense: a control
        // over what the popup does rather than what it says. Shown whether or not there
        // are messages, since it is normally armed *before* the request that needs it --
        // by the time a task is running the sheet is already a bubble and this button is
        // unreachable.
        //
        // Tinted when armed rather than swapped for a different icon, so the state is
        // legible at a glance and the shape stays put in the row.
        IconButton(onClick = onToggleStayCollapsed) {
            Icon(
                imageVector = Icons.Filled.Compress,
                contentDescription = stringResource(
                    if (stayCollapsed) {
                        R.string.assistant_stay_collapsed_on
                    } else {
                        R.string.assistant_stay_collapsed
                    },
                ),
                tint = if (stayCollapsed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        IconButton(onClick = onToggleInputMode) {
            Icon(
                imageVector = if (inputMode == InputMode.Mic) {
                    Icons.Filled.Keyboard
                } else {
                    Icons.Filled.Mic
                },
                contentDescription = stringResource(
                    if (inputMode == InputMode.Mic) {
                        R.string.assistant_switch_to_text
                    } else {
                        R.string.assistant_switch_to_mic
                    },
                ),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        IconButton(
            onClick = onOpenSettings,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.assistant_open_settings),
            )
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.assistant_greeting),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageList(
    lines: List<AssistantLine>,
    isThinking: Boolean,
    activity: String?,
) {
    val listState = rememberLazyListState()

    // Keep the newest line in view as the conversation grows.
    //
    // Three things were wrong with the obvious version of this, and each one is visible
    // on the phone:
    //
    //  * It only fired on a change in the line *count*. A turn appends the assistant's
    //    reply and then a progress row, so the count moves twice for one answer -- and a
    //    reply that arrives before the row is measured can land a frame short of the
    //    bottom. Keyed on the newest line's id instead, which changes once per line
    //    rather than once per render.
    //  * `animateScrollToItem` over a long conversation is an animation the user watches
    //    drag through every intervening bubble. `scrollToItem` is immediate, and the
    //    fling it does not start is not missed because there is nothing to read on the
    //    way.
    //  * It fought the user. Any scroll or fling marks the list as no longer at the
    //    bottom, and from then on a new message would yank the view away from whatever
    //    they had scrolled up to read.
    val newestId = lines.lastOrNull()?.id
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    LaunchedEffect(newestId, isThinking) {
        val target = lines.lastIndex + if (isThinking) 1 else 0
        // Only while pinned to the bottom. A user part-way up the transcript is reading,
        // and being dragged to the newest message is the single most irritating thing a
        // chat window can do.
        if (target >= 0 && atBottom) {
            runCatching { listState.scrollToItem(target) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = lines, key = { it.id }) { line ->
            LineRow(line)
        }

        if (isThinking) {
            item(key = "thinking") {
                ThinkingBubble(activity = activity)
            }
        }

        item(key = "tail") { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun LineRow(line: AssistantLine) {
    when (line.role) {
        AssistantLineRole.Activity -> ActivityRow(line)
        else -> MessageBubble(line)
    }
}

@Composable
private fun MessageBubble(line: AssistantLine) {
    val isUser = line.role == AssistantLineRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    // Only the assistant's own text is cleaned up. Whatever the user typed
                    // is theirs, asterisks and all.
                    text = if (isUser) line.text else plainText(line.text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * A progress line, shown as an inset activity row rather than a chat bubble.
 *
 * Deliberately not a speech bubble because the assistant did not say it; the phone did.
 * Keeping that distinction visible stops a status line being read as something the
 * model asserted.
 */
@Composable
private fun ActivityRow(line: AssistantLine) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThinkingBubble(activity: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 6.dp,
                bottomEnd = 20.dp,
            ),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = activity ?: stringResource(R.string.assistant_thinking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The default composer. The transcript grows in place above the button so the layout
 * never jumps, which keeps the sheet stable while the keyboard comes and goes elsewhere
 * in the system.
 */
@Composable
private fun MicBar(
    voiceState: VoiceInputState,
    onMicClick: () -> Unit,
    onGrantMicPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !voiceState.permissionGranted -> HintWithAction(
                    text = stringResource(R.string.assistant_mic_needs_permission),
                    actionLabel = stringResource(R.string.assistant_mic_grant),
                    onAction = onGrantMicPermission,
                )

                !voiceState.available -> Text(
                    text = stringResource(R.string.assistant_mic_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                voiceState.error != null -> Text(
                    text = stringResource(
                        when (voiceState.error) {
                            VoiceInputController.ERROR_PERMISSION ->
                                R.string.assistant_mic_needs_permission

                            else -> R.string.assistant_mic_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                voiceState.partial.isNotEmpty() -> Text(
                    text = voiceState.partial,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                else -> Text(
                    text = stringResource(
                        if (voiceState.listening) {
                            R.string.assistant_listening
                        } else {
                            R.string.assistant_tap_to_talk
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        FilledIconButton(
            onClick = onMicClick,
            enabled = voiceState.permissionGranted && voiceState.available,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = when {
                    voiceState.listening -> Icons.Filled.Stop
                    !voiceState.permissionGranted -> Icons.Filled.MicOff
                    else -> Icons.Filled.Mic
                },
                contentDescription = stringResource(
                    if (voiceState.listening) {
                        R.string.assistant_stop_listening
                    } else {
                        R.string.assistant_start_listening
                    },
                ),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun HintWithAction(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onAction) {
            Text(actionLabel)
        }
    }
}

/** The alternative composer, shown when the user taps the keyboard icon. */
@Composable
private fun TextBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    thinking: Boolean,
    focusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.assistant_input_hint)) },
            shape = RoundedCornerShape(28.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        )

        Spacer(Modifier.width(10.dp))

        // Send while idle. There is deliberately no stop: a turn here is owned by
        // ConversationalAgentService, which is a foreground service with its own
        // notification and its own stop path, and cancelling it from this window would
        // mean reaching into a service it does not own. Guarded so an empty draft cannot
        // fire a request at all.
        FilledIconButton(
            onClick = onSubmit,
            enabled = draft.isNotBlank(),
            modifier = Modifier.height(56.dp),
        ) {
            Icon(
                // AutoMirrored: the manifest declares supportsRtl, and a send arrow that
                // points the wrong way in a right-to-left layout is the kind of detail
                // that gets noticed and never mentioned.
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.assistant_send),
            )
        }
    }
}
