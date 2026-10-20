package com.thrym.app.presentation.statistics

import com.thrym.app.presentation.common.UiStrings
import com.thrym.feature.statistics.R as UxR
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrym.core.designsystem.R
import com.thrym.app.domain.error.toAppError
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.PersonalRecord
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.StatisticsRepository
import com.thrym.app.domain.util.MuscleVolume
import com.thrym.app.domain.util.MuscleVolumeCalculator
import com.thrym.app.domain.util.WorkoutCalculations
import com.thrym.app.domain.util.catchAndLog
import com.thrym.app.presentation.common.toSharedUnit
import com.thrym.app.presentation.common.toUserMessage
import com.thrym.shared.recognition.StrengthMarkLadder
import com.thrym.shared.recognition.StrengthMarks
import com.thrym.shared.workout.RecordSetInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class ChartMetric(@StringRes val labelRes: Int) {
    WEIGHT(R.string.stats_metric_weight),
    E1RM(R.string.stats_metric_e1rm),
    VOLUME(R.string.stats_metric_volume)
}

data class ChartDataPoint(
    /** Day of the session; the UI formats it, so a runtime language switch shows at once. */
    val date: LocalDate,
    val value: Float,
    /** Full timestamp retained for an identifiable value outside the chart axis. */
    val timestamp: LocalDateTime? = null,
    val sessionId: Long = 0L
)

data class ChartComparison(
    val previous: ChartDataPoint,
    val latest: ChartDataPoint
) {
    val delta: Float
        get() = latest.value - previous.value
}

/**
 * Entwicklung des geschätzten 1RM über die Trainingseinheiten: erster, aktuellster
 * und bester Session-Bestwert sowie die Veränderung vom ersten zum aktuellen Wert.
 */
data class E1rmProgression(
    val first: Float,
    val latest: Float,
    val best: Float
) {
    /** Veränderung vom ersten zum aktuellsten Session-Bestwert (absolut, in kg). */
    val delta: Float get() = latest - first

    /** Relative Veränderung vom ersten zum aktuellsten Session-Bestwert (in Prozent). */
    val deltaPercent: Float get() = if (first > 0f) delta / first * 100f else 0f
}

