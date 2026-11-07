package com.thrym.app.presentation.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.thrym.app.domain.model.AppearanceStyle
import com.thrym.app.presentation.theme.GlassLevel
import com.thrym.app.presentation.theme.LocalAppearanceStyle
import com.thrym.app.presentation.theme.liquidGlass
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.thrym.app.presentation.theme.glassmorphism
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.thrymSurfaceRoles

@Composable
fun BottomNavBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val surfaces = thrymSurfaceRoles
    val dims = thrymDimens
    val navigate: (BottomNavItem) -> Unit = { item ->
        if (currentRoute != item.screen.route) {
            navController.navigateToTopLevel(item.screen.route)
        }
    }

    if (LocalAppearanceStyle.current == AppearanceStyle.LIQUID_GLASS) {
        LiquidGlassNavBar(currentRoute = currentRoute, onSelect = navigate)
        return
    }

    NavigationBar(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = dims.spacingMd, vertical = dims.spacing2)
            .clip(MaterialTheme.shapes.extraLarge)
            .glassmorphism(
                shape = MaterialTheme.shapes.extraLarge,
                backgroundColor = surfaces.elevated.copy(alpha = 0.76f)
            ),
        tonalElevation = 0.dp,
        containerColor = Color.Transparent
    ) {
        BottomNavItem.entries.forEach { item ->
            val label = stringResource(id = item.labelRes)
            NavigationBarItem(
                modifier = Modifier.testTag(item.testTag),
                icon = { Icon(item.icon, contentDescription = label) },
                label = { Text(label) },
                selected = currentRoute == item.screen.route,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                ),
                onClick = { navigate(item) }
            )
        }
    }
}

/**
 * Floating glass pill of the Liquid Glass look: the active tab is a solid pill with icon and
 * label, the others show their icon only (labelled for accessibility and UI tests).
 */
@Composable
private fun LiquidGlassNavBar(
    currentRoute: String?,
    onSelect: (BottomNavItem) -> Unit
) {
    val pillShape = RoundedCornerShape(32.dp)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val activeBackground = if (dark) Color.White else Color(0xFF0B0D12)
    val activeContent = if (dark) Color(0xFF0B0D12) else Color.White
    Row(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(64.dp)
            .liquidGlass(GlassLevel.STRONG, pillShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BottomNavItem.entries.forEach { item ->
            val label = stringResource(id = item.labelRes)
            val selected = currentRoute == item.screen.route
            Box(
                modifier = Modifier
                    .weight(if (selected) 1.8f else 1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .then(if (selected) Modifier.background(activeBackground) else Modifier)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item) })
                    .semantics { contentDescription = label }
                    .testTag(item.testTag),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = if (selected) activeContent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    if (selected) {
                        Text(
                            text = label,
                            color = activeContent,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}
