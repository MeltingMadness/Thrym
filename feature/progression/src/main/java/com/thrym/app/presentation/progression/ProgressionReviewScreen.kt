package com.thrym.app.presentation.progression

import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.common.ThrymTopBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.thrym.app.presentation.theme.semantic
import com.thrym.app.presentation.theme.semanticText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.app.domain.model.ProgressionScheme
import com.thrym.app.domain.model.ProgressionSuggestionStatus
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.localizedExerciseName
import com.thrym.core.designsystem.R
import com.thrym.feature.progression.R as ProgressionR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.NumberFormat
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionReviewScreen(
    onClose: () -> Unit,
    viewModel: ProgressionReviewViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingSuggestionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val messageText = state.message?.let { progressionReviewMessageText(it) }

    LaunchedEffect(state.message, messageText) {
        val localizedMessage = messageText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(localizedMessage)
        viewModel.clearMessage()
    }

    ThrymScreenScaffold(
        topBar = {
            ThrymTopBar(
                modifier = Modifier.testTag("progression_review_top_bar"),
                title = { Text(stringResource(R.string.progression_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.progression_review_close_cd)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.safePendingItems.size > 1) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.edits.isNotEmpty()) Text(stringResource(ProgressionR.string.review_finish_edit_first), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = viewModel::acceptAllSafe, enabled = !state.isWorking && state.edits.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(pluralStringResource(ProgressionR.plurals.review_apply_all_count, state.safePendingItems.size, state.safePendingItems.size))
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.decisionItems.isNotEmpty()) {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(pluralStringResource(ProgressionR.plurals.review_decision_count, state.decisionItems.size, state.decisionItems.size), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(stringResource(ProgressionR.string.review_plan_changes_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.decisionItems.isEmpty() && state.noteItems.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(when {
                        state.isLoading -> ProgressionR.string.review_loading
                        state.isSessionScoped -> ProgressionR.string.review_empty_session
                        else -> ProgressionR.string.review_empty_all
                    }), modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.decisionItems, key = { "decision_${it.id}" }) { item ->
                ProgressionReviewCard(item, state.unitSystem, state.isWorking,
                    onAccept = { viewModel.acceptOne(item.id) },
                    onEdit = { viewModel.beginEdit(item.id); editingSuggestionId = item.id },
                    onReject = { viewModel.reject(item.id) })
            }
            if (state.noteItems.isNotEmpty()) {
                item(key = "notes") { CompactSection(stringResource(ProgressionR.string.review_notes), state.noteItems, state.unitSystem, false, initiallyExpanded = state.decisionItems.isEmpty()) }
            }
            if (state.pastItems.isNotEmpty()) {
                item(key = "past") { CompactSection(stringResource(ProgressionR.string.review_earlier_decisions), state.pastItems, state.unitSystem, true) }
            }
        }
    }

    editingSuggestionId?.let { suggestionId ->
        val draft = state.edits[suggestionId]
        val item = state.items.firstOrNull { it.id == suggestionId && it.canDecide }
        if (draft != null && item != null) {
            ProgressionEditSheet(
                draft = draft,
                isWorking = state.isWorking,
                onUpdate = { sets, reps, weight ->
                    viewModel.updateEdit(suggestionId, sets, reps, weight)
                },
                onAccept = { viewModel.acceptOne(suggestionId) },
                onDismiss = {
                    viewModel.dismissEdit(suggestionId)
                    editingSuggestionId = null
                }
            )
        } else {
            LaunchedEffect(suggestionId) {
                editingSuggestionId = null
            }
        }
    }
}

@Composable
private fun exerciseTitle(item: ProgressionReviewItemUi): String =
    item.exerciseName?.let { localizedExerciseName(it) } ?: stringResource(R.string.progression_review_exercise, item.exerciseId)

@Composable
private fun CompactSection(title: String, items: List<ProgressionReviewItemUi>, unitSystem: UnitSystem, showStatus: Boolean, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val stateLabel = stringResource(if (expanded) ProgressionR.string.review_expanded else ProgressionR.string.review_collapsed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = stateLabel }.clickable(role = Role.Button) { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(items.size.toString(), style = MaterialTheme.typography.labelLarge)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    ThrymSurfaceCard(Modifier.fillMaxWidth(), tone = ThrymSurfaceTone.MUTED) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(exerciseTitle(item), style = MaterialTheme.typography.titleSmall)
                            Text(ProgressionReasonText(item, unitSystem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (showStatus) {
                                Text(progressionStatusText(item.status), style = MaterialTheme.typography.labelMedium)
                                item.proposed?.let { Text(compactChange(progressionChange(item.source, it), unitSystem), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun compactChange(change: ProgressionChange, unitSystem: UnitSystem): String = when (change.kind) {
    ProgressionChangeKind.WEIGHT -> "${WeightFormatting.formatWeight(change.fromWeightKg, unitSystem)} → ${WeightFormatting.formatWeight(change.toWeightKg, unitSystem)}"
    ProgressionChangeKind.REPS -> pluralStringResource(ProgressionR.plurals.review_reps_change, change.toCount, change.fromCount, change.toCount)
    ProgressionChangeKind.SETS -> pluralStringResource(ProgressionR.plurals.review_sets_change, change.toCount, change.fromCount, change.toCount)
    ProgressionChangeKind.NONE -> stringResource(ProgressionR.string.review_target_stays)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressionReviewCard(item: ProgressionReviewItemUi, unitSystem: UnitSystem, isWorking: Boolean, onAccept: () -> Unit, onEdit: () -> Unit, onReject: () -> Unit) {
    val proposed = item.proposed ?: return
    val change = progressionChange(item.source, proposed)
    var details by rememberSaveable(item.id) { mutableStateOf(false) }
    ThrymSurfaceCard(modifier = Modifier.fillMaxWidth(), tone = ThrymSurfaceTone.ACCENT) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(exerciseTitle(item), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(item.planName ?: stringResource(ProgressionR.string.review_plan_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (change.kind == ProgressionChangeKind.NONE) {
                    Text(stringResource(ProgressionR.string.review_target_stays), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                } else {
                    val from = if (change.kind == ProgressionChangeKind.WEIGHT) WeightFormatting.formatWeight(change.fromWeightKg, unitSystem) else change.fromCount.toString()
                    val to = when (change.kind) {
                        ProgressionChangeKind.WEIGHT -> WeightFormatting.formatWeight(change.toWeightKg, unitSystem)
                        ProgressionChangeKind.REPS -> pluralStringResource(ProgressionR.plurals.review_reps_value, change.toCount, change.toCount)
                        else -> pluralStringResource(ProgressionR.plurals.review_sets_value, change.toCount, change.toCount)
                    }
                    Text(from, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(to, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    val up = change.direction == ProgressionChangeDirection.UP
                    val delta = when (change.kind) {
                        ProgressionChangeKind.WEIGHT -> WeightFormatting.formatWeightDelta(change.toWeightKg - change.fromWeightKg, unitSystem).replace('-', '−')
                        ProgressionChangeKind.REPS -> pluralStringResource(ProgressionR.plurals.review_reps_delta, kotlin.math.abs(change.toCount - change.fromCount), signedCount(change.toCount - change.fromCount))
                        else -> pluralStringResource(ProgressionR.plurals.review_sets_delta, kotlin.math.abs(change.toCount - change.fromCount), signedCount(change.toCount - change.fromCount))
                    }
                    Surface(shape = MaterialTheme.shapes.small, color = (if (up) MaterialTheme.semantic.success else MaterialTheme.semantic.warning).copy(alpha = 0.16f)) {
                        Text(delta, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = if (up) MaterialTheme.semanticText.success else MaterialTheme.semanticText.warning, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            val also = listOfNotNull(change.alsoReps?.let { pluralStringResource(ProgressionR.plurals.review_reps_change, it.second, it.first, it.second) }, change.alsoSets?.let { pluralStringResource(ProgressionR.plurals.review_sets_change, it.second, it.first, it.second) })
            if (also.isNotEmpty()) Text(also.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(ProgressionR.string.review_new_target, targetText(proposed, unitSystem)), style = MaterialTheme.typography.bodyMedium)
            val count = item.reasonArguments["countedWorkSets"]?.takeIf { it.isFinite() && it >= 1 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
            val shortReason = if (count != null && item.reasonCode in setOf(com.thrym.app.domain.model.ProgressionReasonCode.LOAD_ADVANCED, com.thrym.app.domain.model.ProgressionReasonCode.REP_TARGET_ADVANCED)) pluralStringResource(ProgressionR.plurals.review_target_reached, count, count) else ProgressionReasonText(item, unitSystem)
            Text(shortReason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAccept, enabled = !isWorking, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.progression_review_accept)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit, enabled = !isWorking, modifier = Modifier.weight(1f), colors = ThrymInteractiveColors.textButton()) { Text(stringResource(ProgressionR.string.review_adjust)) }
                TextButton(onClick = onReject, enabled = !isWorking, modifier = Modifier.weight(1f), colors = ThrymInteractiveColors.textButton()) { Text(stringResource(R.string.progression_review_reject)) }
                TextButton(onClick = { details = !details }, enabled = !isWorking, modifier = Modifier.weight(1f), colors = ThrymInteractiveColors.textButton()) { Text(stringResource(ProgressionR.string.review_details)); Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null) }
            }
            AnimatedVisibility(details) { ProgressionDetails(item, unitSystem) }
        }
    }
}

private fun signedCount(value: Int): String = if (value > 0) "+$value" else "−${-value}"
private fun targetText(target: com.thrym.app.domain.model.ProgressionTarget, unitSystem: UnitSystem): String = "${target.sets} × ${target.reps} · ${WeightFormatting.formatWeight(target.weightKg, unitSystem)}"

@Composable
private fun ProgressionDetails(item: ProgressionReviewItemUi, unitSystem: UnitSystem) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(ProgressionR.string.review_before, targetText(item.source, unitSystem)))
        EvidenceSets(item.countedSets, unitSystem)
        ProgressionEvidenceExplanation(item, unitSystem)
        Text(ProgressionReasonText(item, unitSystem), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.progression_review_scheme, progressionSchemeText(item.scheme)), style = MaterialTheme.typography.bodySmall)
        val evidenceDate = item.countedSets.maxOfOrNull { it.completedAt }
        val date = evidenceDate ?: item.createdAtEpochMillis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }
        date?.let { Text(stringResource(if (evidenceDate != null) ProgressionR.string.review_training_date else ProgressionR.string.review_created_date, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))), style = MaterialTheme.typography.bodySmall) }
        uniformCountedWeightKg(item.countedSets)?.let { weight ->
            Text(stringResource(ProgressionR.string.review_trained_weight, WeightFormatting.formatWeight(weight, unitSystem)))
            if (kotlin.math.abs(weight - item.source.weightKg) > 0.1) Text(stringResource(ProgressionR.string.review_stored_plan_weight, WeightFormatting.formatWeight(item.source.weightKg, unitSystem)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EvidenceSets(sets: List<WorkoutSet>, unitSystem: UnitSystem) {
    val workSets = sets.filterNot(WorkoutSet::isWarmup)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(ProgressionR.string.review_counted_sets),
            style = MaterialTheme.typography.labelLarge
        )
        if (workSets.isEmpty()) {
            Text(
                text = stringResource(R.string.progression_review_evidence_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            workSets.forEach { set ->
                val rpe = set.rpe
                val text = if (rpe == null) {
                    stringResource(
                        R.string.progression_review_evidence_set,
                        set.setNumber,
                        WeightFormatting.formatWeight(set.weightKg, unitSystem),
                        set.reps
                    )
                } else {
                    stringResource(
                        R.string.progression_review_evidence_set_rpe,
                        set.setNumber,
                        WeightFormatting.formatWeight(set.weightKg, unitSystem),
                        set.reps,
                        formatNumber(rpe)
                    )
                }
                Text(
                    text = if (set.setType == com.thrym.app.domain.model.SetType.FAILURE)
                        "$text · ${stringResource(R.string.workout_set_type_failure)}" else text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressionEditSheet(
    draft: ProgressionEditDraft,
    isWorking: Boolean,
    onUpdate: (sets: String, reps: String, weight: String) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.progression_review_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            EditField(
                value = draft.sets,
                onValueChange = { onUpdate(it, draft.reps, draft.weight) },
                label = stringResource(R.string.progression_review_edit_sets),
                error = draft.errors[ProgressionEditField.SETS]?.let {
                    progressionEditErrorText(it)
                },
                keyboardType = KeyboardType.Number
            )
            EditField(
                value = draft.reps,
                onValueChange = { onUpdate(draft.sets, it, draft.weight) },
                label = stringResource(R.string.progression_review_edit_reps),
                error = draft.errors[ProgressionEditField.REPS]?.let {
                    progressionEditErrorText(it)
                },
                keyboardType = KeyboardType.Number
            )
            EditField(
                value = draft.weight,
                onValueChange = { onUpdate(draft.sets, draft.reps, it) },
                label = stringResource(
                    R.string.progression_review_edit_weight,
                    WeightFormatting.unitLabel(draft.unitSystem)
                ),
                error = draft.errors[ProgressionEditField.WEIGHT]?.let {
                    progressionEditErrorText(it)
                },
                keyboardType = KeyboardType.Decimal
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss, enabled = !isWorking, colors = ThrymInteractiveColors.textButton()) {
                    Text(stringResource(R.string.common_cancel))
                }
                Button(onClick = onAccept, enabled = !isWorking) {
                    Text(stringResource(R.string.progression_review_accept))
                }
            }
        }
    }
}

@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardType: KeyboardType
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
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
private fun progressionReviewMessageText(message: ProgressionReviewMessage): String = stringResource(
    when (message) {
        ProgressionReviewMessage.STALE -> R.string.progression_review_message_stale
        ProgressionReviewMessage.INVALID -> R.string.progression_review_message_invalid
        ProgressionReviewMessage.ACTION_FAILED -> R.string.progression_review_message_action_failed
    }
)

@Composable
private fun progressionEditErrorText(error: ProgressionEditError): String = stringResource(
    when (error) {
        ProgressionEditError.INVALID_SETS -> R.string.progression_review_edit_error_sets
        ProgressionEditError.INVALID_REPS -> R.string.progression_review_edit_error_reps
        ProgressionEditError.INVALID_WEIGHT -> R.string.progression_review_edit_error_weight
    }
)

private fun formatNumber(value: Double): String = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = 1
    minimumFractionDigits = 0
    isGroupingUsed = false
}.format(value)

@Composable
private fun ProgressionEvidenceExplanation(item: ProgressionReviewItemUi, unitSystem: UnitSystem) {
    val args = item.reasonArguments
    fun count(key: String): Int? = args[key]?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
    val counted = count("countedWorkSets") ?: return
    val extra = count("ignoredExtraSets") ?: 0
    Text(
        pluralStringResource(ProgressionR.plurals.review_evidence_counted, counted, counted, count("failureMarkedSets") ?: 0) + " " +
            pluralStringResource(ProgressionR.plurals.review_evidence_excluded, extra,
                count("ignoredWarmupSets") ?: 0, count("ignoredDropSets") ?: 0, count("ignoredBackoffSets") ?: 0, extra),
        style = MaterialTheme.typography.bodySmall
    )
    val successes = count("successfulSessions")
    val required = count("requiredSuccesses")
    if (successes != null && required != null && item.reasonCode != com.thrym.app.domain.model.ProgressionReasonCode.SUCCESS_CONFIRMATION_REQUIRED) {
        Text(pluralStringResource(ProgressionR.plurals.review_success_count, required, successes, required), style = MaterialTheme.typography.bodySmall)
    }
    val failures = count("failedSessions")
    val threshold = count("failureThreshold")
    if (failures != null && threshold != null) {
        Text(stringResource(ProgressionR.string.review_failure_count, failures, threshold), style = MaterialTheme.typography.bodySmall)
    }
    val minimum = args["minWeightKg"]
    val maximum = args["maxWeightKg"]
    if (item.proposed != null && minimum != null && maximum != null && minimum.isFinite() && maximum.isFinite() && maximum - minimum > 0.1) {
        Text(stringResource(ProgressionR.string.review_mixed_confirmed, WeightFormatting.formatWeight(minimum, unitSystem)), style = MaterialTheme.typography.bodySmall)
    }
}
