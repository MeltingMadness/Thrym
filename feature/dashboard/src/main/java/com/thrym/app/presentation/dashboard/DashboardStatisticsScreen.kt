package com.thrym.app.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thrym.app.presentation.common.DashboardSkeleton
import com.thrym.app.presentation.common.ThrymScreenScaffold
import com.thrym.app.presentation.common.ThrymTopBar
import com.thrym.app.presentation.common.WeeklyMuscleVolumeCard
import com.thrym.app.presentation.common.label
import com.thrym.app.presentation.common.localizedName
import com.thrym.app.presentation.common.rememberDateTimeFormatter
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.core.designsystem.R as DesignR
import com.thrym.feature.dashboard.R
import org.koin.androidx.compose.koinViewModel

@Composable
fun DashboardStatisticsScreen(onBack: () -> Unit, viewModel: DashboardViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dims = thrymDimens
    ThrymScreenScaffold(topBar = {
        ThrymTopBar(
            title = { Text(stringResource(R.string.dashboard_statistics_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(DesignR.string.nav_back))
                }
            }
        )
    }) { padding ->
        if (state.isLoading) {
            DashboardSkeleton(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(dims.spacingMd),
                verticalArrangement = Arrangement.spacedBy(dims.spacingMd)
            ) {
                item(key = "training_trend") { TrainingTrendCard(state.trainingTrend, viewModel::reloadTrainingTrend) }
                item(key = "weekly_volume") { WeeklyVolumeCard(state.weeklyVolume, state.unitSystem) }
                item(key = "weekly_muscle_volume") {
                    val volume = state.weeklyMuscleVolume
                    WeeklyMuscleVolumeCard(
                        volumes = volume.volumes,
                        collapsible = true,
                        weekStart = volume.weekStart,
                        currentWeekStart = volume.currentWeekStart,
                        completedWorkoutCount = volume.completedWorkoutCount,
                        isLoading = volume.isLoading,
                        error = volume.error,
                        onRetry = viewModel::reloadWeeklyMuscleVolume,
                        onPreviousWeek = viewModel::showPreviousMuscleVolumeWeek,
                        onNextWeek = viewModel::showNextMuscleVolumeWeek
                    )
                }
                item { SectionTitle(stringResource(DesignR.string.dashboard_recent_records)) }
                if (state.recentRecords.isEmpty()) {
                    item {
                        Text(
                            stringResource(if (state.lastWorkout != null) DesignR.string.dashboard_no_records_with_history else DesignR.string.dashboard_no_records),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item {
                        LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dims.spacingSm)) {
                            items(state.recentRecords, key = { (record, _) -> "${record.exerciseId}-${record.type.name}-${record.achievedAt}" }) { (record, exercise) ->
                                RecordCard(
                                    exerciseName = exercise?.localizedName() ?: stringResource(R.string.dashboard_unknown_exercise),
                                    recordType = record.type.label(),
                                    recordValue = formatRecordValue(record.type, record.value, state.unitSystem)
                                )
                            }
                        }
                    }
                }
                state.lastWorkout?.let { workout ->
                    item { SectionTitle(stringResource(DesignR.string.dashboard_last_workout)) }
                    item {
                        LastWorkoutCard(workout.startTime.format(rememberDateTimeFormatter()), (workout.durationSeconds / 60).toInt(), state.lastWorkoutExerciseCount)
                    }
                }
            }
        }
    }
}
