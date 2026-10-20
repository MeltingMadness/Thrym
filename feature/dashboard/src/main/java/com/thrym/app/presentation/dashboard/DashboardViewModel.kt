package com.thrym.app.presentation.dashboard

import com.thrym.app.presentation.common.toSharedWeekStart
import com.thrym.shared.recognition.TrainingMilestones
import com.thrym.shared.recognition.WeeklyGoalEngine
import com.thrym.shared.recognition.WeeklyGoalSession
import com.thrym.shared.recognition.WeeklyGoalStatus
import com.thrym.app.presentation.common.UiStrings
import com.thrym.app.presentation.common.toUserDetail
import com.thrym.feature.dashboard.R as UxR
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrym.app.data.local.entity.EpochConverter
import com.thrym.app.domain.model.DeloadAssessment
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.MetaPlanRotationEvent
import com.thrym.app.domain.model.MetaTrainingPlan
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.PersonalRecord
import com.thrym.app.domain.model.TrainingPlan
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.util.MuscleVolume
import com.thrym.app.domain.util.MuscleVolumeCalculator
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.DeloadRepository
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.MetaTrainingPlanRepository
import com.thrym.app.domain.repository.ProgressionRepository
import com.thrym.app.domain.repository.StatisticsRepository
import com.thrym.app.domain.repository.ReadinessProjectionSource
import com.thrym.app.domain.repository.ReadinessRepository
import com.thrym.app.domain.repository.TrainingPlanRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.analytics.SharedReadinessProjection
import com.thrym.shared.readiness.ReadinessAssessment
import com.thrym.shared.readinessdata.ReadinessCheckIn
import com.thrym.shared.model.MuscleGroup as SharedMuscleGroup
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.domain.util.AppLogger
import com.thrym.app.domain.util.catchAndLog
import com.thrym.app.domain.util.resolveMetaPlanRotation
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


data class DashboardMetaPlanOption(
    val metaPlanId: Long,
    val metaPlanName: String,
    val nextPlan: TrainingPlan?,
    val rotationPlans: List<DashboardMetaSubPlanStatus>,
    val canSkip: Boolean
)

data class DashboardMetaSubPlanStatus(
    val plan: TrainingPlan,
    val lastDoneDaysAgo: Long?
)

data class DashboardPlanStatus(
    val plan: TrainingPlan,
    val lastDoneDaysAgo: Long?
)

/** Plan the dashboard suggests for the next workout, with the meta plan it comes from. */
data class DashboardRecommendedPlan(
    val plan: TrainingPlan,
    val metaPlan: DashboardMetaPlanOption?,
    val lastDoneDaysAgo: Long?
)

/**
 * The next sub plan of the first meta plan wins. Without a meta plan the suggestion rotates
 * through the normal plans: a plan never trained first, otherwise the one trained longest
 * ago. Ties keep the list order.
 */
fun recommendedPlan(
    metaPlanOptions: List<DashboardMetaPlanOption>,
    trainingPlans: List<DashboardPlanStatus>
): DashboardRecommendedPlan? {
    metaPlanOptions.firstOrNull { it.nextPlan != null }?.let { meta ->
        val next = meta.nextPlan!!
        // Last training of the plan in any context; the rotation's own history only as fallback,
        // so a plan trained outside the rotation does not read "noch nie trainiert".
        val lastDone = trainingPlans.firstOrNull { it.plan.id == next.id }?.lastDoneDaysAgo
            ?: meta.rotationPlans.firstOrNull { it.plan.id == next.id }?.lastDoneDaysAgo
        return DashboardRecommendedPlan(plan = next, metaPlan = meta, lastDoneDaysAgo = lastDone)
    }
    val candidate = trainingPlans.firstOrNull { it.lastDoneDaysAgo == null }
        ?: trainingPlans.maxByOrNull { it.lastDoneDaysAgo ?: Long.MAX_VALUE }
    return candidate?.let {
        DashboardRecommendedPlan(plan = it.plan, metaPlan = null, lastDoneDaysAgo = it.lastDoneDaysAgo)
    }
}

/**
 * Wochenbezogene Muskelvolumen-Auswertung für das Dashboard.
 *
 * [volumes] enthält nach erfolgreicher Abfrage immer alle zehn Muskelgruppen,
 * einschließlich Gruppen ohne Sätze. Während der Abfrage oder bei einem Fehler
 * bleibt die Liste leer, damit ein Lade-/Fehlerzustand nicht als Nullvolumen
 * missverstanden wird.
 */
data class DashboardWeeklyMuscleVolumeState(
    val weekStart: LocalDate? = null,
    val currentWeekStart: LocalDate? = null,
    val volumes: List<MuscleVolume> = emptyList(),
    val completedWorkoutCount: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val isCurrentWeek: Boolean
        get() = weekStart != null && weekStart == currentWeekStart
}

/**
 * Mehrwöchiger Trainingstrend aus dem geteilten Readiness-Kern.
 *
 * [assessment] bleibt `null`, solange die Berechnung noch läuft oder
 * fehlgeschlagen ist. Es gibt bewusst keinen Platzhalter-Index: eine fehlende
 * Evidenz darf nie als Zahl erscheinen.
 */
