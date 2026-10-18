package com.thrym.app.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.core.designsystem.R
import com.thrym.shared.readinessdata.SetIntention
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.semanticText

/** Parsed values of the set editor; weight is already converted to kg. */
internal data class HistorySetInput(
    val reps: Int,
    val weightKg: Double,
    val rpe: Double?
)

internal sealed interface HistorySetInputResult {
    data class Valid(val input: HistorySetInput) : HistorySetInputResult
    data object InvalidWeight : HistorySetInputResult
    data object InvalidReps : HistorySetInputResult
    data object InvalidIntensity : HistorySetInputResult
}

/**
 * Parses the editor fields like the iOS history editor: comma or dot as decimal separator
 * (native digits accepted, see [WeightFormatting.parseInputNumber]),
 * weight in the user's unit, optional intensity in the active system (RIR is stored as RPE).
 * With intensity tracking off the stored RPE is kept unchanged.
 */
internal fun parseHistorySetInput(
    weightText: String,
    repsText: String,
    intensityText: String,
    unitSystem: UnitSystem,
    intensitySystem: IntensitySystem,
    currentRpe: Double?
): HistorySetInputResult {
    val displayWeight = WeightFormatting.parseInputNumber(weightText)
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?: return HistorySetInputResult.InvalidWeight
    val reps = repsText.trim().toIntOrNull()?.takeIf { it >= 0 }
        ?: return HistorySetInputResult.InvalidReps
    val rpe = when {
        intensitySystem == IntensitySystem.OFF -> currentRpe
        intensityText.isBlank() -> null
        else -> {
            val value = WeightFormatting.parseInputNumber(intensityText)
                ?.takeIf { it.isFinite() && it in 0.0..10.0 }
                ?: return HistorySetInputResult.InvalidIntensity
            if (intensitySystem == IntensitySystem.RIR) 10.0 - value else value
        }
    }
    return HistorySetInputResult.Valid(
        HistorySetInput(
            reps = reps,
            weightKg = WeightFormatting.convertToKg(displayWeight, unitSystem),
            rpe = rpe
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistorySetEditDialog(
    set: WorkoutSet,
    /** Bodyweight exercise: the weight field holds the added load. */
    addedLoad: Boolean = false,
    intention: SetIntention?,
    intentionsLoaded: Boolean,
    unitSystem: UnitSystem,
    intensitySystem: IntensitySystem,
    isSaving: Boolean,
    saveError: String?,
    onSave: (HistorySetInput, SetIntention?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var weightText by remember(set.id) {
        mutableStateOf(WeightFormatting.formatInputNumber(WeightFormatting.convertToDisplay(set.weightKg, unitSystem)))
    }
    var repsText by remember(set.id) { mutableStateOf(set.reps.toString()) }
    var intensityText by remember(set.id) {
        mutableStateOf(
            set.rpe?.let { rpe ->
                val value = if (intensitySystem == IntensitySystem.RIR) 10.0 - rpe else rpe
                WeightFormatting.formatInputNumber(value, 1)
            }.orEmpty()
        )
    }
    var selectedIntention by remember(set.id) { mutableStateOf(intention ?: SetIntention.UNKNOWN) }
    var validationError by remember(set.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(set.id) { mutableStateOf(false) }

    val invalidWeight = stringResource(R.string.workout_detail_invalid_weight)
    val invalidReps = stringResource(R.string.workout_detail_invalid_reps)
    val invalidIntensity = stringResource(R.string.workout_detail_invalid_intensity)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!isSaving) confirmDelete = false },
            title = { Text(stringResource(R.string.workout_detail_delete_set_title)) },
            text = { Text(stringResource(R.string.workout_detail_delete_set_text)) },
            confirmButton = {
                TextButton(onClick = onDelete, enabled = !isSaving, colors = ThrymInteractiveColors.textButton()) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.semanticText.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, enabled = !isSaving, colors = ThrymInteractiveColors.textButton()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.workout_detail_edit_set_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = {
                        Text(
                            stringResource(
                                if (addedLoad) R.string.weight_added_load_field_label else R.string.workout_detail_weight_label,
                                WeightFormatting.unitLabel(unitSystem)
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { repsText = it },
                    label = { Text(stringResource(R.string.workout_detail_reps_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (intensitySystem != IntensitySystem.OFF) {
                    OutlinedTextField(
                        value = intensityText,
                        onValueChange = { intensityText = it },
                        label = {
                            Text(
                                stringResource(
                                    if (intensitySystem == IntensitySystem.RIR) {
                                        R.string.workout_detail_rir_label
                                    } else {
                                        R.string.workout_detail_rpe_label
                                    }
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (intentionsLoaded) {
                    Text(
                        text = stringResource(R.string.workout_set_intention_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SetIntention.entries.forEach { value ->
                            FilterChip(
                                selected = selectedIntention == value,
                                onClick = { selectedIntention = value },
                                label = { Text(stringResource(historyIntentionLabelRes(value))) },
                                colors = ThrymInteractiveColors.filterChip()
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.workout_detail_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                (validationError ?: saveError)?.let { message ->
                    Text(message, color = MaterialTheme.semanticText.danger, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = !isSaving,
                    // Offset by the button's inner padding so the label lines up with the fields.
                    modifier = Modifier.heightIn(min = 48.dp).offset(x = (-12).dp)
                ) {
                    Text(stringResource(R.string.workout_detail_delete_set), color = MaterialTheme.semanticText.danger)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    when (
                        val result = parseHistorySetInput(
                            weightText = weightText,
                            repsText = repsText,
                            intensityText = intensityText,
                            unitSystem = unitSystem,
                            intensitySystem = intensitySystem,
                            currentRpe = set.rpe
                        )
                    ) {
                        is HistorySetInputResult.Valid -> {
                            validationError = null
                            // Without loaded intentions the stored answer must stay untouched.
                            onSave(result.input, if (intentionsLoaded) selectedIntention else null)
                        }
                        HistorySetInputResult.InvalidWeight -> validationError = invalidWeight
                        HistorySetInputResult.InvalidReps -> validationError = invalidReps
                        HistorySetInputResult.InvalidIntensity -> validationError = invalidIntensity
                    }
                },
                colors = ThrymInteractiveColors.textButton()
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving, colors = ThrymInteractiveColors.textButton()) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
internal fun HistoryNotesDialog(
    initialNotes: String,
    isSaving: Boolean,
    saveError: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var notes by remember { mutableStateOf(initialNotes) }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (initialNotes.isBlank()) R.string.workout_detail_notes_add else R.string.workout_detail_notes_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.workout_detail_notes_label)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                saveError?.let { Text(it, color = MaterialTheme.semanticText.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(notes) }, enabled = !isSaving, colors = ThrymInteractiveColors.textButton()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving, colors = ThrymInteractiveColors.textButton()) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
