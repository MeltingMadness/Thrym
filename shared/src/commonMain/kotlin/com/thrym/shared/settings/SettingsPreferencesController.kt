package com.thrym.shared.settings

import com.thrym.shared.model.AppPreferences
import com.thrym.shared.model.DeloadMode
import com.thrym.shared.model.IntensitySystem
import com.thrym.shared.model.ReminderConfig
import com.thrym.shared.model.ThemeMode
import com.thrym.shared.model.AppearanceStyle
import com.thrym.shared.model.ThemeScheme
import com.thrym.shared.model.UnitSystem
import com.thrym.shared.model.WeekStart
import com.thrym.shared.recognition.WeeklyGoalChange
import com.thrym.shared.recognition.WeeklyGoalRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface SharedAppPreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun updateUnitSystem(unitSystem: UnitSystem)
    suspend fun updateWeekStart(weekStart: WeekStart)
    suspend fun updateThemeMode(themeMode: ThemeMode)
    suspend fun updateThemeScheme(themeScheme: ThemeScheme)
    suspend fun updateAppearanceStyle(appearanceStyle: AppearanceStyle)
    suspend fun updateUseDynamicColor(enabled: Boolean)
    suspend fun updateReducedMotion(enabled: Boolean)
    suspend fun updateDefaultWarmupFlag(enabled: Boolean)
    suspend fun updateTimerKeepScreenOn(enabled: Boolean)
    suspend fun updateBetaDiagnosticsOptIn(enabled: Boolean)
    suspend fun updateReminderConfig(config: ReminderConfig)
    suspend fun updateIntensitySystem(intensitySystem: IntensitySystem)
    suspend fun updateShareWeightHistoryAcrossContexts(enabled: Boolean)
    suspend fun updateAutoRestTimerEnabled(enabled: Boolean)
    suspend fun updateDefaultRestTimeSeconds(seconds: Int)
    suspend fun updateDeloadMode(mode: DeloadMode)
    suspend fun updatePlateCalculatorEnabled(enabled: Boolean)
    suspend fun updateAvailablePlates(plates: List<Double>)
    suspend fun updateBarbellWeightKg(weightKg: Double)
    suspend fun updateLastSuccessfulExportEpochMillis(timestampMillis: Long?)
    suspend fun updateBackupReminderEnabled(enabled: Boolean)
    suspend fun updateWeeklyGoalEnabled(enabled: Boolean)
    suspend fun updateWeeklyGoalHistory(history: List<WeeklyGoalChange>)
}

interface SharedReminderScheduler {
    suspend fun sync(config: ReminderConfig)
    suspend fun cancel()
}

data class SettingsPreferencesState(
    val preferences: AppPreferences = AppPreferences()
)

/** The export is considered stale at the seven-day boundary, not after it. */
const val BACKUP_REMINDER_THRESHOLD_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L

/**
 * Returns whether an opt-in reminder should be shown in Settings.
 *
 * A missing export is actionable as soon as the user opts in. Future timestamps are
 * treated as fresh, which keeps a device clock adjustment from producing a false warning.
 */
fun isBackupReminderDue(
    reminderEnabled: Boolean,
    lastSuccessfulExportEpochMillis: Long?,
    nowEpochMillis: Long
): Boolean {
    if (!reminderEnabled) return false
    val lastExport = lastSuccessfulExportEpochMillis ?: return true
    if (nowEpochMillis < lastExport) return false
    return nowEpochMillis - lastExport >= BACKUP_REMINDER_THRESHOLD_MILLIS
}

