package com.thrym.app.presentation.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thrym.app.domain.model.AppearanceStyle
import com.thrym.app.presentation.theme.GlassLevel
import com.thrym.app.presentation.theme.LocalAppearanceStyle
import com.thrym.app.presentation.theme.liquidGlass

/** One segment of a [ThrymModeSwitcher]: name, symbol and an optional count badge. */
@Immutable
data class ModeSegment(
    val label: String,
    val icon: ImageVector,
    val count: Int? = null
)

/**
 * Full-width switch between the modes of a screen, such as plans and meta plans or workouts and
 * achievements. In Liquid Glass a glass capsule whose selected segment is a bright pill (white on
 * dark, near black on light glass); in Ember a muted capsule with the accent color as selection.
 * Each segment is a tab at least 44 dp high and ticks when it becomes selected.
 */
@Composable
fun ThrymModeSwitcher(
    segments: List<ModeSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalAppearanceStyle.current == AppearanceStyle.LIQUID_GLASS
    val shape = RoundedCornerShape(percent = 50)
    val container = if (glass) {
        Modifier.liquidGlass(GlassLevel.STANDARD, shape)
    } else {
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(container)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        segments.forEachIndexed { index, segment ->
            ModeSwitcherSegment(
                selected = index == selectedIndex,
                segment = segment,
                glass = glass,
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeSwitcherSegment(
    selected: Boolean,
    segment: ModeSegment,
    glass: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val haptics = rememberHapticFeedback()
    val selectedFill = when {
        !glass -> MaterialTheme.colorScheme.primary
        dark -> Color.White
        else -> Color(0xFF0B0D12)
    }
    val selectedInk = when {
        !glass -> MaterialTheme.colorScheme.onPrimary
        dark -> Color(0xFF0B0D12)
        else -> Color.White
    }
    val fill by animateColorAsState(if (selected) selectedFill else Color.Transparent, label = "modeSwitcherFill")
    val ink by animateColorAsState(
        if (selected) selectedInk else MaterialTheme.colorScheme.onSurface,
        label = "modeSwitcherInk"
    )
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(fill)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    if (!selected) {
                        haptics.tick()
                        onClick()
                    }
                }
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        Icon(segment.icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(
            text = segment.label,
            color = ink,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        segment.count?.let { count ->
            Text(
                text = count.toString(),
                color = ink,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(ink.copy(alpha = if (selected) 0.14f else 0.10f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            )
        }
    }
}
