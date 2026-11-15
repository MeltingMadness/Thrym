package com.thrym.app.presentation.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.label
import com.thrym.core.designsystem.R as DesignR
import com.thrym.feature.dashboard.R
import com.thrym.shared.readiness.MuscleGroupContext
import com.thrym.shared.readiness.ReadinessAssessment

enum class DashboardOpenItem { PROGRESSION, DELOAD, COMEBACK }

internal fun dashboardOpenItems(pendingProgressions: Int, hasDeload: Boolean, hasComeback: Boolean): List<DashboardOpenItem> = buildList {
    if (pendingProgressions > 0) add(DashboardOpenItem.PROGRESSION)
    if (hasDeload) add(DashboardOpenItem.DELOAD)
    if (hasComeback) add(DashboardOpenItem.COMEBACK)
}

internal fun orderedMuscles(muscles: List<MuscleGroupContext>): List<MuscleGroupContext> =
    // Only muscles with a reason to be on today's card: planned, sore, or trained this week.
    muscles.filter { it.plannedToday || it.soreness != null || it.setsInWindow > 0.0 || it.setsToday > 0.0 }.sortedWith(compareByDescending<MuscleGroupContext> { it.plannedToday }.thenByDescending { it.lastTrainedEpochMillis ?: Long.MIN_VALUE })

@Composable
internal fun CommandSecondaryActions(hasRecommendation: Boolean, onChoosePlan: () -> Unit, onFreeWorkout: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onChoosePlan, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.dashboard_other_plan)) }
        if (hasRecommendation) {
            TextButton(onClick = onFreeWorkout, modifier = Modifier.weight(1f)) { Text(stringResource(DesignR.string.plan_selection_free_workout)) }
        }
    }
}

@Composable
internal fun SkipPlanMenu(option: DashboardMetaPlanOption?, skippingId: Long?, onSkip: (Long) -> Unit) {
    if (option == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = skippingId == null) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.dashboard_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.dashboard_skip_subplan)) }, onClick = { expanded = false; confirm = true })
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.dashboard_skip_subplan)) },
            text = { Text(stringResource(R.string.dashboard_skip_confirm, option.nextPlan?.name.orEmpty())) },
            confirmButton = { TextButton(onClick = { confirm = false; onSkip(option.metaPlanId) }) { Text(stringResource(DesignR.string.plan_selection_meta_skip)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(DesignR.string.common_cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardOpenCard(rows: List<DashboardOpenItem>, state: DashboardUiState, onProgression: () -> Unit, onActivateDeload: (DeloadMode) -> Unit, onDeactivateDeload: () -> Unit) {
    var showDeload by rememberSaveable { mutableStateOf(false) }
    ThrymSurfaceCard(modifier = Modifier.fillMaxWidth(), tone = ThrymSurfaceTone.MUTED) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.dashboard_todo), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                when (row) {
                    DashboardOpenItem.PROGRESSION -> HomeActionRow(
                        Icons.Default.CheckCircle, stringResource(R.string.dashboard_progression_review),
                        pluralStringResource(R.plurals.dashboard_suggestions_waiting, state.pendingProgressionCount, state.pendingProgressionCount), onProgression
                    )
                    DashboardOpenItem.DELOAD -> HomeActionRow(
                        Icons.Default.FitnessCenter,
                        stringResource(if (state.deloadMode == null) R.string.dashboard_deload_suggested else R.string.dashboard_deload_active),
                        if (state.deloadMode == null) stringResource(R.string.dashboard_trend_deload_suggested) else stringResource(if (state.deloadMode == DeloadMode.HALVE_SET_VOLUME) DesignR.string.deload_active_hint_volume else DesignR.string.deload_active_hint_intensity),
                        { showDeload = true }
                    )
                    DashboardOpenItem.COMEBACK -> {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            HomeActionRow(Icons.Default.Refresh, pluralStringResource(DesignR.plurals.recognition_comeback_pause, state.pauseDays ?: 0, state.pauseDays ?: 0),
                                stringResource(DesignR.string.recognition_comeback_offer),
                                { onActivateDeload(DeloadMode.HALVE_SET_VOLUME) }, trailing = {
                                    OutlinedButton(onClick = { onActivateDeload(DeloadMode.HALVE_SET_VOLUME) }, enabled = !state.isDeloadModeUpdating, modifier = Modifier.clearAndSetSemantics {}) {
                                        Text(stringResource(DesignR.string.recognition_comeback_action))
                                    }
                                }, enabled = !state.isDeloadModeUpdating)
                        }
                    }
                }
            }
        }
    }
    if (showDeload) {
        ModalBottomSheet(onDismissRequest = { showDeload = false }) {
            DeloadCard(state.deloadMode, state.isDeloadModeUpdating, onActivateDeload, onDeactivateDeload)
        }
    }
}

