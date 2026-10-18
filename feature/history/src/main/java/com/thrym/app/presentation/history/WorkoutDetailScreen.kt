package com.thrym.app.presentation.history

import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.accentText
import com.thrym.feature.history.R as UxR
import com.thrym.app.presentation.common.setLoadText
import com.thrym.app.presentation.common.tracksAddedLoad
import com.thrym.app.presentation.common.ThrymTopBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import com.thrym.shared.readinessdata.SetIntention
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.core.designsystem.R
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.ProgressionScheme
import com.thrym.app.domain.model.ProgressionSuggestionStatus
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.presentation.common.EmptyStateScreen
import com.thrym.app.presentation.common.LoadingScreen
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.localizedExerciseName
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.thrymSharedElement
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.progression.ProgressionReasonText
import com.thrym.app.presentation.progression.ProgressionReviewItemUi
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import com.thrym.feature.history.R as HistoryR
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Keeps meaningful history rows visible. A zero-repetition FAILURE still records an attempt that
 * reached failure before a repetition was completed; empty normal rows remain hidden.
 */
fun visibleHistorySets(sets: List<WorkoutSet>): List<WorkoutSet> =
    sets.filter { it.reps > 0 || it.setType == SetType.FAILURE }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun WorkoutDetailScreen(
    onBack: () -> Unit,
    onExerciseClick: (Long) -> Unit,
    viewModel: WorkoutDetailViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appPreferencesRepository: AppPreferencesRepository = koinInject()
    val preferences by appPreferencesRepository.preferences.collectAsStateWithLifecycle(
        initialValue = AppPreferences()
    )
    val dims = thrymDimens
    var editSetId by remember { mutableStateOf<Long?>(null) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var confirmNotesDelete by remember { mutableStateOf(false) }

    editSetId?.let { setId ->
        val detail = state.exercises.firstOrNull { detail -> detail.sets.any { it.id == setId } }
        val set = detail?.sets?.firstOrNull { it.id == setId }
        if (set == null) {
            editSetId = null
        } else {
            HistorySetEditDialog(
                set = set,
                addedLoad = detail.exercise.tracksAddedLoad,
                intention = state.setIntentions[set.id],
                intentionsLoaded = state.intentionsLoaded,
                unitSystem = preferences.unitSystem,
                intensitySystem = preferences.intensitySystem,
                isSaving = state.editSaving,
                saveError = state.editError,
                onSave = { input, intention ->
                    viewModel.updateSet(set.id, input.reps, input.weightKg, input.rpe, intention) {
                        editSetId = null
                    }
                },
                onDelete = { viewModel.deleteSet(set.id) { editSetId = null } },
                onDismiss = {
                    viewModel.clearEditError()
                    editSetId = null
                }
            )
        }
    }

    if (showNotesDialog) {
        HistoryNotesDialog(
            initialNotes = state.session?.notes.orEmpty(),
            isSaving = state.editSaving,
            saveError = state.editError,
            onSave = { notes -> viewModel.updateNotes(notes) { showNotesDialog = false } },
            onDismiss = {
                viewModel.clearEditError()
                showNotesDialog = false
            }
        )
    }

    if (confirmNotesDelete) {
        AlertDialog(
            onDismissRequest = { if (!state.editSaving) confirmNotesDelete = false },
            title = { Text(stringResource(R.string.workout_detail_notes_delete_title)) },
            text = { Text(stringResource(R.string.workout_detail_notes_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.updateNotes("") { confirmNotesDelete = false } },
                    enabled = !state.editSaving,
                    colors = ThrymInteractiveColors.textButton()
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.semanticText.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmNotesDelete = false }, enabled = !state.editSaving, colors = ThrymInteractiveColors.textButton()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    ThrymScreenScaffold(
        topBar = {
            ThrymTopBar(
                title = { Text(stringResource(id = R.string.workout_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.nav_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            LoadingScreen(modifier = Modifier.padding(padding))
        } else if (state.notFound) {
            EmptyStateScreen(
                title = stringResource(id = R.string.workout_detail_not_found_title),
                subtitle = stringResource(id = R.string.workout_detail_not_found_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                action = {
                    TextButton(onClick = onBack, colors = ThrymInteractiveColors.textButton()) {
                        Text(text = stringResource(id = R.string.nav_back))
                    }
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(dims.spacingMd),
                verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
            ) {
                state.intentionError?.let { message ->
                    item { Text(message, color = MaterialTheme.semanticText.danger) }
                }
                item {
                    state.session?.let { session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .thrymSharedElement("workout_card_${session.id}")
                        ) {
                            // Name the plan the session came from; free sessions carry an auto name.
                            Text(
                                text = if (session.planId != null && session.name.isNotBlank()) {
                                    session.name
                                } else {
                                    stringResource(id = R.string.plan_selection_free_workout)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.accentText
                            )
                            Text(
                                text = session.startTime.format(DateFormatting.dateFull(currentLocale())),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val durationMin = session.durationSeconds / 60
                            Text(
                                text = stringResource(
                                    id = R.string.workout_detail_meta,
                                    durationMin,
                                    WeightFormatting.formatVolume(state.totalVolume, preferences.unitSystem)
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (session.notes.isNotBlank()) {
                                Text(
                                    text = session.notes,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(modifier = Modifier.offset(x = (-12).dp)) {
                                TextButton(onClick = { showNotesDialog = true }, enabled = !state.editSaving, colors = ThrymInteractiveColors.textButton()) {
                                    Text(
                                        stringResource(
                                            if (session.notes.isBlank()) {
                                                R.string.workout_detail_notes_add
                                            } else {
                                                R.string.workout_detail_notes_edit
                                            }
                                        )
                                    )
                                }
                                if (session.notes.isNotBlank()) {
                                    TextButton(onClick = { confirmNotesDelete = true }, enabled = !state.editSaving, colors = ThrymInteractiveColors.textButton()) {
                                        Text(
                                            stringResource(R.string.workout_detail_notes_delete),
                                            color = MaterialTheme.semanticText.danger
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(dims.spacing2)) }

                items(state.exercises) { exerciseDetail ->
                    ThrymSurfaceCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExerciseClick(exerciseDetail.exercise.id) },
                        tone = ThrymSurfaceTone.MUTED,
                        alpha = 0.68f
                    ) {
                        Column(modifier = Modifier.padding(dims.spacingMd)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = exerciseDetail.exercise.localizedName(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (exerciseDetail.records.isNotEmpty()) {
                                    androidx.compose.material3.Surface(
                                        color = MaterialTheme.semantic.violet.copy(alpha = 0.15f),
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = dims.spacingSm, vertical = dims.spacing2),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = stringResource(id = R.string.workout_detail_record_cd),
                                                tint = MaterialTheme.semantic.violet,
                                                modifier = Modifier.size(12.dp).padding(end = dims.spacing2)
                                            )
                                            Text(
                                                text = stringResource(id = R.string.workout_detail_pr_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.semanticText.violet,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(dims.spacingXs))

                            visibleHistorySets(exerciseDetail.sets).forEach { set ->
                                // Die ganze Zeile oeffnet die Satzbearbeitung (Werte, Absicht,
                                // Loeschen); angezeigt wird die Absicht nur, wenn sie gesetzt ist.
                                val intention = state.setIntentions[set.id] ?: SetIntention.UNKNOWN
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable(
                                            enabled = !state.editSaving,
                                            onClickLabel = stringResource(R.string.workout_detail_edit_set_action)
                                        ) { editSetId = set.id },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = setTypeLabel(set.setType, set.setNumber),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontStyle = if (set.setType == SetType.WARMUP) FontStyle.Italic else FontStyle.Normal
                                    )
                                    val rpe = set.rpe
                                    val intensityString = if (rpe == null) {
                                        ""
                                    } else {
                                        when (preferences.intensitySystem) {
                                            IntensitySystem.OFF -> ""
                                            IntensitySystem.RPE -> stringResource(UxR.string.history_intensity_rpe, WeightFormatting.formatNumber(rpe))
                                            IntensitySystem.RIR -> stringResource(UxR.string.history_intensity_rir, WeightFormatting.formatNumber(10.0 - rpe))
                                        }
                                    }

                                    Text(
                                        text = stringResource(
                                            id = R.string.workout_detail_set_value,
                                            setLoadText(set.weightKg, preferences.unitSystem, exerciseDetail.exercise.tracksAddedLoad),
                                            set.reps
                                        ) + intensityString,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                if (state.intentionsLoaded && intention != SetIntention.UNKNOWN) {
                                    Text(
                                        text = stringResource(
                                            R.string.workout_detail_intention_value,
                                            stringResource(historyIntentionLabelRes(intention))
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.accentText
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(dims.spacingMd)) }

                if (state.progressionOutcomes.isNotEmpty()) {
                    item(key = "progression_title") {
                        Text(
                            text = stringResource(id = R.string.workout_detail_progression_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(
                        state.progressionOutcomes,
                        key = { outcome -> "progression_${outcome.id}" }
                    ) { outcome ->
                        ProgressionOutcomeCard(
                            outcome = outcome,
                            unitSystem = preferences.unitSystem
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(dims.spacingMd)) }
            }
        }
    }
}

@Composable
private fun setTypeLabel(type: SetType, setNumber: Int): String = when (type) {
    SetType.NORMAL -> stringResource(id = R.string.workout_detail_set_work, setNumber)
    SetType.WARMUP -> stringResource(id = R.string.workout_detail_set_warmup, setNumber)
    SetType.DROP_SET -> stringResource(id = HistoryR.string.history_set_drop, setNumber)
    SetType.FAILURE -> stringResource(id = HistoryR.string.history_set_failure, setNumber)
    SetType.BACKOFF -> stringResource(id = HistoryR.string.history_set_backoff, setNumber)
}

@Composable
private fun ProgressionOutcomeCard(
    outcome: ProgressionReviewItemUi,
    unitSystem: UnitSystem
) {
    ThrymSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (outcome.status == ProgressionSuggestionStatus.PENDING) {
            ThrymSurfaceTone.ACCENT
        } else {
            ThrymSurfaceTone.MUTED
        },
        alpha = 0.68f
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = outcome.exerciseName?.let { localizedExerciseName(it) }
                        ?: stringResource(R.string.progression_review_exercise, outcome.exerciseId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = progressionStatusText(outcome.status),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = progressionStatusColor(outcome.status),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                text = stringResource(
                    R.string.progression_review_scheme,
                    progressionSchemeText(outcome.scheme)
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.accentText
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = ProgressionReasonText(outcome, unitSystem),
                style = MaterialTheme.typography.bodyMedium
            )
            TargetChangesSummary(outcome = outcome, unitSystem = unitSystem)
        }
    }
}

@Composable
private fun TargetChangesSummary(
    outcome: ProgressionReviewItemUi,
    unitSystem: UnitSystem
) {
    val proposed = outcome.proposed ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (outcome.source.sets != proposed.sets) {
            Text(
                text = stringResource(
                    R.string.progression_review_change_sets,
                    outcome.source.sets,
                    proposed.sets
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (outcome.source.reps != proposed.reps) {
            Text(
                text = stringResource(
                    R.string.progression_review_change_reps,
                    outcome.source.reps,
                    proposed.reps
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (outcome.source.weightKg != proposed.weightKg) {
            Text(
                text = stringResource(
                    R.string.progression_review_change_weight,
                    WeightFormatting.formatWeight(outcome.source.weightKg, unitSystem),
                    WeightFormatting.formatWeight(proposed.weightKg, unitSystem)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun progressionSchemeText(scheme: ProgressionScheme): String = stringResource(
    when (scheme) {
        ProgressionScheme.MANUAL -> R.string.progression_review_scheme_manual
        ProgressionScheme.LINEAR -> R.string.progression_review_scheme_linear
        ProgressionScheme.DOUBLE -> R.string.progression_review_scheme_double
        ProgressionScheme.TOTAL_REPS -> R.string.progression_review_scheme_total_reps
        ProgressionScheme.RPE_RIR -> R.string.progression_review_scheme_rpe_rir
    }
)

@Composable
private fun progressionStatusText(status: ProgressionSuggestionStatus): String = stringResource(
    when (status) {
        ProgressionSuggestionStatus.PENDING -> R.string.progression_review_status_pending
        ProgressionSuggestionStatus.INFORMATIONAL -> R.string.progression_review_status_informational
        ProgressionSuggestionStatus.ACCEPTED -> R.string.progression_review_status_accepted
        ProgressionSuggestionStatus.REJECTED -> R.string.progression_review_status_rejected
        ProgressionSuggestionStatus.STALE -> R.string.progression_review_status_stale
    }
)

@Composable
private fun progressionStatusColor(status: ProgressionSuggestionStatus): Color = when (status) {
    ProgressionSuggestionStatus.PENDING -> MaterialTheme.accentText
    ProgressionSuggestionStatus.ACCEPTED -> MaterialTheme.semanticText.success
    ProgressionSuggestionStatus.REJECTED -> MaterialTheme.semanticText.danger
    ProgressionSuggestionStatus.INFORMATIONAL,
    ProgressionSuggestionStatus.STALE -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun historyIntentionLabelRes(value: SetIntention): Int = when (value) {
    SetIntention.UNKNOWN -> R.string.workout_set_intention_unknown
    SetIntention.PLANNED_FAILURE -> R.string.workout_set_intention_planned_failure
    SetIntention.UNEXPECTED_TARGET_MISS -> R.string.workout_set_intention_unexpected_miss
}