data class DashboardTrendState(
    val assessment: ReadinessAssessment? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

/** One day of the current week for the week strip; [trained] means a completed workout started that day. */
data class DashboardWeekDay(
    val date: LocalDate,
    val trained: Boolean,
    val isToday: Boolean
)

data class DashboardUiState(
    val activeSession: WorkoutSession? = null,
    val trainingPlans: List<DashboardPlanStatus> = emptyList(),
    val metaPlanOptions: List<DashboardMetaPlanOption> = emptyList(),
    val showPlanSelectionSheet: Boolean = false,
    val workoutsThisWeek: Int = 0,
    val workoutsThisMonth: Int = 0,
    /** Seven days from the configured week start; empty until loaded. */
    val weekDays: List<DashboardWeekDay> = emptyList(),
    val volumeThisWeekKg: Double = 0.0,
    /** Weekly goal and streak; null while loading or when the goal is switched off. */
    val weeklyGoal: WeeklyGoalStatus? = null,
    /** Days since the last workout when they make a pause (21 or more), else null. */
    val pauseDays: Int? = null,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    /**
     * Exercises by id for plan previews; plan rows do not always carry the name. The state keeps
     * language-neutral data (exercises, dates) and the UI resolves names and labels, so a
     * runtime language switch shows at once instead of after the next data reload.
     */
    val planExercises: Map<Long, Exercise> = emptyMap(),
    /** Recent records with their exercise; `null` when the exercise no longer exists. */
    val recentRecords: List<Pair<PersonalRecord, Exercise?>> = emptyList(),
    val lastWorkout: WorkoutSession? = null,
    val lastWorkoutExerciseCount: Int = 0,
    /** Total volume in kg per week, keyed by the first day of the week. */
    val weeklyVolume: List<Pair<LocalDate, Double>> = emptyList(),
    val weeklyMuscleVolume: DashboardWeeklyMuscleVolumeState = DashboardWeeklyMuscleVolumeState(),
    val pendingProgressionCount: Int = 0,
    val deload: DeloadAssessment? = null,
    val deloadExercise: Exercise? = null,
    val deloadMode: DeloadMode? = null,
    /** Verhindert doppelte Deload-Aktionen, solange der Persistenzschritt läuft. */
    val isDeloadModeUpdating: Boolean = false,
    val trainingTrend: DashboardTrendState = DashboardTrendState(),
    /** Optionaler Tagesform-Entwurf; ohne Antwort bleibt er leer statt vorbelegt. */
    val checkIn: DashboardCheckInState = DashboardCheckInState(),
    val checkInNotice: DashboardCheckInNotice? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val skippingMetaPlanId: Long? = null
)

class DashboardViewModel(
    private val workoutRepository: WorkoutRepository,
    private val statisticsRepository: StatisticsRepository,
    private val exerciseRepository: ExerciseRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val trainingPlanRepository: TrainingPlanRepository,
    private val metaTrainingPlanRepository: MetaTrainingPlanRepository,
    private val progressionRepository: ProgressionRepository,
    private val deloadRepository: DeloadRepository,
    private val readinessProjectionSource: ReadinessProjectionSource,
    private val readinessRepository: ReadinessRepository,
    private val strings: UiStrings
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState
    private var deloadModeUpdateInFlight = false
    private var selectedMuscleVolumeWeekStart: LocalDate? = null
    private var selectedMuscleVolumeWeekAnchor: DayOfWeek? = null
    private var dashboardLoadGeneration = 0L
    private var weeklyVolumeLoadGeneration = 0L
    private var checkInSaveInFlight = false
    /**
     * Kalendertag-Quelle für den Tageswechsel.
     *
     * Produktion liest die Systemuhr; Tests setzen einen festen Tag, um einen
     * Tageswechsel deterministisch nachzustellen.
     */
    @VisibleForTesting
    var todayProvider: () -> LocalDate = { LocalDate.now() }
    /** Plan id the current trend was projected with, so a late plan change re-projects. */
    private var projectedPlanId: Long? = null

    init {
        loadDashboard()
        observeActiveSession()
        observeDashboardRefreshSignals()
        observePendingProgressionCount()
        observeTrainingTrend()
        observeTodayCheckIn()
        recoverProgressions()
    }

    private fun observePendingProgressionCount() {
        viewModelScope.launch {
            progressionRepository.observePendingCount()
                .catchAndLog("DashboardVM_ProgressionCount")
                .collect { count ->
                    _uiState.update { it.copy(pendingProgressionCount = count) }
                }
        }
    }

    private fun recoverProgressions() {
        viewModelScope.launch {
            runCatching {
                progressionRepository.reconcileOutstandingSuggestions()
                progressionRepository.generateMissingOutcomes()
            }.onFailure { error ->
                AppLogger.w(
                    "DashboardVM",
                    "Progression recovery failed: ${error.message}",
                    error
                )
            }
        }
    }

    /**
     * Berechnet den Trainingstrend ausschließlich aus dem geteilten Kern.
     *
     * Der Plattform-Adapter liefert die Trainingsdaten als Backup-Payload; die
     * Kalender- und Fenster-Semantik bleibt in [SharedReadinessProjection]. Die
     * UI erhält nur das Ergebnis und formuliert daraus deutsche Sätze.
     *
     * Ein Fehler im Datenstrom wird sichtbar gemacht und der alte Wert
     * verworfen: ein veralteter Trend darf nicht neben einer Fehlermeldung
     * weiter angezeigt werden.
     */
    private fun observeTrainingTrend() {
        viewModelScope.launch {
            readinessProjectionSource.observePayload()
                .catch { error -> onTrendFailure(error) }
                .collect { payload -> recomputeTrainingTrend(payload) }
        }
    }

    fun reloadTrainingTrend() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(trainingTrend = it.trainingTrend.copy(isLoading = true, error = null))
            }
            fetchTrendPayload()
        }
    }

    private suspend fun fetchTrendPayload() {
        runCatching { readinessProjectionSource.currentPayload() }
            .onSuccess { payload -> recomputeTrainingTrend(payload) }
            .onFailure(::onTrendFailure)
    }

    private fun recomputeTrainingTrend(payload: BackupPayloadV1) {
        val selectedPlanId = selectedPlanIdForToday()
        runCatching {
            SharedReadinessProjection.assess(
                payload = payload,
                nowEpochMillis = System.currentTimeMillis(),
                timeZoneId = java.util.TimeZone.getDefault().id,
                muscleWindowDays = SharedReadinessProjection.MUSCLE_WINDOW_DAYS,
                selectedPlanId = selectedPlanId
            )
        }
            .onSuccess { assessment ->
                projectedPlanId = selectedPlanId
                _uiState.update {
                    it.copy(
                        trainingTrend = DashboardTrendState(
                            assessment = assessment,
                            isLoading = false,
                            error = null
                        )
                    )
                }
            }
            .onFailure(::onTrendFailure)
    }

    /**
     * Verwirft den alten Trend und macht den Fehler sichtbar.
     *
     * Die veraltete Bewertung wird bewusst auf `null` gesetzt, damit die UI
     * keine Zahlen zeigt, die zu den aktuellen Daten nicht mehr passen.
     */
    private fun onTrendFailure(error: Throwable) {
        AppLogger.w("DashboardVM", "Trainingstrend fehlgeschlagen: ${error.message}", error)
        _uiState.update {
            it.copy(
                trainingTrend = DashboardTrendState(
                    assessment = null,
                    isLoading = false,
                    error = strings.get(UxR.string.dashboard_readiness_failed)
                )
            )
        }
    }

    /** Vor dem Workout ist der als Nächstes geplante Plan der Tageskontext. */
    private fun selectedPlanIdForToday(): Long? {
        val state = _uiState.value
        return recommendedPlan(state.metaPlanOptions, state.trainingPlans)?.plan?.id
    }

    /**
     * Projiziert neu, sobald sich der Tagesplan geändert hat.
     *
     * Die Planoptionen kommen asynchron; ohne diesen Schritt bliebe der erste
     * Snapshot für immer ohne `selectedPlanId` und die geplanten Muskelgruppen
     * fehlten.
     */
    private fun reprojectTrendIfPlanChanged() {
        if (selectedPlanIdForToday() == projectedPlanId) return
        viewModelScope.launch { fetchTrendPayload() }
    }

    private fun observeTodayCheckIn() {
        viewModelScope.launch {
            readinessRepository.observeCheckIns()
                .catchAndLog("DashboardVM_CheckIns")
                .collect { checkIns -> applyStoredCheckIns(checkIns) }
        }
    }

    /**
     * Übernimmt den gespeicherten Stand.
     *
     * Ein laufender Entwurf bleibt unangetastet; nur der gespeicherte Teil und
     * das heutige Datum werden nachgeführt.
     */
    private fun applyStoredCheckIns(checkIns: Map<kotlinx.datetime.LocalDate, ReadinessCheckIn>) {
        val today = todayProvider()
        _uiState.update { state ->
            val stored = checkIns[toKotlinxDate(today)]?.toStoredCheckIn(today)
            val checkIn = state.checkIn
            state.copy(
                checkIn = if (checkIn.isEditing) {
                    checkIn.copy(today = today, stored = stored)
                } else {
                    DashboardCheckInState(today = today, stored = stored)
                }
            )
        }
    }

    /**
     * Führt den Tagesbezug nach, wenn der Kalendertag gewechselt hat.
     *
     * Wird beim Sichtbarwerden/Resume und während das Dashboard offen ist
     * periodisch aufgerufen. Ein offener Entwurf gehört zu seinem eigenen Tag
     * und wird beim Wechsel verworfen, statt gestrige Eingaben als heutige
     * Tagesform zu zeigen. Der Trainingstrend wird neu projiziert, weil ein
     * neuer Tag Fenster und heutige Einheit verschiebt.
     */
    fun refreshCheckInDay() {
        val today = todayProvider()
        val previousDay = _uiState.value.checkIn.today
        if (previousDay == today) return

        _uiState.update { state ->
            state.copy(
                checkIn = DashboardCheckInState(today = today),
                checkInNotice = null
            )
        }
        viewModelScope.launch {
            reloadStoredCheckIn(today)
            // Der erste Aufbau bekommt den Trend bereits aus dem Payload-Stream;
            // erst ein echter Tageswechsel braucht eine neue Projektion.
            if (previousDay != null) fetchTrendPayload()
        }
    }

    private suspend fun reloadStoredCheckIn(today: LocalDate) {
        val checkIns = runCatching { readinessRepository.getReadinessData() }
            .map { data -> data.checkIns.associateBy { it.localDate } }
            .getOrNull()
            ?: return
        _uiState.update { state ->
            val stored = checkIns[toKotlinxDate(today)]?.toStoredCheckIn(today)
            val checkIn = state.checkIn
            state.copy(
                checkIn = if (checkIn.isEditing) {
                    checkIn.copy(today = today, stored = stored)
                } else {
                    DashboardCheckInState(today = today, stored = stored)
                }
            )
        }
    }

    /**
     * Startet einen Entwurf für heute.
     *
     * Ist der Tag gewechselt, wird zuerst der Tagesbezug nachgeführt. Ein
     * gespeicherter Stand wird nur übernommen, wenn er wirklich von heute
     * stammt; Vortagesantworten werden nie als heute vorbelegt.
     */
    fun startCheckInEdit() {
        val today = todayProvider()
        if (_uiState.value.checkIn.today != today) {
            refreshCheckInDay()
        }
        _uiState.update { state ->
            val storedToday = state.checkIn.stored?.takeIf { it.date == today }
            state.copy(
                checkIn = state.checkIn.copy(
                    today = today,
                    stored = storedToday,
                    draft = DashboardCheckInDraft.from(storedToday),
                    draftDate = today,
                    isEditing = true
                ),
                checkInNotice = null
            )
        }
    }

    /**
     * Bricht den Entwurf ab und zeigt wieder den gespeicherten Stand.
     *
     * Der Entwurf wird verworfen; ungespeicherte Eingaben erscheinen danach
     * nicht als gespeicherte Tagesform.
     */
    fun cancelCheckInEdit() {
        _uiState.update { state ->
            state.copy(
                checkIn = state.checkIn.copy(
                    draft = DashboardCheckInDraft.from(state.checkIn.stored),
                    draftDate = null,
                    isEditing = false
                ),
                checkInNotice = null
            )
        }
    }

    fun updateCheckInSleepQuality(value: Int?) =
        updateCheckInDraft { it.copy(sleepQuality = value) }

    fun updateCheckInEnergy(value: Int?) =
        updateCheckInDraft { it.copy(energy = value) }

    fun updateCheckInStress(value: Int?) =
        updateCheckInDraft { it.copy(stress = value) }

    fun updateCheckInSoreness(muscle: MuscleGroup, value: Int?) = updateCheckInDraft { draft ->
        val updated = draft.sorenessByMuscle.toMutableMap()
        if (value == null) updated.remove(muscle) else updated[muscle] = value
        draft.copy(sorenessByMuscle = updated)
    }

    private fun updateCheckInDraft(transform: (DashboardCheckInDraft) -> DashboardCheckInDraft) {
        _uiState.update { state ->
            state.copy(checkIn = state.checkIn.copy(draft = transform(state.checkIn.draft)), checkInNotice = null)
        }
    }

    /**
     * Speichert die Tagesform unter dem Datum des Entwurfs.
     *
     * Ein leerer Entwurf wird abgelehnt, damit "keine Antwort" nie als
     * Datenzeile erscheint. Ein vor Mitternacht begonnener Entwurf wird unter
     * seinem eigenen Tag gespeichert und nicht stillschweigend auf heute
     * umgeschrieben. Plan und Deload-Status bleiben unverändert.
     */
    fun saveCheckIn() {
        val checkIn = _uiState.value.checkIn
        val draft = checkIn.draft
        val draftDate = checkIn.draftDate
        if (draftDate == null || !draft.hasAnyAnswer) {
            _uiState.update { it.copy(checkInNotice = DashboardCheckInNotice.NEEDS_ANSWER) }
            return
        }
        viewModelScope.launch {
            if (checkInSaveInFlight) return@launch
            checkInSaveInFlight = true
            _uiState.update {
                it.copy(checkIn = it.checkIn.copy(isSaving = true), checkInNotice = null)
            }
            try {
                val now = System.currentTimeMillis()
                readinessRepository.upsertCheckIn(
                    ReadinessCheckIn(
                        localDate = toKotlinxDate(draftDate),
                        sleepQuality = draft.sleepQuality,
                        energy = draft.energy,
                        stress = draft.stress,
                        muscleSoreness = draft.sorenessByMuscle.toSharedSoreness(),
                        recordedAtEpochMillis = checkIn.stored
                            ?.takeIf { it.date == draftDate }
                            ?.recordedAtEpochMillis ?: now,
                        updatedAtEpochMillis = now
                    )
                )
                _uiState.update {
                    it.copy(
                        checkIn = it.checkIn.copy(isSaving = false, isEditing = false, draftDate = null),
                        checkInNotice = DashboardCheckInNotice.SAVED
                    )
                }
            } catch (error: Exception) {
                AppLogger.w("DashboardVM", "Tagesform speichern fehlgeschlagen: ${error.message}", error)
                _uiState.update {
                    it.copy(checkIn = it.checkIn.copy(isSaving = false), error = strings.get(UxR.string.dashboard_check_in_failed))
                }
            } finally {
                checkInSaveInFlight = false
            }
        }
    }

    fun deleteCheckIn() {
        val date = _uiState.value.checkIn.stored?.date ?: todayProvider()
        viewModelScope.launch {
            try {
                readinessRepository.deleteCheckIn(toKotlinxDate(date))
                _uiState.update {
                    it.copy(
                        checkIn = DashboardCheckInState(today = date),
                        checkInNotice = DashboardCheckInNotice.DELETED
                    )
                }
            } catch (error: Exception) {
                AppLogger.w("DashboardVM", "Tagesform löschen fehlgeschlagen: ${error.message}", error)
                _uiState.update { it.copy(error = strings.get(UxR.string.dashboard_check_in_failed)) }
            }
        }
    }

    fun dismissCheckInNotice() {
        _uiState.update { it.copy(checkInNotice = null) }
    }

    private fun observeActiveSession() {
        viewModelScope.launch {
            workoutRepository.observeActiveSession()
                .catchAndLog("DashboardVM")
                .collect { session ->
                    val previousSession = _uiState.value.activeSession
                    _uiState.update { it.copy(activeSession = session) }
                    if (previousSession != null && session == null) {
                        loadDashboard()
                    }
                }
        }

        viewModelScope.launch {
            combine(
                trainingPlanRepository.getAllPlans(),
                workoutRepository.observeLastSessionPerPlan()
            ) { plans, lastPlanSessions ->
                val exerciseIds = plans.flatMap { plan -> plan.exercises.map { it.exerciseId } }.distinct()
                val exercises = runCatching {
                    exerciseRepository.getExercisesByIds(exerciseIds).associateBy { it.id }
                }.getOrDefault(emptyMap())
                buildPlanOptions(
                    plans = plans,
                    lastSessionsPerPlan = lastPlanSessions
                ) to exercises
            }
                .catchAndLog("DashboardVM_Plans")
                .collect { (plans, exercises) ->
                    _uiState.update { it.copy(trainingPlans = plans, planExercises = exercises) }
                    reprojectTrendIfPlanChanged()
                }
        }

        viewModelScope.launch {
            combine(
                trainingPlanRepository.getAllPlans(),
                workoutRepository.observeLastSessionPerMetaPlanSubPlan(),
                metaTrainingPlanRepository.observeLastRotationEventPerMetaPlanSubPlan(),
                metaTrainingPlanRepository.getAllMetaPlans()
            ) { plans, lastSessions, rotationEvents, metaPlans ->
                buildMetaPlanOptions(
                    plans = plans,
                    lastSessionsPerSubPlan = lastSessions,
                    rotationEvents = rotationEvents,
                    metaPlans = metaPlans
                )
            }
                .catchAndLog("DashboardVM_MetaPlans")
                .collect { options ->
                    _uiState.update { it.copy(metaPlanOptions = options) }
                    reprojectTrendIfPlanChanged()
                }
        }
    }

    fun loadDashboard() {
        loadDashboard(showLoadingIndicator = true)
    }

    private fun loadDashboard(showLoadingIndicator: Boolean) {
        refreshCheckInDay()
        val requestId = ++dashboardLoadGeneration
        val weeklyGenerationAtStart = weeklyVolumeLoadGeneration
        viewModelScope.launch {
            if (showLoadingIndicator) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            } else {
                _uiState.update { it.copy(error = null) }
            }

            var weeklyRequestId: Long? = null
            try {
                val preferences = appPreferencesRepository.preferences.first()
                val nowDateTime = LocalDateTime.now()
                val now = nowDateTime.toLocalDate()

                val weekAnchor = when (preferences.weekStart) {
                    WeekStart.MONDAY -> DayOfWeek.MONDAY
                    WeekStart.SUNDAY -> DayOfWeek.SUNDAY
                }

                val currentWeekStart = MuscleVolumeCalculator.weekStartFor(now, weekAnchor)
                val selectedWeekStart = resolveSelectedMuscleVolumeWeek(
                    currentWeekStart = currentWeekStart,
                    weekAnchor = weekAnchor
                )
                if (requestId != dashboardLoadGeneration) return@launch
                // A navigation request may have started while this dashboard
                // refresh was waiting for preferences. In that case navigation
                // owns the weekly state and this refresh must leave it alone.
                if (weeklyVolumeLoadGeneration == weeklyGenerationAtStart) {
                    weeklyRequestId = ++weeklyVolumeLoadGeneration
                    selectedMuscleVolumeWeekStart = selectedWeekStart
                    selectedMuscleVolumeWeekAnchor = weekAnchor
                    _uiState.update {
                        it.copy(
                            weeklyMuscleVolume = it.weeklyMuscleVolume.copy(
                                weekStart = selectedWeekStart,
                                currentWeekStart = currentWeekStart,
                                volumes = emptyList(),
                                completedWorkoutCount = 0,
                                isLoading = true,
                                error = null
                            )
                        )
                    }
                }

                val startOfWeek = currentWeekStart
                val startOfWeekMillis = EpochConverter.toLong(startOfWeek.atStartOfDay())
                val nowEpochMillis = EpochConverter.toLong(nowDateTime)
                val workoutsThisWeek = workoutRepository.getCompletedSessionCountBetween(
                    sinceEpochMillis = startOfWeekMillis,
                    untilEpochMillis = nowEpochMillis
                )

                // Same counting as workoutsThisWeek, split per day for the week strip.
                val weekDays = (0L until 7L).map { offset ->
                    val day = currentWeekStart.plusDays(offset)
                    val trained = !day.isAfter(now) && workoutRepository.getCompletedSessionCountBetween(
                        sinceEpochMillis = EpochConverter.toLong(day.atStartOfDay()),
                        // The query's upper bound is inclusive, so stop one millisecond before midnight.
                        untilEpochMillis = minOf(
                            EpochConverter.toLong(day.plusDays(1).atStartOfDay()) - 1,
                            nowEpochMillis
                        )
                    ) > 0
                    DashboardWeekDay(date = day, trained = trained, isToday = day == now)
                }

                val startOfMonth = now.withDayOfMonth(1)
                val startOfMonthMillis = EpochConverter.toLong(startOfMonth.atStartOfDay())
                val workoutsThisMonth = workoutRepository.getCompletedSessionCountBetween(
                    sinceEpochMillis = startOfMonthMillis,
                    untilEpochMillis = nowEpochMillis
                )

                val records = statisticsRepository.getRecentRecordsList(5)
                val recordExerciseMap = exerciseRepository.getExercisesByIds(records.map { it.exerciseId })
                    .associateBy { it.id }
                val recordsWithExercises = records.map { record ->
                    Pair(record, recordExerciseMap[record.exerciseId])
                }

                val lastWorkout = workoutRepository.getLastCompletedSessionBefore(nowEpochMillis)
                val weeklyGoal = if (preferences.weeklyGoalEnabled) {
                    WeeklyGoalEngine.evaluate(
                        todayEpochDay = now.toEpochDay(),
                        weekStart = preferences.weekStart.toSharedWeekStart(),
                        sessions = workoutRepository.getCompletedSessionStarts().map { start ->
                            WeeklyGoalSession(start.startTime.toLocalDate().toEpochDay(), start.isDeload)
                        },
                        history = preferences.weeklyGoalHistory
                    )
                } else {
                    null
                }
                val pauseDays = TrainingMilestones.pauseDays(
                    lastEpochDay = lastWorkout?.startTime?.toLocalDate()?.toEpochDay(),
                    todayEpochDay = now.toEpochDay()
                )
                val lastWorkoutExerciseCount = if (lastWorkout != null) {
                    workoutRepository.getExerciseIdsForSession(lastWorkout.id).size
                } else 0

                // Build a fixed, chronological eight-week window. The bins are
                // derived from the configured week anchor instead of from the
                // sets that happen to exist, so an empty week remains visible
                // as a zero and week/year boundaries cannot collide on a KW
                // number. Sets outside the window (including future timestamps
                // in the current week) are ignored before aggregation.
                val windowStart = currentWeekStart.minusWeeks(7)
                val windowEndExclusive = currentWeekStart.plusWeeks(1)
                val trendSets = statisticsRepository.getWorkSetsCompletedBetween(
                    EpochConverter.toLong(windowStart.atStartOfDay()),
                    nowEpochMillis
                )
                val volumeByWeek = (0L until 8L).map { weekOffset ->
                    val weekStart = windowStart.plusWeeks(weekOffset)
                    val weekEndExclusive = weekStart.plusWeeks(1)
                    val volume = trendSets.asSequence()
                        .filter { set ->
                            val completedAt = set.completedAt
                            !completedAt.isBefore(windowStart.atStartOfDay()) &&
                                completedAt.isBefore(windowEndExclusive.atStartOfDay()) &&
                                !completedAt.isBefore(weekStart.atStartOfDay()) &&
                                completedAt.isBefore(weekEndExclusive.atStartOfDay()) &&
                                !completedAt.isAfter(nowDateTime)
                        }
                        .sumOf { it.weightKg * it.reps }
                    weekStart to volume
                }

                val deload = runCatching { deloadRepository.assess() }
                    .onFailure { error ->
                        AppLogger.w("DashboardVM", "Deload-Analyse fehlgeschlagen: ${error.message}", error)
                    }
                    .getOrNull()
                val deloadExercise = deload?.strongestExerciseId
                    ?.let { id ->
                        runCatching { exerciseRepository.getExerciseById(id) }.getOrNull()
                    }

                val weeklyMuscleVolume = weeklyRequestId?.let {
                    runCatching {
                        loadWeeklyMuscleVolumeData(
                            weekStart = selectedWeekStart,
                            currentWeekStart = currentWeekStart
                        )
                    }.getOrElse {
                        DashboardWeeklyMuscleVolumeState(
                            weekStart = selectedWeekStart,
                            currentWeekStart = currentWeekStart,
                            error = strings.get(UxR.string.dashboard_weekly_volume_failed),
                            isLoading = false
                        )
                    }
                }

                if (requestId != dashboardLoadGeneration) return@launch
                _uiState.update {
                    val weeklyState = if (
                        weeklyRequestId != null &&
                        weeklyRequestId == weeklyVolumeLoadGeneration
                    ) {
                        weeklyMuscleVolume!!
                    } else {
                        it.weeklyMuscleVolume
                    }
                    it.copy(
                        workoutsThisWeek = workoutsThisWeek,
                        workoutsThisMonth = workoutsThisMonth,
                        weekDays = weekDays,
                        volumeThisWeekKg = volumeByWeek.lastOrNull()?.second ?: 0.0,
                        weeklyGoal = weeklyGoal,
                        pauseDays = pauseDays,
                        unitSystem = preferences.unitSystem,
                        recentRecords = recordsWithExercises,
                        lastWorkout = lastWorkout,
                        lastWorkoutExerciseCount = lastWorkoutExerciseCount,
                        weeklyVolume = volumeByWeek,
                        weeklyMuscleVolume = weeklyState,
                        deload = deload,
                        deloadExercise = deloadExercise,
                        deloadMode = preferences.deloadMode,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                if (requestId != dashboardLoadGeneration) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = strings.get(UxR.string.dashboard_load_failed),
                        weeklyMuscleVolume = if (
                            (weeklyRequestId != null &&
                                weeklyRequestId == weeklyVolumeLoadGeneration) ||
                                (weeklyRequestId == null &&
                                    weeklyVolumeLoadGeneration == weeklyGenerationAtStart)
                        ) {
                            it.weeklyMuscleVolume.copy(
                                volumes = emptyList(),
                                completedWorkoutCount = 0,
                                isLoading = false,
                                error = strings.get(UxR.string.dashboard_weekly_volume_failed)
                            )
                        } else {
                            it.weeklyMuscleVolume
                        }
                    )
                }
            }
        }
    }

    private fun resolveSelectedMuscleVolumeWeek(
        currentWeekStart: LocalDate,
        weekAnchor: DayOfWeek
    ): LocalDate {
        val selected = selectedMuscleVolumeWeekStart
        return if (
            selected == null ||
            selectedMuscleVolumeWeekAnchor != weekAnchor ||
            selected.isAfter(currentWeekStart)
        ) {
            currentWeekStart
        } else {
            selected
        }
    }

    private suspend fun loadWeeklyMuscleVolumeData(
        weekStart: LocalDate,
        currentWeekStart: LocalDate
    ): DashboardWeeklyMuscleVolumeState {
        val weekEndExclusive = weekStart.plusDays(7)
        val nowEpochMillis = EpochConverter.toLong(LocalDateTime.now())
        val weekSets = statisticsRepository.getWorkSetsCompletedBetween(
            EpochConverter.toLong(weekStart.atStartOfDay()),
            nowEpochMillis
        ).filter { set ->
            val setDate = set.completedAt.toLocalDate()
            MuscleVolumeCalculator.isCountedSet(set.setType) &&
                !setDate.isBefore(weekStart) &&
                setDate.isBefore(weekEndExclusive)
        }
        val exerciseIds = weekSets.map { it.exerciseId }.distinct()
        val exercises = exerciseRepository.getExercisesByIds(exerciseIds)
        if (exercises.map { it.id }.toSet() != exerciseIds.toSet()) {
            // Do not silently turn an incomplete exercise mapping into ten
            // apparently valid zero rows.
            throw IllegalStateException("Incomplete exercise mapping for weekly volume")
        }
        val aggregated = MuscleVolumeCalculator
            .aggregateByMuscleGroup(weekSets, exercises, weekStart)
            .associateBy { it.muscleGroup }

        // The overview deliberately keeps zero-volume groups visible. A missing
        // group is a valid observation for a week, not an unhealthy diagnosis.
        // Keep the row order in sync with the shared analytics projection. The
        // threshold map is keyed by muscle and its insertion order is an
        // implementation detail, whereas the shared contract deliberately
        // keeps the arm rows before glutes and finishes with forearms/core.
        val sharedMuscleOrder = listOf(
            MuscleGroup.BRUST,
            MuscleGroup.RUECKEN,
            MuscleGroup.BEINE,
            MuscleGroup.SCHULTERN,
            MuscleGroup.BIZEPS,
            MuscleGroup.TRIZEPS,
            MuscleGroup.GESAESS,
            MuscleGroup.CORE,
            MuscleGroup.UNTERARME,
            MuscleGroup.WADEN
        )
        val allMuscles = sharedMuscleOrder.map { group ->
            aggregated[group] ?: MuscleVolume(
                muscleGroup = group,
                weeklySets = 0.0,
                thresholds = MuscleVolumeCalculator.DEFAULT_THRESHOLDS.getValue(group)
            )
        }
        val completedWorkoutCount = weekSets
            .asSequence()
            .filter { set ->
                MuscleVolumeCalculator.isCountedSet(set.setType) &&
                    !set.completedAt.toLocalDate().isBefore(weekStart) &&
                    set.completedAt.toLocalDate().isBefore(weekEndExclusive)
            }
            .map { it.sessionId }
            .distinct()
            .count()

        return DashboardWeeklyMuscleVolumeState(
            weekStart = weekStart,
            currentWeekStart = currentWeekStart,
            volumes = allMuscles,
            completedWorkoutCount = completedWorkoutCount,
            isLoading = false
        )
    }

    fun showPreviousMuscleVolumeWeek() {
        val weekly = _uiState.value.weeklyMuscleVolume
        val selected = weekly.weekStart ?: return
        val previous = selected.minusWeeks(1)
        selectedMuscleVolumeWeekStart = previous
        loadWeeklyMuscleVolume(previous)
    }

    fun showNextMuscleVolumeWeek() {
        val weekly = _uiState.value.weeklyMuscleVolume
        val selected = weekly.weekStart ?: return
        val current = weekly.currentWeekStart ?: return
        if (!selected.isBefore(current)) return
        val next = selected.plusWeeks(1)
        if (next.isAfter(current)) return
        selectedMuscleVolumeWeekStart = next
        loadWeeklyMuscleVolume(next)
    }

    fun reloadWeeklyMuscleVolume() {
        val selected = _uiState.value.weeklyMuscleVolume.weekStart
            ?: selectedMuscleVolumeWeekStart
            ?: return
        loadWeeklyMuscleVolume(selected)
    }

    private fun loadWeeklyMuscleVolume(requestedWeekStart: LocalDate) {
        val requestId = ++weeklyVolumeLoadGeneration
        _uiState.update {
            it.copy(
                weeklyMuscleVolume = it.weeklyMuscleVolume.copy(
                    weekStart = requestedWeekStart,
                    volumes = emptyList(),
                    completedWorkoutCount = 0,
                    isLoading = true,
                    error = null
                )
            )
        }
        viewModelScope.launch {
            try {
                val preferences = appPreferencesRepository.preferences.first()
                val now = LocalDate.now()
                val weekAnchor = when (preferences.weekStart) {
                    WeekStart.MONDAY -> DayOfWeek.MONDAY
                    WeekStart.SUNDAY -> DayOfWeek.SUNDAY
                }
                val currentWeekStart = MuscleVolumeCalculator.weekStartFor(now, weekAnchor)
                val alignedRequestedWeekStart = MuscleVolumeCalculator.weekStartFor(
                    requestedWeekStart,
                    weekAnchor
                )
                val weekStart = if (alignedRequestedWeekStart.isAfter(currentWeekStart)) {
                    currentWeekStart
                } else {
                    alignedRequestedWeekStart
                }
                if (requestId != weeklyVolumeLoadGeneration) return@launch
                selectedMuscleVolumeWeekStart = weekStart
                selectedMuscleVolumeWeekAnchor = weekAnchor
                val result = runCatching {
                    loadWeeklyMuscleVolumeData(weekStart, currentWeekStart)
                }.getOrElse { error ->
                    DashboardWeeklyMuscleVolumeState(
                        weekStart = weekStart,
                        currentWeekStart = currentWeekStart,
                        error = strings.get(UxR.string.dashboard_weekly_volume_failed),
                        isLoading = false
                    )
                }
                if (requestId != weeklyVolumeLoadGeneration) return@launch
                _uiState.update { it.copy(weeklyMuscleVolume = result) }
            } catch (error: Exception) {
                if (requestId != weeklyVolumeLoadGeneration) return@launch
                _uiState.update {
                    it.copy(
                        weeklyMuscleVolume = it.weeklyMuscleVolume.copy(
                            volumes = emptyList(),
                            completedWorkoutCount = 0,
                            isLoading = false,
                            error = strings.get(UxR.string.dashboard_weekly_volume_failed)
                        )
                    )
                }
            }
        }
    }

    private fun observeDashboardRefreshSignals() {
        viewModelScope.launch {
            combine(
                workoutRepository.getAllCompletedSessions(),
                statisticsRepository.getRecentRecords(limit = 1),
                appPreferencesRepository.preferences
            ) { _, _, _ -> Unit }
                .drop(1)
                .catchAndLog("DashboardVM_Refresh")
                .collect {
                    loadDashboard(showLoadingIndicator = false)
                }
        }
    }

    fun showPlanSelectionSheet() {
        _uiState.update { it.copy(showPlanSelectionSheet = true) }
    }

    fun dismissPlanSelectionSheet() {
        _uiState.update { it.copy(showPlanSelectionSheet = false) }
    }

    fun skipCurrentMetaSubPlan(metaPlanId: Long) {
        viewModelScope.launch {
            val option = _uiState.value.metaPlanOptions.firstOrNull { it.metaPlanId == metaPlanId }
                ?: return@launch
            val expectedPlanId = option.nextPlan?.id ?: return@launch
            if (!option.canSkip) return@launch
            if (workoutRepository.getActiveSession() != null) {
                _uiState.update {
                    it.copy(error = strings.get(UxR.string.dashboard_workout_already_active))
                }
                return@launch
            }
            if (_uiState.value.skippingMetaPlanId != null) return@launch

            _uiState.update { it.copy(skippingMetaPlanId = metaPlanId) }
            try {
                val skipped = metaTrainingPlanRepository.skipCurrentSubPlan(metaPlanId, expectedPlanId)
                if (!skipped) {
                    _uiState.update {
                        it.copy(error = strings.get(UxR.string.dashboard_plan_changed))
                    }
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(error = strings.get(UxR.string.dashboard_skip_failed, error.toUserDetail(strings)))
                }
            } finally {
                _uiState.update { it.copy(skippingMetaPlanId = null) }
            }
        }
    }

    fun startNewWorkoutWithMetaPlan(
        metaPlanId: Long,
        onSessionCreated: (Long, Long?, Long?) -> Unit
    ) {
        viewModelScope.launch {
            if (_uiState.value.skippingMetaPlanId != null) return@launch
            try {
                val option = _uiState.value.metaPlanOptions.firstOrNull { it.metaPlanId == metaPlanId }
                val nextPlan = option?.nextPlan
                if (option == null || nextPlan == null) {
                    _uiState.update {
                        it.copy(error = strings.get(UxR.string.dashboard_meta_plan_invalid))
                    }
                    return@launch
                }

                val activeSession = workoutRepository.getActiveSession()
                if (activeSession != null) {
                    if (activeSession.planId == nextPlan.id && activeSession.metaPlanId == option.metaPlanId) {
                        onSessionCreated(activeSession.id, nextPlan.id, option.metaPlanId)
                    } else {
                        _uiState.update {
                            it.copy(error = strings.get(UxR.string.dashboard_workout_already_active))
                        }
                    }
                    return@launch
                }

                val sessionId = workoutRepository.startWorkout(
                    name = nextPlan.name,
                    planId = nextPlan.id,
                    metaPlanId = option.metaPlanId
                )
                onSessionCreated(sessionId, nextPlan.id, option.metaPlanId)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = strings.get(UxR.string.dashboard_meta_start_failed, e.toUserDetail(strings)))
                }
            }
        }
    }

    fun startNewWorkout(onSessionCreated: (Long, Long?) -> Unit) {
        viewModelScope.launch {
            try {
                val activeSession = workoutRepository.getActiveSession()
                if (activeSession != null) {
                    onSessionCreated(activeSession.id, activeSession.planId)
                    return@launch
                }

                // Persisted as the session name: "Workout 2/10" (en-US) / "Training 10.02." (de), as before in German.
                val autoName = strings.get(
                    UxR.string.dashboard_workout_auto_name,
                    LocalDate.now().format(DateFormatting.dayMonth(strings.locale))
                )
                val sessionId = workoutRepository.startWorkout(
                    name = autoName,
                    planId = null,
                    metaPlanId = null
                )
                onSessionCreated(sessionId, null)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = strings.get(UxR.string.dashboard_start_failed, e.toUserDetail(strings))) }
            }
        }
    }

    fun startNewWorkoutWithPlan(plan: TrainingPlan, onSessionCreated: (Long, Long?) -> Unit) {
        viewModelScope.launch {
            try {
                val activeSession = workoutRepository.getActiveSession()
                if (activeSession != null) {
                    if (activeSession.planId == plan.id) {
                        onSessionCreated(activeSession.id, plan.id)
                    } else {
                        _uiState.update {
                            it.copy(error = strings.get(UxR.string.dashboard_workout_already_active))
                        }
                    }
                    return@launch
                }

                val sessionId = workoutRepository.startWorkout(
                    name = plan.name,
                    planId = plan.id,
                    metaPlanId = null
                )
                onSessionCreated(sessionId, plan.id)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = strings.get(UxR.string.dashboard_plan_start_failed, e.toUserDetail(strings)))
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun activateDeloadMode(mode: DeloadMode) {
        updateDeloadMode(mode)
    }

    fun deactivateDeloadMode() {
        updateDeloadMode(null)
    }

    private fun updateDeloadMode(mode: DeloadMode?) {
        viewModelScope.launch {
            // The guard lives inside the launched block so rapid UI clicks are
            // serialized on the ViewModel's main dispatcher.
            if (deloadModeUpdateInFlight) return@launch
            deloadModeUpdateInFlight = true
            _uiState.update { it.copy(isDeloadModeUpdating = true, error = null) }

            try {
                appPreferencesRepository.updateDeloadMode(mode)
                // Update the visible state only after persistence succeeded. A
                // failed write must leave the previous mode intact.
                _uiState.update { it.copy(deloadMode = mode) }
            } catch (_: Exception) {
                val message = if (mode == null) {
                    strings.get(UxR.string.dashboard_deload_end_failed)
                } else {
                    strings.get(UxR.string.dashboard_deload_start_failed)
                }
                _uiState.update {
                    it.copy(error = message)
                }
            } finally {
                deloadModeUpdateInFlight = false
                _uiState.update { it.copy(isDeloadModeUpdating = false) }
            }
        }
    }

    private fun buildMetaPlanOptions(
        plans: List<TrainingPlan>,
        lastSessionsPerSubPlan: List<com.thrym.app.domain.model.LastMetaPlanSession>,
        rotationEvents: List<MetaPlanRotationEvent>,
        metaPlans: List<MetaTrainingPlan>
    ): List<DashboardMetaPlanOption> {
        if (metaPlans.isEmpty()) return emptyList()

        val plansById = plans.associateBy { it.id }
        val lastTimeIndex = lastSessionsPerSubPlan.associateBy(
            keySelector = { it.planId to it.metaPlanId },
            valueTransform = { it.lastStartTime }
        )

        return metaPlans.map { metaPlan ->
            val orderedSubPlans = metaPlan.items
                .sortedBy { it.orderIndex }
                .mapNotNull { item -> plansById[item.trainingPlanId] }

            if (orderedSubPlans.isEmpty()) {
                return@map DashboardMetaPlanOption(
                    metaPlanId = metaPlan.id,
                    metaPlanName = metaPlan.name,
                    nextPlan = null,
                    rotationPlans = emptyList(),
                    canSkip = false
                )
            }

            val eventIndexForMetaPlan = rotationEvents
                .filter { it.metaPlanId == metaPlan.id }
                .associate { it.trainingPlanId to it.lastEventAt }

            val rotationIds = resolveMetaPlanRotation(
                orderedPlanIds = orderedSubPlans.map { it.id },
                lastEventAtByPlanId = eventIndexForMetaPlan
            )

            val rotatedPlans = rotationIds.mapNotNull(plansById::get)

            DashboardMetaPlanOption(
                metaPlanId = metaPlan.id,
                metaPlanName = metaPlan.name,
                nextPlan = rotatedPlans.firstOrNull(),
                rotationPlans = rotatedPlans.map { plan ->
                    val lastMillis = lastTimeIndex[plan.id to metaPlan.id]
                    DashboardMetaSubPlanStatus(
                        plan = plan,
                        lastDoneDaysAgo = lastMillis?.let { millis ->
                            java.time.temporal.ChronoUnit.DAYS.between(
                                java.time.Instant.ofEpochMilli(millis)
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                                LocalDate.now()
                            )
                        }
                    )
                },
                canSkip = orderedSubPlans.size > 1
            )
        }
    }

    private fun buildPlanOptions(
        plans: List<TrainingPlan>,
        lastSessionsPerPlan: List<com.thrym.app.domain.model.LastPlanSession>
    ): List<DashboardPlanStatus> {
        if (plans.isEmpty()) return emptyList()

        val lastTimeByPlanId = lastSessionsPerPlan.associateBy(
            keySelector = { it.planId },
            valueTransform = { it.lastStartTime }
        )

        return plans.map { plan ->
            DashboardPlanStatus(
                plan = plan,
                lastDoneDaysAgo = lastTimeByPlanId[plan.id]?.let { millis ->
                    java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate(),
                        LocalDate.now()
                    )
                }
            )
        }
    }
}

