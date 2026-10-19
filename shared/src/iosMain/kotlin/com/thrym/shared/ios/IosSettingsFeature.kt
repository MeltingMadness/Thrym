@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.thrym.shared.ios

import com.thrym.shared.incident.IncidentDiagnostics
import com.thrym.shared.incident.IncidentReportPayload
import com.thrym.shared.incident.IncidentReportSanitizer
import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.model.DeloadMode
import com.thrym.shared.model.AppPreferences
import com.thrym.shared.model.BackupBlob
import com.thrym.shared.model.BuildInfo
import com.thrym.shared.model.IncidentAttachment
import com.thrym.shared.model.IntensitySystem
import com.thrym.shared.model.ProfileSex
import com.thrym.shared.model.ReminderConfig
import com.thrym.shared.model.ThemeMode
import com.thrym.shared.model.AppearanceStyle
import com.thrym.shared.model.ThemeScheme
import com.thrym.shared.model.UnitSystem
import com.thrym.shared.model.WeekStart
import com.thrym.shared.model.Weekday
import com.thrym.shared.repository.SharedBackupRepository
import com.thrym.shared.repository.SharedIncidentReportRepository
import com.thrym.shared.settings.SettingsPreferencesController
import com.thrym.shared.settings.SettingsPreferencesState
import com.thrym.shared.settings.SharedAppPreferencesRepository
import com.thrym.shared.settings.SharedReminderScheduler
import com.thrym.shared.settings.isBackupReminderDue
import com.thrym.shared.store.ProgressionLifecycle
import com.thrym.shared.store.SharedStateStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSDateComponents
import platform.Foundation.NSString
import platform.Foundation.NSUserDefaults
import platform.Foundation.setObject
import platform.UIKit.UIDevice
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.localizedUserNotificationStringForKey
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Sentinel stored when the user explicitly deselects every reminder day. Mirrors the
// Android REMINDER_DAYS_NONE_SENTINEL so a reload can distinguish "user chose no
// days" from "preference was never written" instead of snapping back to Mon/Wed/Fri.
private const val REMINDER_DAYS_NONE_SENTINEL = "none"
private const val AVAILABLE_PLATES_NONE_SENTINEL = "none"
private const val MIN_REST_TIME_SECONDS = 30
private const val MAX_REST_TIME_SECONDS = 600

class IosSettingsFeature {
    private val scope: CoroutineScope = MainScope()
    private val buildInfo = currentBuildInfo()
    private val preferencesRepository = IosPreferencesStore.repository
    private val reminderScheduler = IosReminderScheduler()
    private val backupRepository = IosBackupRepository()
    private val incidentRepository = IosIncidentReportRepository(buildInfo = buildInfo)
    private val controller = SettingsPreferencesController(
        scope = scope,
        appPreferencesRepository = preferencesRepository,
        reminderScheduler = reminderScheduler,
    )

    init {
        // Restore scheduled notifications after a relaunch. Updating a preference already syncs
        // the scheduler; this initial pass covers reminders that were enabled in an earlier run.
        scope.launch {
            runCatching { reminderScheduler.sync(preferencesRepository.currentPreferences.reminderConfig) }
        }
    }

    fun currentState(): IosSettingsState =
        SettingsPreferencesState(preferencesRepository.currentPreferences).toIosState(buildInfo)

    fun watchState(onState: (IosSettingsState) -> Unit): IosCloseable {
        val job = scope.launch {
            controller.state.collect { state ->
                onState(state.toIosState(buildInfo))
            }
        }
        return IosCloseable { job.cancel() }
    }

    fun unitSystemOptions(): List<String> = UnitSystem.entries.map { it.name }
    fun weekStartOptions(): List<String> = WeekStart.entries.map { it.name }
    fun themeModeOptions(): List<String> = ThemeMode.entries.map { it.name }
    fun themeSchemeOptions(): List<String> = ThemeScheme.entries.map { it.name }
    fun appearanceStyleOptions(): List<String> = AppearanceStyle.entries.map { it.name }
    fun intensitySystemOptions(): List<String> = IntensitySystem.entries.map { it.name }
    fun deloadModeOptions(): List<String> = DeloadMode.entries.map { it.name }
    fun weekdayOptions(): List<String> = Weekday.entries.map { it.name }

    fun updateUnitSystem(value: String) {
        controller.updateUnitSystem(UnitSystem.entries.firstOrNull { it.name == value } ?: UnitSystem.METRIC)
    }

    fun updateWeekStart(value: String) {
        controller.updateWeekStart(WeekStart.entries.firstOrNull { it.name == value } ?: WeekStart.MONDAY)
    }

    fun updateThemeMode(value: String) {
        controller.updateThemeMode(ThemeMode.entries.firstOrNull { it.name == value } ?: ThemeMode.SYSTEM)
    }

    fun updateThemeScheme(value: String) {
        controller.updateThemeScheme(ThemeScheme.entries.firstOrNull { it.name == value } ?: ThemeScheme.AMBER)
    }

    fun updateAppearanceStyle(value: String) {
        controller.updateAppearanceStyle(AppearanceStyle.entries.firstOrNull { it.name == value } ?: AppearanceStyle.LIQUID_GLASS)
    }

