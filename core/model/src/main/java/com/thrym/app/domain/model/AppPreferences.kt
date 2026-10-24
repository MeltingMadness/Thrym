package com.thrym.app.domain.model

import com.thrym.shared.recognition.WeeklyGoalChange
import com.thrym.shared.recognition.WeeklyGoalRules
import java.time.DayOfWeek
import java.time.LocalDate

enum class UnitSystem {
    METRIC,
    IMPERIAL
}

enum class WeekStart {
    MONDAY,
    SUNDAY
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/** Overall look: the current "Ember" surfaces or the "Liquid Glass" redesign. */
enum class AppearanceStyle {
    EMBER,
    LIQUID_GLASS
}

enum class ThemeScheme {
    AMBER,
    DEEP_CYAN,
    NEON_RED,
    FORGE,
    RASTER,
    TIDE,
    PULSE
}

data class ReminderConfig(
    val enabled: Boolean = false,
    val hour: Int = 19,
    val minute: Int = 0,
    val daysOfWeek: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.FRIDAY
    )
)

data class AppPreferences(
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val weekStart: WeekStart = WeekStart.MONDAY,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val themeScheme: ThemeScheme = ThemeScheme.AMBER,
    val appearanceStyle: AppearanceStyle = AppearanceStyle.LIQUID_GLASS,
    val useDynamicColor: Boolean = false,
    val reducedMotion: Boolean = false,
    val defaultWarmupFlag: Boolean = false,
    val timerKeepScreenOn: Boolean = false,
    val betaDiagnosticsOptIn: Boolean = false,
    val reminderConfig: ReminderConfig = ReminderConfig(),
    val intensitySystem: IntensitySystem = IntensitySystem.RPE,
    val shareWeightHistoryAcrossContexts: Boolean = false,
    val autoRestTimerEnabled: Boolean = false,
    val defaultRestTimeSeconds: Int = 120,
    val deloadMode: DeloadMode? = null,
    val plateCalculatorEnabled: Boolean = true,
    val availablePlates: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25),
    val barbellWeightKg: Double = 20.0,
    /** Epoch millis of the most recent completed export to a user-selected document. */
    val lastSuccessfulExportEpochMillis: Long? = null,
    /** Opt-in for the local Settings reminder when the external export is stale. */
    val backupReminderEnabled: Boolean = false,
    /** Shows the weekly goal and its streak; switching it off keeps [weeklyGoalHistory]. */
    val weeklyGoalEnabled: Boolean = true,
    /** Goal per week from its change on (see [WeeklyGoalRules]); empty means the default goal. */
    val weeklyGoalHistory: List<WeeklyGoalChange> = emptyList(),
    /** Applies the coach's increases to the plan after a workout (`ProgressionAutoApply`). */
    val autoApplyProgression: Boolean = false
) {
    /** Goal of the week starting at [weekStart]. */
    fun weeklyGoalFor(weekStart: LocalDate): Int = WeeklyGoalRules.goalFor(weeklyGoalHistory, weekStart.toEpochDay())
}
