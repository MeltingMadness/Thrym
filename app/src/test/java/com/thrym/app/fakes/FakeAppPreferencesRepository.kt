package com.thrym.app.fakes

import com.thrym.shared.recognition.WeeklyGoalChange
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.ReminderConfig
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.ThemeScheme
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred

class FakeAppPreferencesRepository(
    initial: AppPreferences = AppPreferences()
) : AppPreferencesRepository {

    private val state = MutableStateFlow(initial)
    private val restTimerStates = mutableMapOf<Long, String>()

    /** Optional gates used by workout timer race tests. */
    var restTimerReadGate: CompletableDeferred<Unit>? = null
    var restTimerClearGate: CompletableDeferred<Unit>? = null

    /** Stored workout exercise order, as [AppPreferencesRepositoryImpl] keeps it in DataStore. */
    var workoutExerciseOrder: String? = null

    /** Optional hooks used by workout exercise order tests. */
    var workoutExerciseOrderReadGate: CompletableDeferred<Unit>? = null
    var workoutExerciseOrderWriteGate: CompletableDeferred<Unit>? = null
    var workoutExerciseOrderWriteError: Throwable? = null
    var workoutExerciseOrderWriteCount = 0
        private set

    val current: AppPreferences
        get() = state.value

    override val preferences: Flow<AppPreferences> = state.asStateFlow()

    override suspend fun updateUnitSystem(unitSystem: UnitSystem) {
        state.value = state.value.copy(unitSystem = unitSystem)
    }

    override suspend fun updateWeekStart(weekStart: WeekStart) {
        state.value = state.value.copy(weekStart = weekStart)
    }

    override suspend fun updateDeloadMode(mode: DeloadMode?) {
        state.value = state.value.copy(deloadMode = mode)
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        state.value = state.value.copy(themeMode = themeMode)
    }

    override suspend fun updateThemeScheme(themeScheme: ThemeScheme) {
        state.value = state.value.copy(themeScheme = themeScheme)
    }

    override suspend fun updateAppearanceStyle(appearanceStyle: com.thrym.app.domain.model.AppearanceStyle) {
        state.value = state.value.copy(appearanceStyle = appearanceStyle)
    }

    override suspend fun updateUseDynamicColor(enabled: Boolean) {
        state.value = state.value.copy(useDynamicColor = enabled)
    }

    override suspend fun updateReducedMotion(enabled: Boolean) {
        state.value = state.value.copy(reducedMotion = enabled)
    }

    override suspend fun updateDefaultWarmupFlag(enabled: Boolean) {
        state.value = state.value.copy(defaultWarmupFlag = enabled)
    }

    override suspend fun updateTimerKeepScreenOn(enabled: Boolean) {
        state.value = state.value.copy(timerKeepScreenOn = enabled)
    }

    override suspend fun updateBetaDiagnosticsOptIn(enabled: Boolean) {
        state.value = state.value.copy(betaDiagnosticsOptIn = enabled)
    }

    override suspend fun updateIntensitySystem(intensitySystem: IntensitySystem) {
        state.value = state.value.copy(intensitySystem = intensitySystem)
    }

    override suspend fun updateShareWeightHistoryAcrossContexts(enabled: Boolean) {
        state.value = state.value.copy(shareWeightHistoryAcrossContexts = enabled)
    }

    override suspend fun updateAutoRestTimerEnabled(enabled: Boolean) {
        state.value = state.value.copy(autoRestTimerEnabled = enabled)
    }

    override suspend fun updateDefaultRestTimeSeconds(seconds: Int) {
        // Paritaet mit AppPreferencesRepositoryImpl: die Dauer wird dort beim
        // Persistieren in den gueltigen Bereich gezwungen.
        state.value = state.value.copy(defaultRestTimeSeconds = seconds.coerceIn(30, 600))
    }

    override suspend fun updateReminderConfig(config: ReminderConfig) {
        // Paritaet mit AppPreferencesRepositoryImpl: Stunden/Minuten werden
        // dort beim Persistieren in den gueltigen Bereich gezwungen.
        state.value = state.value.copy(
            reminderConfig = config.copy(
                hour = config.hour.coerceIn(0, 23),
                minute = config.minute.coerceIn(0, 59)
            )
        )
    }

    override suspend fun updatePlateCalculatorEnabled(enabled: Boolean) {
        state.value = state.value.copy(plateCalculatorEnabled = enabled)
    }

    override suspend fun updateAvailablePlates(plates: List<Double>) {
        state.value = state.value.copy(availablePlates = plates.filter { it > 0.0 }.distinct().sortedDescending())
    }

    override suspend fun updateBarbellWeightKg(weightKg: Double) {
        state.value = state.value.copy(barbellWeightKg = weightKg)
    }

    override suspend fun updateLastSuccessfulExportEpochMillis(timestampMillis: Long?) {
        state.value = state.value.copy(lastSuccessfulExportEpochMillis = timestampMillis)
    }

    override suspend fun updateBackupReminderEnabled(enabled: Boolean) {
        state.value = state.value.copy(backupReminderEnabled = enabled)
    }

    override suspend fun updateWeeklyGoalEnabled(enabled: Boolean) {
        state.value = state.value.copy(weeklyGoalEnabled = enabled)
    }

    override suspend fun updateWeeklyGoalHistory(history: List<WeeklyGoalChange>) {
        state.value = state.value.copy(weeklyGoalHistory = history)
    }

    override suspend fun readRestTimerState(sessionId: Long): String? {
        restTimerReadGate?.await()
        return restTimerStates[sessionId]
    }

    override suspend fun writeRestTimerState(sessionId: Long, encodedState: String?) {
        if (encodedState.isNullOrBlank()) {
            restTimerClearGate?.await()
        }
        if (encodedState.isNullOrBlank()) {
            restTimerStates.remove(sessionId)
        } else {
            restTimerStates[sessionId] = encodedState
        }
    }

    override suspend fun readWorkoutExerciseOrder(): String? {
        workoutExerciseOrderReadGate?.await()
        return workoutExerciseOrder
    }

    override suspend fun writeWorkoutExerciseOrder(encoded: String?) {
        workoutExerciseOrderWriteCount += 1
        workoutExerciseOrderWriteGate?.await()
        workoutExerciseOrderWriteError?.let { throw it }
        workoutExerciseOrder = encoded?.takeIf { it.isNotBlank() }
    }
}
