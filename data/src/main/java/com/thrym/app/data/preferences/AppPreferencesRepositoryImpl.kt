package com.thrym.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.ReminderConfig
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.ThemeScheme
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.data.local.dao.WorkoutSessionDao
import com.thrym.shared.recognition.WeeklyGoalChange
import com.thrym.shared.recognition.WeeklyGoalRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AppPreferencesRepositoryImpl(
    private val context: Context,
    /**
     * Optional write-through to the running workout. Activating a deload mode marks
     * the active session's explicit deload context conservatively as `true`, so the
     * history never has to derive it from the switch at read time. `null` in tests
     * and legacy constructions; the app wires the real DAO.
     */
    private val workoutSessionDao: WorkoutSessionDao? = null
) : AppPreferencesRepository {

    override val preferences: Flow<AppPreferences> = context.appPreferencesDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
        val unitSystem = prefs[AppPreferenceKeys.UNIT_SYSTEM]
            ?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
            ?: UnitSystem.METRIC

        val weekStart = prefs[AppPreferenceKeys.WEEK_START]
            ?.let { runCatching { WeekStart.valueOf(it) }.getOrNull() }
            ?: WeekStart.MONDAY

        val themeMode = prefs[AppPreferenceKeys.THEME_MODE]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK

        val themeScheme = prefs[AppPreferenceKeys.THEME_SCHEME]
            ?.let { runCatching { ThemeScheme.valueOf(it) }.getOrNull() }
            ?: ThemeScheme.AMBER

        val appearanceStyle = prefs[AppPreferenceKeys.APPEARANCE_STYLE]
            ?.let { runCatching { com.thrym.app.domain.model.AppearanceStyle.valueOf(it) }.getOrNull() }
            ?: com.thrym.app.domain.model.AppearanceStyle.LIQUID_GLASS

        val intensitySystem = prefs[AppPreferenceKeys.INTENSITY_SYSTEM]
            ?.let { runCatching { IntensitySystem.valueOf(it) }.getOrNull() }
            ?: IntensitySystem.RPE

        val reminderConfig = ReminderConfig(
            enabled = prefs[AppPreferenceKeys.REMINDER_ENABLED] ?: false,
            hour = (prefs[AppPreferenceKeys.REMINDER_HOUR] ?: ReminderConfig().hour).coerceIn(0, 23),
            minute = (prefs[AppPreferenceKeys.REMINDER_MINUTE] ?: ReminderConfig().minute).coerceIn(0, 59),
            daysOfWeek = parseReminderDays(prefs.stringOrNull(AppPreferenceKeys.REMINDER_DAYS))
        )

        AppPreferences(
            unitSystem = unitSystem,
            weekStart = weekStart,
            themeMode = themeMode,
            themeScheme = themeScheme,
            appearanceStyle = appearanceStyle,
            useDynamicColor = prefs[AppPreferenceKeys.USE_DYNAMIC_COLOR] ?: false,
            reducedMotion = prefs[AppPreferenceKeys.REDUCED_MOTION] ?: false,
            defaultWarmupFlag = prefs[AppPreferenceKeys.DEFAULT_WARMUP_FLAG] ?: false,
            timerKeepScreenOn = prefs[AppPreferenceKeys.TIMER_KEEP_SCREEN_ON] ?: false,
            betaDiagnosticsOptIn = prefs[AppPreferenceKeys.BETA_DIAGNOSTICS_OPT_IN] ?: false,
            reminderConfig = reminderConfig,
            intensitySystem = intensitySystem,
            shareWeightHistoryAcrossContexts =
                prefs[AppPreferenceKeys.SHARE_WEIGHT_HISTORY_ACROSS_CONTEXTS] ?: false,
            autoRestTimerEnabled = prefs[AppPreferenceKeys.AUTO_REST_TIMER_ENABLED] ?: false,
            defaultRestTimeSeconds = prefs[AppPreferenceKeys.DEFAULT_REST_TIME_SECONDS] ?: 120,
            deloadMode = prefs.stringOrNull(AppPreferenceKeys.DELOAD_MODE)
                ?.let { runCatching { DeloadMode.valueOf(it) }.getOrNull() },
            plateCalculatorEnabled = prefs[AppPreferenceKeys.PLATE_CALCULATOR_ENABLED] ?: true,
            availablePlates = parseAvailablePlates(prefs.stringOrNull(AppPreferenceKeys.AVAILABLE_PLATES)),
            barbellWeightKg = prefs[AppPreferenceKeys.BARBELL_WEIGHT_KG] ?: DEFAULT_BARBELL_WEIGHT_KG,
            lastSuccessfulExportEpochMillis = prefs[AppPreferenceKeys.LAST_SUCCESSFUL_EXPORT_EPOCH_MILLIS],
            backupReminderEnabled = prefs[AppPreferenceKeys.BACKUP_REMINDER_ENABLED] ?: false,
            weeklyGoalEnabled = prefs[AppPreferenceKeys.WEEKLY_GOAL_ENABLED] ?: true,
            weeklyGoalHistory = WeeklyGoalRules.decode(prefs.stringOrNull(AppPreferenceKeys.WEEKLY_GOAL_HISTORY))
        )
    }

    override suspend fun updateUnitSystem(unitSystem: UnitSystem) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.UNIT_SYSTEM] = unitSystem.name
        }
    }

    override suspend fun updateWeekStart(weekStart: WeekStart) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.WEEK_START] = weekStart.name
        }
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.THEME_MODE] = themeMode.name
        }
    }

    override suspend fun updateThemeScheme(themeScheme: ThemeScheme) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.THEME_SCHEME] = themeScheme.name
        }
    }

    override suspend fun updateAppearanceStyle(appearanceStyle: com.thrym.app.domain.model.AppearanceStyle) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.APPEARANCE_STYLE] = appearanceStyle.name
        }
    }

    override suspend fun updateUseDynamicColor(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.USE_DYNAMIC_COLOR] = enabled
        }
    }

    override suspend fun updateReducedMotion(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.REDUCED_MOTION] = enabled
        }
    }

    override suspend fun updateDefaultWarmupFlag(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.DEFAULT_WARMUP_FLAG] = enabled
        }
    }

    override suspend fun updateTimerKeepScreenOn(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.TIMER_KEEP_SCREEN_ON] = enabled
        }
    }

    override suspend fun updateBetaDiagnosticsOptIn(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.BETA_DIAGNOSTICS_OPT_IN] = enabled
        }
    }

    override suspend fun updateReminderConfig(config: ReminderConfig) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.REMINDER_ENABLED] = config.enabled
            prefs[AppPreferenceKeys.REMINDER_HOUR] = config.hour.coerceIn(0, 23)
            prefs[AppPreferenceKeys.REMINDER_MINUTE] = config.minute.coerceIn(0, 59)
            prefs[AppPreferenceKeys.REMINDER_DAYS] = encodeReminderDays(config.daysOfWeek)
        }
    }

    override suspend fun updateIntensitySystem(intensitySystem: IntensitySystem) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.INTENSITY_SYSTEM] = intensitySystem.name
        }
    }

    override suspend fun updateShareWeightHistoryAcrossContexts(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.SHARE_WEIGHT_HISTORY_ACROSS_CONTEXTS] = enabled
        }
    }

    override suspend fun updateAutoRestTimerEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.AUTO_REST_TIMER_ENABLED] = enabled
        }
    }

    override suspend fun updateDefaultRestTimeSeconds(seconds: Int) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.DEFAULT_REST_TIME_SECONDS] = seconds.coerceIn(30, 600)
        }
    }

    override suspend fun updateDeloadMode(mode: DeloadMode?) {
        context.appPreferencesDataStore.edit { prefs ->
            if (mode == null) {
                prefs.remove(AppPreferenceKeys.DELOAD_MODE)
            } else {
                prefs[AppPreferenceKeys.DELOAD_MODE] = mode.name
            }
        }
        if (mode != null) {
            // A workout that is already running when the user switches the mode on is
            // conservatively recorded as a planned deload. Deactivation intentionally
            // does not clear the flag: a session that happened under a deload stays a
            // deload in history.
            workoutSessionDao?.getActiveSession()?.let { active ->
                if (active.isDeload != true) {
                    workoutSessionDao.update(active.copy(isDeload = true))
                }
            }
        }
    }

    override suspend fun updatePlateCalculatorEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.PLATE_CALCULATOR_ENABLED] = enabled
        }
    }

    override suspend fun updateAvailablePlates(plates: List<Double>) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.AVAILABLE_PLATES] = encodeAvailablePlates(plates)
        }
    }

    override suspend fun updateBarbellWeightKg(weightKg: Double) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.BARBELL_WEIGHT_KG] = weightKg
        }
    }

    override suspend fun updateLastSuccessfulExportEpochMillis(timestampMillis: Long?) {
        context.appPreferencesDataStore.edit { prefs ->
            if (timestampMillis == null) {
                prefs.remove(AppPreferenceKeys.LAST_SUCCESSFUL_EXPORT_EPOCH_MILLIS)
            } else {
                prefs[AppPreferenceKeys.LAST_SUCCESSFUL_EXPORT_EPOCH_MILLIS] = timestampMillis
            }
        }
    }

    override suspend fun updateBackupReminderEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.BACKUP_REMINDER_ENABLED] = enabled
        }
    }

    override suspend fun updateWeeklyGoalEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.WEEKLY_GOAL_ENABLED] = enabled
        }
    }

    override suspend fun updateWeeklyGoalHistory(history: List<WeeklyGoalChange>) {
        context.appPreferencesDataStore.edit { prefs ->
            prefs[AppPreferenceKeys.WEEKLY_GOAL_HISTORY] = WeeklyGoalRules.encode(history)
        }
    }

    override suspend fun readRestTimerState(sessionId: Long): String? =
        context.appPreferencesDataStore.data
            .catch { emit(emptyPreferences()) }
            .first()[AppPreferenceKeys.restTimerState(sessionId)]

    override suspend fun writeRestTimerState(sessionId: Long, encodedState: String?) {
        context.appPreferencesDataStore.edit { prefs ->
            val key = AppPreferenceKeys.restTimerState(sessionId)
            if (encodedState.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = encodedState
            }
        }
    }

    override suspend fun readWorkoutExerciseOrder(): String? =
        context.appPreferencesDataStore.data
            .catch { emit(emptyPreferences()) }
            .first()[AppPreferenceKeys.ACTIVE_WORKOUT_EXERCISE_ORDER]

    override suspend fun writeWorkoutExerciseOrder(encoded: String?) {
        context.appPreferencesDataStore.edit { prefs ->
            if (encoded.isNullOrBlank()) {
                prefs.remove(AppPreferenceKeys.ACTIVE_WORKOUT_EXERCISE_ORDER)
            } else {
                prefs[AppPreferenceKeys.ACTIVE_WORKOUT_EXERCISE_ORDER] = encoded
            }
        }
    }
}
