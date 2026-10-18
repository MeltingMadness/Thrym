package com.thrym.app.presentation.plans

import com.thrym.feature.plans.R as UxR
import com.thrym.app.presentation.common.tracksAddedLoad
import com.thrym.app.presentation.common.ThrymTopBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.core.designsystem.R
import com.thrym.feature.plans.R as PlansR
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.ProgressionScheme
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.EmptyStateScreen
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semanticText
import com.thrym.app.presentation.workout.ExercisePickerSheet
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditorScreen(
    onBack: () -> Unit,
    viewModel: PlanEditorViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val dims = thrymDimens

    fun requestBack() {
        if (state.isSaving) return
        if (state.hasUnsavedChanges && !state.isSaved) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    // Keep the back event consumed while a repository write is in flight so
    // a late completion cannot navigate away from newly entered values.
    BackHandler(onBack = ::requestBack)

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onBack()
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    ThrymScreenScaffold(
        topBar = {
            ThrymTopBar(
                title = {
                    Text(
                        if (viewModel.isEditMode) {
                            stringResource(id = R.string.plan_editor_title_edit)
                        } else {
                            stringResource(id = R.string.plan_editor_title_new)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack, enabled = !state.isSaving) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.nav_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::savePlan,
                        enabled = !state.isSaving && !state.isLoading
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(id = R.string.plan_editor_save_cd)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.notFound) {
            EmptyStateScreen(
                title = stringResource(id = R.string.plan_editor_not_found_title),
                subtitle = stringResource(id = R.string.plan_editor_not_found_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                action = {
                    TextButton(onClick = onBack, colors = ThrymInteractiveColors.textButton()) {
                        Text(text = stringResource(id = R.string.nav_back))
                    }
                }
            )
            return@ThrymScreenScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = dims.spacingMd),
            verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
        ) {
            item {
                Spacer(modifier = Modifier.height(dims.spacing2))
                com.thrym.app.presentation.common.ThrymTextField(
                    value = state.planName,
                    onValueChange = viewModel::updatePlanName,
                    label = { Text(stringResource(id = R.string.plan_editor_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                TextButton(
                    onClick = viewModel::showExercisePicker,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ThrymInteractiveColors.textButton()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(id = R.string.plan_editor_add_exercise))
                }
            }

            itemsIndexed(state.exercises) { index, item ->
                PlanExerciseCard(
                    item = item,
                    index = index,
                    isFirst = index == 0,
                    isLast = index == state.exercises.size - 1,
                    onGroupWithPrevious = { viewModel.groupWithPrevious(index) },
                    onUngroup = { viewModel.ungroup(index) },
                    onMoveUp = { viewModel.moveUp(index) },
                    onMoveDown = { viewModel.moveDown(index) },
                    onRemove = { viewModel.removeExercise(index) },
                    onSetsChange = { viewModel.updateTargetSetsInput(index, it) },
                    onRepsChange = { viewModel.updateTargetRepsInput(index, it) },
                    onWeightChange = { viewModel.updateTargetWeightInput(index, it) },
                    targetInputErrors = state.targetInputErrors[index].orEmpty(),
                    onTargetInputFocusLost = { field ->
                        viewModel.validateTargetInput(index, field)
                    },
                    onOpenProgression = { viewModel.openProgressionEditor(index) },
                    onSetTargetsChange = { viewModel.updateSetTargets(index, it) }
                )
            }

            item { Spacer(modifier = Modifier.height(dims.spacingXl)) }
        }

        if (state.showExercisePicker) {
            ExercisePickerSheet(
                onDismiss = viewModel::dismissExercisePicker,
                onExerciseSelected = { exercise ->
                    viewModel.addExercise(exercise)
                },
                allowCreateCustomExercise = true,
                onCreationError = viewModel::onPickerError
            )
        }

        state.progressionEditor?.let { draft ->
            ProgressionEditorSheet(
                draft = draft,
                onSchemeSelected = viewModel::chooseProgressionScheme,
                onFieldChanged = viewModel::updateProgressionField,
                onDismiss = viewModel::dismissProgressionEditor,
                onApply = viewModel::saveProgressionEditor
            )
        }

        if (showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                title = { Text(stringResource(id = PlansR.string.plan_editor_discard_title)) },
                text = { Text(stringResource(id = PlansR.string.plan_editor_discard_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDiscardDialog = false
                            onBack()
                        },
                        colors = ThrymInteractiveColors.textButton()
                    ) {
                        Text(stringResource(id = PlansR.string.plan_editor_discard_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardDialog = false }, colors = ThrymInteractiveColors.textButton()) {
                        Text(stringResource(id = PlansR.string.plan_editor_discard_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun PlanExerciseCard(
    item: PlanExerciseUi,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onGroupWithPrevious: () -> Unit,
    onUngroup: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onSetsChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    targetInputErrors: Set<PlanExerciseNumericField>,
    onTargetInputFocusLost: (PlanExerciseNumericField) -> Unit,
    onOpenProgression: () -> Unit,
    onSetTargetsChange: (List<com.thrym.shared.plans.PlannedSet>) -> Unit
) {
    val dims = thrymDimens
    var showSetTargets by remember { mutableStateOf(false) }
    if (showSetTargets) {
        IndividualSetTargetsSheet(item, onDismiss = { showSetTargets = false }, onApply = {
            onSetTargetsChange(it)
            showSetTargets = false
        })
    }
    val supersetGroupId = item.planExercise.supersetGroupId

    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = ThrymSurfaceTone.MUTED,
        alpha = 0.68f,
        border = if (supersetGroupId != null) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        } else {
            null
        }
    ) {
        Column(modifier = Modifier.padding(dims.spacingSm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.plan_editor_exercise_indexed, index + 1, item.exercise.localizedName()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Row {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = !isFirst,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropUp,
                            contentDescription = stringResource(id = R.string.plan_editor_move_up_cd),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = !isLast,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(id = R.string.plan_editor_move_down_cd),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(id = R.string.plan_editor_remove_cd),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(dims.spacing2))

            SupersetStatusBadge(supersetGroupId = supersetGroupId)

            Spacer(modifier = Modifier.height(dims.spacing2))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dims.spacingXs)
            ) {
                TextButton(
                    onClick = onGroupWithPrevious,
                    enabled = !isFirst,
                    colors = ThrymInteractiveColors.textButton()
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(dims.spacing2))
                    Text(stringResource(id = R.string.plan_editor_group_with_previous))
                }
                if (supersetGroupId != null) {
                    TextButton(
                        onClick = onUngroup,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.semanticText.danger
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(dims.spacing2))
                        Text(stringResource(id = R.string.plan_editor_ungroup))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = item.planExercise.setTargets.isEmpty(), onClick = { onSetTargetsChange(emptyList()) }, label = { Text(stringResource(UxR.string.plan_targets_simple)) }, colors = ThrymInteractiveColors.filterChip())
                FilterChip(selected = item.planExercise.setTargets.isNotEmpty(), onClick = { showSetTargets = true }, label = { Text(stringResource(UxR.string.plan_targets_individual)) }, colors = ThrymInteractiveColors.filterChip())
            }
            if (item.planExercise.setTargets.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dims.spacingXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TargetNumericInput(
                    value = item.targetSetsInput,
                    onValueChange = onSetsChange,
                    label = { Text(stringResource(id = R.string.plan_editor_sets_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    isError = PlanExerciseNumericField.SETS in targetInputErrors,
                    errorText = stringResource(id = PlansR.string.plan_editor_target_sets_error),
                    onFocusLost = { onTargetInputFocusLost(PlanExerciseNumericField.SETS) }
                )
                TargetNumericInput(
                    value = item.targetRepsInput,
                    onValueChange = onRepsChange,
                    label = { Text(stringResource(id = R.string.common_reps_short)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    isError = PlanExerciseNumericField.REPS in targetInputErrors,
                    errorText = stringResource(id = PlansR.string.plan_editor_target_reps_error),
                    onFocusLost = { onTargetInputFocusLost(PlanExerciseNumericField.REPS) }
                )
                TargetNumericInput(
                    value = item.targetWeightInput,
                    onValueChange = onWeightChange,
                    label = {
                        Text(
                            stringResource(
                                id = if (item.exercise.tracksAddedLoad) R.string.weight_added_load_field_label else R.string.plan_editor_weight_label,
                                WeightFormatting.unitLabel(item.targetWeightInputUnit)
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(90.dp),
                    singleLine = true,
                    isError = PlanExerciseNumericField.WEIGHT in targetInputErrors,
                    errorText = stringResource(id = PlansR.string.plan_editor_target_weight_error),
                    onFocusLost = { onTargetInputFocusLost(PlanExerciseNumericField.WEIGHT) }
                )
            }

            } else {
                item.planExercise.setTargets.forEachIndexed { i, target ->
                    Text(pluralStringResource(UxR.plurals.plan_target_summary, target.reps, i + 1, plannedSetLabel(target.kind), WeightFormatting.formatWeight(target.weightKg, item.targetWeightInputUnit), target.reps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
                }
                TextButton(onClick = { showSetTargets = true }, colors = ThrymInteractiveColors.textButton()) { Text(stringResource(UxR.string.plan_targets_edit)) }
                Text(stringResource(UxR.string.plan_targets_manual_hint), style = MaterialTheme.typography.bodySmall)
            }

            TextButton(
                onClick = onOpenProgression,
                enabled = item.planExercise.setTargets.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ThrymInteractiveColors.textButton()
            ) {
                Text(progressionSummary(item.planExercise.progressionConfig))
            }
        }
    }
}

@Composable
private fun TargetNumericInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier,
    singleLine: Boolean,
    isError: Boolean,
    errorText: String,
    onFocusLost: () -> Unit
) {
    val dims = thrymDimens
    var wasFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.onFocusChanged { focusState ->
            if (focusState.hasFocus) {
                wasFocused = true
            } else if (wasFocused) {
                wasFocused = false
                onFocusLost()
            }
        }
    ) {
        com.thrym.app.presentation.common.ThrymTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine
        )
        if (isError) {
            Spacer(modifier = Modifier.height(dims.spacing2))
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticText.danger
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressionEditorSheet(
    draft: ProgressionEditorUi,
    onSchemeSelected: (ProgressionScheme) -> Unit,
    onFieldChanged: (ProgressionField, String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit
) {
    val dims = thrymDimens
    var showDetails by remember(draft.exerciseIndex) { mutableStateOf(false) }
    LaunchedEffect(draft.errors) {
        if (draft.errors.isNotEmpty()) showDetails = true
    }
    val schemes = listOf(
        ProgressionScheme.MANUAL,
        ProgressionScheme.LINEAR,
        ProgressionScheme.DOUBLE,
        ProgressionScheme.TOTAL_REPS,
        ProgressionScheme.RPE_RIR
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()
                .padding(horizontal = dims.spacingMd, vertical = dims.spacingSm),
            verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
        ) {
            Text(
                text = stringResource(id = R.string.plan_editor_progression_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
            ) {
                if (!showDetails) {
                    schemes.forEach { scheme ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSchemeSelected(scheme) }
                                .padding(vertical = dims.spacing2),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = draft.scheme == scheme, onClick = { onSchemeSelected(scheme) })
                            Spacer(modifier = Modifier.width(dims.spacingXs))
                            Text(progressionSchemeName(scheme))
                        }
                    }
                    Text(
                        stringResource(PlansR.string.plan_editor_progression_choice_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(progressionSchemeName(draft.scheme), style = MaterialTheme.typography.titleMedium)
                    if (draft.scheme == ProgressionScheme.LINEAR || draft.scheme == ProgressionScheme.DOUBLE) {
                        Text(
                            text = stringResource(if (draft.scheme == ProgressionScheme.LINEAR)
                                R.string.plan_editor_progression_linear_hint else R.string.plan_editor_progression_double_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (draft.scheme != ProgressionScheme.MANUAL) {
                        ProgressionInput(
                            value = draft.step,
                            onValueChange = { onFieldChanged(ProgressionField.STEP, it) },
                            label = stringResource(
                                id = R.string.plan_editor_progression_step,
                                WeightFormatting.unitLabel(draft.unitSystem)
                            ),
                            field = ProgressionField.STEP,
                            errors = draft.errors,
                            keyboardType = KeyboardType.Decimal
                        )

                        if (draft.scheme == ProgressionScheme.LINEAR) {
                            ProgressionInput(
                                value = draft.successThreshold,
                                onValueChange = { onFieldChanged(ProgressionField.SUCCESS_THRESHOLD, it) },
                                label = stringResource(R.string.plan_editor_progression_success_threshold),
                                field = ProgressionField.SUCCESS_THRESHOLD,
                                errors = draft.errors,
                                keyboardType = KeyboardType.Number
                            )
                            Text(stringResource(R.string.plan_editor_progression_success_hint),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        when (draft.scheme) {
                            ProgressionScheme.DOUBLE -> {
                                ProgressionInput(
                                    value = draft.minReps,
                                    onValueChange = { onFieldChanged(ProgressionField.MIN_REPS, it) },
                                    label = stringResource(id = R.string.plan_editor_progression_min_reps),
                                    field = ProgressionField.MIN_REPS,
                                    errors = draft.errors,
                                    keyboardType = KeyboardType.Number
                                )
                                ProgressionInput(
                                    value = draft.maxReps,
                                    onValueChange = { onFieldChanged(ProgressionField.MAX_REPS, it) },
                                    label = stringResource(id = R.string.plan_editor_progression_max_reps),
                                    field = ProgressionField.MAX_REPS,
                                    errors = draft.errors,
                                    keyboardType = KeyboardType.Number
                                )
                            }
                            ProgressionScheme.TOTAL_REPS -> ProgressionInput(
                                value = draft.totalReps,
                                onValueChange = { onFieldChanged(ProgressionField.TOTAL_REPS, it) },
                                label = stringResource(id = R.string.plan_editor_progression_total_reps),
                                field = ProgressionField.TOTAL_REPS,
                                errors = draft.errors,
                                keyboardType = KeyboardType.Number
                            )
                            ProgressionScheme.RPE_RIR -> {
                                ProgressionInput(
                                    value = draft.targetRpe,
                                    onValueChange = { onFieldChanged(ProgressionField.TARGET_RPE, it) },
                                    label = stringResource(id = R.string.plan_editor_progression_target_rpe),
                                    field = ProgressionField.TARGET_RPE,
                                    errors = draft.errors,
                                    keyboardType = KeyboardType.Decimal
                                )
                                ProgressionInput(
                                    value = draft.rpeTolerance,
                                    onValueChange = { onFieldChanged(ProgressionField.RPE_TOLERANCE, it) },
                                    label = stringResource(id = R.string.plan_editor_progression_rpe_tolerance),
                                    field = ProgressionField.RPE_TOLERANCE,
                                    errors = draft.errors,
                                    keyboardType = KeyboardType.Decimal
                                )
                            }
                            ProgressionScheme.MANUAL,
                            ProgressionScheme.LINEAR -> Unit
                        }

                        ProgressionInput(
                            value = draft.stallThreshold,
                            onValueChange = { onFieldChanged(ProgressionField.STALL_THRESHOLD, it) },
                            label = stringResource(id = R.string.plan_editor_progression_stall_threshold),
                            field = ProgressionField.STALL_THRESHOLD,
                            errors = draft.errors,
                            keyboardType = KeyboardType.Number
                        )
                        ProgressionInput(
                            value = draft.backoffPercent,
                            onValueChange = { onFieldChanged(ProgressionField.BACKOFF_PERCENT, it) },
                            label = stringResource(id = R.string.plan_editor_progression_backoff_percent),
                            field = ProgressionField.BACKOFF_PERCENT,
                            errors = draft.errors,
                            keyboardType = KeyboardType.Decimal
                        )
                    }

                    Text(
                        text = progressionPreview(draft),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                }
            }
            if (showDetails) {
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, colors = ThrymInteractiveColors.textButton()) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(modifier = Modifier.width(dims.spacingXs))
                    Button(onClick = onApply) { Text(stringResource(R.string.plan_editor_progression_apply)) }
                }
            } else if (draft.scheme != ProgressionScheme.MANUAL) {
                TextButton(onClick = { showDetails = true }, modifier = Modifier.fillMaxWidth(), colors = ThrymInteractiveColors.textButton()) {
                    Text(stringResource(PlansR.string.plan_editor_progression_details))
                }
            }
            Spacer(modifier = Modifier.height(dims.spacingSm))
        }
    }
}

@Composable
private fun ProgressionInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    field: ProgressionField,
    errors: Map<ProgressionField, String>,
    keyboardType: KeyboardType
) {
    val dims = thrymDimens
    Column(modifier = Modifier.fillMaxWidth()) {
        com.thrym.app.presentation.common.ThrymTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (errors.containsKey(field)) {
            Spacer(modifier = Modifier.height(dims.spacing2))
            Text(
                text = progressionError(field),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticText.danger
            )
        }
    }
}

@Composable
private fun progressionSummary(config: ProgressionConfig): String {
    val status = when (config) {
        is ProgressionConfig.Manual -> stringResource(id = R.string.plan_editor_progression_off)
        is ProgressionConfig.Invalid -> stringResource(id = R.string.plan_editor_progression_invalid)
        else -> progressionSchemeName(config.scheme)
    }
    return stringResource(id = R.string.plan_editor_progression_summary, status)
}

@Composable
private fun progressionSchemeName(scheme: ProgressionScheme): String = stringResource(
    id = when (scheme) {
        ProgressionScheme.MANUAL -> R.string.plan_editor_progression_scheme_manual
        ProgressionScheme.LINEAR -> R.string.plan_editor_progression_scheme_linear
        ProgressionScheme.DOUBLE -> R.string.plan_editor_progression_scheme_double
        ProgressionScheme.TOTAL_REPS -> R.string.plan_editor_progression_scheme_total_reps
        ProgressionScheme.RPE_RIR -> R.string.plan_editor_progression_scheme_rpe_rir
    }
)

@Composable
private fun progressionPreview(draft: ProgressionEditorUi): String {
    val unit = WeightFormatting.unitLabel(draft.unitSystem)
    return when (draft.scheme) {
        ProgressionScheme.MANUAL -> stringResource(id = R.string.plan_editor_progression_preview_manual)
        ProgressionScheme.LINEAR -> stringResource(
            id = R.string.plan_editor_progression_preview_linear,
            draft.step,
            unit
        )
        ProgressionScheme.DOUBLE -> stringResource(
            id = R.string.plan_editor_progression_preview_double,
            draft.minReps,
            draft.maxReps,
            draft.step,
            unit
        )
        ProgressionScheme.TOTAL_REPS -> stringResource(
            id = R.string.plan_editor_progression_preview_total_reps,
            draft.totalReps,
            draft.step,
            unit
        )
        ProgressionScheme.RPE_RIR -> stringResource(
            id = R.string.plan_editor_progression_preview_rpe_rir,
            draft.targetRpe,
            draft.rpeTolerance,
            draft.step,
            unit
        )
    }
}

@Composable
private fun progressionError(field: ProgressionField): String = stringResource(
    id = when (field) {
        ProgressionField.SUCCESS_THRESHOLD -> R.string.plan_editor_progression_error_success_threshold
        ProgressionField.STEP -> R.string.plan_editor_progression_error_step
        ProgressionField.MIN_REPS -> R.string.plan_editor_progression_error_min_reps
        ProgressionField.MAX_REPS -> R.string.plan_editor_progression_error_max_reps
        ProgressionField.TOTAL_REPS -> R.string.plan_editor_progression_error_total_reps
        ProgressionField.TARGET_RPE -> R.string.plan_editor_progression_error_target_rpe
        ProgressionField.RPE_TOLERANCE -> R.string.plan_editor_progression_error_rpe_tolerance
        ProgressionField.STALL_THRESHOLD -> R.string.plan_editor_progression_error_stall_threshold
        ProgressionField.BACKOFF_PERCENT -> R.string.plan_editor_progression_error_backoff_percent
    }
)

@Composable
private fun SupersetStatusBadge(supersetGroupId: Int?) {
    val dims = thrymDimens
    val isGrouped = supersetGroupId != null
    Surface(
        color = if (isGrouped) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isGrouped) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.large
    ) {
        Text(
            text = if (supersetGroupId == null) {
                stringResource(id = R.string.plan_editor_superset_none)
            } else {
                stringResource(id = R.string.plan_editor_superset_label, supersetGroupId)
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = dims.spacingSm, vertical = dims.spacing2)
        )
    }
}