class SettingsPreferencesController(
    scope: CoroutineScope,
    private val appPreferencesRepository: SharedAppPreferencesRepository,
    private val reminderScheduler: SharedReminderScheduler
) {
    val state: StateFlow<SettingsPreferencesState> =
        appPreferencesRepository.preferences
            .map(::SettingsPreferencesState)
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SettingsPreferencesState()
            )

    private val controllerScope = scope

    fun updateUnitSystem(unitSystem: UnitSystem) {
        controllerScope.launch { appPreferencesRepository.updateUnitSystem(unitSystem) }
    }

    fun updateWeekStart(weekStart: WeekStart) {
        controllerScope.launch { appPreferencesRepository.updateWeekStart(weekStart) }
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        controllerScope.launch { appPreferencesRepository.updateThemeMode(themeMode) }
    }

    fun updateThemeScheme(themeScheme: ThemeScheme) {
        controllerScope.launch { appPreferencesRepository.updateThemeScheme(themeScheme) }
    }

    fun updateAppearanceStyle(appearanceStyle: AppearanceStyle) {
        controllerScope.launch { appPreferencesRepository.updateAppearanceStyle(appearanceStyle) }
    }

    fun updateUseDynamicColor(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateUseDynamicColor(enabled) }
    }

    fun updateReducedMotion(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateReducedMotion(enabled) }
    }

    fun updateDefaultWarmupFlag(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateDefaultWarmupFlag(enabled) }
    }

    fun updateTimerKeepScreenOn(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateTimerKeepScreenOn(enabled) }
    }

    fun updateBetaDiagnosticsOptIn(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateBetaDiagnosticsOptIn(enabled) }
    }

    fun updateReminderConfig(config: ReminderConfig) {
        controllerScope.launch {
            appPreferencesRepository.updateReminderConfig(config)
            // Scheduler implementations (e.g. iOS notification scheduling) can fail; a
            // reminder-sync error must never become an uncaught crash in the caller's scope.
            runCatching { reminderScheduler.sync(config) }
        }
    }

    fun updateIntensitySystem(intensitySystem: IntensitySystem) {
        controllerScope.launch { appPreferencesRepository.updateIntensitySystem(intensitySystem) }
    }

    fun updateShareWeightHistoryAcrossContexts(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateShareWeightHistoryAcrossContexts(enabled) }
    }

    fun updateAutoRestTimerEnabled(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateAutoRestTimerEnabled(enabled) }
    }

    fun updateDefaultRestTimeSeconds(seconds: Int) {
        controllerScope.launch { appPreferencesRepository.updateDefaultRestTimeSeconds(seconds) }
    }

    fun updateDeloadMode(mode: DeloadMode) {
        controllerScope.launch { appPreferencesRepository.updateDeloadMode(mode) }
    }

    fun updatePlateCalculatorEnabled(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updatePlateCalculatorEnabled(enabled) }
    }

    fun updateAvailablePlates(plates: List<Double>) {
        controllerScope.launch { appPreferencesRepository.updateAvailablePlates(plates) }
    }

    fun updateBarbellWeightKg(weightKg: Double) {
        controllerScope.launch { appPreferencesRepository.updateBarbellWeightKg(weightKg) }
    }

    fun updateLastSuccessfulExportEpochMillis(timestampMillis: Long?) {
        controllerScope.launch {
            appPreferencesRepository.updateLastSuccessfulExportEpochMillis(timestampMillis)
        }
    }

    fun updateBackupReminderEnabled(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateBackupReminderEnabled(enabled) }
    }

    fun updateWeeklyGoalEnabled(enabled: Boolean) {
        controllerScope.launch { appPreferencesRepository.updateWeeklyGoalEnabled(enabled) }
    }

    /**
     * Sets the weekly goal from the week starting at [currentWeekStartEpochDay] on; earlier
     * weeks keep the goal they had. The caller supplies the week, the controller has no clock.
     */
    fun updateWeeklyGoal(goal: Int, currentWeekStartEpochDay: Long) {
        controllerScope.launch {
            val history = appPreferencesRepository.preferences.first().weeklyGoalHistory
            appPreferencesRepository.updateWeeklyGoalHistory(
                WeeklyGoalRules.withChange(history, currentWeekStartEpochDay, goal)
            )
        }
    }
}
