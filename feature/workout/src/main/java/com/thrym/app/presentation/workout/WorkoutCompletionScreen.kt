package com.thrym.app.presentation.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.common.tracksAddedLoad
import com.thrym.app.presentation.common.setLoadText
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.rememberHapticFeedback
import com.thrym.app.presentation.common.rememberUiStrings
import com.thrym.app.presentation.common.userMessageOrNull
import com.thrym.app.presentation.theme.accentText
import com.thrym.app.presentation.theme.semanticText
import com.thrym.core.designsystem.R
import com.thrym.app.domain.model.*
import com.thrym.app.domain.repository.TrainingPlanRepository
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.shared.plans.PlannedSet
import com.thrym.shared.plans.PlannedSets
import com.thrym.shared.workout.RecordSetInput
import com.thrym.shared.workout.SessionRecords
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject

internal fun WorkoutPlanTarget.loggingSlots(): List<PlannedSet> = setTargets.ifEmpty {
    List(target.sets.coerceIn(0, 100)) { PlannedSet(reps = target.reps, weightKg = target.weightKg) }
}
internal fun ExerciseWithSets.openSlotCount(): Int {
    val slots = planTarget?.loggingSlots() ?: return 0
    return PlannedSets.matchedIndices(slots, sets.filter { it.reps > 0 }.map { it.setType.name }).count { it == null }
}

/**
 * True when the workout has planned sets and none of them is open. A free workout has no last
 * set and never is. Ad-hoc rows and sets beyond the plan change nothing.
 */