    fun updateIntensitySystem(value: String) {
        controller.updateIntensitySystem(IntensitySystem.entries.firstOrNull { it.name == value } ?: IntensitySystem.RPE)
    }

    fun updateDeloadMode(value: String) {
        controller.updateDeloadMode(DeloadMode.entries.firstOrNull { it.name == value } ?: DeloadMode.NONE)
    }

    fun updateUseDynamicColor(enabled: Boolean) = controller.updateUseDynamicColor(enabled)
    fun updateReducedMotion(enabled: Boolean) = controller.updateReducedMotion(enabled)
    fun updateDefaultWarmupFlag(enabled: Boolean) = controller.updateDefaultWarmupFlag(enabled)
    fun updateTimerKeepScreenOn(enabled: Boolean) = controller.updateTimerKeepScreenOn(enabled)
    fun updateBetaDiagnosticsOptIn(enabled: Boolean) = controller.updateBetaDiagnosticsOptIn(enabled)
    fun updateShareWeightHistoryAcrossContexts(enabled: Boolean) =
        controller.updateShareWeightHistoryAcrossContexts(enabled)
    fun updateAutoRestTimerEnabled(enabled: Boolean) = controller.updateAutoRestTimerEnabled(enabled)
    fun updateDefaultRestTimeSeconds(seconds: Int) = controller.updateDefaultRestTimeSeconds(seconds)
    fun updatePlateCalculatorEnabled(enabled: Boolean) = controller.updatePlateCalculatorEnabled(enabled)
    fun updateAvailablePlates(plates: List<Double>) = controller.updateAvailablePlates(plates)
    fun updateBarbellWeightKg(weightKg: Double) = controller.updateBarbellWeightKg(weightKg)
    fun updateBackupReminderEnabled(enabled: Boolean) = controller.updateBackupReminderEnabled(enabled)

    /** An empty or unknown value clears the answer. */
    fun updateProfileSex(value: String) {
        controller.updateProfileSex(ProfileSex.entries.firstOrNull { it.name == value })
    }

    /** `0` clears the answer, so Swift does not need an optional Kotlin integer. */
    fun updateProfileBirthYear(year: Int) {
        controller.updateProfileBirthYear(year.takeIf { it > 0 })
    }

    fun profileBirthYearOptions(currentYear: Int): List<Int> =
        com.thrym.shared.settings.profileBirthYearOptions(currentYear)

    /**
     * Records a completed external export after the platform document writer confirms success.
     * The payload preparation callback alone is not sufficient because a user may cancel the
     * subsequent SwiftUI file exporter.
     */
    fun recordSuccessfulBackupExport() {
        controller.updateLastSuccessfulExportEpochMillis(currentEpochMillis())
    }

    fun updateReminder(enabled: Boolean, hour: Int, minute: Int, days: List<String>) {
        controller.updateReminderConfig(
            ReminderConfig(
                enabled = enabled,
                hour = hour.coerceIn(0, 23),
                minute = minute.coerceIn(0, 59),
                daysOfWeek = days.mapNotNull { dayName ->
                    Weekday.entries.firstOrNull { it.name == dayName }
                }.toSet(),
            ),
        )
    }

