package com.thrym.app.domain.model

import java.time.DayOfWeek

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

/** Sex from the optional profile, used only to scale strength comparisons. Persisted by name. */
enum class ProfileSex {
    FEMALE,
    MALE
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
    /** Optional profile: sex for fair strength comparisons. `null` means no answer. */
    val profileSex: ProfileSex? = null,
    /** Optional profile: birth year rather than age, so it stays valid. `null` means no answer. */
    val profileBirthYear: Int? = null
)