data class ExerciseStatsUiState(
    val exercise: Exercise? = null,
    val records: List<PersonalRecord> = emptyList(),
    val selectedMetric: ChartMetric = ChartMetric.WEIGHT,
    val chartData: List<ChartDataPoint> = emptyList(),
    val lastWorkoutComparison: ChartComparison? = null,
    val e1rmProgression: E1rmProgression? = null,
    val weeklyMuscleVolume: List<MuscleVolume> = emptyList(),
    val recentSets: List<WorkoutSet> = emptyList(),
    /** Round marks of a barbell or bodyweight exercise, one ladder per kind that has a mark. */
    val markLadders: List<StrengthMarkLadder> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class ExerciseStatsViewModel(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val statisticsRepository: StatisticsRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val strings: UiStrings
) : ViewModel() {

    private fun LocalDateTime.toEpochMillis(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val exerciseId: Long = savedStateHandle["exerciseId"] ?: -1L

    private val _uiState = MutableStateFlow(ExerciseStatsUiState())
    val uiState: StateFlow<ExerciseStatsUiState> = _uiState

    init {
        loadStats()
        observeRecords()
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val exercise = exerciseRepository.getExerciseById(exerciseId)
                val sets = loadHistoricalSets()
                val unitSystem = appPreferencesRepository.preferences.first().unitSystem
                val markLadders = exercise?.let {
                    StrengthMarks.history(sets.map { set -> set.toMarkInput() }, it.category.name, unitSystem.toSharedUnit())
                }.orEmpty()

                _uiState.value = _uiState.value.copy(
                    exercise = exercise,
                    recentSets = sets.take(50),
                    markLadders = markLadders,
                    isLoading = false
                )

                updateChartData(sets, ChartMetric.WEIGHT)
                loadWeeklyMuscleVolume(exercise)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toAppError().toUserMessage(strings.get(UxR.string.stats_action_load), strings)
                )
            }
        }
    }

    /**
     * Lädt alle abgeschlossenen Arbeitssätze der aktuellen Woche und bewertet das
     * gewichtete Volumen der Muskelgruppen dieser Übung gegen MEV/MAV/MRV.
     */
    private suspend fun loadWeeklyMuscleVolume(exercise: Exercise?) {
        if (exercise == null) {
            _uiState.value = _uiState.value.copy(weeklyMuscleVolume = emptyList())
            return
        }

        val preferences = appPreferencesRepository.preferences.first()
        val weekAnchor = when (preferences.weekStart) {
            WeekStart.MONDAY -> DayOfWeek.MONDAY
            WeekStart.SUNDAY -> DayOfWeek.SUNDAY
        }
        val startOfWeek = MuscleVolumeCalculator.weekStartFor(LocalDate.now(), weekAnchor)
        val startOfWeekMillis = startOfWeek
            .atStartOfDay()
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val weekSets = statisticsRepository.getWorkSetsCompletedBetween(
            startOfWeekMillis,
            LocalDateTime.now().toEpochMillis()
        )
        val exercises = exerciseRepository.getExercisesByIds(weekSets.map { it.exerciseId }.distinct())
        val relevantGroups = setOf(exercise.primaryMuscleGroup) + exercise.secondaryMuscleGroups

        _uiState.value = _uiState.value.copy(
            weeklyMuscleVolume = MuscleVolumeCalculator
                .aggregateByMuscleGroup(weekSets, exercises, startOfWeek)
                .filter { it.muscleGroup in relevantGroups }
        )
    }

    private fun observeRecords() {
        viewModelScope.launch {
            statisticsRepository.getRecordsForExercise(exerciseId)
                .catchAndLog("ExerciseStatsVM")
                .collect { records ->
                    _uiState.value = _uiState.value.copy(records = records)
                }
        }
    }

    fun onMetricSelected(metric: ChartMetric) {
        viewModelScope.launch {
            try {
                val sets = loadHistoricalSets()
                _uiState.value = _uiState.value.copy(selectedMetric = metric)
                updateChartData(sets, metric)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.toAppError().toUserMessage(strings.get(UxR.string.stats_action_change_metric), strings)
                )
            }
        }
    }

    /**
     * Historical charts intentionally exclude active sessions and future-dated
     * rows. The repository query owns the session/timestamp predicates so this
     * screen cannot accidentally drift back to the live-training query.
     */
    private suspend fun loadHistoricalSets(): List<WorkoutSet> =
        statisticsRepository.getCompletedSetsForExerciseList(
            exerciseId = exerciseId,
            nowEpochMillis = LocalDateTime.now().toEpochMillis()
        )
            .filter { !it.isWarmup && it.reps > 0 }
            .sortedWith(compareByDescending<WorkoutSet> { it.completedAt }.thenByDescending { it.id })

    private fun updateChartData(sets: List<WorkoutSet>, metric: ChartMetric) {
        val sessions = aggregateBySession(sets)
        if (sessions.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                chartData = emptyList(),
                lastWorkoutComparison = null,
                e1rmProgression = null
            )
            return
        }

        val ordered = sessions.sortedWith(
            compareBy<SessionAggregate> { it.latestTime }.thenBy { it.sessionId }
        )
        val e1rmSeries = ordered.map { it.maxE1Rm }
        val chartData = ordered.map { agg ->
            val date = agg.latestTime
            ChartDataPoint(
                date = date.toLocalDate(),
                value = when (metric) {
                    ChartMetric.WEIGHT -> agg.maxWeight.toFloat()
                    ChartMetric.E1RM   -> agg.maxE1Rm.toFloat()
                    ChartMetric.VOLUME -> agg.totalVolume.toFloat()
                },
                timestamp = date,
                sessionId = agg.sessionId
            )
        }

        _uiState.value = _uiState.value.copy(
            chartData = chartData,
            lastWorkoutComparison = chartData.takeIf { it.size >= 2 }?.let {
                ChartComparison(previous = it[it.lastIndex - 1], latest = it.last())
            },
            e1rmProgression = E1rmProgression(
                first = e1rmSeries.first().toFloat(),
                latest = e1rmSeries.last().toFloat(),
                best = e1rmSeries.max().toFloat()
            )
        )
    }

    private data class SessionAggregate(
        val sessionId: Long,
        val latestTime: LocalDateTime,
        val maxWeight: Double,
        val maxE1Rm: Double,
        val totalVolume: Double
    )

    private fun aggregateBySession(sets: List<WorkoutSet>): List<SessionAggregate> {
        val bySession: Map<Long, SessionAggregate> = sets.fold(mutableMapOf()) { acc, set ->
            val e1rm = WorkoutCalculations.calculateE1RM(set.weightKg, set.reps)
            val prev = acc[set.sessionId]
            acc[set.sessionId] = if (prev == null) {
                SessionAggregate(set.sessionId, set.completedAt, set.weightKg, e1rm, set.weightKg * set.reps)
            } else {
                SessionAggregate(
                    sessionId = prev.sessionId,
                    latestTime = if (set.completedAt > prev.latestTime) set.completedAt else prev.latestTime,
                    maxWeight = maxOf(prev.maxWeight, set.weightKg),
                    maxE1Rm = maxOf(prev.maxE1Rm, e1rm),
                    totalVolume = prev.totalVolume + set.weightKg * set.reps
                )
            }
            acc
        }
        return bySession.values.toList()
    }
}

/** A completed work set as the mark rules see it; warm-ups are already filtered out. */
private fun WorkoutSet.toMarkInput(): RecordSetInput = RecordSetInput(
    id = id,
    sessionId = sessionId,
    exerciseId = exerciseId,
    weightKg = weightKg,
    reps = reps,
    setType = setType.name,
    completedAtEpochMillis = completedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    sessionFinished = true
)
