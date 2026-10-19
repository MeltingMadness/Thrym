package com.thrym.shared.settings

import com.thrym.shared.model.AppPreferences
import com.thrym.shared.model.DeloadMode
import com.thrym.shared.model.IntensitySystem
import com.thrym.shared.model.ProfileSex
import com.thrym.shared.model.ReminderConfig
import com.thrym.shared.model.ThemeMode
import com.thrym.shared.model.AppearanceStyle
import com.thrym.shared.model.ThemeScheme
import com.thrym.shared.model.UnitSystem
import com.thrym.shared.model.WeekStart
import com.thrym.shared.model.Weekday
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPreferencesControllerTest {

    @Test
    fun updateThemeMode_persistsSelectedMode() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )

        controller.updateThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, repository.current.themeMode)
    }

    @Test
    fun updateAppearanceStyle_persistsEmberAndDefaultsToLiquidGlass() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = FakeSharedReminderScheduler()
        )
        assertEquals(AppearanceStyle.LIQUID_GLASS, repository.current.appearanceStyle)

        controller.updateAppearanceStyle(AppearanceStyle.EMBER)
        advanceUntilIdle()

        assertEquals(AppearanceStyle.EMBER, repository.current.appearanceStyle)
    }

    @Test
    fun updateReminderConfig_persistsAndSyncsReminderScheduler() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )
        val config = ReminderConfig(enabled = true, daysOfWeek = setOf(Weekday.MONDAY, Weekday.FRIDAY))

        controller.updateReminderConfig(config)
        advanceUntilIdle()

        assertEquals(config, repository.current.reminderConfig)
        assertEquals(config, reminderScheduler.syncedConfig)
    }

    @Test
    fun updateReminderConfig_swallowsReminderSchedulerFailures() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FailingSharedReminderScheduler()
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, throwable ->
                uncaught += throwable
            }
        )
        val controller = SettingsPreferencesController(
            scope = scope,
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )
        val config = ReminderConfig(enabled = true, daysOfWeek = setOf(Weekday.MONDAY))

        controller.updateReminderConfig(config)
        advanceUntilIdle()

        assertTrue(uncaught.isEmpty())
        assertEquals(config, repository.current.reminderConfig)
    }

    @Test
    fun updateShareWeightHistoryAcrossContexts_persistsFlag() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )

        controller.updateShareWeightHistoryAcrossContexts(true)
        advanceUntilIdle()

        assertTrue(repository.current.shareWeightHistoryAcrossContexts)
    }

    @Test
    fun updateAutoRestTimerEnabled_persistsFlag() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )

        controller.updateAutoRestTimerEnabled(true)
        advanceUntilIdle()

        assertTrue(repository.current.autoRestTimerEnabled)
    }

    @Test
    fun updateDefaultRestTimeSeconds_persistsDuration() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )

        controller.updateDefaultRestTimeSeconds(180)
        advanceUntilIdle()

        assertEquals(180, repository.current.defaultRestTimeSeconds)
    }

    @Test
    fun updatePlateCalculatorEnabled_persistsFlag() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )

        controller.updatePlateCalculatorEnabled(false)
        advanceUntilIdle()

        assertEquals(false, repository.current.plateCalculatorEnabled)
    }

    @Test
    fun updateAvailablePlates_persistsCustomPlateList() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )
        val customPlates = listOf(25.0, 20.0, 10.0, 5.0, 2.5, 1.25) // e.g. no 15kg plate

        controller.updateAvailablePlates(customPlates)
        advanceUntilIdle()

        assertEquals(customPlates, repository.current.availablePlates)
    }

    @Test
    fun updateBarbellWeightKg_persistsWeight() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )

        controller.updateBarbellWeightKg(15.0)
        advanceUntilIdle()

        assertEquals(15.0, repository.current.barbellWeightKg)
    }

    @Test
    fun updateBackupReminderEnabled_persistsOptInWithoutSchedulingTrainingReminder() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val reminderScheduler = FakeSharedReminderScheduler()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = reminderScheduler
        )

        controller.updateBackupReminderEnabled(true)
        advanceUntilIdle()

        assertTrue(repository.current.backupReminderEnabled)
        assertEquals(null, reminderScheduler.syncedConfig)
    }

    @Test
    fun backupReminder_isDueAtSevenDaysOnlyWhenOptedIn() {
        val sevenDays = BACKUP_REMINDER_THRESHOLD_MILLIS

        assertTrue(
            isBackupReminderDue(
                reminderEnabled = true,
                lastSuccessfulExportEpochMillis = null,
                nowEpochMillis = 0L
            )
        )
        assertTrue(
            isBackupReminderDue(
                reminderEnabled = true,
                lastSuccessfulExportEpochMillis = 100L,
                nowEpochMillis = 100L + sevenDays
            )
        )
        assertEquals(
            false,
            isBackupReminderDue(
                reminderEnabled = true,
                lastSuccessfulExportEpochMillis = 100L,
                nowEpochMillis = 100L + sevenDays - 1L
            )
        )
        assertEquals(
            false,
            isBackupReminderDue(
                reminderEnabled = false,
                lastSuccessfulExportEpochMillis = null,
                nowEpochMillis = 0L
            )
        )
    }

    @Test
    fun profile_defaultsToNoAnswer() {
        val preferences = AppPreferences()

        assertEquals(null, preferences.profileSex)
        assertEquals(null, preferences.profileBirthYear)
    }

    @Test
    fun updateProfile_persistsSexAndBirthYearAndClearsThem() = runTest {
        val repository = FakeSharedAppPreferencesRepository()
        val controller = SettingsPreferencesController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            appPreferencesRepository = repository,
            reminderScheduler = FakeSharedReminderScheduler()
        )

        controller.updateProfileSex(ProfileSex.FEMALE)
        controller.updateProfileBirthYear(1985)
        advanceUntilIdle()

        assertEquals(ProfileSex.FEMALE, repository.current.profileSex)
        assertEquals(1985, repository.current.profileBirthYear)

        controller.updateProfileSex(null)
        controller.updateProfileBirthYear(null)
        advanceUntilIdle()

        assertEquals(null, repository.current.profileSex)
        assertEquals(null, repository.current.profileBirthYear)
    }

    @Test
    fun profileBirthYearOptions_coverAgesTenToHundredNewestFirst() {
        val options = profileBirthYearOptions(currentYear = 2026)

        assertEquals(2016, options.first())
        assertEquals(1926, options.last())
        assertEquals(91, options.size)
    }
}

