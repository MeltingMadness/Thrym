package com.thrym.app.presentation.plans

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrym.app.domain.model.AppearanceStyle
import com.thrym.app.presentation.common.rememberHapticFeedback
import com.thrym.app.presentation.theme.GlassLevel
import com.thrym.app.presentation.theme.LocalAppearanceStyle
import com.thrym.app.presentation.theme.liquidGlass
import com.thrym.core.designsystem.R

@Composable
internal fun isLiquidGlass(): Boolean = LocalAppearanceStyle.current == AppearanceStyle.LIQUID_GLASS

/** Compact start button in Liquid Glass: bright pill with a colored play circle, as on the dashboard. */
@Composable
internal fun GlassStartPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pill = if (dark) Color.White else Color(0xFF0B0D12)
    val ink = if (dark) Color(0xFF0B0D12) else Color.White
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        }
        Text(text, color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** What the plans tab lists: training plans or meta plans (rotations). */
internal enum class PlansMode { PLANS, META_PLANS }

/**
 * Full-width switch between plans and meta plans with the count per kind. In Liquid Glass a
 * glass capsule whose selected segment is a bright pill (white on dark, near black on light
 * glass); in Ember a muted capsule with the accent color as selection.
 */
@Composable
internal fun PlansModeSwitcher(
    selected: PlansMode,
    planCount: Int,
    metaPlanCount: Int,
    onSelect: (PlansMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = isLiquidGlass()
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
        PlansModeSegment(
            selected = selected == PlansMode.PLANS,
            label = stringResource(R.string.plans_section_plans),
            icon = Icons.AutoMirrored.Filled.ListAlt,
            count = planCount,
            onClick = { onSelect(PlansMode.PLANS) },
            modifier = Modifier.weight(1f)
        )
        PlansModeSegment(
            selected = selected == PlansMode.META_PLANS,
            label = stringResource(R.string.plans_section_meta),
            icon = Icons.Default.Repeat,
            count = metaPlanCount,
            onClick = { onSelect(PlansMode.META_PLANS) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PlansModeSegment(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = isLiquidGlass()
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
    val fill by animateColorAsState(if (selected) selectedFill else Color.Transparent, label = "plansModeFill")
    val ink by animateColorAsState(
        if (selected) selectedInk else MaterialTheme.colorScheme.onSurface,
        label = "plansModeInk"
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
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Text(
            text = label,
            color = ink,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
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