internal fun List<ExerciseWithSets>.allPlannedSetsDone(): Boolean =
    sumOf { it.planTarget?.loggingSlots()?.size ?: 0 } > 0 && sumOf { it.openSlotCount() } == 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutFinishSheet(rows: List<ExerciseWithSets>, busy: Boolean, error: String?, onContinue: () -> Unit, onFinish: () -> Unit) {
    val remaining = rows.sumOf { it.openSlotCount() }
    val planned = rows.sumOf { it.planTarget?.loggingSlots()?.size ?: 0 }
    val confirmed = rows.sumOf { row -> row.sets.count { it.reps > 0 } }
    ModalBottomSheet(onDismissRequest = { if (!busy) onContinue() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.workout_finish_dialog_title), style = MaterialTheme.typography.headlineSmall)
            Text(when {
                remaining > 0 -> pluralStringResource(R.plurals.workout_finish_remaining, remaining, remaining, planned - remaining, planned)
                confirmed == 0 -> stringResource(R.string.workout_finish_empty)
                else -> pluralStringResource(R.plurals.workout_finish_confirmed, confirmed, confirmed)
            })
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.filter { it.openSlotCount() > 0 }.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.exercise.localizedName(), Modifier.weight(1f)); Text(stringResource(R.string.workout_finish_open_count, row.openSlotCount()))
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.semanticText.danger) }
            Button(onClick = onContinue, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.workout_finish_dialog_cancel)) }
            OutlinedButton(onClick = onFinish, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ThrymInteractiveColors.outlinedButton()) {
                Text(
                    stringResource(
                        when {
                            busy -> R.string.workout_finish_saving
                            remaining > 0 -> R.string.workout_finish_confirm_partial
                            else -> R.string.workout_finish_confirm
                        }
                    )
                )
            }
            Text(stringResource(R.string.workout_finish_footer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutCompletionScreen(session: WorkoutSession, rows: List<ExerciseWithSets>, recordInputs: List<RecordSetInput>?, unitSystem: UnitSystem,
    finishState: WorkoutFinishState, onClose: () -> Unit, onDetails: () -> Unit, onPlanEditor: () -> Unit,
    onProgression: () -> Unit, onRetryProgression: () -> Unit) {
    var showPlanChanges by rememberSaveable(session.id) { mutableStateOf(false) }
    var planApplied by rememberSaveable(session.id) { mutableStateOf(false) }
    val sets = rows.flatMap { it.sets }.filter { it.reps > 0 }.distinctBy { it.id }
    val remaining = rows.sumOf { it.openSlotCount() }
    // Records follow the workout's display order; the rows keep it also after a cold restore.
    val recordOrder = rows.filter { row -> row.sets.any { it.reps > 0 } }.map { it.exercise.id }.distinct()
    val recordSummary = remember(recordInputs, recordOrder) {
        recordInputs?.let { SessionRecords.summarize(session.id, recordOrder, it) }
    }
    // One haptic the first time the board shows records, not again after rotation or a restore.
    val haptic = rememberHapticFeedback()
    var recordsCelebrated by rememberSaveable(session.id) { mutableStateOf(false) }
    LaunchedEffect(recordSummary) {
        if (!recordsCelebrated && (recordSummary?.recordCount ?: 0) > 0) {
            recordsCelebrated = true
            haptic.confirm()
        }
    }
    // Follow-up actions are the same in both appearances.
    val followUps: @Composable () -> Unit = {
        if (session.planId != null && rows.any { it.planTarget != null }) {
            OutlinedButton(onClick = { showPlanChanges = true }, enabled = !planApplied, modifier = Modifier.fillMaxWidth(), colors = ThrymInteractiveColors.outlinedButton()) {
                Text(stringResource(if (planApplied) R.string.workout_summary_plan_changes_applied else R.string.workout_summary_review_plan_changes))
            }
        }
        when (finishState) {
            is WorkoutFinishState.ReviewReady -> if (!planApplied) OutlinedButton(onClick = onProgression, modifier = Modifier.fillMaxWidth(), colors = ThrymInteractiveColors.outlinedButton()) { Text(stringResource(R.string.workout_summary_review_progression)) }
            is WorkoutFinishState.Generating -> Text(stringResource(R.string.workout_summary_progression_generating), style = MaterialTheme.typography.bodySmall)
            is WorkoutFinishState.GenerationFailed -> OutlinedButton(onClick = onRetryProgression, modifier = Modifier.fillMaxWidth(), colors = ThrymInteractiveColors.outlinedButton()) { Text(stringResource(R.string.workout_summary_progression_retry)) }
            else -> Unit
        }
    }
    if (isLiquidGlass()) {
        GlassWorkoutCompletion(
            session = session,
            rows = rows,
            recordSummary = recordSummary,
            unitSystem = unitSystem,
            onClose = onClose,
            onDetails = onDetails,
            followUps = followUps
        )
        return
    }
    // One headline in the content instead of a second title in a top bar; "Fertig" is the
    // main action, the details are the secondary one.
    Scaffold(
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(stringResource(R.string.workout_summary_done))
                }
                OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ThrymInteractiveColors.outlinedButton()) {
                    Text(stringResource(R.string.workout_summary_open_details))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if (remaining > 0) R.string.workout_summary_headline_partial else R.string.workout_summary_headline_complete), style = MaterialTheme.typography.headlineMedium)
            Text(if (remaining > 0) pluralStringResource(R.plurals.workout_summary_partial_subtitle, sets.size, sets.size) else session.name)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryMetric(
                    stringResource(R.string.workout_summary_duration),
                    if (session.durationSeconds < 60) {
                        stringResource(R.string.workout_summary_duration_under_minute)
                    } else {
                        stringResource(R.string.workout_summary_duration_minutes, (session.durationSeconds / 60).toInt())
                    },
                    Modifier.weight(1f)
                )
                SummaryMetric(stringResource(R.string.workout_summary_sets), sets.size.toString(), Modifier.weight(1f))
            }
            SummaryMetric(stringResource(R.string.workout_summary_volume), WeightFormatting.formatVolume(sets.sumOf { it.weightKg * it.reps }, unitSystem), Modifier.fillMaxWidth())
            recordSummary?.let { CompletionRecordsCard(it, rows, unitSystem) }
            rows.filter { it.sets.any { set -> set.reps > 0 } }.forEach { row ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.exercise.localizedName(), style = MaterialTheme.typography.titleMedium)
                    row.sets.filter { it.reps > 0 }.forEach { set -> Text(pluralStringResource(R.plurals.workout_summary_set_line, set.reps, set.setNumber, setLoadText(set.weightKg, unitSystem, row.exercise.tracksAddedLoad), set.reps)) }
                } }
            }
            followUps()
        }
    }
    if (showPlanChanges) {
        WorkoutPlanChangesSheet(session.id, rows, unitSystem, onDismiss = { showPlanChanges = false },
            onEditor = { showPlanChanges = false; onPlanEditor() }, onApplied = { planApplied = true; showPlanChanges = false })
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier) { Column(Modifier.padding(16.dp)) { Text(label, style = MaterialTheme.typography.labelMedium); Text(value, style = MaterialTheme.typography.headlineSmall) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutPlanChangesSheet(sessionId: Long, rows: List<ExerciseWithSets>, unitSystem: UnitSystem,
    onDismiss: () -> Unit, onEditor: () -> Unit, onApplied: () -> Unit, repository: TrainingPlanRepository = koinInject()) {
    var choice by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val planChangeFailedText = stringResource(R.string.workout_plan_changes_error)
    val strings = rememberUiStrings()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.workout_plan_changes_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.workout_plan_changes_intro))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.filter { it.planTarget != null }.forEach { row ->
                    val slots = (row.originalPlanTarget ?: row.planTarget)!!.loggingSlots()
                    val recorded = row.sets.filter { it.reps > 0 }
                    PlannedSets.matchedIndices(slots, recorded.map { it.setType.name }).forEachIndexed { index, match ->
                        val today = match?.let { recorded[it] }
                        if (today != null && (today.reps != slots[index].reps || today.weightKg != slots[index].weightKg)) {
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.workout_plan_changes_set_title, row.exercise.localizedName(), index + 1), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.workout_plan_changes_plan_line, formatTargetWeight(slots[index].weightKg, unitSystem), slots[index].reps))
                                Text(stringResource(R.string.workout_plan_changes_today_line, formatTargetWeight(today.weightKg, unitSystem), today.reps), color = MaterialTheme.accentText)
                            } }
                        }
                    }
                }
                val options = listOf(
                    stringResource(R.string.workout_plan_changes_option_keep) to stringResource(R.string.workout_plan_changes_option_keep_hint),
                    stringResource(R.string.workout_plan_changes_option_apply) to stringResource(R.string.workout_plan_changes_option_apply_hint),
                    stringResource(R.string.workout_plan_changes_option_editor) to stringResource(R.string.workout_plan_changes_option_editor_hint)
                )
                options.forEachIndexed { index, option ->
                    Surface(Modifier.fillMaxWidth().clickable(enabled = !busy) { choice = index },
                        shape = MaterialTheme.shapes.medium,
                        color = if (choice == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, if (choice == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = choice == index, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) { Text(option.first, style = MaterialTheme.typography.titleSmall); Text(option.second, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Text(stringResource(R.string.workout_plan_changes_open_items), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.semanticText.danger) }
            }
            Button(onClick = {
                when(choice) {
                    0 -> onDismiss()
                    2 -> onEditor()
                    else -> scope.launch {
                        busy = true
                        try { repository.applyPerformedSetTargets(sessionId); onApplied() }
                        catch (e: Exception) { if (e is CancellationException) throw e; error = e.userMessageOrNull(strings) ?: planChangeFailedText }
                        finally { busy = false }
                    }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(
                    stringResource(
                        if (busy) {
                            R.string.workout_finish_saving
                        } else {
                            listOf(
                                R.string.workout_plan_changes_confirm_keep,
                                R.string.workout_plan_changes_confirm_apply,
                                R.string.workout_plan_changes_confirm_editor
                            )[choice]
                        }
                    )
                )
            }
        }
    }
}
