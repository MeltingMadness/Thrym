package com.thrym.app.presentation.workout

import com.thrym.feature.workout.R as UxR
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.HapticFeedbackHelper
import com.thrym.app.presentation.common.PlateVisualizer
import com.thrym.app.presentation.common.label
import com.thrym.app.presentation.common.currentLocale
import com.thrym.shared.readinessdata.SetIntention
import kotlin.math.roundToInt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import com.thrym.app.presentation.theme.AthleticLabel
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semanticText

@Composable
internal fun ActiveSetCockpitCard(
    setNumber: Int,
    setType: SetType = SetType.NORMAL,
    onSetTypeChange: ((SetType) -> Unit)? = null,
    isExtraOrAdHoc: Boolean = false,
    isEditMode: Boolean = false,
    coachHint: String? = null,
    valueSource: String? = null,
    coachRecommendation: NextSetRecommendationUi? = null,
    defaultWeight: String = "",
    weightPlaceholder: String? = null,
    defaultReps: String = "",
    repsPlaceholder: String? = null,
    defaultIntensity: String = "",
    intensityPlaceholder: String? = null,
    /**
     * The stored answer to prefill, or `null` when nothing is stored or the readiness
     * channel has not been read. `null` is never rendered as "Nicht angegeben", so an
     * unread channel cannot masquerade as a deliberate answer.
     */
    defaultIntention: SetIntention? = null,
    intentionLoaded: Boolean = false,
    intentionFailed: Boolean = false,
    intensitySystem: IntensitySystem,
    unitSystem: UnitSystem,
    /**
     * Bodyweight exercise: the weight field holds the added load. It is labelled as such and
     * starts at 0 (bodyweight only) when no other value is known, so the set can be logged at once.
     */
    addedLoad: Boolean = false,
    locked: Boolean = false,
    completedSubmissions: Set<Long> = emptySet(),
    plateCalculatorEnabled: Boolean,
    availablePlates: List<Double>,
    barbellWeightKg: Double,
    haptic: com.thrym.app.presentation.common.HapticFeedbackHelper,
    onLog: (Int, Double, SetType, String, Long, SetIntention?) -> Unit,
    onCancelEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dims = thrymDimens
    // Details start collapsed, also when editing, so "Satz löschen" and "Abbrechen" stay in view.
    var showDetails by remember(setNumber) { mutableStateOf(false) }
    val tracksIntensity = intensitySystem != IntensitySystem.OFF
    val weightStep = if (unitSystem == UnitSystem.IMPERIAL) 5.0 else 2.5
    val weightStepText = WeightFormatting.formatNumber(weightStep)
    val weightSuffix = WeightFormatting.unitLabel(unitSystem)

    var currentSetType by remember(setNumber, setType) { mutableStateOf(setType) }
    var currentIntention by remember(setNumber) { mutableStateOf(defaultIntention) }
    var intentionDirty by remember(setNumber) { mutableStateOf(false) }
    // Adopt a late-arriving stored answer only while the user has not chosen one.
    LaunchedEffect(defaultIntention) {
        if (!intentionDirty) currentIntention = defaultIntention
    }
    val initialWeight = if (addedLoad && defaultWeight.isBlank()) "0" else defaultWeight
    var weightInput by remember(setNumber, initialWeight) {
        mutableStateOf(TextFieldValue(initialWeight, TextRange(initialWeight.length)))
    }
    var repsInput by remember(setNumber, defaultReps) {
        mutableStateOf(TextFieldValue(defaultReps, TextRange(defaultReps.length)))
    }
    var intensityInput by remember(setNumber, defaultIntensity) {
        mutableStateOf(TextFieldValue(defaultIntensity, TextRange(defaultIntensity.length)))
    }
    var activeSubmissionId by remember(setNumber) { mutableStateOf<Long?>(null) }

    LaunchedEffect(activeSubmissionId, completedSubmissions) {
        val submissionId = activeSubmissionId ?: return@LaunchedEffect
        if (submissionId in completedSubmissions) {
            if (!isEditMode) {
                weightInput = TextFieldValue("", TextRange.Zero)
                repsInput = TextFieldValue("", TextRange.Zero)
                intensityInput = TextFieldValue("", TextRange.Zero)
            }
            activeSubmissionId = null
            haptic.confirm()
        }
    }

    val adjustWeight: (Double) -> Unit = { delta ->
        val current = parseDecimal(weightInput.text) ?: weightPlaceholder?.let(::parseDecimal) ?: 0.0
        val next = maxOf(0.0, ((current + delta) * 100.0).roundToInt() / 100.0)
        val text = WeightFormatting.formatInputNumber(next)
        weightInput = TextFieldValue(text, TextRange(text.length))
        haptic.tick()
    }

    val adjustReps: (Int) -> Unit = { delta ->
        val current = repsInput.text.toIntOrNull() ?: repsPlaceholder?.toIntOrNull() ?: 0
        val next = maxOf(0, current + delta)
        val text = if (next > 0) next.toString() else ""
        repsInput = TextFieldValue(text, TextRange(text.length))
        haptic.tick()
    }

    val liquidGlass = isLiquidGlass()
    CockpitContainer(liquidGlass = liquidGlass, modifier = modifier.padding(vertical = dims.spacingXs)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (liquidGlass) 4.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (liquidGlass) 14.dp else 10.dp)
        ) {
            // Header Row: Set tag + Coach badge
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tagText = when {
                    isEditMode -> stringResource(R.string.workout_active_set_edit_badge, setNumber)
                    currentSetType == SetType.WARMUP ->
                        stringResource(WorkoutR.string.workout_audit_warmup_set, setNumber)
                    currentSetType == SetType.DROP_SET ->
                        stringResource(WorkoutR.string.workout_audit_drop_set, setNumber)
                    currentSetType == SetType.FAILURE ->
                        stringResource(WorkoutR.string.workout_audit_failure_set, setNumber)
                    else -> stringResource(R.string.workout_active_set_badge, setNumber)
                }
                Text(
                    text = tagText,
                    style = AthleticLabel,
                    color = MaterialTheme.accentText,
                    fontWeight = FontWeight.ExtraBold
                )

                if (coachHint != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = coachHint,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.accentText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            coachRecommendation?.let { recommendation ->
                                val suggestedWeight = formatWeightValue(
                                    recommendation.recommendedWeightKg,
                                    unitSystem
                                )
                                TextButton(
                                    onClick = {
                                        val nextValue = TextFieldValue(
                                            suggestedWeight,
                                            TextRange(suggestedWeight.length)
                                        )
                                        // Applying a coach suggestion is an explicit action. It
                                        // updates only the current draft and never overwrites a
                                        // saved or in-flight set by itself.
                                        weightInput = nextValue
                                        haptic.tick()
                                    },
                                    enabled = !locked && activeSubmissionId == null,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    colors = ThrymInteractiveColors.textButton()
                                ) {
                                    Text(
                                        text = stringResource(WorkoutR.string.workout_audit_apply_coach_weight),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.accentText
                                    )
                                }
                            }
                        }
                    }
                }
            }

            valueSource?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.accentText) }
            if (liquidGlass) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassStepper(
                        label = stringResource(if (addedLoad) R.string.weight_added_load else WorkoutR.string.workout_glass_weight),
                        value = weightInput,
                        onValueChange = { weightInput = it },
                        placeholder = weightPlaceholder,
                        unit = weightSuffix,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        enabled = !locked,
                        decreaseLabel = stringResource(WorkoutR.string.workout_glass_weight_decrease, weightStepText, weightSuffix),
                        increaseLabel = stringResource(WorkoutR.string.workout_glass_weight_increase, weightStepText, weightSuffix),
                        onDecrease = { adjustWeight(-weightStep) },
                        onIncrease = { adjustWeight(weightStep) },
                        modifier = Modifier.weight(1f)
                    )
                    GlassStepper(
                        label = stringResource(WorkoutR.string.workout_glass_reps),
                        value = repsInput,
                        onValueChange = { repsInput = it },
                        placeholder = repsPlaceholder,
                        unit = null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        enabled = !locked,
                        decreaseLabel = stringResource(WorkoutR.string.workout_glass_reps_decrease),
                        increaseLabel = stringResource(WorkoutR.string.workout_glass_reps_increase),
                        onDecrease = { adjustReps(-1) },
                        onIncrease = { adjustReps(1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (tracksIntensity) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(WorkoutR.string.workout_glass_intensity_label, intensitySystem.label()).uppercase(),
                            style = AthleticLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val locale = currentLocale()
                        val options = if (intensitySystem == IntensitySystem.RPE) {
                            listOf(7.0, 7.5, 8.0, 8.5, 9.0, 9.5, 10.0).map { rpe ->
                                val value = if (rpe % 1.0 == 0.0) rpe.toInt().toString() else rpe.toString()
                                value to formatRpeValue(rpe, locale)
                            }
                        } else {
                            (0..4).map { it.toString() to it.toString() }
                        }
                        GlassIntensityBar(
                            options = options,
                            selected = intensityInput.text,
                            onSelect = { value ->
                                intensityInput = if (intensityInput.text == value) {
                                    TextFieldValue("", TextRange.Zero)
                                } else {
                                    TextFieldValue(value, TextRange(value.length))
                                }
                                haptic.tick()
                            }
                        )
                    }
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = weightInput, onValueChange = { weightInput = it },
                    label = { Text(if (addedLoad) stringResource(R.string.weight_added_load_field_label, weightSuffix) else weightSuffix) }, singleLine = true,
                    modifier = Modifier.weight(1f), enabled = !locked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = repsInput, onValueChange = { repsInput = it },
                    label = { Text(stringResource(UxR.string.workout_reps_short)) }, singleLine = true,
                    modifier = Modifier.weight(1f), enabled = !locked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                )
            }
            TextButton(onClick = { showDetails = !showDetails }, colors = ThrymInteractiveColors.textButton()) {
                Text(stringResource(if (showDetails) R.string.workout_set_details_hide else R.string.workout_set_details_show))
            }
            if (showDetails) {
            // Set Type Selector (if Extra or Ad-Hoc)
            if (isExtraOrAdHoc) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SetType.entries.forEach { type ->
                        val selected = currentSetType == type
                        Surface(
                            onClick = {
                                currentSetType = type
                                onSetTypeChange?.invoke(type)
                                haptic.tick()
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(id = type.labelRes()),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Intensity Selector Row (RPE / RIR Chips); Liquid Glass shows its own bar above.
            if (tracksIntensity && !liquidGlass) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = intensitySystem.label().uppercase(),
                            style = AthleticLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        if (intensityInput.text.isNotEmpty()) {
                            val rpeVal = parseDecimal(intensityInput.text)
                            val accent = rpeTextColor(rpeVal) ?: MaterialTheme.accentText
                            // Chips store "8.5"; show it with the UI decimal separator ("8,5" in German).
                            val shownValue = parseDecimal(intensityInput.text)?.let { formatRpeValue(it, currentLocale()) }
                                ?: intensityInput.text
                            Text(
                                text = stringResource(UxR.string.workout_intensity_value, intensitySystem.label(), shownValue),
                                style = AthleticLabel,
                                color = accent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (intensitySystem == IntensitySystem.RPE) {
                        val rpeChips = listOf(7.0, 7.5, 8.0, 8.5, 9.0, 9.5, 10.0)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            rpeChips.forEach { rpe ->
                                val rpeStr = if (rpe % 1.0 == 0.0) rpe.toInt().toString() else rpe.toString()
                                val isSelected = intensityInput.text == rpeStr
                                val chipColor = rpeColor(rpe) ?: MaterialTheme.colorScheme.primary

                                Surface(
                                    onClick = {
                                        intensityInput = if (isSelected) {
                                            TextFieldValue("", TextRange.Zero)
                                        } else {
                                            TextFieldValue(rpeStr, TextRange(rpeStr.length))
                                        }
                                        haptic.tick()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) chipColor else chipColor.copy(alpha = 0.12f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) chipColor else chipColor.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = formatRpeValue(rpe, currentLocale()),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) {
                                                val filledColor = chipColor.compositeOver(MaterialTheme.colorScheme.surface)
                                                if (filledColor.luminance() > 0.179f) Color.Black else Color.White
                                            } else {
                                                rpeTextColor(rpe) ?: MaterialTheme.accentText
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val rirChips = listOf(0, 1, 2, 3, 4)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rirChips.forEach { rir ->
                                val rirStr = rir.toString()
                                val isSelected = intensityInput.text == rirStr
                                Surface(
                                    onClick = {
                                        intensityInput = if (isSelected) {
                                            TextFieldValue("", TextRange.Zero)
                                        } else {
                                            TextFieldValue(rirStr, TextRange(rirStr.length))
                                        }
                                        haptic.tick()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(
                                                WorkoutR.string.workout_audit_rir_value,
                                                rirStr
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Embedded Plate Visualizer (zero disruption when disabled)
            if (plateCalculatorEnabled) {
                val parsedW = parseDecimal(weightInput.text) ?: weightPlaceholder?.let(::parseDecimal) ?: 0.0
                val targetWeightKg = WeightFormatting.convertToKg(parsedW, unitSystem)
                val sideWeight = maxOf(0.0, (targetWeightKg - barbellWeightKg) / 2.0)
                val sideWeightDisplay = WeightFormatting.formatWeight(sideWeight, unitSystem)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.workout_barbell_loading_side, sideWeightDisplay).uppercase(),
                                style = AthleticLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PlateVisualizer(
                            targetWeightKg = targetWeightKg,
                            barbellWeightKg = barbellWeightKg,
                            availablePlates = availablePlates,
                            unitSystem = unitSystem,
                            enabled = true
                        )
                    }
                }
            }

            // Log / Update Action Button
            // Set intention: an answer to *why* the set ended, independent of the set
            // type above. UNKNOWN is the default and stores no record.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.workout_set_intention_label).uppercase(),
                        style = AthleticLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Text(
                        text = stringResource(R.string.workout_set_intention_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(start = dims.spacingSm)
                    )
                }
                SetIntentionChipRow(
                    selected = currentIntention,
                    onSelect = { intention ->
                        currentIntention = intention
                        intentionDirty = true
                        haptic.tick()
                    }
                )
                if (intentionFailed) {
                    Text(
                        text = stringResource(R.string.workout_set_intention_load_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticText.danger
                    )
                }
            }

            }

            val enteredWeight = if (weightInput.text.isBlank()) null else parseDecimal(weightInput.text)
            val enteredReps = repsInput.text.toIntOrNull()
            val canLog = !locked && activeSubmissionId == null &&
                enteredReps != null && enteredReps > 0 &&
                enteredWeight != null && enteredWeight.isFinite() && enteredWeight >= 0

            val logAction = {
                    val reps = repsInput.text.toIntOrNull() ?: repsPlaceholder?.toIntOrNull()
                    val weightVal = parseDecimal(weightInput.text) ?: weightPlaceholder?.let(::parseDecimal)
                    val weightKg = weightVal?.let { WeightFormatting.convertToKg(it, unitSystem) }
                    if (reps != null && reps > 0 && weightKg != null && weightKg >= 0 && weightKg.isFinite()) {
                        val submissionId = nextSubmissionId()
                        activeSubmissionId = submissionId
                        haptic.confirm()
                        onLog(
                            reps,
                            weightKg,
                            currentSetType,
                            if (intensitySystem != IntensitySystem.OFF) intensityInput.text else "",
                            submissionId,
                            // null = the user made no deliberate choice, so an edit keeps
                            // the stored answer instead of erasing it.
                            if (intentionDirty) currentIntention else null
                        )
                    }
            }
            val btnText = when {
                isEditMode -> stringResource(R.string.workout_update_set_button, setNumber)
                enteredWeight != null && enteredReps != null -> {
                    if (enteredWeight == 0.0) {
                        pluralStringResource(
                            R.plurals.workout_log_set_button_bodyweight,
                            enteredReps,
                            setNumber,
                            stringResource(R.string.weight_bodyweight),
                            enteredReps
                        )
                    } else if (addedLoad) {
                        val wStr = WeightFormatting.formatNumber(enteredWeight, maxFractionDigits = 2)
                        pluralStringResource(
                            R.plurals.workout_log_set_button_bodyweight,
                            enteredReps,
                            setNumber,
                            stringResource(R.string.weight_bodyweight_plus_load, "$wStr $weightSuffix"),
                            enteredReps
                        )
                    } else {
                        val wStr = WeightFormatting.formatNumber(enteredWeight, maxFractionDigits = 2)
                        pluralStringResource(R.plurals.workout_log_set_button, enteredReps, setNumber, wStr, weightSuffix, enteredReps)
                    }
                }
                else -> stringResource(R.string.workout_log_set_button_short, setNumber)
            }

            if (liquidGlass) {
                GlassPrimaryButton(text = btnText, enabled = canLog, onClick = logAction)
            } else Button(
                onClick = logAction,
                enabled = canLog,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = btnText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isEditMode && (onCancelEdit != null || onDelete != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onDelete != null) {
                        TextButton(onClick = onDelete, enabled = !locked, colors = ThrymInteractiveColors.textButton()) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(id = R.string.workout_delete_set_cd),
                                color = MaterialTheme.semanticText.danger
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    if (onCancelEdit != null) {
                        TextButton(onClick = onCancelEdit, colors = ThrymInteractiveColors.textButton()) {
                            Text(stringResource(id = R.string.common_cancel))
                        }
                    }
                }
            }
        }
    }
}

/** Ember: bordered accent surface. Liquid Glass: no own surface, the exercise card is the glass. */
@Composable
private fun CockpitContainer(
    liquidGlass: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    if (liquidGlass) {
        Box(modifier.fillMaxWidth()) { content() }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
        ) {
            content()
        }
    }
}

/**
 * Tri-state selector for a set's intention, stacked vertically so the long German labels
 * always wrap instead of being clipped. The selection is nullable: `null` means "nothing
 * stored or not read yet" and highlights no chip, while [SetIntention.UNKNOWN] is an
 * explicit "no answer" choice that clears any stored record.
 */
@Composable
internal fun SetIntentionChipRow(
    selected: SetIntention?,
    onSelect: (SetIntention) -> Unit
) {
    val options = listOf(
        SetIntention.UNKNOWN,
        SetIntention.PLANNED_FAILURE,
        SetIntention.UNEXPECTED_TARGET_MISS
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelect(option) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
                border = BorderStroke(
                    1.dp,
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = stringResource(option.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
