package com.thrym.app.presentation.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.theme.accentText
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.core.designsystem.R
import com.thrym.shared.companion.CompanionAnimation
import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionLine

/**
 * The companion with what it says: name and line on top, below a stage on which it walks around
 * (see [CompanionStage]). [entrance] plays once when the card appears, for example a celebration
 * for a record. [onHide] adds a button that hides the companion; without it the card has no close
 * button (onboarding).
 */
@Composable
fun CompanionCard(
    character: CompanionCharacter,
    line: CompanionLine,
    modifier: Modifier = Modifier,
    stageHeight: Dp = 112.dp,
    entrance: CompanionAnimation? = null,
    onHide: (() -> Unit)? = null
) {
    val dims = thrymDimens
    val name = companionName(character)
    val text = companionLineText(line, character)
    val spoken = stringResource(R.string.companion_says, name, text)

    ThrymSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.ELEVATED
    ) {
        Column {
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = dims.spacingMd,
                            top = dims.spacingMd,
                            end = if (onHide != null) 40.dp else dims.spacingMd
                        )
                        .clearAndSetSemantics {
                            contentDescription = spoken
                        },
                    verticalArrangement = Arrangement.spacedBy(dims.spacing2)
                ) {
                    Text(
                        text = name.uppercase(currentLocale()),
                        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.accentText
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
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
            CompanionStage(
                character = character,
                height = stageHeight,
                entrance = entrance,
                modifier = Modifier.padding(horizontal = dims.spacingSm)
            )
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
        CompanionLine.WORKOUT_DONE_FOUGHT -> R.string.companion_line_workout_done_fought
        CompanionLine.WORKOUT_DONE_HALL -> R.string.companion_line_workout_done_hall
        CompanionLine.RECORD_SKALDS -> R.string.companion_line_record_skalds
        CompanionLine.RECORD_SAGA -> R.string.companion_line_record_saga
    }
)
