package com.thrym.app.domain.repository

import com.thrym.app.domain.model.CompletedSessionStart
import androidx.paging.PagingData
import com.thrym.app.domain.model.CompletedWorkoutSummary
import com.thrym.app.domain.model.PreviousExerciseSession
import com.thrym.app.domain.model.PreviousSessionScope
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.shared.readinessdata.SetIntention
import kotlinx.coroutines.flow.Flow

interface WorkoutRepository {
    suspend fun startWorkout(
        name: String = "",
        planId: Long? = null,
        metaPlanId: Long? = null
    ): Long
    suspend fun finishWorkout(sessionId: Long)
    suspend fun getActiveSession(): WorkoutSession?
    fun observeActiveSession(): Flow<WorkoutSession?>
    /**
     * Inserts [set] and, in the same transaction, stores [intention] for the new row.
     *
     * [SetIntention.UNKNOWN] carries no information and stores no record, so a set
     * logged without an answer stays "unknown" instead of fake-neutral. Writing both
     * together keeps a logged set and its intention from ever diverging.
     */
    suspend fun addSet(set: WorkoutSet, intention: SetIntention = SetIntention.UNKNOWN): Long

    /**
     * Updates [set] and, in the same transaction, applies the tri-state [intention].
     *
     * `null` (the default) leaves any stored intention untouched, so a caller that only
     * edits reps/weight can never erase an answer the user made earlier. An explicit
     * [SetIntention.UNKNOWN] removes the record, and a concrete value replaces it.
     */
    suspend fun updateSet(set: WorkoutSet, intention: SetIntention? = null)

    /**
     * Streams the stored intention per durable set id. A set without a record resolves to
     * [SetIntention.UNKNOWN]; the map never invents an answer for legacy data.
     */
    fun observeSetIntentions(): Flow<Map<Long, SetIntention>>

    suspend fun deleteSet(setId: Long)

    /**
     * Corrects a set of a completed session (history edit). Records are rebuilt and pending
     * progression suggestions of that session become stale, since they were derived from
     * the old values. [intention] follows the same tri-state rule as [updateSet].
     */
    suspend fun updateCompletedSet(set: WorkoutSet, intention: SetIntention? = null)

    /** Removes a set from a completed session with the same record/suggestion rules. */
    suspend fun deleteCompletedSet(setId: Long)

    /** Replaces the session notes; blank text removes the note. */
    suspend fun updateSessionNotes(sessionId: Long, notes: String)
    fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>>
    suspend fun getSetsForSessionList(sessionId: Long): List<WorkoutSet>
    suspend fun getSetsForSessionsList(sessionIds: List<Long>): List<WorkoutSet>
    fun getAllCompletedSessions(): Flow<List<WorkoutSession>>
    fun getPagedCompletedSessions(): Flow<PagingData<WorkoutSession>>
    fun getPagedCompletedWorkoutSummaries(): Flow<PagingData<CompletedWorkoutSummary>>
    /**
     * Streams completed workout summaries after applying the filters in the database query.
     * Keeping the predicates in the PagingSource is important: filtering a collected
     * PagingData would only inspect the page that has already been loaded.
     *
     * [searchQuery] matches the session name, its notes, the plan name and the names or
     * notes of exercises trained in the session; blank means no text filter.
     * [matchingExerciseNames] are stored exercise names that count as a match as well, so a
     * built-in exercise is also found by its localized name (the database only stores the
     * German canonical name). They are only used together with a non-blank [searchQuery].
     */
    fun getPagedCompletedWorkoutSummaries(
        planId: Long?,
        fromEpochMillis: Long?,
        toEpochMillis: Long?,
        searchQuery: String? = null,
        matchingExerciseNames: List<String> = emptyList()
    ): Flow<PagingData<CompletedWorkoutSummary>>
    suspend fun getSessionById(id: Long): WorkoutSession?
    fun observeSessionById(id: Long): Flow<WorkoutSession?>
    suspend fun deleteSession(sessionId: Long)
    suspend fun getExerciseIdsForSession(sessionId: Long): List<Long>
    suspend fun getSetCountForSession(sessionId: Long): Int
    suspend fun getTotalVolumeForSession(sessionId: Long): Double
    suspend fun getCompletedSessionCountSince(sinceEpochMillis: Long): Int
    /** Counts completed sessions whose start timestamps are inside the bounded window. */
    suspend fun getCompletedSessionCountBetween(sinceEpochMillis: Long, untilEpochMillis: Long): Int =
        getCompletedSessionCountSince(sinceEpochMillis)
    suspend fun getLastCompletedSession(): WorkoutSession?
    /** Returns the latest completed session whose start is not in the future. */
    suspend fun getLastCompletedSessionBefore(untilEpochMillis: Long): WorkoutSession? =
        getLastCompletedSession()
    suspend fun getAllCompletedSessionsList(): List<WorkoutSession>
    /** Start and deload flag of every completed session, without loading whole sessions. */
    suspend fun getCompletedSessionStarts(): List<CompletedSessionStart> =
        getAllCompletedSessionsList().map { CompletedSessionStart(it.startTime, it.isDeload) }
    suspend fun getPreviousSessionDataForExercises(
        currentSessionId: Long,
        exerciseIds: List<Long>,
        scope: PreviousSessionScope = PreviousSessionScope.Global
    ): Map<Long, PreviousExerciseSession>
    fun observeLastSessionPerPlan(): Flow<List<com.thrym.app.domain.model.LastPlanSession>>
    fun observeLastSessionPerMetaPlanSubPlan(): Flow<List<com.thrym.app.domain.model.LastMetaPlanSession>>
}
