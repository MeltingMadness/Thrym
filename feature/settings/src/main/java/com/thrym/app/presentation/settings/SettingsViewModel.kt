package com.thrym.app.presentation.settings

import kotlinx.coroutines.flow.first
import com.thrym.shared.recognition.WeeklyGoalRules
import java.time.LocalDate
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.thrym.app.data.backup.BackupConcurrentModificationException
import com.thrym.app.data.backup.BackupHashMismatchException
import com.thrym.app.data.backup.BackupSchemaTooNewException
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.IncidentReport
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.ReminderConfig
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.ThemeScheme
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.BackupImportPreview
import com.thrym.app.domain.repository.BackupRepository
import com.thrym.app.domain.repository.IncidentReportRepository
import com.thrym.app.domain.repository.RecoveryBackup
import com.thrym.app.domain.repository.ProgressionRepository
import kotlinx.coroutines.CancellationException
import com.thrym.app.domain.repository.ReminderScheduler
import com.thrym.app.domain.util.AppLogger
import com.thrym.app.domain.util.BuildInfo
import com.thrym.shared.settings.SettingsPreferencesController
import com.thrym.shared.settings.isBackupReminderDue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences(),
    val isBusy: Boolean = false,
    val showResetDialog: Boolean = false,
    val importConfirmationVisible: Boolean = false,
    val importPreview: BackupImportPreview? = null,
    val recoveryBackup: RecoveryBackup? = null,
    val showRecoveryRestoreDialog: Boolean = false,
    val backupReminderDue: Boolean = false,
    val buildInfo: BuildInfo = BuildInfo("", 0)
)

sealed interface SettingsEvent {
    data class Message(val textRes: Int, val args: List<Any> = emptyList()) : SettingsEvent
    data class ShareIncident(val report: IncidentReport) : SettingsEvent
}

