package com.blurr.voice.ui.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blurr.voice.R

/**
 * The application's own chrome, shared by every screen in the app.
 *
 * ## Why this exists separately from the assistant
 *
 * The assistant popup was the first part of this app written in Compose; the rest is
 * still late XML layouts, each carrying its own colours, its own corner radii and its own
 * idea of what "secondary text" looks like. The result reads as two applications: the
 * popup follows the system's wallpaper palette and the settings screens do not.
 *
 * Everything here is therefore drawn from [MaterialTheme] and nothing else. That is the
 * whole mechanism by which the halves match. The assistant renders inside
 * [com.blurr.voice.ui.theme.BlurrTheme] and these screens render inside the same theme --
 * loaded by the Activity, not by this file -- and there is not a literal colour between
 * them. A device with dynamic colour gives both the same palette; one without gives both
 * the same fallback.
 *
 * ## Insets
 *
 * [Scaffold] is what keeps content clear of the status and navigation bars: it consumes
 * the window insets and hands them back as the [PaddingValues] the content applies. The
 * activities call `enableEdgeToEdge()` so the window is not letterboxed and the insets
 * actually arrive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeltaScaffold(
    title: String,
    current: DeltaDestination?,
    onNavigate: (DeltaDestination) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Explicit rather than left to the theme's default: Theme.Delta is still a light
        // MaterialComponents style and would paint a white canvas under a dark app on a
        // device whose dynamic palette comes out light.
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            // No bar at all when no destination is current. One-hop sub-screens -- a
            // single provider, a task log -- are not places the bar can take you, and
            // the old navigation showed it there and then finished the Activity behind
            // it, which is a back stack that lies about where you are.
            if (current != null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    DeltaDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = destination == current,
                            onClick = { onNavigate(destination) },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.iconRes),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
        content = content,
    )
}

/**
 * The four places the bottom bar can take you.
 *
 * A value rather than four hand-written click listeners, which is what let the old bar
 * drift: each item was wired separately and each set its own alpha.
 */
enum class DeltaDestination(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    TRIGGERS(R.string.nav_triggers, R.drawable.trigger),
    MOMENTS(R.string.nav_moments, R.drawable.moments),
    HOME(R.string.nav_home, R.drawable.ic_delta_small),
    SETTINGS(R.string.nav_settings, R.drawable.setting),
}

/** Names a group of rows: "Voice", "Essential Key", "Account". */
@Composable
fun DeltaSection(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** The card a group of rows sits in, so a section reads as one object. */
@Composable
fun DeltaCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        // surface plus a small tonal elevation, which is how the assistant's own pill and
        // sheet are drawn. Reaching for a second elevation API here is how the two halves
        // start to drift.
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        Column(content = content)
    }
}

/**
 * Separates two rows inside a [DeltaCard].
 *
 * Inset to the text rather than to the card, so the line reads as separating two rows
 * instead of as cutting the card in half.
 */
@Composable
fun DeltaRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * One line of a [DeltaCard].
 *
 * Deliberately one composable for every kind of row -- navigation, value, status,
 * destructive -- rather than one per shape. The audit's finding was that the old screens
 * had four greys for "secondary text" and six title sizes precisely because every screen
 * grew its own row; the way to stop that is to have exactly one.
 *
 * @param title the row's subject.
 * @param subtitle optional supporting line, in the one secondary colour.
 * @param value optional right-aligned current value, for rows that open a picker.
 * @param icon optional leading icon.
 * @param trailing optional trailing content; a chevron is most of them.
 * @param onClick null makes the row static: no ripple, no click semantics.
 * @param destructive tints the title with the error colour.
 */
@Composable
fun DeltaRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: Painter? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }

        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * A row whose control is the whole line: tapping anywhere toggles it.
 *
 * The switch is presentational. Making the switch the only hit target is how a toggle
 * ends up needing a precise thumb, which is the affordance the old screens offered.
 */
@Composable
fun DeltaSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.clickable { onCheckedChange(!checked) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** The trailing chevron for a row that opens something. */
@Composable
fun RowChevron(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.width(20.dp),
    )
}

/** Breathing room at the end of a scrolling screen, above the navigation bar. */
@Composable
fun DeltaBottomSpacer(height: Dp = 32.dp) {
    Spacer(Modifier.fillMaxWidth().height(height))
}
