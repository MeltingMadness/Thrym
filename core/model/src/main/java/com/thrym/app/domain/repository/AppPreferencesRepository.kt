package com.thrym.app.domain.repository

import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.ProfileSex
import com.thrym.app.domain.model.ReminderConfig
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.ThemeScheme
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import kotlinx.coroutines.flow.Flow

interface AppPreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun updateUnitSystem(unitSystem: UnitSystem)
    suspend fun updateWeekStart(weekStart: WeekStart)
    suspend fun updateThemeMode(themeMode: ThemeMode)
    suspend fun updateThemeScheme(themeScheme: ThemeScheme)
    suspend fun updateAppearanceStyle(appearanceStyle: com.thrym.app.domain.model.AppearanceStyle)
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
    suspend fun updateDeloadMode(mode: DeloadMode?)
    suspend fun updatePlateCalculatorEnabled(enabled: Boolean)
    suspend fun updateAvailablePlates(plates: List<Double>)
    suspend fun updateBarbellWeightKg(weightKg: Double)
    suspend fun updateLastSuccessfulExportEpochMillis(timestampMillis: Long?)
    suspend fun updateBackupReminderEnabled(enabled: Boolean)
    suspend fun updateProfileSex(sex: ProfileSex?)
    suspend fun updateProfileBirthYear(birthYear: Int?)

    /**
     * Persists the active workout rest timers for one session.
     *
     * The payload is owned by the workout feature so this repository deliberately keeps it
     * opaque. Implementations backed by the app DataStore survive process death and a later
     * process restart; test and legacy implementations may keep the default no-op behavior.
     */
    suspend fun readRestTimerState(sessionId: Long): String? = null

    suspend fun writeRestTimerState(sessionId: Long, encodedState: String?) = Unit

    /**
     * Persists the display order of the exercises in the active workout.
     *
     * There is at most one entry. The payload carries its own session guard and stays opaque
     * here, like the rest timer state; `null` removes the entry. It is not part of the backup
     * and never touches the plan target order.
     */
    suspend fun readWorkoutExerciseOrder(): String? = null

    suspend fun writeWorkoutExerciseOrder(encoded: String?) = Unit
}
