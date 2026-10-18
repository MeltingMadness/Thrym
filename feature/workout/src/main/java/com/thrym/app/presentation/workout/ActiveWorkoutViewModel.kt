package com.thrym.app.presentation.workout

import com.thrym.app.presentation.common.UiStrings
import com.thrym.app.presentation.common.UserMessageException
import com.thrym.app.presentation.common.exerciseName
import com.thrym.app.presentation.common.labelRes
import com.thrym.app.presentation.common.toUserDetail
import com.thrym.feature.workout.R as UxR
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.PersonalRecord
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.PreviousSessionScope
import com.thrym.app.domain.model.RecordType
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.ProgressionRepository
import com.thrym.app.domain.repository.StatisticsRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.app.domain.util.RpeAutoregulation
import com.thrym.app.domain.util.WorkoutNumericValidation
import com.thrym.shared.readinessdata.SetIntention
import com.thrym.shared.workout.SessionExerciseOrder
import com.thrym.shared.workout.SessionOrderMove
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Collator
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ActiveWorkoutViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val statisticsRepository: StatisticsRepository,
    private val progressionRepository: ProgressionRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val strings: UiStrings
) : ViewModel() {

    private val sessionId: Long = savedStateHandle["sessionId"] ?: -1L
    private val planId: Long = savedStateHandle["planId"] ?: 0L
    private val metaPlanId: Long = savedStateHandle["metaPlanId"] ?: 0L

    private val showExercisePicker = MutableStateFlow(false)
    private val showFinishDialog = MutableStateFlow(false)
    private val addedExercises = MutableStateFlow<List<Exercise>>(emptyList())
    private val _error = MutableStateFlow<WorkoutErrorUi?>(null)
    private val restTimers = WorkoutRestTimerStore(sessionId, workoutRepository, appPreferencesRepository)
    private val exerciseOrder = WorkoutExerciseOrderStore(sessionId, workoutRepository, appPreferencesRepository)
    private val operationState = MutableStateFlow(OperationUiState())
    private var errorSequence = 0L
    private val mutationMutex = Mutex()
    private var lastDeletedSet: Pair<WorkoutSet, SetIntention>? = null
    private val _completionRecords = MutableStateFlow<List<SessionRecordUi>>(emptyList())

    /** Records set during this session, loaded once the session is finished. */
    val completionRecords: StateFlow<List<SessionRecordUi>> = _completionRecords.asStateFlow()

    private val sessionPhase = workoutRepository.observeSessionById(sessionId)
        .map<WorkoutSession?, ActiveWorkoutSessionPhase> { session ->
            if (session != null) {
                ActiveWorkoutSessionPhase.Active(session)
            } else {
                ActiveWorkoutSessionPhase.Missing
            }
        }
        .onStart { emit(ActiveWorkoutSessionPhase.Loading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActiveWorkoutSessionPhase.Loading)

    private val sessionSets = workoutRepository.getSetsForSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val planTargets = progressionRepository.observeTargetsForSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val shareWeightHistoryAcrossContexts =
        appPreferencesRepository.preferences
            .map { it.shareWeightHistoryAcrossContexts }
            .distinctUntilChanged()

    private val deloadMode =
        appPreferencesRepository.preferences
            .map { it.deloadMode }
            .distinctUntilChanged()

    private val exercisesWithSets = combine(
        sessionSets,
        addedExercises,
        planTargets,
        shareWeightHistoryAcrossContexts,
        deloadMode
    ) { sets, added, targets, shareAcrossContexts, activeDeloadMode ->
        val orderedTargets = targets.sortedWith(
            compareBy(WorkoutPlanTarget::orderIndex, WorkoutPlanTarget::id)
        )
        val adHocExercises = added.distinctBy { it.id }
        val exerciseIds = (
            orderedTargets.map { it.exerciseId } + adHocExercises.map { it.id }
        ).distinct()
        val exercisesById = exerciseRepository.getExercisesByIds(exerciseIds).associateBy { it.id }
        val previousSessionsByExercise = try {
            workoutRepository.getPreviousSessionDataForExercises(
                currentSessionId = sessionId,
                exerciseIds = exerciseIds,
                scope = previousSessionScope(
                    planId = planId,
                    metaPlanId = metaPlanId,
                    shareAcrossContexts = shareAcrossContexts
                )
            )
        } catch (e: Exception) {
            AppLogger.w("ActiveWorkoutVM", "Vorherige Sessiondaten konnten nicht geladen werden: ${e.message}", e)
            emptyMap()
        }

        val plannedRows = orderedTargets.mapNotNull { target ->
            val exercise = exercisesById[target.exerciseId] ?: return@mapNotNull null
            val previousSession = previousSessionsByExercise[exercise.id]
            ExerciseWithSets(
                key = WorkoutExerciseKey.Planned(target.id),
                exercise = exercise,
                sets = sets.filter { it.planTargetSnapshotId == target.id }
                    .sortedBy { it.setNumber },
                planTarget = applyDeloadToTarget(target, activeDeloadMode),
                originalPlanTarget = target,
                previousSession = previousSession?.let {
                    PreviousExerciseSessionUi(
                        sessionId = it.sessionId,
                        sessionStart = it.sessionStart,
                        sets = it.sets,
                        lastWorkSetWeightKg = it.lastWorkSetWeightKg,
                        lastWorkSetReachedTarget = lastWorkSetReachedTarget(
                            planTarget = target,
                            previousSets = it.sets
                        )
                    )
                }
            )
        }
        val adHocRows = adHocExercises.mapNotNull { exercise ->
            val resolvedExercise = exercisesById[exercise.id] ?: exercise
            val previousSession = previousSessionsByExercise[exercise.id]
            ExerciseWithSets(
                key = WorkoutExerciseKey.AdHoc(exercise.id),
                exercise = resolvedExercise,
                sets = sets.filter {
                    it.exerciseId == exercise.id && it.planTargetSnapshotId == null
                }.sortedBy { it.setNumber },
                previousSession = previousSession?.let {
                    PreviousExerciseSessionUi(
                        sessionId = it.sessionId,
                        sessionStart = it.sessionStart,
                        sets = it.sets,
                        lastWorkSetWeightKg = it.lastWorkSetWeightKg
                    )
                }
            )
        }
        plannedRows + adHocRows
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * [exercisesWithSets] in the display order of this session. Waits for the stored order, so
     * the first list the screen gets is already arranged and never jumps after a restart.
     */
    private val exerciseGroups = combine(
        exercisesWithSets,
        exerciseOrder.keys.filterNotNull()
    ) { rows, storedKeys ->
        arrangeExerciseGroups(rows, storedKeys)
    }

    /**
     * Real-time next-set load hints: built from the last work set of each
     * exercise and its RPE_RIR target (when present). Updates whenever a set
     * is logged, edited or deleted through [exercisesWithSets], so the chip
     * always reflects the newest RPE.
     */
    private val nextSetRecommendations = combine(
        exercisesWithSets,
        appPreferencesRepository.preferences
    ) { rows, prefs ->
        rows.mapNotNull { row ->
            if (
                effectiveRowIntensitySystem(prefs.intensitySystem, row.planTarget) ==
                IntensitySystem.OFF
            ) {
                return@mapNotNull null
            }
            val lastWorkSet = row.sets
                .filter { it.setType == SetType.NORMAL || it.setType == SetType.FAILURE }
                .sortedBy { it.setNumber }
                .lastOrNull()
                ?: return@mapNotNull null
            val lastRpe = lastWorkSet.rpe ?: return@mapNotNull null
            val targetRpe = (row.planTarget?.config as? ProgressionConfig.RpeRir)?.targetRpe
            val recommended = RpeAutoregulation.recommendNextSetWeightKg(
                lastWeightKg = lastWorkSet.weightKg,
                lastRpe = lastRpe,
                targetRpe = targetRpe
            ) ?: return@mapNotNull null
            row.key to NextSetRecommendationUi(
                recommendedWeightKg = recommended,
                lastWeightKg = lastWorkSet.weightKg,
                lastRpe = lastRpe,
                targetRpe = targetRpe,
                backoffPercent = configuredBackoffPercent(row.planTarget?.config)
            )
        }.toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    internal fun previousSessionScope(
        planId: Long,
        metaPlanId: Long,
        shareAcrossContexts: Boolean
    ): PreviousSessionScope = when {
        planId <= 0L -> PreviousSessionScope.Global
        shareAcrossContexts -> PreviousSessionScope.SharedPlan(planId)
        metaPlanId > 0L -> PreviousSessionScope.MetaPlan(planId, metaPlanId)
        else -> PreviousSessionScope.NormalPlan(planId)
    }

    // Buffered so emit() never waits for the UI: the screen shows each NewRecords as a
    // suspending snackbar, and logSet emits while holding mutationMutex and the
    // per-exercise in-flight lock. An unbuffered flow kept the exercise locked for
    // several seconds after a set that improved more than one record.
    private val _events = MutableSharedFlow<WorkoutEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    private val chromeState = combine(
        showExercisePicker,
        showFinishDialog,
        restTimers.timers,
        _error
    ) { pickerVisible, finishDialogVisible, restTimers, error ->
        ActiveWorkoutChromeState(
            showExercisePicker = pickerVisible,
            showFinishDialog = finishDialogVisible,
            restTimers = restTimers,
            error = error
        )
    }

    /**
     * The stored intention per durable set id plus whether that read actually succeeded.
     * The loaded/failed pair is what keeps an unread or unreadable channel from being
     * presented as "no answer", which would silently erase a stored intention on edit.
     */
    private data class IntentionSnapshot(
        val bySetId: Map<Long, SetIntention> = emptyMap(),
        val loaded: Boolean = false,
        val failed: Boolean = false
    )

    private val intentions: StateFlow<IntentionSnapshot> =
        workoutRepository.observeSetIntentions()
            .map { IntentionSnapshot(bySetId = it, loaded = true) }
            .catch { error ->
                // A corrupt readiness row degrades the picker instead of crashing the
                // workout screen; the UI shows a note rather than faking UNKNOWN.
                AppLogger.w(
                    "ActiveWorkoutVM",
                    "Satzabsichten konnten nicht geladen werden: ${error.message}",
                    error
                )
                emit(IntentionSnapshot(failed = true))
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                IntentionSnapshot()
            )

    val uiState: StateFlow<ActiveWorkoutUiState> = combine(
        sessionPhase,
        combine(exerciseGroups, intentions) { groups, snapshot -> groups to snapshot },
        nextSetRecommendations,
        chromeState,
        operationState
    ) { phase, groupsAndIntentions, recommendations, chrome, operation ->
        val (groups, intentionSnapshot) = groupsAndIntentions
        ActiveWorkoutUiState(
            sessionPhase = phase,
            exercisesWithSets = groups.flatMap { it.exercises },
            exerciseGroups = groups,
            nextSetRecommendations = recommendations,
            showExercisePicker = chrome.showExercisePicker,
            showFinishDialog = chrome.showFinishDialog,
            restTimers = chrome.restTimers,
            error = chrome.error,
            logInFlightByExercise = operation.logInFlightByExercise,
            logSuccessSubmissions = operation.logSuccessSubmissions,
            updateInFlightBySet = operation.updateInFlightBySet,
            updateSuccessCountBySet = operation.updateSuccessCountBySet,
            setIntentions = intentionSnapshot.bySetId,
            setIntentionsLoaded = intentionSnapshot.loaded,
            setIntentionsFailed = intentionSnapshot.failed,
            finishState = operation.finishState
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActiveWorkoutUiState())

    init {
        restoreAddedExercises()
        persistAddedExerciseIds()
        viewModelScope.launch { restTimers.restore() }
        viewModelScope.launch { exerciseOrder.restore(::restoreOrderedAdHocExercises) }
        observeExerciseReconciliation()
        recoverCompletedSession()
    }

    /**
     * Restores exercises added to this session (e.g. via the exercise picker) after
     * process death, using the exercise IDs persisted in [savedStateHandle].
     */
    private fun restoreAddedExercises() {
        val savedIds: List<Long> = savedStateHandle[KEY_ADDED_EXERCISE_IDS] ?: emptyList()
        if (savedIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val restored = exerciseRepository.getExercisesByIds(savedIds)
                if (restored.isEmpty()) return@launch
                val restoredById = restored.associateBy { it.id }
                val orderedRestored = savedIds.mapNotNull { restoredById[it] }
                addedExercises.update { current ->
                    val currentIds = current.map { it.id }.toSet()
                    (orderedRestored.filterNot { it.id in currentIds } + current).distinctBy { it.id }
                }
            } catch (e: Exception) {
                AppLogger.w("ActiveWorkoutVM", "Hinzugefuegte Uebungen konnten nicht wiederhergestellt werden: ${e.message}", e)
            }
        }
    }

    private fun persistAddedExerciseIds() {
        viewModelScope.launch {
            addedExercises.collect { exercises ->
                savedStateHandle[KEY_ADDED_EXERCISE_IDS] = exercises.map { it.id }
            }
        }
    }

    /**
     * Rest timers are runtime UI state, but losing one during an Android process restart is
     * surprising while the workout session itself remains active. The app preferences DataStore
     * provides durable storage, keyed by session, so the timer can be reconstructed in a fresh
     * ViewModel. The payload stays opaque to the preferences repository.
     */
    private fun observeExerciseReconciliation() {
        viewModelScope.launch {
            sessionSets.collect { sets ->
                val knownIds = addedExercises.value.map { it.id }.toSet()
                val missingExercises = sets
                    .filter { it.planTargetSnapshotId == null }
                    .map { it.exerciseId }
                    .distinct()
                    .filterNot { it in knownIds }
                    .mapNotNull { exerciseRepository.getExerciseById(it) }

                if (missingExercises.isNotEmpty()) {
                    addedExercises.update { current ->
                        val currentIds = current.map { it.id }.toSet()
                        current + missingExercises.filterNot { it.id in currentIds }
                    }
                }
            }
        }
    }

    /**
     * Brings back the ad-hoc exercises listed in the stored order. The SavedStateHandle only
     * survives process death; after a cold start an added exercise without sets would otherwise
     * be gone.
     */
    private suspend fun restoreOrderedAdHocExercises(exerciseIds: List<Long>) {
        val knownIds = addedExercises.value.map { it.id }.toSet()
        val missingIds = exerciseIds.filterNot { it in knownIds }
        if (missingIds.isEmpty()) return
        val restoredById = exerciseRepository.getExercisesByIds(missingIds).associateBy { it.id }
        val restored = missingIds.mapNotNull { restoredById[it] }
        if (restored.isEmpty()) return
        addedExercises.update { current ->
            val currentIds = current.map { it.id }.toSet()
            current + restored.filterNot { it.id in currentIds }
        }
    }

    fun addExercise(exercise: Exercise) {
        addedExercises.update { current ->
            if (current.any { it.id == exercise.id }) current else current + exercise
        }
        val key = WorkoutExerciseKey.AdHoc(exercise.id).stableListKey()
        viewModelScope.launch {
            // Stored with the order, so the exercise survives a cold start even without sets.
            exerciseOrder.update { storedKeys ->
                if (key in storedKeys) return@update null
                val arranged = SessionExerciseOrder.arrange(currentOrderRows(), storedKeys)
                    .flatMap { it.memberKeys }
                if (key in arranged) arranged else arranged + key
            }
        }
    }

    /**
     * Moves the exercise or superset block containing [key] one place up or down. Only the
     * display order of this session changes; plan targets, their `orderIndex` and superset
     * groups stay as they are. The step is computed on the order in memory under the store
     * mutex, so two quick taps move two places.
     */
    fun moveExercise(key: WorkoutExerciseKey, direction: SessionOrderMove) {
        viewModelScope.launch {
            exerciseOrder.update(precondition = ::sessionAllowsReorder) { storedKeys ->
                SessionExerciseOrder.move(
                    arranged = SessionExerciseOrder.arrange(currentOrderRows(), storedKeys),
                    key = key.stableListKey(),
                    direction = direction
                )
            }
        }
    }

    private fun currentOrderRows() = sessionOrderRows(planTargets.value, addedExercises.value)

    private suspend fun sessionAllowsReorder(): Boolean = try {
        sessionIsMutable()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.w("ActiveWorkoutVM", "Session fuer das Verschieben nicht lesbar: ${e.message}", e)
        false
    }

    fun showExercisePicker() {
        showExercisePicker.value = true
    }

    fun dismissExercisePicker() {
        showExercisePicker.value = false
    }

    fun showFinishDialog() {
        showFinishDialog.value = true
    }

    fun dismissFinishDialog() {
        showFinishDialog.value = false
    }

    fun logSet(
        exerciseId: Long,
        reps: Int,
        weightKg: Double,
        setType: SetType = SetType.NORMAL,
        intensity: String = "",
        submissionId: Long = nextSubmissionId(),
        key: WorkoutExerciseKey = WorkoutExerciseKey.AdHoc(exerciseId),
        intention: SetIntention = SetIntention.UNKNOWN
    ) {
        viewModelScope.launch {
            if ((operationState.value.logInFlightByExercise[key] ?: 0) > 0) return@launch
            operationState.update {
                it.copy(logInFlightByExercise = incrementCounter(it.logInFlightByExercise, key))
            }
            var persisted = false
            try {
                mutationMutex.withLock {
                    if (!sessionIsMutable()) return@withLock
                    requireValidSetInput(reps, weightKg)
                    require(key !is WorkoutExerciseKey.AdHoc || key.exerciseId == exerciseId) {
                        "Ad-hoc exercise key does not match exercise"
                    }
                    val persistedSets = workoutRepository.getSetsForSessionList(sessionId)
                        .filter { existing ->
                            when (key) {
                                is WorkoutExerciseKey.Planned ->
                                    existing.planTargetSnapshotId == key.snapshotId
                                is WorkoutExerciseKey.AdHoc ->
                                    existing.exerciseId == key.exerciseId &&
                                        existing.planTargetSnapshotId == null
                            }
                        }
                    val setNumber = (persistedSets.maxOfOrNull { it.setNumber } ?: 0) + 1
                    val prefs = appPreferencesRepository.preferences.first()
                    val parsedRpe = computeIntensity(
                        intensity,
                        effectiveIntensitySystem(prefs.intensitySystem, key)
                    )
                    val completedAtInstant = Instant.now()

                    val set = WorkoutSet(
                        sessionId = sessionId,
                        exerciseId = exerciseId,
                        setNumber = setNumber,
                        reps = reps,
                        weightKg = weightKg,
                        setType = setType,
                        completedAt = LocalDateTime.ofInstant(
                            completedAtInstant,
                            ZoneId.systemDefault()
                        ),
                        rpe = parsedRpe,
                        planTargetSnapshotId = (key as? WorkoutExerciseKey.Planned)?.snapshotId
                    )
                    // The repository owns record recalculation and does it inside the same
                    // transaction as the insert. The ViewModel only compares before/after and
                    // must stay inside mutationMutex until that comparison is done, so a
                    // concurrent delete/update cannot interleave and later be overwritten by
                    // a stale add-based PR write.
                    // Best-effort snapshot: a statistics-read failure must never block the
                    // repository mutation. Without a snapshot no NewRecords event is emitted,
                    // because an unknown baseline could otherwise produce false positives.
                    val recordsBefore: Map<RecordType, PersonalRecord>? = if (setType != SetType.WARMUP) {
                        snapshotRecordsBefore(exerciseId)
                    } else {
                        null
                    }
                    workoutRepository.addSet(set, intention)
                    persisted = true
                    if (setType != SetType.WARMUP && recordsBefore != null) {
                        emitImprovedRecords(exerciseId, recordsBefore)
                    }
                    // A zero duration selects the elapsed-time display. Count only NORMAL sets
                    // against the deload-adjusted target for this exact planned row.
                    val plannedTarget = (key as? WorkoutExerciseKey.Planned)?.let { planned ->
                        planTargets.value.find { it.id == planned.snapshotId }
                    }
                    val exerciseComplete = isPlannedExerciseComplete(
                        planTarget = plannedTarget,
                        deloadMode = prefs.deloadMode,
                        previousSets = persistedSets,
                        newSetType = setType
                    )
                    restTimers.replace { currentTimers ->
                        if (setType == SetType.WARMUP || exerciseComplete) {
                            currentTimers - key
                        } else {
                            currentTimers + (
                                key to RestTimerUi(
                                    startTime = completedAtInstant,
                                    durationSeconds = if (prefs.autoRestTimerEnabled) prefs.defaultRestTimeSeconds else 0
                                )
                            )
                        }
                    }
                }
                if (!persisted) return@launch

                operationState.update {
                    it.copy(logSuccessSubmissions = it.logSuccessSubmissions + submissionId)
                }
            } catch (e: Exception) {
                setError(
                    message = strings.get(UxR.string.workout_set_save_failed, e.toUserDetail(strings)),
                    retry = WorkoutRetryDescriptor.LogSet(
                        key = key,
                        exerciseId = exerciseId,
                        reps = reps,
                        weightKg = weightKg,
                        setType = setType,
                        intensity = intensity,
                        submissionId = submissionId,
                        intention = intention
                    )
                )
            } finally {
                operationState.update {
                    it.copy(
                        logInFlightByExercise = decrementCounter(
                            it.logInFlightByExercise,
                            key
                        )
                    )
                }
            }
        }
    }

    fun dismissRestTimer(key: WorkoutExerciseKey) {
        viewModelScope.launch {
            mutationMutex.withLock {
                restTimers.replace { current ->
                    current - key
                }
            }
        }
    }

    /** Lengthens or shortens a running countdown (Liquid Glass pause: "−15 s" / "+30 s"). */
    fun adjustRestTimer(key: WorkoutExerciseKey, deltaSeconds: Int) {
        viewModelScope.launch {
            mutationMutex.withLock {
                restTimers.replace { current ->
                    val timer = current[key] ?: return@replace current
                    current + (key to timer.adjustedBy(deltaSeconds, Instant.now()))
                }
            }
        }
    }

    private fun effectiveIntensitySystem(
        configured: IntensitySystem,
        key: WorkoutExerciseKey
    ): IntensitySystem {
        if (configured != IntensitySystem.OFF || key !is WorkoutExerciseKey.Planned) {
            return configured
        }
        val target = planTargets.value.find { it.id == key.snapshotId }
        return effectiveRowIntensitySystem(configured, target)
    }

    private fun effectiveRowIntensitySystem(
        configured: IntensitySystem,
        planTarget: WorkoutPlanTarget?
    ): IntensitySystem = if (
        configured == IntensitySystem.OFF && planTarget?.config is ProgressionConfig.RpeRir
    ) {
        IntensitySystem.RPE
    } else {
        configured
    }

    private fun computeIntensity(intensity: String, intensitySystem: IntensitySystem): Double? {
        if (intensity.isBlank()) return null
        val rawVal = parseDecimal(intensity)
            ?: throw UserMessageException(strings.get(UxR.string.workout_intensity_invalid, strings.get(intensitySystem.labelRes())))
        return WorkoutNumericValidation.storedRpe(rawVal, intensitySystem)
    }

    private fun requireValidSetInput(reps: Int, weightKg: Double) {
        if (!WorkoutNumericValidation.isValidReps(reps)) {
            throw UserMessageException(strings.get(UxR.string.workout_reps_invalid))
        }
        if (!WorkoutNumericValidation.isValidWeightKg(weightKg)) {
            throw UserMessageException(strings.get(UxR.string.workout_weight_invalid))
        }
    }

    /**
     * Emits one NewRecords event for the values that actually improved after a successful
     * repository mutation; the repository owns record recalculation, so this
     * never writes a record itself.
     */
    private suspend fun emitImprovedRecords(
        exerciseId: Long,
        recordsBefore: Map<RecordType, PersonalRecord>
    ) {
        try {
            val exercise = exerciseRepository.getExerciseById(exerciseId) ?: return
            val recordsAfter = statisticsRepository.getRecordsForExercisesList(listOf(exerciseId))
                .associateBy { it.type }
            val improved = RecordType.entries.filter { type ->
                val current = recordsAfter[type]?.value ?: return@filter false
                val previous = recordsBefore[type]?.value
                previous == null || current > previous
            }
            if (improved.isNotEmpty()) {
                _events.emit(WorkoutEvent.NewRecords(strings.exerciseName(exercise), improved))
            }
        } catch (e: Exception) {
            AppLogger.w("ActiveWorkoutVM", "PR-Pruefung nach Mutation fehlgeschlagen: ${e.message}", e)
        }
    }

    /**
     * Loads the current personal records as a comparison baseline before a repository
     * mutation. Best-effort by design: when statistics are temporarily unavailable the
     * mutation still proceeds and the caller simply skips NewRecords emission.
     */
    private suspend fun snapshotRecordsBefore(exerciseId: Long): Map<RecordType, PersonalRecord>? =
        try {
            statisticsRepository.getRecordsForExercisesList(listOf(exerciseId))
                .associateBy { it.type }
        } catch (e: Exception) {
            AppLogger.w("ActiveWorkoutVM", "PR-Snapshot konnte nicht geladen werden: ${e.message}", e)
            null
        }

    fun deleteSet(setId: Long) {
        viewModelScope.launch {
            mutationMutex.withLock {
                if (!sessionIsMutable()) return@withLock
                try {
                    // Keep a copy so the snackbar can undo the deletion.
                    val deleted = runCatching { workoutRepository.getSetsForSessionList(sessionId) }
                        .getOrNull()
                        ?.firstOrNull { it.id == setId }
                    val deletedIntention = intentions.value.bySetId[setId] ?: SetIntention.UNKNOWN
                    workoutRepository.deleteSet(setId)
                    if (deleted != null) {
                        lastDeletedSet = deleted to deletedIntention
                        _events.emit(WorkoutEvent.SetDeleted(deleted.setNumber))
                    }
                } catch (e: Exception) {
                    setError(
                        message = strings.get(UxR.string.workout_set_delete_failed, e.toUserDetail(strings)),
                        retry = WorkoutRetryDescriptor.DeleteSet(setId = setId)
                    )
                }
            }
        }
    }

    /**
     * Loads the personal records whose achievement falls into this finished session. Records
     * are rebuilt from the sets, so a deleted or restored set is already reflected here.
     */
    fun loadCompletionRecords() {
        viewModelScope.launch {
            try {
                val session = workoutRepository.getSessionById(sessionId) ?: return@launch
                val end = session.endTime ?: return@launch
                val exerciseIds = workoutRepository.getSetsForSessionList(sessionId)
                    .filter { it.reps > 0 }
                    .map { it.exerciseId }
                    .distinct()
                if (exerciseIds.isEmpty()) return@launch
                val names = exerciseRepository.getExercisesByIds(exerciseIds).associate { it.id to strings.exerciseName(it) }
                _completionRecords.value = statisticsRepository.getRecordsForExercisesList(exerciseIds)
                    .filter { !it.achievedAt.isBefore(session.startTime) && !it.achievedAt.isAfter(end) }
                    .groupBy { it.exerciseId }
                    .map { (exerciseId, records) ->
                        SessionRecordUi(
                            exerciseName = names[exerciseId].orEmpty(),
                            types = records.map { it.type }.distinct().sortedBy { it.ordinal }
                        )
                    }
                    .sortedWith(compareBy(Collator.getInstance(strings.locale)) { it.exerciseName })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.w("ActiveWorkoutVM", "Rekorde fuer die Zusammenfassung nicht geladen: ${e.message}", e)
            }
        }
    }

    /** Restores the set removed by the most recent [deleteSet] (snackbar "Rückgängig"). */
    fun undoDeleteSet() {
        viewModelScope.launch {
            mutationMutex.withLock {
                val (set, intention) = lastDeletedSet ?: return@withLock
                lastDeletedSet = null
                if (!sessionIsMutable()) return@withLock
                try {
                    workoutRepository.addSet(set.copy(id = 0), intention)
                } catch (e: Exception) {
                    setError(message = strings.get(UxR.string.workout_set_restore_failed, e.toUserDetail(strings)))
                }
            }
        }
    }

    fun updateSet(
        setId: Long,
        reps: Int,
        weightKg: Double,
        intensity: String = "",
        intention: SetIntention? = null
    ) {
        viewModelScope.launch {
            operationState.update {
                it.copy(updateInFlightBySet = incrementCounter(it.updateInFlightBySet, setId))
            }
            try {
                mutationMutex.withLock {
                    if (!sessionIsMutable()) return@withLock
                    // Failed validation must leave the edit row open so the user's values can be
                    // corrected in place. The retry descriptor below also keeps the submitted
                    // values available when the repository is temporarily unavailable.
                    requireValidSetInput(reps, weightKg)

                    val sets = workoutRepository.getSetsForSessionList(sessionId)
                    val set = sets.find { it.id == setId } ?: return@withLock

                    val prefs = appPreferencesRepository.preferences.first()
                    // When intensity tracking is OFF, the intensity UI is hidden and the
                    // incoming string is always blank — preserve the existing RPE instead
                    // of wiping it out. When intensity tracking is on, a blank string means
                    // the user intentionally cleared the field.
                    val intensitySystem = effectiveIntensitySystem(
                        configured = prefs.intensitySystem,
                        key = set.planTargetSnapshotId?.let { WorkoutExerciseKey.Planned(it) }
                            ?: WorkoutExerciseKey.AdHoc(set.exerciseId)
                    )
                    val newRpe = if (intensitySystem == IntensitySystem.OFF) {
                        set.rpe
                    } else {
                        computeIntensity(intensity, intensitySystem)
                    }

                    val updatedSet = set.copy(
                        reps = reps,
                        weightKg = weightKg,
                        rpe = newRpe
                    )
                    val recordsBefore = snapshotRecordsBefore(updatedSet.exerciseId)
                    workoutRepository.updateSet(updatedSet, intention)
                    if (recordsBefore != null) {
                        emitImprovedRecords(updatedSet.exerciseId, recordsBefore)
                    }
                    operationState.update {
                        it.copy(
                            updateSuccessCountBySet = incrementCounter(
                                it.updateSuccessCountBySet,
                                setId
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                setError(
                    message = strings.get(UxR.string.workout_set_update_failed, e.toUserDetail(strings)),
                    retry = WorkoutRetryDescriptor.UpdateSet(
                        setId = setId,
                        reps = reps,
                        weightKg = weightKg,
                        intensity = intensity,
                        intention = intention
                    )
                )
            } finally {
                operationState.update {
                    it.copy(updateInFlightBySet = decrementCounter(it.updateInFlightBySet, setId))
                }
            }
        }
    }

    fun finishWorkout() {
        viewModelScope.launch {
            var generateAfterFinish = false
            mutationMutex.withLock {
                if (operationState.value.finishState != WorkoutFinishState.Idle) {
                    return@withLock
                }
                operationState.update { it.copy(finishState = WorkoutFinishState.Completing) }
                var discardEmptySession = false
                try {
                    val session = workoutRepository.getSessionById(sessionId)
                    when {
                        session == null -> {
                            showFinishDialog.value = false
                            operationState.update {
                                it.copy(finishState = WorkoutFinishState.CompletedWithoutReview)
                            }
                            // No session left to rest between sets of.
                            restTimers.clear()
                        }
                        session.endTime != null -> {
                            showFinishDialog.value = false
                            operationState.update {
                                it.copy(finishState = WorkoutFinishState.Generating)
                            }
                            generateAfterFinish = true
                            // The session is over; pending rest timers must not linger.
                            restTimers.clear()
                        }
                        else -> {
                            discardEmptySession =
                                workoutRepository.getSetCountForSession(sessionId) == 0
                            if (discardEmptySession) {
                                workoutRepository.deleteSession(sessionId)
                                showFinishDialog.value = false
                                operationState.update {
                                    it.copy(
                                        finishState = WorkoutFinishState.CompletedWithoutReview
                                    )
                                }
                                restTimers.clear()
                                // A discarded session has no summary that could show the order.
                                exerciseOrder.clear()
                            } else {
                                workoutRepository.finishWorkout(sessionId)
                                showFinishDialog.value = false
                                operationState.update {
                                    it.copy(finishState = WorkoutFinishState.Generating)
                                }
                                generateAfterFinish = true
                                // The session is over; pending rest timers must not linger.
                                restTimers.clear()
                            }
                        }
                    }
                } catch (e: Exception) {
                    setError(
                        message = strings.get(UxR.string.workout_finish_failed, e.toUserDetail(strings)),
                        retry = WorkoutRetryDescriptor.FinishWorkout(
                            discardEmptySession = discardEmptySession
                        )
                    )
                    operationState.update { it.copy(finishState = WorkoutFinishState.Idle) }
                }
            }
            if (generateAfterFinish) {
                generateProgression(sessionId)
            }
        }
    }

    fun retryProgressionGeneration() {
        viewModelScope.launch {
            var retrySessionId: Long? = null
            mutationMutex.withLock {
                val failed = operationState.value.finishState as? WorkoutFinishState.GenerationFailed
                    ?: return@withLock
                retrySessionId = failed.sessionId
                operationState.update { it.copy(finishState = WorkoutFinishState.Generating) }
            }
            retrySessionId?.let { generateProgression(it) }
        }
    }

    private fun recoverCompletedSession() {
        viewModelScope.launch {
            var completedSessionId: Long? = null
            try {
                mutationMutex.withLock {
                    if (operationState.value.finishState != WorkoutFinishState.Idle) {
                        return@withLock
                    }
                    val session = workoutRepository.getSessionById(sessionId)
                    if (session?.endTime != null) {
                        completedSessionId = session.id
                        restTimers.clear()
                        operationState.update { it.copy(finishState = WorkoutFinishState.Generating) }
                    }
                }
                completedSessionId?.let { generateProgression(it) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                runCatching {
                    AppLogger.w(
                        "ActiveWorkoutVM",
                        "Recovery-Pruefung fuer beendetes Training fehlgeschlagen: ${e.message}",
                        e
                    )
                }
            }
        }
    }

    private suspend fun generateProgression(completedSessionId: Long) {
        try {
            val result = progressionRepository.generateOutcomesForSession(completedSessionId)
            operationState.update {
                it.copy(
                    finishState = if (result.reviewItemCount > 0 || result.insertedCount > 0) {
                        // Fresh outcomes exist: either decisions are pending or the coach
                        // evaluated the session and recorded informational results
                        // (KEEP_TARGET / INSUFFICIENT_DATA). Both deserve the review
                        // screen instead of a silent close without feedback.
                        WorkoutFinishState.ReviewReady(completedSessionId)
                    } else {
                        WorkoutFinishState.CompletedWithoutReview
                    }
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            operationState.update {
                it.copy(
                    finishState = WorkoutFinishState.GenerationFailed(
                        sessionId = completedSessionId,
                        message = GENERATION_FAILURE_MESSAGE
                    )
                )
            }
            runCatching {
                AppLogger.w(
                    "ActiveWorkoutVM",
                    "Progressionsvorschlaege konnten nicht erzeugt werden: ${e.message}",
                    e
                )
            }
        }
    }

    private suspend fun sessionIsMutable(): Boolean {
        val session = workoutRepository.getSessionById(sessionId)
        return session != null && session.endTime == null
    }

    fun retryLastError() {
        val retry = _error.value?.retry ?: return
        _error.value = null
        when (retry) {
            is WorkoutRetryDescriptor.LogSet -> logSet(
                key = retry.key,
                exerciseId = retry.exerciseId,
                reps = retry.reps,
                weightKg = retry.weightKg,
                setType = retry.setType,
                intensity = retry.intensity,
                submissionId = retry.submissionId,
                intention = retry.intention
            )
            is WorkoutRetryDescriptor.UpdateSet -> updateSet(
                setId = retry.setId,
                reps = retry.reps,
                weightKg = retry.weightKg,
                intensity = retry.intensity,
                intention = retry.intention
            )
            is WorkoutRetryDescriptor.DeleteSet -> deleteSet(retry.setId)
            is WorkoutRetryDescriptor.FinishWorkout -> finishWorkout()
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Surfaces picker-level failures (loading or creating exercises) through the regular
     * error snackbar. Method reference is stable across recompositions, so the picker's
     * exercise flow is not restarted on every recomposition.
     */
    fun reportPickerError(message: String) {
        setError(message = message)
    }

    private fun setError(message: String, retry: WorkoutRetryDescriptor? = null) {
        _error.value = WorkoutErrorUi(
            message = message,
            retry = retry,
            id = errorSequence++
        )
    }

    private fun <K> incrementCounter(counter: Map<K, Int>, key: K): Map<K, Int> =
        counter + (key to ((counter[key] ?: 0) + 1))

    private fun <K> decrementCounter(counter: Map<K, Int>, key: K): Map<K, Int> {
        val next = (counter[key] ?: 0) - 1
        return if (next <= 0) counter - key else counter + (key to next)
    }

    companion object {
        private const val KEY_ADDED_EXERCISE_IDS = "addedExerciseIds"
        private const val GENERATION_FAILURE_MESSAGE = "progression_generation_failed"
    }
}
