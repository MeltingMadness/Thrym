package com.thrym.app.fakes

import com.thrym.app.domain.model.CompletedSessionStart
import androidx.paging.PagingData
import androidx.paging.PagingSource
import com.thrym.app.domain.model.CompletedWorkoutSummary
import com.thrym.app.domain.model.PreviousExerciseSession
import com.thrym.app.domain.model.PreviousSessionScope
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.domain.util.WorkoutNumericValidation
import com.thrym.shared.readinessdata.SetIntention
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeWorkoutRepository : WorkoutRepository {

    private val sessions = MutableStateFlow<List<WorkoutSessionData>>(emptyList())
    private val sets = MutableStateFlow<List<WorkoutSet>>(emptyList())
    private val setIntentions = MutableStateFlow<Map<Long, SetIntention>>(emptyMap())
    private var nextSessionId = 1L
    private var nextSetId = 1L

    var getExerciseIdsForSessionCallCount = 0
    var getSetCountForSessionCallCount = 0
    var getTotalVolumeForSessionCallCount = 0
    var getSetsForSessionsListCallCount = 0
    var getAllCompletedSessionsListCallCount = 0
    var addSetCallCount = 0
    var lastPagedCompletedWorkoutSummariesSearch: String? = null
    var updateSetCallCount = 0
    var deleteSetCallCount = 0
    var finishWorkoutCallCount = 0
    var deleteSessionCallCount = 0
    var getPreviousSessionDataForExercisesCallCount = 0
    var getPagedCompletedWorkoutSummariesFilteredCallCount = 0
    var lastPagedCompletedWorkoutSummariesFilter: Triple<Long?, Long?, Long?>? = null

    var failAddSet = false
    var failUpdateSet = false
    var failDeleteSet = false
    var failFinishWorkout = false
    var failDeleteSession = false

    private fun java.time.LocalDateTime.toEpochMillis(): Long =
        atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    data class WorkoutSessionData(
        val session: WorkoutSession,
        val isActive: Boolean = false
    )

    // --- Controls for tests ---

    fun addSession(session: WorkoutSession, isActive: Boolean = false) {
        sessions.value = sessions.value + WorkoutSessionData(session, isActive)
    }

    fun addSetDirectly(set: WorkoutSet) {
        sets.value = sets.value + set
    }

    /** Test control: the stored intention for a set, `UNKNOWN` when there is no record. */
    fun intentionFor(setId: Long): SetIntention =
        setIntentions.value[setId] ?: SetIntention.UNKNOWN

    /** Test control: seeds an intention without going through a set mutation. */
    fun setIntentionDirectly(setId: Long, intention: SetIntention) {
        setIntentions.value = setIntentions.value + (setId to intention)
    }

    // --- WorkoutRepository ---

    suspend fun startWorkout(name: String = ""): Long =
        startWorkout(name = name, planId = null, metaPlanId = null)

    override suspend fun startWorkout(
        name: String,
        planId: Long?,
        metaPlanId: Long?
    ): Long {
        // Invariant: there can only be one active session, matching WorkoutRepositoryImpl -
        // if one already exists, return its id and ignore the requested name/planId/metaPlanId.
        sessions.value.find { it.isActive }?.let { return it.session.id }

        val id = nextSessionId++
        val session = WorkoutSession(
            id = id,
            startTime = java.time.LocalDateTime.now(),
            name = name,
            planId = planId,
            metaPlanId = metaPlanId
        )
        sessions.value = sessions.value + WorkoutSessionData(session, isActive = true)
        return id
    }

    override suspend fun finishWorkout(sessionId: Long) {
        finishWorkoutCallCount++
        if (failFinishWorkout) {
            throw IllegalStateException("Injected finishWorkout failure")
        }
        sessions.value = sessions.value.map {
            if (it.session.id == sessionId && it.isActive && it.session.endTime == null) {
                val now = java.time.LocalDateTime.now()
                val durationSeconds =
                    java.time.Duration.between(it.session.startTime, now).seconds
                it.copy(
                    session = it.session.copy(endTime = now, durationSeconds = durationSeconds),
                    isActive = false
                )
            } else it
        }
    }

    override suspend fun getActiveSession(): WorkoutSession? =
        sessions.value.find { it.isActive }?.session

    override fun observeActiveSession(): Flow<WorkoutSession?> =
        sessions.map { list -> list.find { it.isActive }?.session }

    override suspend fun addSet(set: WorkoutSet, intention: SetIntention): Long {
        addSetCallCount++
        if (failAddSet) {
            throw IllegalStateException("Injected addSet failure")
        }
        WorkoutNumericValidation.requireValidWorkoutSet(set)
        val id = nextSetId++
        sets.value = sets.value + set.copy(id = id)
        // Mirrors the real repository: UNKNOWN records nothing, so a freshly logged set
        // without an answer resolves to UNKNOWN instead of storing a placeholder.
        if (intention != SetIntention.UNKNOWN) {
            setIntentions.value = setIntentions.value + (id to intention)
        }
        return id
    }

    override suspend fun updateSet(set: WorkoutSet, intention: SetIntention?) {
        updateSetCallCount++
        if (failUpdateSet) {
            throw IllegalStateException("Injected updateSet failure")
        }
        WorkoutNumericValidation.requireValidWorkoutSet(set)
        sets.value = sets.value.map { existing ->
            if (existing.id == set.id) set else existing
        }
        // Tri-state, like the real repository: null preserves, UNKNOWN clears, a
        // concrete value replaces, so tests can prove an edit never erases an answer.
        when (intention) {
            null -> Unit
            SetIntention.UNKNOWN -> setIntentions.value = setIntentions.value - set.id
            else -> setIntentions.value = setIntentions.value + (set.id to intention)
        }
    }

    override fun observeSetIntentions(): Flow<Map<Long, SetIntention>> = setIntentions

    override suspend fun deleteSet(setId: Long) {
        deleteSetCallCount++
        if (failDeleteSet) {
            throw IllegalStateException("Injected deleteSet failure")
        }
        sets.value = sets.value.filter { it.id != setId }
        setIntentions.value = setIntentions.value - setId
    }

    override suspend fun updateCompletedSet(set: WorkoutSet, intention: SetIntention?) {
        val stored = sets.value.firstOrNull { it.id == set.id }
            ?: throw IllegalStateException("Workout set ${set.id} does not exist")
        check(sessions.value.any { it.session.id == stored.sessionId && !it.isActive }) {
            "Workout session ${stored.sessionId} is not completed"
        }
        WorkoutNumericValidation.requireValidWorkoutSet(set)
        sets.value = sets.value.map { if (it.id == set.id) set else it }
        when (intention) {
            null -> Unit
            SetIntention.UNKNOWN -> setIntentions.value = setIntentions.value - set.id
            else -> setIntentions.value = setIntentions.value + (set.id to intention)
        }
    }

    override suspend fun deleteCompletedSet(setId: Long) {
        val stored = sets.value.firstOrNull { it.id == setId } ?: return
        check(sessions.value.any { it.session.id == stored.sessionId && !it.isActive }) {
            "Workout session ${stored.sessionId} is not completed"
        }
        sets.value = sets.value.filter { it.id != setId }
        setIntentions.value = setIntentions.value - setId
    }

    override suspend fun updateSessionNotes(sessionId: Long, notes: String) {
        check(sessions.value.any { it.session.id == sessionId }) { "Workout session $sessionId does not exist" }
        sessions.value = sessions.value.map {
            if (it.session.id == sessionId) it.copy(session = it.session.copy(notes = notes.trim())) else it
        }
    }

    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> =
        sets.map { list -> list.filter { it.sessionId == sessionId } }

    override suspend fun getSetsForSessionList(sessionId: Long): List<WorkoutSet> =
        sets.value.filter { it.sessionId == sessionId }

    override suspend fun getSetsForSessionsList(sessionIds: List<Long>): List<WorkoutSet> {
        getSetsForSessionsListCallCount++
        val idSet = sessionIds.toSet()
        return sets.value.filter { it.sessionId in idSet }
    }

    override fun getAllCompletedSessions(): Flow<List<WorkoutSession>> =
        sessions.map { list -> list.filter { !it.isActive }.map { it.session } }

    override fun getPagedCompletedSessions(): Flow<PagingData<WorkoutSession>> {
        val completed = sessions.value.filter { !it.isActive }.map { it.session }
        return kotlinx.coroutines.flow.flowOf(PagingData.from(completed))
    }

    /**
     * Completed-session summaries computed from the real set list (work sets only for
     * volume, matching [com.thrym.app.data.repository.WorkoutRepositoryImpl]).
     * Kept separate from the PagingData wrapper so unit tests can assert the values
     * without consuming [PagingData].
     */
    fun completedWorkoutSummaries(): List<CompletedWorkoutSummary> =
        sessions.value.filter { !it.isActive }.map { sessionData ->
            val sessionSets = sets.value.filter { it.sessionId == sessionData.session.id }
            CompletedWorkoutSummary(
                session = sessionData.session,
                exerciseCount = sessionSets.map { it.exerciseId }.distinct().size,
                setCount = sessionSets.size,
                totalVolume = sessionSets
                    .filter {
                        !it.isWarmup &&
                            WorkoutNumericValidation.isValidReps(it.reps) &&
                            WorkoutNumericValidation.isValidWeightKg(it.weightKg)
                    }
                    .fold(0.0) { total, set ->
                        val volume = set.weightKg * set.reps.toDouble()
                        val next = total + volume
                        if (volume.isFinite() && next.isFinite()) next else total
                    }
            )
        }

    override fun getPagedCompletedWorkoutSummaries(): Flow<PagingData<CompletedWorkoutSummary>> =
        kotlinx.coroutines.flow.flowOf(PagingData.from(completedWorkoutSummaries()))

    override fun getPagedCompletedWorkoutSummaries(
        planId: Long?,
        fromEpochMillis: Long?,
        toEpochMillis: Long?,
        searchQuery: String?,
        matchingExerciseNames: List<String>
    ): Flow<PagingData<CompletedWorkoutSummary>> {
        getPagedCompletedWorkoutSummariesFilteredCallCount++
        lastPagedCompletedWorkoutSummariesFilter = Triple(planId, fromEpochMillis, toEpochMillis)
        lastPagedCompletedWorkoutSummariesSearch = searchQuery
        val needle = searchQuery?.trim().orEmpty()
        val filtered = completedWorkoutSummaries().filter { summary ->
            val session = summary.session
            (needle.isEmpty() || session.name.contains(needle, ignoreCase = true) ||
                session.notes.contains(needle, ignoreCase = true)) &&
            (planId == null || session.planId == planId) &&
                (fromEpochMillis == null || session.startTime.toEpochMillis() >= fromEpochMillis) &&
                (toEpochMillis == null || session.startTime.toEpochMillis() < toEpochMillis)
        }
        return kotlinx.coroutines.flow.flowOf(PagingData.from(filtered))
    }

    override suspend fun getSessionById(id: Long): WorkoutSession? =
        sessions.value.find { it.session.id == id }?.session

    override fun observeSessionById(id: Long): Flow<WorkoutSession?> =
        sessions.map { list -> list.find { it.session.id == id }?.session }

    override suspend fun deleteSession(sessionId: Long) {
        deleteSessionCallCount++
        if (failDeleteSession) {
            throw IllegalStateException("Injected deleteSession failure")
        }
        sessions.value = sessions.value.filter { it.session.id != sessionId }
        sets.value = sets.value.filter { it.sessionId != sessionId }
    }

    override suspend fun getExerciseIdsForSession(sessionId: Long): List<Long> {
        getExerciseIdsForSessionCallCount++
        return sets.value.filter { it.sessionId == sessionId }.map { it.exerciseId }.distinct()
    }

    override suspend fun getSetCountForSession(sessionId: Long): Int {
        getSetCountForSessionCallCount++
        return sets.value.count { it.sessionId == sessionId }
    }

    override suspend fun getTotalVolumeForSession(sessionId: Long): Double {
        getTotalVolumeForSessionCallCount++
        return sets.value
            .filter { it.sessionId == sessionId && !it.isWarmup }
            .fold(0.0) { total, set ->
                val volume = set.weightKg * set.reps.toDouble()
                val next = total + volume
                if (volume.isFinite() && next.isFinite()) next else total
            }
    }

    override suspend fun getCompletedSessionCountSince(sinceEpochMillis: Long): Int =
        sessions.value.count {
            !it.isActive && it.session.startTime.toEpochMillis() >= sinceEpochMillis
        }

    override suspend fun getCompletedSessionCountBetween(
        sinceEpochMillis: Long,
        untilEpochMillis: Long
    ): Int = sessions.value.count {
        !it.isActive &&
            it.session.startTime.toEpochMillis() >= sinceEpochMillis &&
            it.session.startTime.toEpochMillis() <= untilEpochMillis
    }

    override suspend fun getLastCompletedSession(): WorkoutSession? =
        sessions.value.filter { !it.isActive }.maxByOrNull { it.session.startTime }?.session

    override suspend fun getLastCompletedSessionBefore(untilEpochMillis: Long): WorkoutSession? =
        sessions.value
            .filter { !it.isActive && it.session.startTime.toEpochMillis() <= untilEpochMillis }
            .maxByOrNull { it.session.startTime }
            ?.session

    override suspend fun getAllCompletedSessionsList(): List<WorkoutSession> {
        getAllCompletedSessionsListCallCount++
        return sessions.value.filter { !it.isActive }.map { it.session }
    }

    override suspend fun getCompletedSessionStarts(): List<CompletedSessionStart> =
        sessions.value.filter { !it.isActive }
            .map { CompletedSessionStart(it.session.startTime, it.session.isDeload) }
            .sortedBy { it.startTime }

    override suspend fun getPreviousSessionDataForExercises(
        currentSessionId: Long,
        exerciseIds: List<Long>,
        scope: PreviousSessionScope
    ): Map<Long, PreviousExerciseSession> {
        getPreviousSessionDataForExercisesCallCount++
        if (exerciseIds.isEmpty()) return emptyMap()

        val completedById = sessions.value
            .filter { !it.isActive && it.session.id != currentSessionId }
            .filter { sessionData ->
                when (scope) {
                    PreviousSessionScope.Global -> true
                    is PreviousSessionScope.NormalPlan ->
                        sessionData.session.planId == scope.planId &&
                            sessionData.session.metaPlanId == null
                    is PreviousSessionScope.MetaPlan ->
                        sessionData.session.planId == scope.planId &&
                            sessionData.session.metaPlanId == scope.metaPlanId
                    is PreviousSessionScope.SharedPlan ->
                        sessionData.session.planId == scope.planId
                }
            }
            .associateBy { it.session.id }
        if (completedById.isEmpty()) return emptyMap()

        return exerciseIds.distinct().mapNotNull { exerciseId ->
            val previousSessionId = sets.value
                .filter { set ->
                    set.exerciseId == exerciseId &&
                        set.sessionId in completedById.keys
                }
                .sortedWith(
                    compareByDescending<WorkoutSet> {
                        completedById.getValue(it.sessionId).session.startTime
                    }.thenByDescending { it.sessionId }
                )
                .firstOrNull()
                ?.sessionId
                ?: return@mapNotNull null

            val session = completedById[previousSessionId]?.session ?: return@mapNotNull null
            val sessionSets = sets.value
                .filter { it.exerciseId == exerciseId && it.sessionId == previousSessionId }
                .sortedBy { it.setNumber }
            exerciseId to PreviousExerciseSession(
                sessionId = previousSessionId,
                sessionStart = session.startTime,
                sets = sessionSets,
                lastWorkSetWeightKg = sessionSets.lastOrNull { !it.isWarmup }?.weightKg
            )
        }.toMap()
    }

    override fun observeLastSessionPerPlan(): Flow<List<com.thrym.app.domain.model.LastPlanSession>> =
        sessions.map { list ->
            list.filter { !it.isActive && it.session.planId != null }
                .groupBy { it.session.planId!! }
                .map { (planId, entries) ->
                    com.thrym.app.domain.model.LastPlanSession(
                        planId = planId,
                        lastStartTime = entries.maxOf { it.session.startTime.toEpochMillis() }
                    )
                }
        }

    override fun observeLastSessionPerMetaPlanSubPlan(): Flow<List<com.thrym.app.domain.model.LastMetaPlanSession>> =
        sessions.map { list ->
            list.filter { !it.isActive && it.session.metaPlanId != null && it.session.planId != null }
                .groupBy { it.session.planId!! to it.session.metaPlanId!! }
                .map { (key, entries) ->
                    com.thrym.app.domain.model.LastMetaPlanSession(
                        planId = key.first,
                        metaPlanId = key.second,
                        lastStartTime = entries.maxOf { it.session.startTime.toEpochMillis() }
                    )
                }
        }
}
