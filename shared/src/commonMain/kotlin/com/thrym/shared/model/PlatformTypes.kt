package com.thrym.shared.model

import com.thrym.shared.recognition.WeeklyGoalChange
import com.thrym.shared.recognition.WeeklyGoalRules
import kotlinx.serialization.Serializable

@Serializable
enum class UnitSystem {
    METRIC,
    IMPERIAL
}

@Serializable
enum class Weekday {
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY
}

@Serializable
enum class WeekStart {
    MONDAY,
    SUNDAY
}

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/** Overall look: the current "Ember" surfaces or the "Liquid Glass" redesign. */
@Serializable
enum class AppearanceStyle {
    EMBER,
    LIQUID_GLASS
}

@Serializable
enum class ThemeScheme {
    AMBER,
    DEEP_CYAN,
    NEON_RED,
    FORGE,
    RASTER,
    TIDE,
    PULSE
}

@Serializable
enum class IntensitySystem {
    OFF,
    RPE,
    RIR
}

/**
 * Active deload strategy. NONE keeps the planned target unchanged. The enum mirrors Android's
 * persisted DeloadMode names so the two clients keep one durable preference vocabulary.
 */
@Serializable
enum class DeloadMode {
    NONE,
    HALVE_SET_VOLUME,
    REDUCE_INTENSITY_BY_15_PERCENT,
}

@Serializable
data class ReminderConfig(
    val enabled: Boolean = false,
    val hour: Int = 19,
    val minute: Int = 0,
    val daysOfWeek: Set<Weekday> = setOf(
        Weekday.MONDAY,
        Weekday.WEDNESDAY,
        Weekday.FRIDAY
    )
)

@Serializable
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
    val deloadMode: DeloadMode = DeloadMode.NONE,
    val plateCalculatorEnabled: Boolean = true,
    val availablePlates: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25),
    val barbellWeightKg: Double = 20.0,
    /** Epoch millis of the most recent completed export to a user-selected document. */
    val lastSuccessfulExportEpochMillis: Long? = null,
    /** Opt-in for the local Settings reminder when the external export is stale. */
    val backupReminderEnabled: Boolean = false,
    /** Shows the weekly goal and its streak; switching it off keeps [weeklyGoalHistory]. */
    val weeklyGoalEnabled: Boolean = true,
    /** Goal per week from its change on; empty means the default goal throughout. */
    val weeklyGoalHistory: List<WeeklyGoalChange> = emptyList(),
    /** Applies the coach's increases to the plan after a workout ([com.thrym.shared.progression.ProgressionAutoApply]). */
    val autoApplyProgression: Boolean = false
) {
    /** Goal of the week starting at [weekStartEpochDay]. */
    fun weeklyGoalFor(weekStartEpochDay: Long): Int = WeeklyGoalRules.goalFor(weeklyGoalHistory, weekStartEpochDay)
}

@Serializable
data class BuildInfo(
    val versionName: String,
    val versionCode: Int
)
