package com.thrym.app.presentation.workout

import com.thrym.shared.workout.SessionSetBadge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.HapticFeedbackHelper
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.label
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.setLoadText
import com.thrym.app.presentation.common.tracksAddedLoad
import com.thrym.app.presentation.common.currentLocale
import com.thrym.shared.readinessdata.SetIntention
import androidx.compose.material3.Surface
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.IconButton
import com.thrym.app.presentation.theme.AthleticLabel
import com.thrym.app.presentation.theme.ButtonSize
import com.thrym.app.presentation.theme.IconSize
import com.thrym.app.presentation.theme.Radius
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText

@Composable
internal fun ExerciseCard(
    exerciseWithSets: ExerciseWithSets,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    nextSetRecommendation: NextSetRecommendationUi?,
    tintColor: Color?,
    defaultWarmupFlag: Boolean,
    intensitySystem: IntensitySystem,
    unitSystem: UnitSystem,
    plateCalculatorEnabled: Boolean,
    availablePlates: List<Double>,
    barbellWeightKg: Double,
    isLogging: Boolean,
    logSuccessSubmissions: Set<Long>,
    updateInFlightBySet: Map<Long, Int>,
    updateSuccessCountBySet: Map<Long, Int>,
    setIntentions: Map<Long, SetIntention>,
    setIntentionsLoaded: Boolean,
    setIntentionsFailed: Boolean,
    /** Record markers of this workout's sets by set id, see `SessionSetBadges`. */
    setBadges: Map<Long, SessionSetBadge> = emptyMap(),
    onLogSet: (Int, Double, SetType, String, Long, SetIntention?) -> Unit,
    onUpdateSet: (Long, Int, Double, String, SetIntention?) -> Unit,
    onDeleteSet: (Long) -> Unit,
    haptic: HapticFeedbackHelper,
    moveControls: ExerciseMoveControls? = null
) {
    val dims = thrymDimens
    val planTarget = exerciseWithSets.planTarget
    val previousSession = exerciseWithSets.previousSession
    val rowIntensitySystem = if (
        intensitySystem == IntensitySystem.OFF && planTarget?.config is ProgressionConfig.RpeRir
    ) {
        IntensitySystem.RPE
    } else {
        intensitySystem
    }
    val showHistoryToggle = previousSession != null
    val previousWeightHint = previousSession?.lastWorkSetWeightKg?.let { formatWeightValue(it, unitSystem) }
    val addedLoad = exerciseWithSets.exercise.tracksAddedLoad
    // RPE_RIR plan targets show what to enter into the intensity field; the
    // stored RPE is 10 - RIR when the RIR scale is active, so the hint mirrors
    // the displayed scale. A missing value would otherwise surface as
    // RPE_MISSING with no indication that the field needs input.
    val intensityPlaceholder = (planTarget?.config as? ProgressionConfig.RpeRir)?.let { config ->
        val hint = when (rowIntensitySystem) {
            IntensitySystem.RIR -> 10.0 - config.targetRpe
            else -> config.targetRpe
        }
        formatRpeValue(hint)
    }
    var showPreviousSession by remember(exerciseWithSets.key) { mutableStateOf(false) }
    val loggedSets = exerciseWithSets.sets.filter { it.reps > 0 }
    val completedWorkSets = loggedSets.count { (it.setType == SetType.NORMAL || it.setType == SetType.FAILURE) }
    val slots = planTarget?.loggingSlots().orEmpty()
    val targetSetCount = slots.size

    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        // Liquid Glass lifts the open exercise to the strong glass level.
        tone = if (expanded && isLiquidGlass()) ThrymSurfaceTone.ELEVATED else ThrymSurfaceTone.MUTED,
        border = tintColor?.let { BorderStroke(1.dp, it.copy(alpha = 0.3f)) },
        alpha = 1f
    ) {
        Column(modifier = Modifier.padding(dims.spacingMd)) {
            val exerciseName = exerciseWithSets.exercise.localizedName()
            val openSlots = exerciseWithSets.openSlotCount()
            val isDone = planTarget != null && targetSetCount > 0 && openSlots == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(
                            if (expanded) R.string.workout_exercise_collapse_cd else R.string.workout_exercise_expand_cd,
                            exerciseName
                        ),
                        onClick = onToggleExpanded
                    )
                    .padding(vertical = dims.spacingXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = exerciseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // The exercise carries the strongest marker of its sets: a mark before "PR".
                val exerciseBadges = loggedSets.mapNotNull { setBadges[it.id] }
                exerciseBadges.firstOrNull { it.mark != null }?.let { RecordMarker(it, Modifier.padding(start = dims.spacingXs)) }
                    ?: exerciseBadges.firstOrNull()?.let { RecordMarker(it, Modifier.padding(start = dims.spacingXs)) }
                Spacer(Modifier.weight(1f))
                val progressText = if (targetSetCount > 0) {
                    stringResource(R.string.workout_exercise_progress, targetSetCount - openSlots, targetSetCount)
                } else if (loggedSets.isNotEmpty()) {
                    loggedSets.size.toString()
                } else {
                    null
                }
                progressText?.let {
                    Text(
                        text = it,
                        style = AthleticLabel,
                        color = if (isDone) MaterialTheme.semanticText.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = dims.spacingXs)
                    )
                }
                if (expanded && moveControls != null) {
                    ExerciseMoveButtons(
                        controls = moveControls,
                        moveUpDescription = stringResource(WorkoutR.string.workout_exercise_move_up_cd, exerciseName),
                        moveDownDescription = stringResource(WorkoutR.string.workout_exercise_move_down_cd, exerciseName)
                    )
                }
                if (showHistoryToggle && expanded) {
                    IconButton(
                        onClick = { showPreviousSession = !showPreviousSession },
                        modifier = Modifier.size(ButtonSize.iconButton)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = if (showPreviousSession) {
                                stringResource(id = R.string.workout_previous_hide_cd)
                            } else {
                                stringResource(id = R.string.workout_previous_show_cd)
                            },
                            tint = if (showPreviousSession) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(IconSize.sm)
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.sm)
                )
            }

            if (planTarget != null) {
                val targetText = if (planTarget.target.weightKg > 0) {
                    stringResource(
                        id = WorkoutR.string.workout_audit_plan_target_with_weight,
                        planTarget.target.sets,
                        planTarget.target.reps,
                        setLoadText(planTarget.target.weightKg, unitSystem, addedLoad)
                    )
                } else {
                    stringResource(
                        id = WorkoutR.string.workout_audit_plan_target_no_weight,
                        planTarget.target.sets,
                        planTarget.target.reps
                    )
                }
                Text(
                    text = if (isDone && !expanded) {
                        "${stringResource(R.string.workout_exercise_done)} · $targetText"
                    } else {
                        targetText
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDone) MaterialTheme.semanticText.success else MaterialTheme.accentText
                )
                if (expanded) Text(
                    text = stringResource(
                        id = R.string.workout_progression_scheme,
                        progressionSchemeLabel(planTarget.config)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (expanded && previousSession?.lastWorkSetReachedTarget == true) {
                    Text(
                        text = stringResource(id = R.string.workout_previous_last_set_target_reached),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.semanticText.success
                    )
                }
                if (expanded && completedWorkSets >= planTarget.target.sets) {
                    Text(
                        text = stringResource(id = R.string.workout_target_completed),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.semanticText.success
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(dims.spacingXs))

                if (planTarget != null && targetSetCount > 0) {
                    val matched = com.thrym.shared.plans.PlannedSets.matchedIndices(slots, loggedSets.map { it.setType.name })
                    val nextSlotIndex = matched.indexOfFirst { it == null }

                    for (setIndex in 1..targetSetCount) {
                        val slot = slots[setIndex - 1]
                        val matchingSet = matched[setIndex - 1]?.let { loggedSets[it] }
                        if (matchingSet != null) {
                            LoggedSetRow(
                                set = matchingSet,
                                badge = setBadges[matchingSet.id],
                                displayNumber = setIndex,
                                intention = setIntentions[matchingSet.id],
                                intentionFailed = setIntentionsFailed,
                                intensitySystem = rowIntensitySystem,
                                unitSystem = unitSystem,
                                addedLoad = addedLoad,
                                plateCalculatorEnabled = plateCalculatorEnabled,
                                availablePlates = availablePlates,
                                barbellWeightKg = barbellWeightKg,
                                isUpdating = (updateInFlightBySet[matchingSet.id] ?: 0) > 0,
                                updateSuccessCount = updateSuccessCountBySet[matchingSet.id] ?: 0,
                                onUpdateSet = onUpdateSet,
                                onDeleteSet = onDeleteSet,
                                haptic = haptic
                            )
                        } else {
                            val isNextToLog = setIndex - 1 == nextSlotIndex
                            if (isNextToLog) {
                                val prefill = planSlotWeightPrefill(
                                    slot = slot,
                                    hasSetTargets = planTarget.setTargets.isNotEmpty(),
                                    lastWorkSetWeightKg = loggedSets.lastOrNull { (it.setType == SetType.NORMAL || it.setType == SetType.FAILURE) }?.weightKg,
                                    previousWorkoutWeightKg = previousSession?.sets?.let(::previousWorkoutWorkSetWeightKg)
                                )
                                val coachHint = nextSetRecommendation?.recommendedWeightKg?.let {
                                    stringResource(
                                        WorkoutR.string.workout_audit_coach_suggestion,
                                        "${formatWeightValue(it, unitSystem)} ${WeightFormatting.unitLabel(unitSystem)}"
                                    )
                                }
                                ActiveSetCockpitCard(
                                    setNumber = setIndex,
                                    setType = when (slot.kind) { "WARMUP" -> SetType.WARMUP; "BACKOFF" -> SetType.BACKOFF; else -> SetType.NORMAL },
                                    isExtraOrAdHoc = false,
                                    isEditMode = false,
                                    coachHint = coachHint,
                                    valueSource = stringResource(
                                        when (prefill.source) {
                                            PlanSlotWeightSource.LAST_SET -> R.string.workout_value_source_last_set
                                            PlanSlotWeightSource.LAST_WORKOUT -> R.string.workout_value_source_last_workout
                                            PlanSlotWeightSource.PLAN -> R.string.workout_value_source_plan
                                        }
                                    ),
                                    coachRecommendation = nextSetRecommendation,
                                    defaultWeight = formatWeightValue(prefill.weightKg, unitSystem),
                                    weightPlaceholder = targetWeightHint(planTarget, unitSystem, previousWeightHint),
                                    defaultReps = (if (planTarget.setTargets.isEmpty()) loggedSets.lastOrNull { (it.setType == SetType.NORMAL || it.setType == SetType.FAILURE) }?.reps ?: slot.reps else slot.reps).toString(),
                                    repsPlaceholder = if (planTarget.target.reps > 0) planTarget.target.reps.toString() else null,
                                    defaultIntensity = "",
                                    intensityPlaceholder = intensityPlaceholder,
                                    intensitySystem = rowIntensitySystem,
                                    unitSystem = unitSystem,
                                    addedLoad = addedLoad,
                                    locked = isLogging,
                                    completedSubmissions = logSuccessSubmissions,
                                    plateCalculatorEnabled = plateCalculatorEnabled,
                                    availablePlates = availablePlates,
                                    barbellWeightKg = barbellWeightKg,
                                    haptic = haptic,
                                    intentionFailed = setIntentionsFailed,
                                    onLog = { reps, weight, setType, intensity, submissionId, intention ->
                                        onLogSet(reps, weight, setType, intensity, submissionId, intention)
                                    }
                                )
                            } else {
                                PlannedSetPreviewRow(
                                    setNumber = setIndex,
                                    targetWeightKg = slot.weightKg,
                                    targetReps = slot.reps.toString(),
                                    unitSystem = unitSystem,
                                    addedLoad = addedLoad
                                )
                            }
                        }
                    }

                    loggedSets.filterIndexed { index, _ -> index !in matched.filterNotNull() }.forEach { set ->
                        LoggedSetRow(
                            set = set,
                            badge = setBadges[set.id],
                            intention = setIntentions[set.id],
                            intentionFailed = setIntentionsFailed,
                            intensitySystem = rowIntensitySystem,
                            unitSystem = unitSystem,
                            addedLoad = addedLoad,
                            plateCalculatorEnabled = plateCalculatorEnabled,
                            availablePlates = availablePlates,
                            barbellWeightKg = barbellWeightKg,
                            isUpdating = (updateInFlightBySet[set.id] ?: 0) > 0,
                            updateSuccessCount = updateSuccessCountBySet[set.id] ?: 0,
                            onUpdateSet = onUpdateSet,
                            onDeleteSet = onDeleteSet,
                            haptic = haptic
                        )
                    }

                    Spacer(modifier = Modifier.height(dims.spacingXs))

                    var showExtraInput by remember { mutableStateOf(false) }
                    AnimatedVisibility(visible = showExtraInput) {
                        val nextExtraSetNumber = loggedSets.size + 1
                        ActiveSetCockpitCard(
                            setNumber = nextExtraSetNumber,
                            setType = if (defaultWarmupFlag) SetType.WARMUP else SetType.NORMAL,
                            isExtraOrAdHoc = true,
                            isEditMode = false,
                            coachHint = null,
                            defaultWeight = loggedSets.lastOrNull()?.let { formatWeightValue(it.weightKg, unitSystem) } ?: targetWeightHint(planTarget, unitSystem, previousWeightHint).orEmpty(),
                            weightPlaceholder = targetWeightHint(planTarget, unitSystem, previousWeightHint),
                            defaultReps = (loggedSets.lastOrNull()?.reps ?: planTarget.target.reps).toString(),
                            repsPlaceholder = if (planTarget.target.reps > 0) planTarget.target.reps.toString() else null,
                            defaultIntensity = "",
                            intensityPlaceholder = intensityPlaceholder,
                            intensitySystem = rowIntensitySystem,
                            unitSystem = unitSystem,
                            addedLoad = addedLoad,
                            locked = isLogging,
                            completedSubmissions = logSuccessSubmissions,
                            plateCalculatorEnabled = plateCalculatorEnabled,
                            availablePlates = availablePlates,
                            barbellWeightKg = barbellWeightKg,
                            haptic = haptic,
                            intentionFailed = setIntentionsFailed,
                            onLog = { reps, weight, setType, intensity, submissionId, intention ->
                                onLogSet(reps, weight, setType, intensity, submissionId, intention)
                            },
                            onCancelEdit = { showExtraInput = false }
                        )
                    }
                    if (!showExtraInput) {
                        TextButton(onClick = { showExtraInput = true }, colors = ThrymInteractiveColors.textButton()) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(id = R.string.workout_add_extra_set))
                        }
                    }
                } else {
                    loggedSets.forEach { set ->
                        LoggedSetRow(
                            set = set,
                            badge = setBadges[set.id],
                            intention = setIntentions[set.id],
                            intentionFailed = setIntentionsFailed,
                            intensitySystem = rowIntensitySystem,
                            unitSystem = unitSystem,
                            addedLoad = addedLoad,
                            plateCalculatorEnabled = plateCalculatorEnabled,
                            availablePlates = availablePlates,
                            barbellWeightKg = barbellWeightKg,
                            isUpdating = (updateInFlightBySet[set.id] ?: 0) > 0,
                            updateSuccessCount = updateSuccessCountBySet[set.id] ?: 0,
                            onUpdateSet = onUpdateSet,
                            onDeleteSet = onDeleteSet,
                            haptic = haptic
                        )
                    }

                    Spacer(modifier = Modifier.height(dims.spacingXs))

                    val nextSetNumber = loggedSets.size + 1
                    ActiveSetCockpitCard(
                        setNumber = nextSetNumber,
                        setType = if (defaultWarmupFlag) SetType.WARMUP else SetType.NORMAL,
                        isExtraOrAdHoc = true,
                        isEditMode = false,
                        coachHint = null,
                        defaultWeight = loggedSets.lastOrNull()?.let { formatWeightValue(it.weightKg, unitSystem) } ?: previousWeightHint.orEmpty(),
                        weightPlaceholder = previousWeightHint,
                        defaultReps = loggedSets.lastOrNull()?.reps?.toString().orEmpty(),
                        repsPlaceholder = null,
                        defaultIntensity = "",
                        intensityPlaceholder = intensityPlaceholder,
                        intensitySystem = rowIntensitySystem,
                        unitSystem = unitSystem,
                        addedLoad = addedLoad,
                        locked = isLogging,
                        completedSubmissions = logSuccessSubmissions,
                        plateCalculatorEnabled = plateCalculatorEnabled,
                        availablePlates = availablePlates,
                        barbellWeightKg = barbellWeightKg,
                        haptic = haptic,
                        intentionFailed = setIntentionsFailed,
                        onLog = { reps, weight, setType, intensity, submissionId, intention ->
                            onLogSet(reps, weight, setType, intensity, submissionId, intention)
                        }
                    )
                }

                nextSetRecommendation?.let { recommendation ->
                    NextSetRecommendationChips(
                        recommendation = recommendation,
                        unitSystem = unitSystem,
                        modifier = Modifier.padding(top = dims.spacingXs)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded && showPreviousSession && previousSession != null,
                enter = fadeIn() + expandVertically(animationSpec = spring()),
                exit = fadeOut() + shrinkVertically(animationSpec = spring())
            ) {
                previousSession?.let {
                    PreviousSessionMiniHistory(
                        previousSession = it,
                        intensitySystem = rowIntensitySystem,
                        unitSystem = unitSystem,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = dims.spacingSm)
                    )
                }
            }
        }
    }
}