private fun LocalDateTime.toRelativeDaysAgo(): Long {
    return ChronoUnit.DAYS.between(this.toLocalDate(), LocalDate.now())
}

/**
 * Übersetzt gespeicherten Muskelkater in die App-Sicht.
 *
 * Codes ohne App-Entsprechung werden verworfen statt geraten, damit keine
 * falsch benannte Muskelgruppe in der Oberfläche auftaucht.
 */
private fun Map<SharedMuscleGroup, Int>?.toAppSoreness(): Map<MuscleGroup, Int> =
    this.orEmpty().mapNotNull { (muscle, value) ->
        MuscleGroup.entries.firstOrNull { it.name == muscle.name }
            ?.let { appMuscle -> appMuscle to value }
    }.toMap()

/** Übersetzt den Entwurf zurück in die persistierte Schreibweise. */
private fun Map<MuscleGroup, Int>.toSharedSoreness(): Map<SharedMuscleGroup, Int> =
    mapNotNull { (muscle, value) ->
        SharedMuscleGroup.entries.firstOrNull { it.name == muscle.name }
            ?.let { sharedMuscle -> sharedMuscle to value }
    }.toMap()

/** Dieselbe Kalenderangabe in der persistierten Schreibweise. */
private fun toKotlinxDate(date: LocalDate): kotlinx.datetime.LocalDate =
    kotlinx.datetime.LocalDate.parse(date.toString())

/** Gespeicherte Zeile in die Anzeigeform; nichts wird dabei erfunden. */
private fun ReadinessCheckIn.toStoredCheckIn(date: LocalDate): DashboardStoredCheckIn =
    DashboardStoredCheckIn(
        date = date,
        sleepQuality = sleepQuality,
        energy = energy,
        stress = stress,
        sorenessByMuscle = muscleSoreness.toAppSoreness(),
        recordedAtEpochMillis = recordedAtEpochMillis
    )
