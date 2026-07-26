package com.willykez.repomaster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.willykez.repomaster.ui.theme.CommandBlue
import com.willykez.repomaster.ui.theme.StatusClean

/**
 * A single row's position within a [GroupedListColumn] — determines whether a divider is
 * drawn under it (every position except the last one in a group) and, at [GroupedListItem]'s
 * discretion, could drive per-row corner rounding. RepoMaster's version leans on one shared
 * [GlassCard]-style container per group instead of per-row clipping (the reference app this
 * pattern is adapted from uses flat Material cards without a glass surface, so it clips each
 * row individually) — the divider is what visually separates rows here.
 */
enum class GroupPosition { TOP, MIDDLE, BOTTOM, ONLY }

fun groupPositionFor(index: Int, count: Int): GroupPosition = when {
    count <= 1 -> GroupPosition.ONLY
    index == 0 -> GroupPosition.TOP
    index == count - 1 -> GroupPosition.BOTTOM
    else -> GroupPosition.MIDDLE
}

/**
 * The rounded, glass-styled card a group of [GroupedListItem] rows lives inside — one
 * continuous surface instead of separate floating cards per row, same "grouped list" reading
 * as an iOS/Settings-style list. Reuses [GlassCard]'s own background/border treatment rather
 * than a flat fill, so this fits RepoMaster's established visual language instead of
 * introducing a second, competing card style.
 */
@Composable
fun GroupedListColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

/**
 * One row inside a [GroupedListColumn]. Draws a hairline divider under itself unless it's the
 * last row in the group ([GroupPosition.BOTTOM] or [GroupPosition.ONLY]), indented past where
 * a row's leading icon would sit so it doesn't cut across the icon column.
 */
@Composable
fun GroupedListItem(position: GroupPosition, content: @Composable () -> Unit) {
    Column {
        content()
        if (position == GroupPosition.TOP || position == GroupPosition.MIDDLE) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 66.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                thickness = 0.5.dp,
            )
        }
    }
}

/**
 * A section label (small, uppercase, muted) above its [GroupedListColumn] — the same
 * "SECTION TITLE" + rounded group card pairing used throughout the reference Settings screen
 * this pattern is adapted from.
 */
@Composable
fun GroupedListSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = StatusClean,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        GroupedListColumn(content = content)
    }
}

/**
 * A single tappable settings row: icon in a tinted rounded chip, title + subtitle, and an
 * optional trailing slot (a Switch, a chevron, a value label...). Distinct from [GlassCard]'s
 * usual "card per item" usage elsewhere in the app — several of these stack inside one
 * [GroupedListColumn] to read as one cohesive list.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = CommandBlue,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconTint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, title, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.width(0.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StatusClean)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = StatusClean.copy(alpha = 0.6f))
        }
    }
}