@Composable
private fun HomeActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {}.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
internal fun StatisticsLink(onClick: () -> Unit) {
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            HomeActionRow(Icons.Default.FitnessCenter, stringResource(R.string.dashboard_view_statistics), stringResource(R.string.dashboard_statistics_subtitle), onClick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayCard(checkIn: DashboardCheckInState, notice: DashboardCheckInNotice?, assessment: ReadinessAssessment?, onStartEdit: () -> Unit, onCancelEdit: () -> Unit, onSleepChange: (Int?) -> Unit, onEnergyChange: (Int?) -> Unit, onStressChange: (Int?) -> Unit, onSorenessChange: (MuscleGroup, Int?) -> Unit, onSave: () -> Unit, onDelete: () -> Unit, onDismissNotice: () -> Unit) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stored = checkIn.stored?.takeIf { it.hasAnyAnswer }
    val summary = buildList {
        stored?.sleepQuality?.let { add(stringResource(R.string.dashboard_checkin_scale_value, stringResource(R.string.dashboard_checkin_sleep), it)) }
        stored?.energy?.let { add(stringResource(R.string.dashboard_checkin_scale_value, stringResource(R.string.dashboard_checkin_energy), it)) }
        stored?.stress?.let { add(stringResource(R.string.dashboard_checkin_scale_value, stringResource(R.string.dashboard_checkin_stress), it)) }
        stored?.sorenessByMuscle?.entries?.sortedBy { it.key.ordinal }?.forEach { (muscle, value) -> add(stringResource(R.string.dashboard_checkin_scale_value, muscle.label(), value)) }
    }.joinToString(" · ")
    val muscles = orderedMuscles(assessment?.muscleGroups.orEmpty())
    DashboardPanel(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashboard_today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HomeActionRow(Icons.Default.CheckCircle, stringResource(if (stored == null) R.string.dashboard_checkin_today else R.string.dashboard_daily_form),
                if (stored == null) stringResource(R.string.dashboard_checkin_optional) else summary,
                { onStartEdit(); showSheet = true })
            CheckInNoticeRow(notice, onDismissNotice)
            if (muscles.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.dashboard_muscles_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                (if (expanded) muscles else muscles.take(4)).forEach { MuscleContextRow(it, assessment!!.generatedAtEpochMillis) }
                if (muscles.size > 4 && !expanded) {
                    TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.dashboard_muscles_show_all, muscles.size)) }
                }
            }
        }
    }
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { onCancelEdit(); showSheet = false }) {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            DailyCheckInCard(checkIn, notice, onStartEdit, { onCancelEdit(); showSheet = false }, onSleepChange, onEnergyChange, onStressChange, onSorenessChange,
                { onSave() }, { onDelete(); showSheet = false }, onDismissNotice)
            if (stored != null && checkIn.isEditing) {
                TextButton(onClick = { onDelete(); showSheet = false }, enabled = !checkIn.isSaving) { Text(stringResource(R.string.dashboard_checkin_delete)) }
            }
          }
        }
    }
}
