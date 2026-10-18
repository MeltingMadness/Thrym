package com.thrym.app.presentation.workout

import com.thrym.feature.workout.R as UxR
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.theme.accentText
import com.thrym.core.designsystem.R
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.HapticFeedbackHelper
import com.thrym.app.presentation.common.label
import com.thrym.app.presentation.common.setLoadText
import com.thrym.shared.readinessdata.SetIntention
import androidx.compose.material3.Surface
import com.thrym.app.presentation.theme.AthleticNumber
import com.thrym.app.presentation.theme.ButtonSize
import com.thrym.app.presentation.theme.IconSize
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText

@Composable
internal fun LoggedSetRow(
    set: com.thrym.app.domain.model.WorkoutSet,
    /** Position shown in the badge; plan rows pass their slot so gaps after a delete do not show. */
    displayNumber: Int = set.setNumber,
    /**
     * Stored answer, or `null` when nothing is stored / the readiness channel was not read.
     * `null` leaves the edit sheet without a preselected chip, while [SetIntention.UNKNOWN]
     * is an explicit "no answer" that is likewise never rendered as data.
     */
    intention: SetIntention?,
    intentionFailed: Boolean = false,
    intensitySystem: com.thrym.app.domain.model.IntensitySystem,
    unitSystem: UnitSystem,
    /** Bodyweight exercise: the weight is the added load ("Bodyweight + 10 kg"). */
    addedLoad: Boolean = false,
    plateCalculatorEnabled: Boolean,
    availablePlates: List<Double>,
    barbellWeightKg: Double,
    isUpdating: Boolean,
    updateSuccessCount: Int,
    onUpdateSet: (Long, Int, Double, String, SetIntention?) -> Unit,
    onDeleteSet: (Long) -> Unit,
    haptic: com.thrym.app.presentation.common.HapticFeedbackHelper
) {
    val dims = thrymDimens
    val tracksIntensity = intensitySystem != com.thrym.app.domain.model.IntensitySystem.OFF
    var isEditing by remember(set.id) { mutableStateOf(false) }
    val weightText = remember(set.id, set.weightKg, unitSystem) {
        formatWeightValue(set.weightKg, unitSystem)
    }
    val intensityText = remember(set.id, set.rpe, intensitySystem) {
        formatIntensity(set.rpe, intensitySystem)
    }
    val intensityInputText = remember(set.id, set.rpe, intensitySystem) {
        formatIntensityInput(set.rpe, intensitySystem)
    }

    LaunchedEffect(updateSuccessCount) {
        if (updateSuccessCount > 0 && isEditing) {
            isEditing = false
            haptic.confirm()
        }
    }

    if (isEditing) {
        ActiveSetCockpitCard(
            setNumber = displayNumber,
            setType = set.setType,
            isExtraOrAdHoc = false,
            isEditMode = true,
            defaultWeight = weightText,
            weightPlaceholder = weightText,
            defaultReps = set.reps.toString(),
            repsPlaceholder = set.reps.toString(),
            defaultIntensity = intensityInputText,
            intensityPlaceholder = intensityInputText,
            defaultIntention = intention,
            intentionFailed = intentionFailed,
            intensitySystem = intensitySystem,
            unitSystem = unitSystem,
            addedLoad = addedLoad,
            locked = isUpdating,
            plateCalculatorEnabled = plateCalculatorEnabled,
            availablePlates = availablePlates,
            barbellWeightKg = barbellWeightKg,
            haptic = haptic,
            onLog = { reps, weightKg, _, intensityStr, _, chosenIntention ->
                onUpdateSet(set.id, reps, weightKg, intensityStr, chosenIntention)
            },
            onCancelEdit = { isEditing = false },
            onDelete = {
                haptic.reject()
                isEditing = false
                onDeleteSet(set.id)
            }
        )
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = dims.spacingXs)
                .clickable(onClickLabel = stringResource(R.string.workout_edit_set_action)) {
                    isEditing = true
                    haptic.confirm()
                },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Completed set number badge
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = MaterialTheme.semantic.success.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = setTypeLabel(displayNumber, set.setType),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.semanticText.success
                    )
                }

                // Set Weight and Reps
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (set.weightKg == 0.0 || addedLoad) {
                                stringResource(UxR.string.workout_bodyweight_set_value, setLoadText(set.weightKg, unitSystem, addedLoad), set.reps, pluralStringResource(R.plurals.common_reps_unit, set.reps))
                            } else {
                                stringResource(UxR.string.workout_weighted_set_value, weightText, WeightFormatting.unitLabel(unitSystem), set.reps, pluralStringResource(R.plurals.common_reps_unit, set.reps))
                            },
                            style = AthleticNumber,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (set.isWarmup) {
                            Text(
                                text = stringResource(UxR.string.workout_parenthesized_label, stringResource(R.string.workout_warmup_chip)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    }

                    // Only a known answer is shown; a set without a record stays UNKNOWN
                    // instead of displaying "Nicht angegeben" as if it were data.
                    if (intention != null && intention != SetIntention.UNKNOWN) {
                        val intentionLabel = stringResource(intention.labelRes())
                        val intentionDescription = stringResource(
                            R.string.workout_set_intention_chip_cd,
                            intentionLabel
                        )
                        Text(
                            text = intentionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.accentText,
                            fontStyle = FontStyle.Italic,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics {
                                contentDescription = intentionDescription
                            }
                        )
                    }
                }

                // RPE chip
                if (tracksIntensity && intensityText.isNotEmpty()) {
                    val accentColor = rpeColor(set.rpe) ?: MaterialTheme.colorScheme.primary
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = stringResource(UxR.string.workout_intensity_value, intensitySystem.label(), intensityText),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = rpeTextColor(set.rpe) ?: MaterialTheme.accentText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Tapping the row edits the set (delete lives in edit mode).
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )

            }
        }
    }
}
