package com.thrym.app.presentation.companion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.core.designsystem.R
import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionLine
import kotlinx.coroutines.delay

private const val CHEER_FOR_MS = 1_200L

/**
 * The companion with a speech bubble. A tap on the companion makes it cheer. [onHide] adds a
 * button that hides the companion; without it the card has no close button (onboarding).
 * [cheering] keeps the happy pose, for example when a trophy is unlocked.
 */
@Composable
fun CompanionCard(
    character: CompanionCharacter,
    line: CompanionLine,
    modifier: Modifier = Modifier,
    spriteSize: Dp = 88.dp,
    cheering: Boolean = false,
    onHide: (() -> Unit)? = null
) {
    val dims = thrymDimens
    val name = companionName(character)
    val text = companionLineText(line, character)
    val spoken = stringResource(R.string.companion_says, name, text)
    var tapCheer by remember { mutableStateOf(false) }

    LaunchedEffect(tapCheer) {
        if (tapCheer) {
            delay(CHEER_FOR_MS)
            tapCheer = false
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(dims.spacingSm)
    ) {
        CompanionSprite(
            character = character,
            size = spriteSize,
            cheering = cheering || tapCheer,
            contentDescription = null,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = null,
                onClick = { tapCheer = true }
            )
        )
        ThrymSurfaceCard(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = dims.spacingMd),
            tone = ThrymSurfaceTone.ELEVATED
        ) {
            Box {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(
                            start = dims.spacingMd,
                            top = dims.spacingMd,
                            bottom = dims.spacingMd,
                            end = if (onHide != null) 40.dp else dims.spacingMd
                        )
                        .clearAndSetSemantics {
                            contentDescription = spoken
                        }
                )
                if (onHide != null) {
                    IconButton(
                        onClick = onHide,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.companion_hide_today),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The localized text of [line]; the onboarding welcome names [character]. */
@Composable
fun companionLineText(line: CompanionLine, character: CompanionCharacter): String = stringResource(
    when (line) {
        CompanionLine.ONBOARDING_WELCOME -> when (character) {
            CompanionCharacter.ODIN -> R.string.companion_line_onboarding_welcome_odin
            CompanionCharacter.VALKYRIE -> R.string.companion_line_onboarding_welcome_valkyrie
        }
        CompanionLine.ONBOARDING_UNITS -> R.string.companion_line_onboarding_units
        CompanionLine.ONBOARDING_START -> R.string.companion_line_onboarding_start
        CompanionLine.FIRST_STEPS_WELCOME -> R.string.companion_line_first_steps_welcome
        CompanionLine.TRAINED_TODAY_REST -> R.string.companion_line_trained_today_rest
        CompanionLine.TRAINED_TODAY_SLEEP -> R.string.companion_line_trained_today_sleep
        CompanionLine.WELCOME_BACK_ONE_SET -> R.string.companion_line_welcome_back_one_set
        CompanionLine.EVERYDAY_YGGDRASIL -> R.string.companion_line_everyday_yggdrasil
        CompanionLine.EVERYDAY_WARMUP -> R.string.companion_line_everyday_warmup
        CompanionLine.EVERYDAY_TECHNIQUE -> R.string.companion_line_everyday_technique
        CompanionLine.EVERYDAY_RECOVERY -> R.string.companion_line_everyday_recovery
        CompanionLine.EVERYDAY_SMALL_PLATES -> R.string.companion_line_everyday_small_plates
    }
)