class SettingsViewModel(
    private val appPreferencesRepository: AppPreferencesRepository,
    private val backupRepository: BackupRepository,
    private val reminderScheduler: ReminderScheduler,
    private val incidentReportRepository: IncidentReportRepository,
    private val buildInfo: BuildInfo,
    private val progressionRepository: ProgressionRepository
) : ViewModel() {
    private val preferencesController = SettingsPreferencesController(
        scope = viewModelScope,
        appPreferencesRepository = AndroidSharedAppPreferencesRepository(appPreferencesRepository),
        reminderScheduler = AndroidSharedReminderScheduler(reminderScheduler)
    )

    private val isBusy = MutableStateFlow(false)
    private val showResetDialog = MutableStateFlow(false)
    private val importConfirmationVisible = MutableStateFlow(false)
    private val importPreview = MutableStateFlow<BackupImportPreview?>(null)
    private val recoveryBackup = MutableStateFlow<RecoveryBackup?>(null)
    private val showRecoveryRestoreDialog = MutableStateFlow(false)
    private val _events = MutableSharedFlow<SettingsEvent>()
    private var selectedImportUri: Uri? = null

    init {
        viewModelScope.launch { refreshRecoveryAvailability() }
    }

    val events = _events.asSharedFlow()

    private val baseState = combine(
        preferencesController.state,
        isBusy,
        showResetDialog,
        importConfirmationVisible,
        importPreview
    ) { preferencesState, busy, resetDialog, importVisible, preview ->
        SettingsUiState(
            preferences = preferencesState.preferences.toApp(),
            isBusy = busy,
            showResetDialog = resetDialog,
            importConfirmationVisible = importVisible,
            importPreview = preview,
            backupReminderDue = isBackupReminderDue(
                reminderEnabled = preferencesState.preferences.backupReminderEnabled,
                lastSuccessfulExportEpochMillis = preferencesState.preferences.lastSuccessfulExportEpochMillis,
                nowEpochMillis = System.currentTimeMillis(),
            ),
            buildInfo = buildInfo
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        baseState,
        recoveryBackup,
        showRecoveryRestoreDialog
    ) { base, recovery, restoreDialog ->
        base.copy(
            recoveryBackup = recovery,
            showRecoveryRestoreDialog = restoreDialog
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun updateUnitSystem(unitSystem: UnitSystem) {
        preferencesController.updateUnitSystem(unitSystem.toShared())
    }

    fun updateWeekStart(weekStart: WeekStart) {
        preferencesController.updateWeekStart(weekStart.toShared())
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        preferencesController.updateThemeMode(themeMode.toShared())
    }

    fun updateThemeScheme(themeScheme: ThemeScheme) {
        preferencesController.updateThemeScheme(themeScheme.toShared())
    }

    fun updateAppearanceStyle(appearanceStyle: com.thrym.app.domain.model.AppearanceStyle) {
        preferencesController.updateAppearanceStyle(appearanceStyle.toShared())
    }

    fun updateUseDynamicColor(enabled: Boolean) {
        preferencesController.updateUseDynamicColor(enabled)
    }

    fun updateReducedMotion(enabled: Boolean) {
        preferencesController.updateReducedMotion(enabled)
    }

    fun updateDefaultWarmupFlag(enabled: Boolean) {
        preferencesController.updateDefaultWarmupFlag(enabled)
    }

    fun updateTimerKeepScreenOn(enabled: Boolean) {
        preferencesController.updateTimerKeepScreenOn(enabled)
    }

    fun updateBetaDiagnosticsOptIn(enabled: Boolean) {
        preferencesController.updateBetaDiagnosticsOptIn(enabled)
    }

    fun updateIntensitySystem(system: IntensitySystem) {
        preferencesController.updateIntensitySystem(system.toShared())
    }

    fun updateShareWeightHistoryAcrossContexts(enabled: Boolean) {
        preferencesController.updateShareWeightHistoryAcrossContexts(enabled)
    }

    fun updateReminderConfig(config: ReminderConfig) {
        preferencesController.updateReminderConfig(config.toShared())
    }

    fun updateAutoRestTimerEnabled(enabled: Boolean) {
        preferencesController.updateAutoRestTimerEnabled(enabled)
    }

    fun updateDefaultRestTimeSeconds(seconds: Int) {
        preferencesController.updateDefaultRestTimeSeconds(seconds)
    }

    fun updatePlateCalculatorEnabled(enabled: Boolean) {
        preferencesController.updatePlateCalculatorEnabled(enabled)
    }

    fun updateAvailablePlates(plates: List<Double>) {
        preferencesController.updateAvailablePlates(plates)
    }

    fun updateBarbellWeightKg(weightKg: Double) {
        preferencesController.updateBarbellWeightKg(weightKg)
    }

    fun updateBackupReminderEnabled(enabled: Boolean) {
        preferencesController.updateBackupReminderEnabled(enabled)
    }

    fun updateWeeklyGoalEnabled(enabled: Boolean) {
        preferencesController.updateWeeklyGoalEnabled(enabled)
    }

    /** Sets the weekly goal from the running week on; earlier weeks keep theirs. */
    fun updateWeeklyGoal(goal: Int) {
        viewModelScope.launch {
            val weekStart = appPreferencesRepository.preferences.first().weekStart.toShared()
            preferencesController.updateWeeklyGoal(goal, currentWeekStartEpochDay(weekStart))
        }
    }


    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            runBusy {
                backupRepository.exportBackup(uri)
                // Keep the timestamp tied to the verified write. A failed export therefore
                // leaves the previous timestamp untouched and keeps the reminder actionable.
                appPreferencesRepository.updateLastSuccessfulExportEpochMillis(
                    System.currentTimeMillis()
                )
            }.onSuccess {
                _events.emit(
                    SettingsEvent.Message(
                        com.thrym.core.designsystem.R.string.settings_msg_backup_exported
                    )
                )
            }.onFailure { error -> emitError(error) }
        }
    }

    fun onImportUriPicked(uri: Uri) {
        if (isBusy.value) return
        viewModelScope.launch {
            if (isBusy.value) return@launch
            runBusy { backupRepository.previewImport(uri) }
                .onSuccess { preview ->
                    // Fail closed again: if another action claimed busy or a selection
                    // was bound while this preview was in flight, do not overwrite it.
                    if (isBusy.value || selectedImportUri != null) return@onSuccess
                    selectedImportUri = uri
                    importPreview.value = preview
                    importConfirmationVisible.value = true
                }
                .onFailure { error ->
                    clearImportSelection()
                    emitError(error)
                }
        }
    }

    fun cancelImport() {
        clearImportSelection()
    }

    fun confirmImport() {
        val preview = importPreview.value
        val uri = selectedImportUri
        if (isBusy.value || uri == null || preview == null || !preview.isValid) return

        viewModelScope.launch {
            runBusy {
                backupRepository.importBackup(uri, preview.sha256)
                recoverImportedProgressions()
            }
                .onSuccess { progressionRecovered ->
                    clearImportSelection()
                    _events.emit(
                        SettingsEvent.Message(
                            com.thrym.core.designsystem.R.string.settings_msg_backup_imported
                        )
                    )
                    if (!progressionRecovered) {
                        _events.emit(SettingsEvent.Message(
                            com.thrym.core.designsystem.R.string.settings_msg_progression_recovery_failed
                        ))
                    }
                    syncReminderAndWarn()
                    refreshRecoveryAvailability()
                }
                .onFailure { error ->
                    if (error is BackupHashMismatchException) {
                        clearImportSelection()
                        _events.emit(
                            SettingsEvent.Message(
                                com.thrym.core.designsystem.R.string.settings_msg_backup_source_changed
                            )
                        )
                    } else {
                        // Keep the preview and URI so the same document stays retryable.
                        emitError(error)
                    }
                }
        }
    }

    fun showRecoveryRestoreDialog() {
        showRecoveryRestoreDialog.value = true
    }

    fun dismissRecoveryRestoreDialog() {
        showRecoveryRestoreDialog.value = false
    }

    fun restoreLatestRecovery() {
        if (isBusy.value || recoveryBackup.value == null) return

        viewModelScope.launch {
            showRecoveryRestoreDialog.value = false
            runBusy {
                backupRepository.restoreLatestRecovery()
                recoverImportedProgressions()
            }
                .onSuccess { progressionRecovered ->
                    _events.emit(
                        SettingsEvent.Message(
                            com.thrym.core.designsystem.R.string.settings_msg_recovery_restored
                        )
                    )
                    if (!progressionRecovered) {
                        _events.emit(SettingsEvent.Message(
                            com.thrym.core.designsystem.R.string.settings_msg_progression_recovery_failed
                        ))
                    }
                    syncReminderAndWarn()
                    refreshRecoveryAvailability()
                }
                .onFailure { error -> emitError(error) }
        }
    }

    fun showResetDialog() {
        showResetDialog.value = true
    }

    fun dismissResetDialog() {
        showResetDialog.value = false
    }

    fun resetUserData() {
        viewModelScope.launch {
            showResetDialog.value = false
            runBusyAction(
                action = { backupRepository.resetUserData() },
                successMessageRes = com.thrym.core.designsystem.R.string.settings_msg_data_reset
            )
        }
    }

    fun createIncidentReport(summary: String, details: String, currentScreen: String) {
        viewModelScope.launch {
            runBusyAction(
                action = {
                    val report = incidentReportRepository.createIncidentReport(
                        summary = summary,
                        details = details,
                        currentScreen = currentScreen,
                        includeDiagnostics = uiState.value.preferences.betaDiagnosticsOptIn
                    )
                    _events.emit(SettingsEvent.ShareIncident(report))
                },
                successMessageRes = com.thrym.core.designsystem.R.string.settings_msg_incident_created
            )
        }
    }

    fun onNotificationPermissionDenied() {
        viewModelScope.launch {
            _events.emit(SettingsEvent.Message(com.thrym.core.designsystem.R.string.settings_msg_notification_denied))
        }
    }

    private suspend fun runBusyAction(
        action: suspend () -> Unit,
        @StringRes successMessageRes: Int
    ) {
        runBusy { action() }
            .onSuccess {
                _events.emit(SettingsEvent.Message(successMessageRes))
            }
            .onFailure { error -> emitError(error) }
    }

    private suspend fun <T> runBusy(block: suspend () -> T): Result<T> {
        isBusy.value = true
        val result = runCatching { block() }
        isBusy.value = false
        return result
    }

    private fun clearImportSelection() {
        selectedImportUri = null
        importPreview.value = null
        importConfirmationVisible.value = false
    }

    /**
     * Localized copy for the failures the user can act on. Other exception messages are
     * English developer diagnostics: they go to the log, the UI shows a generic message.
     */
    private suspend fun emitError(error: Throwable) {
        val message = when (error) {
            is BackupSchemaTooNewException -> SettingsEvent.Message(
                com.thrym.feature.settings.R.string.settings_error_backup_too_new,
                listOf(error.backupSchemaVersion, error.appSchemaVersion)
            )
            is BackupConcurrentModificationException -> SettingsEvent.Message(
                com.thrym.feature.settings.R.string.settings_error_backup_data_changed
            )
            else -> {
                // Logging is best-effort (android.util.Log is unavailable in JVM unit tests).
                runCatching { AppLogger.w(TAG, "Settings action failed: ${error.message}", error) }
                SettingsEvent.Message(com.thrym.feature.settings.R.string.settings_unknown_error)
            }
        }
        _events.emit(message)
    }

    // A committed import stays successful even if its derived-data catch-up fails.
    // Run while busy so a second import cannot overlap this post-processing.
    private suspend fun recoverImportedProgressions(): Boolean = try {
        progressionRepository.generateMissingOutcomes()
        progressionRepository.reconcileOutstandingSuggestions()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun syncReminderAndWarn() {
        runCatching { reminderScheduler.sync(uiState.value.preferences.reminderConfig) }
            .onFailure {
                _events.emit(
                    SettingsEvent.Message(
                        com.thrym.core.designsystem.R.string.settings_msg_reminder_sync_failed
                    )
                )
            }
    }

    private suspend fun refreshRecoveryAvailability() {
        runCatching { backupRepository.latestRecovery() }
            .onSuccess { recoveryBackup.value = it }
            .onFailure {
                Log.w(TAG, "Could not refresh recovery backup availability", it)
            }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}

/** First day of the running week as an epoch day, by the configured week start. */
internal fun currentWeekStartEpochDay(weekStart: com.thrym.shared.model.WeekStart): Long =
    WeeklyGoalRules.weekStartOf(LocalDate.now().toEpochDay(), weekStart)