private class FakeSharedAppPreferencesRepository(
    initial: AppPreferences = AppPreferences()
) : SharedAppPreferencesRepository {
    private val state = MutableStateFlow(initial)

    val current: AppPreferences
        get() = state.value

    override val preferences: Flow<AppPreferences> = state.asStateFlow()

    override suspend fun updateUnitSystem(unitSystem: UnitSystem) {
        state.value = state.value.copy(unitSystem = unitSystem)
    }

    override suspend fun updateWeekStart(weekStart: WeekStart) {
        state.value = state.value.copy(weekStart = weekStart)
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        state.value = state.value.copy(themeMode = themeMode)
    }

    override suspend fun updateAppearanceStyle(appearanceStyle: AppearanceStyle) {
        state.value = state.value.copy(appearanceStyle = appearanceStyle)
    }

    override suspend fun updateThemeScheme(themeScheme: ThemeScheme) {
        state.value = state.value.copy(themeScheme = themeScheme)
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

    override suspend fun updateReminderConfig(config: ReminderConfig) {
        state.value = state.value.copy(reminderConfig = config)
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
        state.value = state.value.copy(defaultRestTimeSeconds = seconds)
    }

    override suspend fun updateDeloadMode(mode: DeloadMode) {
        state.value = state.value.copy(deloadMode = mode)
    }

    override suspend fun updatePlateCalculatorEnabled(enabled: Boolean) {
        state.value = state.value.copy(plateCalculatorEnabled = enabled)
    }

    override suspend fun updateAvailablePlates(plates: List<Double>) {
        state.value = state.value.copy(availablePlates = plates)
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

    override suspend fun updateProfileSex(sex: ProfileSex?) {
        state.value = state.value.copy(profileSex = sex)
    }

    override suspend fun updateProfileBirthYear(birthYear: Int?) {
        state.value = state.value.copy(profileBirthYear = birthYear)
    }
}

private class FailingSharedReminderScheduler : SharedReminderScheduler {
    override suspend fun sync(config: ReminderConfig) {
        throw IllegalStateException("scheduler kaputt")
    }

    override suspend fun cancel() = Unit
}

private class FakeSharedReminderScheduler : SharedReminderScheduler {
    var syncedConfig: ReminderConfig? = null
        private set

    override suspend fun sync(config: ReminderConfig) {
        syncedConfig = config
    }

    override suspend fun cancel() = Unit
}