@Composable
internal fun PreviousSessionMiniHistory(
    previousSession: PreviousExerciseSessionUi,
    intensitySystem: IntensitySystem,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier
) {
    val dims = thrymDimens
    val dateLabel = previousSession.sessionStart.format(DateFormatting.dayMonth(currentLocale()))

    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.semantic.skyLight.copy(alpha = 0.35f),
                shape = MaterialTheme.shapes.small
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.semantic.sky.copy(alpha = 0.28f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = dims.spacingSm, vertical = dims.spacingXs),
        verticalArrangement = Arrangement.spacedBy(dims.spacing2)
    ) {
        Text(
            text = stringResource(id = WorkoutR.string.workout_audit_previous_session_title, dateLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = dims.spacing2),
            verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
        ) {
            previousSession.sets.forEach { set ->
                PreviousSessionSetRow(
                    set = set,
                    intensitySystem = intensitySystem,
                    unitSystem = unitSystem
                )
            }
        }
    }
}

@Composable
internal fun PreviousSessionSetRow(
    set: com.thrym.app.domain.model.WorkoutSet,
    intensitySystem: IntensitySystem,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier
) {
    val dims = thrymDimens
    val tracksIntensity = intensitySystem != IntensitySystem.OFF
    val setLabel = setTypeLabel(set.setNumber, set.setType)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = dims.spacing2),
        horizontalArrangement = Arrangement.spacedBy(dims.spacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = setLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(20.dp)
        )

        val weightText = formatWeightValue(set.weightKg, unitSystem)

        LoggedSetBox(
            value = weightText,
            suffix = WeightFormatting.unitLabel(unitSystem),
            isWarmup = set.isWarmup,
            modifier = Modifier.weight(1.2f).alpha(0.7f)
        )

        LoggedSetBox(
            value = set.reps.toString(),
            suffix = stringResource(id = R.string.common_reps_short),
            isWarmup = set.isWarmup,
            modifier = Modifier.weight(1f).alpha(0.7f)
        )

        if (tracksIntensity) {
            val intensityText = formatIntensity(set.rpe, intensitySystem)
            val accentColor = rpeColor(set.rpe)

            LoggedSetBox(
                value = intensityText,
                suffix = intensitySystem.label(),
                isWarmup = set.isWarmup,
                modifier = Modifier.weight(1f).alpha(0.7f),
                overrideContainerColor = accentColor?.copy(alpha = 0.15f),
                overrideContentColor = rpeTextColor(set.rpe)
            )
        }

        // Placeholder for the delete button to maintain perfect alignment with LoggedSetRow
        Spacer(modifier = Modifier.size(40.dp))
    }
}

