package com.thrym.app.presentation.settings

import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.ReminderConfig
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.ThemeScheme
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.ReminderScheduler
import com.thrym.shared.model.Weekday
import com.thrym.shared.recognition.WeeklyGoalChange
import com.thrym.shared.settings.SharedAppPreferencesRepository
import com.thrym.shared.settings.SharedReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek

internal class AndroidSharedAppPreferencesRepository(
    private val delegate: AppPreferencesRepository
) : SharedAppPreferencesRepository {
    override val preferences: Flow<com.thrym.shared.model.AppPreferences> =
        delegate.preferences.map(AppPreferences::toShared)

    override suspend fun updateUnitSystem(unitSystem: com.thrym.shared.model.UnitSystem) {
        delegate.updateUnitSystem(unitSystem.toApp())
    }

    override suspend fun updateWeekStart(weekStart: com.thrym.shared.model.WeekStart) {
        delegate.updateWeekStart(weekStart.toApp())
    }

    override suspend fun updateThemeMode(themeMode: com.thrym.shared.model.ThemeMode) {
        delegate.updateThemeMode(themeMode.toApp())
    }

    override suspend fun updateThemeScheme(themeScheme: com.thrym.shared.model.ThemeScheme) {
        delegate.updateThemeScheme(themeScheme.toApp())
    }

    override suspend fun updateAppearanceStyle(appearanceStyle: com.thrym.shared.model.AppearanceStyle) {
        delegate.updateAppearanceStyle(appearanceStyle.toApp())
    }

    override suspend fun updateUseDynamicColor(enabled: Boolean) {
        delegate.updateUseDynamicColor(enabled)
    }

    override suspend fun updateReducedMotion(enabled: Boolean) {
        delegate.updateReducedMotion(enabled)
    }

    override suspend fun updateDefaultWarmupFlag(enabled: Boolean) {
        delegate.updateDefaultWarmupFlag(enabled)
    }

    override suspend fun updateTimerKeepScreenOn(enabled: Boolean) {
        delegate.updateTimerKeepScreenOn(enabled)
    }

    override suspend fun updateBetaDiagnosticsOptIn(enabled: Boolean) {
        delegate.updateBetaDiagnosticsOptIn(enabled)
    }

    override suspend fun updateReminderConfig(config: com.thrym.shared.model.ReminderConfig) {
        delegate.updateReminderConfig(config.toApp())
    }

    override suspend fun updateIntensitySystem(intensitySystem: com.thrym.shared.model.IntensitySystem) {
        delegate.updateIntensitySystem(intensitySystem.toApp())
    }

    override suspend fun updateShareWeightHistoryAcrossContexts(enabled: Boolean) {
        delegate.updateShareWeightHistoryAcrossContexts(enabled)
    }

    override suspend fun updateAutoRestTimerEnabled(enabled: Boolean) {
        delegate.updateAutoRestTimerEnabled(enabled)
    }

    override suspend fun updateDefaultRestTimeSeconds(seconds: Int) {
        delegate.updateDefaultRestTimeSeconds(seconds)
    }

    override suspend fun updateDeloadMode(mode: com.thrym.shared.model.DeloadMode) {
        delegate.updateDeloadMode(mode.toApp())
    }

    override suspend fun updatePlateCalculatorEnabled(enabled: Boolean) {
        delegate.updatePlateCalculatorEnabled(enabled)
    }

    override suspend fun updateAvailablePlates(plates: List<Double>) {
        delegate.updateAvailablePlates(plates)
    }

    override suspend fun updateBarbellWeightKg(weightKg: Double) {
        delegate.updateBarbellWeightKg(weightKg)
    }

    override suspend fun updateLastSuccessfulExportEpochMillis(timestampMillis: Long?) {
        delegate.updateLastSuccessfulExportEpochMillis(timestampMillis)
    }

    override suspend fun updateBackupReminderEnabled(enabled: Boolean) {
        delegate.updateBackupReminderEnabled(enabled)
    }

    override suspend fun updateWeeklyGoalEnabled(enabled: Boolean) {
        delegate.updateWeeklyGoalEnabled(enabled)
    }

    override suspend fun updateWeeklyGoalHistory(history: List<WeeklyGoalChange>) {
        delegate.updateWeeklyGoalHistory(history)
    }

    override suspend fun updateAutoApplyProgression(enabled: Boolean) {
        delegate.updateAutoApplyProgression(enabled)
    }
}

