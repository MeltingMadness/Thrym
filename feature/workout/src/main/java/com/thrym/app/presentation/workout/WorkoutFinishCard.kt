package com.thrym.app.presentation.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR

/**
 * End of the workout list once every planned set is logged: "Alle 9 geplanten Sätze geschafft"
 * on a green card and "Training beenden", which opens the finish sheet like "Beenden" does.
 * Under Liquid Glass the card becomes tinted glass and the button the bright glass pill.
 */
@Composable
internal fun WorkoutFinishCard(
    plannedSetCount: Int,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = isLiquidGlass()
    ThrymSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.COLORED,
        semanticColor = MaterialTheme.semantic.success,
        shape = if (glass) RoundedCornerShape(26.dp) else MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (glass) 18.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.semanticText.success
                )
                Text(
                    text = pluralStringResource(WorkoutR.plurals.workout_finish_card_title, plannedSetCount, plannedSetCount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            val finishText = stringResource(R.string.workout_finish_confirm)
            if (glass) {
                GlassPrimaryButton(
                    text = finishText,
                    enabled = true,
                    onClick = onFinish,
                    modifier = Modifier.testTag(FINISH_CARD_BUTTON_TAG)
                )
            } else {
                Button(
                    onClick = onFinish,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag(FINISH_CARD_BUTTON_TAG)
                ) {
                    Text(finishText)
                }
            }
        }
    }
}

/** UI tests tell the card's button apart from the sheet's button with the same text. */
private const val FINISH_CARD_BUTTON_TAG = "workout_finish_card_button"
