package com.thrym.app.presentation.plans

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrym.app.domain.model.AppearanceStyle
import com.thrym.app.presentation.common.ModeSegment
import com.thrym.app.presentation.common.ThrymModeSwitcher
import com.thrym.app.presentation.theme.LocalAppearanceStyle
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
 * Full-width switch between plans and meta plans with the count per kind, drawn by the shared
 * [ThrymModeSwitcher].
 */
@Composable
internal fun PlansModeSwitcher(
    selected: PlansMode,
    planCount: Int,
    metaPlanCount: Int,
    onSelect: (PlansMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = PlansMode.entries
    ThrymModeSwitcher(
        segments = listOf(
            ModeSegment(stringResource(R.string.plans_section_plans), Icons.AutoMirrored.Filled.ListAlt, planCount),
            ModeSegment(stringResource(R.string.plans_section_meta), Icons.Default.Repeat, metaPlanCount)
        ),
        selectedIndex = modes.indexOf(selected),
        onSelect = { index -> onSelect(modes[index]) },
        modifier = modifier
    )
}