@Composable
internal fun LoggedSetBox(
    value: String,
    suffix: String,
    isWarmup: Boolean,
    modifier: Modifier = Modifier,
    overrideContainerColor: Color? = null,
    overrideContentColor: Color? = null
) {
    val dims = thrymDimens
    val containerColor = overrideContainerColor ?: if (isWarmup) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }

    Box(
        modifier = modifier
            .height(ButtonSize.iconButton)
            .background(color = containerColor, shape = RoundedCornerShape(Radius.sm)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = dims.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = value,
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = overrideContentColor ?: if (isWarmup) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    fontStyle = if (isWarmup) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.bodyMedium,
                    color = (overrideContentColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.9f),
                    modifier = Modifier.padding(start = dims.spacingXs)
                )
            }
        }
    }
}

@Composable
internal fun NextSetRecommendationChips(
    recommendation: NextSetRecommendationUi,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier
) {
    val dims = thrymDimens
    val weightText = formatWeightValue(recommendation.recommendedWeightKg, unitSystem)
    val delta = recommendation.recommendedWeightKg - recommendation.lastWeightKg
    val loadText = if (kotlin.math.abs(delta) < 0.05) {
        stringResource(id = R.string.workout_autoregulation_next_set, weightText)
    } else {
        stringResource(
            id = R.string.workout_autoregulation_next_set_delta,
            weightText,
            WeightFormatting.formatWeightDelta(delta, unitSystem)
        )
    }
    val accent = if (recommendation.isOvershoot) {
        MaterialTheme.semantic.danger
    } else {
        MaterialTheme.semantic.sky
    }
    val accentText = if (recommendation.isOvershoot) {
        MaterialTheme.semanticText.danger
    } else {
        MaterialTheme.semanticText.sky
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dims.spacingXs),
        verticalArrangement = Arrangement.spacedBy(dims.spacingXs)
    ) {
        recommendation.targetRpe?.let { target ->
            RecommendationPill(
                text = stringResource(id = R.string.workout_autoregulation_target_rpe, formatRpeValue(target)),
                color = MaterialTheme.semantic.sky,
                textColor = MaterialTheme.semanticText.sky
            )
        }
        if (recommendation.isOvershoot) {
            RecommendationPill(
                text = stringResource(
                    id = R.string.workout_autoregulation_overshoot,
                    formatRpeValue(recommendation.lastRpe)
                ),
                color = MaterialTheme.semantic.danger,
                textColor = MaterialTheme.semanticText.danger
            )
        }
        RecommendationPill(
            text = stringResource(WorkoutR.string.workout_audit_coach_suggestion, loadText),
            color = accent,
            textColor = accentText
        )
        recommendation.backoffWeightKg?.let { backoff ->
            RecommendationPill(
                text = stringResource(
                    id = R.string.workout_autoregulation_backoff_set,
                    formatWeightValue(backoff, unitSystem)
                ),
                color = MaterialTheme.semantic.danger,
                textColor = MaterialTheme.semanticText.danger
            )
        }
    }
}

@Composable
internal fun RecommendationPill(
    text: String,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun PlannedSetPreviewRow(
    setNumber: Int,
    targetWeightKg: Double?,
    targetReps: String?,
    unitSystem: UnitSystem,
    addedLoad: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dims = thrymDimens
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = dims.spacingXs),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = setNumber.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val loadLabel = targetWeightKg?.let { setLoadText(it, unitSystem, addedLoad) }
            val targetDesc = buildString {
                if (loadLabel != null) append(loadLabel)
                if (targetReps != null) {
                    if (isNotEmpty()) append(" × ")
                    append(targetReps)
                    append(" ")
                    append(
                        targetReps.toIntOrNull()?.let { pluralStringResource(R.plurals.common_reps_unit, it) }
                            ?: stringResource(R.string.common_reps_short)
                    )
                }
            }

            Text(
                text = stringResource(R.string.workout_planned_set_preview, setNumber, targetDesc.ifEmpty { "-" }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