internal class AndroidSharedReminderScheduler(
    private val delegate: ReminderScheduler
) : SharedReminderScheduler {
    override suspend fun sync(config: com.thrym.shared.model.ReminderConfig) {
        delegate.sync(config.toApp())
    }

    override suspend fun cancel() {
        delegate.cancel()
    }
}

internal fun AppPreferences.toShared(): com.thrym.shared.model.AppPreferences =
    com.thrym.shared.model.AppPreferences(
        unitSystem = unitSystem.toShared(),
        weekStart = weekStart.toShared(),
        themeMode = themeMode.toShared(),
        themeScheme = themeScheme.toShared(),
        appearanceStyle = appearanceStyle.toShared(),
        useDynamicColor = useDynamicColor,
        reducedMotion = reducedMotion,
        defaultWarmupFlag = defaultWarmupFlag,
        timerKeepScreenOn = timerKeepScreenOn,
        betaDiagnosticsOptIn = betaDiagnosticsOptIn,
        reminderConfig = reminderConfig.toShared(),
        intensitySystem = intensitySystem.toShared(),
        shareWeightHistoryAcrossContexts = shareWeightHistoryAcrossContexts,
        autoRestTimerEnabled = autoRestTimerEnabled,
        defaultRestTimeSeconds = defaultRestTimeSeconds,
        deloadMode = deloadMode
            ?.let { com.thrym.shared.model.DeloadMode.valueOf(it.name) }
            ?: com.thrym.shared.model.DeloadMode.NONE,
        plateCalculatorEnabled = plateCalculatorEnabled,
        availablePlates = availablePlates,
        barbellWeightKg = barbellWeightKg,
        lastSuccessfulExportEpochMillis = lastSuccessfulExportEpochMillis,
        backupReminderEnabled = backupReminderEnabled,
        weeklyGoalEnabled = weeklyGoalEnabled,
        weeklyGoalHistory = weeklyGoalHistory,
        autoApplyProgression = autoApplyProgression
    )

internal fun com.thrym.shared.model.AppPreferences.toApp(): AppPreferences =
    AppPreferences(
        unitSystem = unitSystem.toApp(),
        weekStart = weekStart.toApp(),
        themeMode = themeMode.toApp(),
        themeScheme = themeScheme.toApp(),
        appearanceStyle = appearanceStyle.toApp(),
        useDynamicColor = useDynamicColor,
        reducedMotion = reducedMotion,
        defaultWarmupFlag = defaultWarmupFlag,
        timerKeepScreenOn = timerKeepScreenOn,
        betaDiagnosticsOptIn = betaDiagnosticsOptIn,
        reminderConfig = reminderConfig.toApp(),
        intensitySystem = intensitySystem.toApp(),
        shareWeightHistoryAcrossContexts = shareWeightHistoryAcrossContexts,
        autoRestTimerEnabled = autoRestTimerEnabled,
        defaultRestTimeSeconds = defaultRestTimeSeconds,
        deloadMode = deloadMode.toApp(),
        plateCalculatorEnabled = plateCalculatorEnabled,
        availablePlates = availablePlates,
        barbellWeightKg = barbellWeightKg,
        lastSuccessfulExportEpochMillis = lastSuccessfulExportEpochMillis,
        backupReminderEnabled = backupReminderEnabled,
        weeklyGoalEnabled = weeklyGoalEnabled,
        weeklyGoalHistory = weeklyGoalHistory,
        autoApplyProgression = autoApplyProgression
    )