    fun requestReminderPermission(onResult: (Boolean, String?) -> Unit) {
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, error ->
            onResult(granted, error?.localizedDescription)
        }
    }

    fun exportBackup(onResult: (IosDocumentPayload?, String?) -> Unit) {
        scope.launch {
            runCatching {
                backupRepository.exportBackup().toIosDocumentPayload()
            }.onSuccess { payload ->
                onResult(payload, null)
            }.onFailure { error ->
                onResult(null, error.message)
            }
        }
    }

    /**
     * Decodes and validates a backup without changing the active training store. The returned
     * immutable snapshot is the exact state observed for the confirmation guard; the import
     * path compares it again while holding the store transaction mutex.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun inspectBackup(base64Data: String, onResult: (IosBackupPreview?, String?) -> Unit) {
        scope.launch {
            runCatching {
                val serialized = Base64.decode(base64Data).decodeToString()
                val payload = SharedStateStore.decodeAndUpgradeForPreview(serialized)
                val currentStore = IosTrainingStore.current()
                IosBackupPreview(
                    formatVersion = payload.formatVersion,
                    schemaVersion = payload.schemaVersion,
                    exerciseCount = payload.exercises.size,
                    workoutSessionCount = payload.workoutSessions.size,
                    workoutSetCount = payload.workoutSets.size,
                    trainingPlanCount = payload.trainingPlans.size,
                    metaPlanCount = payload.metaTrainingPlans.size,
                    progressionSuggestionCount = payload.progressionSuggestions.size,
                    replacesExistingData = currentStore != null,
                    expectedSnapshot = currentStore?.snapshot(),
                )
            }.onSuccess { preview ->
                onResult(preview, null)
            }.onFailure { error ->
                onResult(null, error.message)
            }
        }
    }

    /** Loads metadata for the most recent verified pre-import snapshot, if one exists. */
    fun latestRecovery(onResult: (IosRecoveryBackup?, String?) -> Unit) {
        scope.launch {
            runCatching { backupRepository.latestRecovery() }
                .onSuccess { recovery -> onResult(recovery, null) }
                .onFailure { error ->
                    onResult(
                        null,
                        error.message ?: "Wiederherstellungspunkt konnte nicht gelesen werden.",
                    )
                }
        }
    }

    /** Restores the latest verified snapshot after the SwiftUI confirmation dialog. */
    fun restoreLatestRecovery(onResult: (IosRecoveryBackup?, String?) -> Unit) {
        scope.launch {
            runCatching { backupRepository.restoreLatestRecovery() }
                .onSuccess { recovery ->
                    onResult(recovery, null)
                }
                .onFailure { error ->
                    onResult(
                        null,
                        error.message ?: "Wiederherstellungspunkt konnte nicht geladen werden.",
                    )
                }
        }
    }

    /** Imports the bytes previously shown in a validated confirmation preview. */
    @OptIn(ExperimentalEncodingApi::class)
    fun importBackup(
        base64Data: String,
        expectedSnapshot: BackupPayloadV1?,
        onResult: (Boolean, String?) -> Unit,
    ) {
        scope.launch {
            runCatching {
                backupRepository.importBackupAfterPreview(
                    bytes = Base64.decode(base64Data),
                    expectedSnapshot = expectedSnapshot,
                )
            }.onSuccess {
                onResult(true, null)
            }.onFailure { error ->
                onResult(false, error.message)
            }
        }
    }

    fun resetUserData(onResult: (Boolean, String?) -> Unit) {
        scope.launch {
            runCatching {
                backupRepository.resetUserData()
            }.onSuccess {
                onResult(true, null)
            }.onFailure { error ->
                onResult(false, error.message)
            }
        }
    }

    fun createIncidentReport(
        summary: String,
        details: String,
        currentScreen: String,
        includeDiagnostics: Boolean,
        onResult: (IosDocumentPayload?, String?) -> Unit,
    ) {
        scope.launch {
            runCatching {
                incidentRepository.createIncidentReport(
                    summary = summary,
                    details = details,
                    currentScreen = currentScreen,
                    includeDiagnostics = includeDiagnostics,
                ).toIosDocumentPayload()
            }.onSuccess { payload ->
                onResult(payload, null)
            }.onFailure { error ->
                onResult(null, error.message)
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}

class IosCloseable(
    private val closeAction: () -> Unit,
) {
    fun close() {
        closeAction()
    }
}

class IosDocumentPayload(
    val base64Data: String,
    val fileName: String,
    val mimeType: String,
)

/** User-facing, validator-backed summary shown before replacing local training data. */
class IosBackupPreview(
    val formatVersion: Int,
    val schemaVersion: Int,
    val exerciseCount: Int,
    val workoutSessionCount: Int,
    val workoutSetCount: Int,
    val trainingPlanCount: Int,
    val metaPlanCount: Int,
    val progressionSuggestionCount: Int,
    val replacesExistingData: Boolean,
    val expectedSnapshot: BackupPayloadV1?,
)

/** Metadata for the latest verified pre-import training snapshot. */
class IosRecoveryBackup(
    val timestampMillis: Long,
    val sizeBytes: Long,
    val exerciseCount: Int,
    val workoutSessionCount: Int,
    val workoutSetCount: Int,
    val trainingPlanCount: Int,
    val planExerciseCount: Int,
    val personalRecordCount: Int,
    val metaPlanCount: Int,
    val metaPlanItemCount: Int,
    val metaPlanSkipCount: Int,
    val workoutPlanTargetCount: Int,
    val progressionSuggestionCount: Int,
)

class IosSettingsState(
    val unitSystem: String,
    val weekStart: String,
    val themeMode: String,
    val themeScheme: String,
    val appearanceStyle: String,
    val useDynamicColor: Boolean,
    val reducedMotion: Boolean,
    val defaultWarmupFlag: Boolean,
    val timerKeepScreenOn: Boolean,
    val betaDiagnosticsOptIn: Boolean,
    val shareWeightHistoryAcrossContexts: Boolean,
    val autoRestTimerEnabled: Boolean,
    val defaultRestTimeSeconds: Int,
    val deloadMode: String,
    val plateCalculatorEnabled: Boolean,
    val availablePlates: List<Double>,
    val barbellWeightKg: Double,
    val reminderEnabled: Boolean,
    val reminderHour: Int,
    val reminderMinute: Int,
    val reminderDays: List<String>,
    val intensitySystem: String,
    val lastSuccessfulExportEpochMillis: Long?,
    val backupReminderEnabled: Boolean,
    val backupReminderDue: Boolean,
    /** `ProfileSex` name, or an empty string for no answer. */
    val profileSex: String,
    /** Birth year, or `0` for no answer. */
    val profileBirthYear: Int,
    val versionName: String,
    val versionCode: Int,
)

/**
 * Process-wide iOS preferences repository.
 *
 * Settings and the workout bridge must observe the same in-memory snapshot. Keeping the
 * repository here also avoids one feature constructing a second NSUserDefaults-backed flow whose
 * updates would otherwise lag behind the active workout.
 */
internal object IosPreferencesStore {
    internal val repository = IosAppPreferencesRepository()
}

internal class IosAppPreferencesRepository(
    private val userDefaults: NSUserDefaults = NSUserDefaults.standardUserDefaults(),
) : SharedAppPreferencesRepository {
    private val mutablePreferences = MutableStateFlow(loadPreferences())

    override val preferences: Flow<AppPreferences> = mutablePreferences.asStateFlow()

    override suspend fun updateUnitSystem(unitSystem: UnitSystem) = persist { it.copy(unitSystem = unitSystem) }
    override suspend fun updateWeekStart(weekStart: WeekStart) = persist { it.copy(weekStart = weekStart) }
    override suspend fun updateThemeMode(themeMode: ThemeMode) = persist { it.copy(themeMode = themeMode) }
    override suspend fun updateThemeScheme(themeScheme: ThemeScheme) = persist { it.copy(themeScheme = themeScheme) }
    override suspend fun updateAppearanceStyle(appearanceStyle: AppearanceStyle) = persist { it.copy(appearanceStyle = appearanceStyle) }
    override suspend fun updateUseDynamicColor(enabled: Boolean) = persist { it.copy(useDynamicColor = enabled) }
    override suspend fun updateReducedMotion(enabled: Boolean) = persist { it.copy(reducedMotion = enabled) }
    override suspend fun updateDefaultWarmupFlag(enabled: Boolean) = persist { it.copy(defaultWarmupFlag = enabled) }
    override suspend fun updateTimerKeepScreenOn(enabled: Boolean) = persist { it.copy(timerKeepScreenOn = enabled) }
    override suspend fun updateBetaDiagnosticsOptIn(enabled: Boolean) = persist { it.copy(betaDiagnosticsOptIn = enabled) }
    override suspend fun updateReminderConfig(config: ReminderConfig) = persist { it.copy(reminderConfig = config) }
    override suspend fun updateIntensitySystem(intensitySystem: IntensitySystem) = persist { it.copy(intensitySystem = intensitySystem) }
    override suspend fun updateShareWeightHistoryAcrossContexts(enabled: Boolean) = persist { it.copy(shareWeightHistoryAcrossContexts = enabled) }
    override suspend fun updateAutoRestTimerEnabled(enabled: Boolean) = persist { it.copy(autoRestTimerEnabled = enabled) }
    override suspend fun updateDefaultRestTimeSeconds(seconds: Int) = persist {
        it.copy(defaultRestTimeSeconds = seconds.coerceIn(MIN_REST_TIME_SECONDS, MAX_REST_TIME_SECONDS))
    }
    override suspend fun updateDeloadMode(mode: DeloadMode) = persist {
        it.copy(deloadMode = mode)
    }
    override suspend fun updatePlateCalculatorEnabled(enabled: Boolean) = persist { it.copy(plateCalculatorEnabled = enabled) }
    override suspend fun updateAvailablePlates(plates: List<Double>) = persist {
        it.copy(availablePlates = normalizeAvailablePlates(plates))
    }
    override suspend fun updateBarbellWeightKg(weightKg: Double) = persist {
        it.copy(barbellWeightKg = weightKg.coerceAtLeast(0.0))
    }
    override suspend fun updateLastSuccessfulExportEpochMillis(timestampMillis: Long?) =
        persist { it.copy(lastSuccessfulExportEpochMillis = timestampMillis) }
    override suspend fun updateBackupReminderEnabled(enabled: Boolean) =
        persist { it.copy(backupReminderEnabled = enabled) }
    override suspend fun updateProfileSex(sex: ProfileSex?) = persist { it.copy(profileSex = sex) }
    override suspend fun updateProfileBirthYear(birthYear: Int?) =
        persist { it.copy(profileBirthYear = birthYear?.takeIf { year -> year > 0 }) }

    private fun persist(transform: (AppPreferences) -> AppPreferences) {
        val updated = transform(mutablePreferences.value)
        savePreferences(updated)
        mutablePreferences.value = updated
    }

    private fun loadPreferences(): AppPreferences {
        val defaults = AppPreferences()
        return AppPreferences(
            unitSystem = userDefaults.stringForKey(Keys.unitSystem)
                ?.let { value -> UnitSystem.entries.firstOrNull { it.name == value } }
                ?: defaults.unitSystem,
            weekStart = userDefaults.stringForKey(Keys.weekStart)
                ?.let { value -> WeekStart.entries.firstOrNull { it.name == value } }
                ?: defaults.weekStart,
            themeMode = userDefaults.stringForKey(Keys.themeMode)
                ?.let { value -> ThemeMode.entries.firstOrNull { it.name == value } }
                ?: defaults.themeMode,
            themeScheme = userDefaults.stringForKey(Keys.themeScheme)
                ?.let { value -> ThemeScheme.entries.firstOrNull { it.name == value } }
                ?: defaults.themeScheme,
            appearanceStyle = userDefaults.stringForKey(Keys.appearanceStyle)
                ?.let { value -> AppearanceStyle.entries.firstOrNull { it.name == value } }
                ?: defaults.appearanceStyle,
            useDynamicColor = userDefaults.boolOrDefault(Keys.useDynamicColor, defaults.useDynamicColor),
            reducedMotion = userDefaults.boolOrDefault(Keys.reducedMotion, defaults.reducedMotion),
            defaultWarmupFlag = userDefaults.boolOrDefault(Keys.defaultWarmupFlag, defaults.defaultWarmupFlag),
            timerKeepScreenOn = userDefaults.boolOrDefault(Keys.timerKeepScreenOn, defaults.timerKeepScreenOn),
            betaDiagnosticsOptIn = userDefaults.boolOrDefault(Keys.betaDiagnosticsOptIn, defaults.betaDiagnosticsOptIn),
            reminderConfig = ReminderConfig(
                enabled = userDefaults.boolOrDefault(Keys.reminderEnabled, defaults.reminderConfig.enabled),
                hour = userDefaults.intOrDefault(Keys.reminderHour, defaults.reminderConfig.hour),
                minute = userDefaults.intOrDefault(Keys.reminderMinute, defaults.reminderConfig.minute),
                daysOfWeek = loadReminderDays(),
            ),
        intensitySystem = userDefaults.stringForKey(Keys.intensitySystem)
                ?.let { value -> IntensitySystem.entries.firstOrNull { it.name == value } }
                ?: defaults.intensitySystem,
            deloadMode = userDefaults.stringForKey(Keys.deloadMode)
                ?.let { value -> DeloadMode.entries.firstOrNull { it.name == value } }
                ?: defaults.deloadMode,
            shareWeightHistoryAcrossContexts = userDefaults.boolOrDefault(
                Keys.shareWeightHistoryAcrossContexts,
                defaults.shareWeightHistoryAcrossContexts
            ),
            autoRestTimerEnabled = userDefaults.boolOrDefault(
                Keys.autoRestTimerEnabled,
                defaults.autoRestTimerEnabled
            ),
            defaultRestTimeSeconds = userDefaults.intOrDefault(
                Keys.defaultRestTimeSeconds,
                defaults.defaultRestTimeSeconds
            ).coerceIn(MIN_REST_TIME_SECONDS, MAX_REST_TIME_SECONDS),
            plateCalculatorEnabled = userDefaults.boolOrDefault(
                Keys.plateCalculatorEnabled,
                defaults.plateCalculatorEnabled
            ),
            availablePlates = loadAvailablePlates(defaults.availablePlates),
            barbellWeightKg = userDefaults.doubleOrDefault(
                Keys.barbellWeightKg,
                defaults.barbellWeightKg
            ),
            lastSuccessfulExportEpochMillis = userDefaults.longOrNull(Keys.lastSuccessfulExportEpochMillis),
            backupReminderEnabled = userDefaults.boolOrDefault(
                Keys.backupReminderEnabled,
                defaults.backupReminderEnabled
            ),
            profileSex = userDefaults.stringForKey(Keys.profileSex)
                ?.let { value -> ProfileSex.entries.firstOrNull { it.name == value } },
            profileBirthYear = userDefaults.intOrDefault(Keys.profileBirthYear, 0).takeIf { it > 0 },
        )
    }

    val currentPreferences: AppPreferences
        get() = mutablePreferences.value

    private fun loadReminderDays(): Set<Weekday> {
        val stored = userDefaults.objectForKey(Keys.reminderDays)
        return when (stored) {
            // Key has never been written — fall back to the Mon/Wed/Fri default.
            null -> ReminderConfig().daysOfWeek
            // Explicitly persisted empty selection — respect it instead of snapping back.
            REMINDER_DAYS_NONE_SENTINEL -> emptySet()
            is List<*> -> stored
                .mapNotNull { value ->
                    (value as? String)?.let { name -> Weekday.entries.firstOrNull { it.name == name } }
                }
                .toSet()
            else -> ReminderConfig().daysOfWeek
        }
    }

    private fun saveReminderDays(days: Set<Weekday>) {
        if (days.isEmpty()) {
            // Distinguish "user chose no days" from "preference was never written" so a
            // reload cannot reset an explicit empty selection to the Mon/Wed/Fri default.
            userDefaults.setObject(REMINDER_DAYS_NONE_SENTINEL, Keys.reminderDays)
        } else {
            userDefaults.setObject(days.map { it.name }, Keys.reminderDays)
        }
    }

    private fun savePreferences(preferences: AppPreferences) {
        userDefaults.setObject(preferences.unitSystem.name, Keys.unitSystem)
        userDefaults.setObject(preferences.weekStart.name, Keys.weekStart)
        userDefaults.setObject(preferences.themeMode.name, Keys.themeMode)
        userDefaults.setObject(preferences.themeScheme.name, Keys.themeScheme)
        userDefaults.setObject(preferences.appearanceStyle.name, Keys.appearanceStyle)
        userDefaults.setBool(preferences.useDynamicColor, Keys.useDynamicColor)
        userDefaults.setBool(preferences.reducedMotion, Keys.reducedMotion)
        userDefaults.setBool(preferences.defaultWarmupFlag, Keys.defaultWarmupFlag)
        userDefaults.setBool(preferences.timerKeepScreenOn, Keys.timerKeepScreenOn)
        userDefaults.setBool(preferences.betaDiagnosticsOptIn, Keys.betaDiagnosticsOptIn)
        userDefaults.setBool(preferences.reminderConfig.enabled, Keys.reminderEnabled)
        userDefaults.setInteger(preferences.reminderConfig.hour.toLong(), Keys.reminderHour)
        userDefaults.setInteger(preferences.reminderConfig.minute.toLong(), Keys.reminderMinute)
        saveReminderDays(preferences.reminderConfig.daysOfWeek)
        userDefaults.setObject(preferences.intensitySystem.name, Keys.intensitySystem)
        userDefaults.setObject(preferences.deloadMode.name, Keys.deloadMode)
        userDefaults.setBool(preferences.shareWeightHistoryAcrossContexts, Keys.shareWeightHistoryAcrossContexts)
        userDefaults.setBool(preferences.autoRestTimerEnabled, Keys.autoRestTimerEnabled)
        userDefaults.setInteger(
            preferences.defaultRestTimeSeconds.coerceIn(MIN_REST_TIME_SECONDS, MAX_REST_TIME_SECONDS).toLong(),
            Keys.defaultRestTimeSeconds,
        )
        userDefaults.setBool(preferences.plateCalculatorEnabled, Keys.plateCalculatorEnabled)
        userDefaults.setObject(encodeAvailablePlates(preferences.availablePlates), Keys.availablePlates)
        userDefaults.setDouble(preferences.barbellWeightKg.coerceAtLeast(0.0), Keys.barbellWeightKg)
        preferences.lastSuccessfulExportEpochMillis?.let { timestamp ->
            userDefaults.setInteger(timestamp, Keys.lastSuccessfulExportEpochMillis)
        } ?: userDefaults.removeObjectForKey(Keys.lastSuccessfulExportEpochMillis)
        userDefaults.setBool(preferences.backupReminderEnabled, Keys.backupReminderEnabled)
        preferences.profileSex?.let { sex ->
            userDefaults.setObject(sex.name, Keys.profileSex)
        } ?: userDefaults.removeObjectForKey(Keys.profileSex)
        preferences.profileBirthYear?.let { year ->
            userDefaults.setInteger(year.toLong(), Keys.profileBirthYear)
        } ?: userDefaults.removeObjectForKey(Keys.profileBirthYear)
    }

    private object Keys {
        const val unitSystem = "settings.unitSystem"
        const val weekStart = "settings.weekStart"
        const val themeMode = "settings.themeMode"
        const val themeScheme = "settings.themeScheme"
        const val appearanceStyle = "settings.appearanceStyle"
        const val useDynamicColor = "settings.useDynamicColor"
        const val reducedMotion = "settings.reducedMotion"
        const val defaultWarmupFlag = "settings.defaultWarmupFlag"
        const val timerKeepScreenOn = "settings.timerKeepScreenOn"
        const val betaDiagnosticsOptIn = "settings.betaDiagnosticsOptIn"
        const val reminderEnabled = "settings.reminderEnabled"
        const val reminderHour = "settings.reminderHour"
        const val reminderMinute = "settings.reminderMinute"
        const val reminderDays = "settings.reminderDays"
        const val intensitySystem = "settings.intensitySystem"
        const val deloadMode = "settings.deloadMode"
        const val shareWeightHistoryAcrossContexts = "settings.shareWeightHistoryAcrossContexts"
        const val autoRestTimerEnabled = "settings.autoRestTimerEnabled"
        const val defaultRestTimeSeconds = "settings.defaultRestTimeSeconds"
        const val plateCalculatorEnabled = "settings.plateCalculatorEnabled"
        const val availablePlates = "settings.availablePlates"
        const val barbellWeightKg = "settings.barbellWeightKg"
        const val lastSuccessfulExportEpochMillis = "settings.lastSuccessfulExportEpochMillis"
        const val backupReminderEnabled = "settings.backupReminderEnabled"
        const val profileSex = "settings.profileSex"
        const val profileBirthYear = "settings.profileBirthYear"
    }

    private fun loadAvailablePlates(defaults: List<Double>): List<Double> {
        val stored = userDefaults.stringForKey(Keys.availablePlates) ?: return defaults
        if (stored == AVAILABLE_PLATES_NONE_SENTINEL) return emptyList()
        return stored.split(',')
            .mapNotNull { it.trim().toDoubleOrNull() }
            .filter { it > 0.0 }
            .distinct()
            .sortedDescending()
    }

    private fun encodeAvailablePlates(plates: List<Double>): String {
        val normalized = normalizeAvailablePlates(plates)
        return if (normalized.isEmpty()) {
            AVAILABLE_PLATES_NONE_SENTINEL
        } else {
            normalized.joinToString(",")
        }
    }

    private fun normalizeAvailablePlates(plates: List<Double>): List<Double> = plates
        .filter { it > 0.0 }
        .distinct()
        .sortedDescending()
}

private class IosReminderScheduler(
    private val notificationCenter: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter(),
) : SharedReminderScheduler {
    override suspend fun sync(config: ReminderConfig) {
        cancel()
        if (!config.enabled || config.daysOfWeek.isEmpty()) return

        config.daysOfWeek.forEach { weekday ->
            scheduleReminder(weekday, config.hour, config.minute)
        }
    }

    override suspend fun cancel() {
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(
            identifiers = Weekday.entries.map(::identifierForWeekday),
        )
    }

    private suspend fun scheduleReminder(weekday: Weekday, hour: Int, minute: Int) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val content = UNMutableNotificationContent().apply {
                setTitle(appDisplayName())
                // Localized from the app's string catalog when the notification is delivered.
                setBody(NSString.localizedUserNotificationStringForKey("Zeit für dein Training.", arguments = null))
                setSound(UNNotificationSound.defaultSound())
            }

            val components = NSDateComponents().apply {
                setWeekday(weekday.toIosWeekday().toLong())
                setHour(hour.toLong())
                setMinute(minute.toLong())
            }

            val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                dateComponents = components,
                repeats = true,
            )

            val request = UNNotificationRequest.requestWithIdentifier(
                identifier = identifierForWeekday(weekday),
                content = content,
                trigger = trigger,
            )

            notificationCenter.addNotificationRequest(request) { error ->
                if (error == null) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException(error.localizedDescription),
                    )
                }
            }
        }
    }

    private fun appDisplayName(): String =
        (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName") as? String)
            ?: (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleName") as? String)
            ?: ""

    private fun identifierForWeekday(weekday: Weekday): String = "thrym.reminder.${weekday.name.lowercase()}"
}

private class IosBackupRepository : SharedBackupRepository {

    override suspend fun exportBackup(): BackupBlob {
        val store = IosTrainingStore.current()
            ?: throw IllegalStateException(
                IosTrainingStore.currentError() ?: "Trainingsdaten konnten nicht geladen werden."
            )
        return BackupBlob(
            bytes = store.exportJson().encodeToByteArray(),
            fileName = "thrym-backup-v1.json",
            mimeType = "application/json",
        )
    }

    override suspend fun importBackup(bytes: ByteArray) {
        importBackup(bytes = bytes, expectedSnapshot = null)
    }

    suspend fun importBackup(bytes: ByteArray, expectedSnapshot: BackupPayloadV1?) {
        importBackupInternal(bytes = bytes, expectedSnapshot = expectedSnapshot, enforceSnapshot = false)
    }

    suspend fun importBackupAfterPreview(bytes: ByteArray, expectedSnapshot: BackupPayloadV1?) {
        importBackupInternal(bytes = bytes, expectedSnapshot = expectedSnapshot, enforceSnapshot = true)
    }

    private suspend fun importBackupInternal(
        bytes: ByteArray,
        expectedSnapshot: BackupPayloadV1?,
        enforceSnapshot: Boolean,
    ) {
        val serialized = bytes.decodeToString()
        val store = IosTrainingStore.current()
        if (!enforceSnapshot) {
            if (store == null) {
                IosTrainingStore.recoverFromImport(serialized)
            } else {
                store.importJson(serialized)
            }
        } else if (store == null) {
            require(expectedSnapshot == null) {
                "Trainingsdaten konnten zwischen Vorschau und Import nicht geladen werden."
            }
            // recoverFromImport has its own mutex guard, so a store that appears after the
            // preview is rejected instead of being overwritten by a stale confirmation.
            IosTrainingStore.recoverFromImport(serialized)
        } else {
            requireNotNull(expectedSnapshot) {
                "Trainingsdaten wurden zwischen Vorschau und Import geladen."
            }
            store.importJsonIfUnchangedWithRecovery(serialized, expectedSnapshot) { recovery ->
                // The callback runs while SharedStateStore holds its transaction mutex. It must
                // only persist the supplied immutable snapshot and never re-enter the store.
                IosTrainingStore.saveRecovery(recovery)
            }
        }
        IosTrainingStore.current()?.let { recoveredStore ->
            val lifecycle = ProgressionLifecycle(recoveredStore)
            lifecycle.generateMissingOutcomes()
            lifecycle.reconcileOutstandingSuggestions()
        }
    }

    suspend fun latestRecovery(): IosRecoveryBackup? = IosTrainingStore.latestRecovery()

    suspend fun restoreLatestRecovery(): IosRecoveryBackup? {
        val restored = IosTrainingStore.restoreLatestRecovery() ?: return null
        IosTrainingStore.current()?.let { restoredStore ->
            val lifecycle = ProgressionLifecycle(restoredStore)
            lifecycle.generateMissingOutcomes()
            lifecycle.reconcileOutstandingSuggestions()
        }
        return restored
    }

    override suspend fun resetUserData() {
        val store = IosTrainingStore.current()
            ?: throw IllegalStateException(
                IosTrainingStore.currentError() ?: "Trainingsdaten konnten nicht geladen werden."
            )
        store.resetUserData()
    }
}

private class IosIncidentReportRepository(
    private val buildInfo: BuildInfo,
) : SharedIncidentReportRepository {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createIncidentReport(
        summary: String,
        details: String,
        currentScreen: String,
        includeDiagnostics: Boolean,
        throwableDescription: String?,
    ): IncidentAttachment {
        val incidentId = Uuid.random().toString().take(8)
        val createdAt = currentEpochMillis()
        val payload = IncidentReportPayload(
            incidentId = incidentId,
            createdAtEpochMillis = createdAt,
            appVersionName = buildInfo.versionName,
            appVersionCode = buildInfo.versionCode,
            currentScreen = currentScreen,
            summary = IncidentReportSanitizer.sanitizeText(summary.trim()),
            details = IncidentReportSanitizer.sanitizeText(details.trim()),
            stacktrace = throwableDescription
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(IncidentReportSanitizer::sanitizeText),
            diagnostics = if (includeDiagnostics) {
                IncidentDiagnostics(
                    osVersion = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
                    deviceModel = UIDevice.currentDevice.model,
                    manufacturer = "Apple",
                )
            } else {
                null
            },
        )

        return IncidentAttachment(
            bytes = json.encodeToString(IncidentReportPayload.serializer(), payload).encodeToByteArray(),
            fileName = "incident-$incidentId.json",
            mimeType = "application/json",
        )
    }
}

private fun SettingsPreferencesState.toIosState(buildInfo: BuildInfo): IosSettingsState = IosSettingsState(
    unitSystem = preferences.unitSystem.name,
    weekStart = preferences.weekStart.name,
    themeMode = preferences.themeMode.name,
    themeScheme = preferences.themeScheme.name,
    appearanceStyle = preferences.appearanceStyle.name,
    useDynamicColor = preferences.useDynamicColor,
    reducedMotion = preferences.reducedMotion,
    defaultWarmupFlag = preferences.defaultWarmupFlag,
    timerKeepScreenOn = preferences.timerKeepScreenOn,
    betaDiagnosticsOptIn = preferences.betaDiagnosticsOptIn,
    shareWeightHistoryAcrossContexts = preferences.shareWeightHistoryAcrossContexts,
    autoRestTimerEnabled = preferences.autoRestTimerEnabled,
    defaultRestTimeSeconds = preferences.defaultRestTimeSeconds,
    deloadMode = preferences.deloadMode.name,
    plateCalculatorEnabled = preferences.plateCalculatorEnabled,
    availablePlates = preferences.availablePlates,
    barbellWeightKg = preferences.barbellWeightKg,
    reminderEnabled = preferences.reminderConfig.enabled,
    reminderHour = preferences.reminderConfig.hour,
    reminderMinute = preferences.reminderConfig.minute,
    reminderDays = preferences.reminderConfig.daysOfWeek.map { it.name },
    intensitySystem = preferences.intensitySystem.name,
    lastSuccessfulExportEpochMillis = preferences.lastSuccessfulExportEpochMillis,
    backupReminderEnabled = preferences.backupReminderEnabled,
    backupReminderDue = isBackupReminderDue(
        reminderEnabled = preferences.backupReminderEnabled,
        lastSuccessfulExportEpochMillis = preferences.lastSuccessfulExportEpochMillis,
        nowEpochMillis = currentEpochMillis(),
    ),
    profileSex = preferences.profileSex?.name.orEmpty(),
    profileBirthYear = preferences.profileBirthYear ?: 0,
    versionName = buildInfo.versionName,
    versionCode = buildInfo.versionCode,
)

@OptIn(ExperimentalEncodingApi::class)
private fun BackupBlob.toIosDocumentPayload(): IosDocumentPayload = IosDocumentPayload(
    base64Data = bytes.encodeBase64(),
    fileName = fileName,
    mimeType = mimeType,
)

@OptIn(ExperimentalEncodingApi::class)
private fun IncidentAttachment.toIosDocumentPayload(): IosDocumentPayload = IosDocumentPayload(
    base64Data = bytes.encodeBase64(),
    fileName = fileName,
    mimeType = mimeType,
)

private fun NSUserDefaults.boolOrDefault(key: String, defaultValue: Boolean): Boolean =
    if (objectForKey(key) == null) defaultValue else boolForKey(key)

private fun NSUserDefaults.intOrDefault(key: String, defaultValue: Int): Int =
    if (objectForKey(key) == null) defaultValue else integerForKey(key).toInt()

private fun NSUserDefaults.longOrNull(key: String): Long? =
    if (objectForKey(key) == null) null else integerForKey(key)

private fun NSUserDefaults.doubleOrDefault(key: String, defaultValue: Double): Double =
    if (objectForKey(key) == null) defaultValue else doubleForKey(key)

private fun Weekday.toIosWeekday(): Int = when (this) {
    Weekday.SUNDAY -> 1
    Weekday.MONDAY -> 2
    Weekday.TUESDAY -> 3
    Weekday.WEDNESDAY -> 4
    Weekday.THURSDAY -> 5
    Weekday.FRIDAY -> 6
    Weekday.SATURDAY -> 7
}

private fun currentBuildInfo(): BuildInfo {
    val bundle = NSBundle.mainBundle
    val versionName = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.1.0"
    val versionCode = (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 1
    return BuildInfo(versionName = versionName, versionCode = versionCode)
}

@OptIn(ExperimentalEncodingApi::class)
private fun ByteArray.encodeBase64(): String = Base64.encode(this)

private fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