internal fun ReminderConfig.toShared(): com.thrym.shared.model.ReminderConfig =
    com.thrym.shared.model.ReminderConfig(
        enabled = enabled,
        hour = hour,
        minute = minute,
        daysOfWeek = daysOfWeek.map(DayOfWeek::toShared).toSet()
    )

internal fun com.thrym.shared.model.ReminderConfig.toApp(): ReminderConfig =
    ReminderConfig(
        enabled = enabled,
        hour = hour,
        minute = minute,
        daysOfWeek = daysOfWeek.map(Weekday::toApp).toSet()
    )

internal fun UnitSystem.toShared(): com.thrym.shared.model.UnitSystem =
    com.thrym.shared.model.UnitSystem.valueOf(name)

internal fun com.thrym.shared.model.UnitSystem.toApp(): UnitSystem = UnitSystem.valueOf(name)

private fun com.thrym.shared.model.DeloadMode.toApp(): com.thrym.app.domain.model.DeloadMode? =
    takeUnless { this == com.thrym.shared.model.DeloadMode.NONE }
        ?.let { com.thrym.app.domain.model.DeloadMode.valueOf(it.name) }

internal fun WeekStart.toShared(): com.thrym.shared.model.WeekStart =
    com.thrym.shared.model.WeekStart.valueOf(name)

internal fun com.thrym.shared.model.WeekStart.toApp(): WeekStart = WeekStart.valueOf(name)

internal fun ThemeMode.toShared(): com.thrym.shared.model.ThemeMode =
    com.thrym.shared.model.ThemeMode.valueOf(name)

internal fun com.thrym.shared.model.ThemeMode.toApp(): ThemeMode = ThemeMode.valueOf(name)

internal fun ThemeScheme.toShared(): com.thrym.shared.model.ThemeScheme =
    com.thrym.shared.model.ThemeScheme.valueOf(name)

internal fun com.thrym.shared.model.ThemeScheme.toApp(): ThemeScheme = ThemeScheme.valueOf(name)

internal fun com.thrym.app.domain.model.AppearanceStyle.toShared(): com.thrym.shared.model.AppearanceStyle =
    com.thrym.shared.model.AppearanceStyle.valueOf(name)

internal fun com.thrym.shared.model.AppearanceStyle.toApp(): com.thrym.app.domain.model.AppearanceStyle = com.thrym.app.domain.model.AppearanceStyle.valueOf(name)

internal fun IntensitySystem.toShared(): com.thrym.shared.model.IntensitySystem =
    com.thrym.shared.model.IntensitySystem.valueOf(name)

internal fun com.thrym.shared.model.IntensitySystem.toApp(): IntensitySystem =
    IntensitySystem.valueOf(name)

private fun DayOfWeek.toShared(): Weekday = when (this) {
    DayOfWeek.MONDAY -> Weekday.MONDAY
    DayOfWeek.TUESDAY -> Weekday.TUESDAY
    DayOfWeek.WEDNESDAY -> Weekday.WEDNESDAY
    DayOfWeek.THURSDAY -> Weekday.THURSDAY
    DayOfWeek.FRIDAY -> Weekday.FRIDAY
    DayOfWeek.SATURDAY -> Weekday.SATURDAY
    DayOfWeek.SUNDAY -> Weekday.SUNDAY
}

private fun Weekday.toApp(): DayOfWeek = when (this) {
    Weekday.MONDAY -> DayOfWeek.MONDAY
    Weekday.TUESDAY -> DayOfWeek.TUESDAY
    Weekday.WEDNESDAY -> DayOfWeek.WEDNESDAY
    Weekday.THURSDAY -> DayOfWeek.THURSDAY
    Weekday.FRIDAY -> DayOfWeek.FRIDAY
    Weekday.SATURDAY -> DayOfWeek.SATURDAY
    Weekday.SUNDAY -> DayOfWeek.SUNDAY
}
