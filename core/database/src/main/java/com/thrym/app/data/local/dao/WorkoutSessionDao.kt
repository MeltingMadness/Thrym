package com.thrym.app.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.thrym.app.data.local.entity.WorkoutSessionEntity
import com.thrym.app.data.local.relation.SessionWithSets
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutSessionDao {

    @Insert
    suspend fun insert(session: WorkoutSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAll(sessions: List<WorkoutSessionEntity>)

    @Update
    suspend fun update(session: WorkoutSessionEntity)

    @Query("SELECT * FROM workout_sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    fun observeActiveSession(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    fun getAllCompletedSessions(): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM workout_sessions WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    fun getPagedCompletedSessions(): PagingSource<Int, WorkoutSessionEntity>

    @androidx.room.Transaction
    @Query("SELECT * FROM workout_sessions WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    fun getPagedCompletedSessionsWithSets(): PagingSource<Int, SessionWithSets>

    /**
     * The filter predicates intentionally live in this query instead of being applied to
     * collected PagingData. Applying them here means a plan/date filter searches every completed
     * session, including sessions on pages that have not been loaded yet.
     */
    @androidx.room.Transaction
    @Query(
        """
        SELECT * FROM workout_sessions
        WHERE endTime IS NOT NULL
          AND (:planId IS NULL OR planId = :planId)
          AND (:fromEpochMillis IS NULL OR startTime >= :fromEpochMillis)
          AND (:toEpochMillis IS NULL OR startTime < :toEpochMillis)
          AND (
            :searchPattern IS NULL
            OR name LIKE :searchPattern ESCAPE '\'
            OR notes LIKE :searchPattern ESCAPE '\'
            OR EXISTS (
              SELECT 1 FROM training_plans p
              WHERE p.id = workout_sessions.planId AND p.name LIKE :searchPattern ESCAPE '\'
            )
            OR EXISTS (
              SELECT 1 FROM workout_sets s JOIN exercises e ON e.id = s.exerciseId
              WHERE s.sessionId = workout_sessions.id
                AND (
                  e.name LIKE :searchPattern ESCAPE '\'
                  OR e.notes LIKE :searchPattern ESCAPE '\'
                  OR e.name IN (:exerciseNames)
                )
            )
          )
        ORDER BY startTime DESC
        """
    )
    fun getPagedCompletedSessionsWithSetsFiltered(
        planId: Long?,
        fromEpochMillis: Long?,
        toEpochMillis: Long?,
        searchPattern: String?,
        /** Stored exercise names that also match (built-ins found by their localized name). */
        exerciseNames: List<String> = emptyList()
    ): PagingSource<Int, SessionWithSets>

    @Query("SELECT * FROM workout_sessions ORDER BY id ASC")
    suspend fun getAllSessionsList(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE id IN (:sessionIds)")
    suspend fun getSessionsByIds(sessionIds: List<Long>): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    fun observeSessionById(id: Long): Flow<WorkoutSessionEntity?>

    @Query("DELETE FROM workout_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM workout_sessions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM workout_sessions WHERE endTime IS NOT NULL AND startTime >= :sinceEpoch")
    suspend fun getCompletedSessionCountSince(sinceEpoch: Long): Int

    @Query(
        "SELECT COUNT(*) FROM workout_sessions " +
            "WHERE endTime IS NOT NULL " +
            "AND startTime >= :sinceEpochMillis " +
            "AND startTime <= :untilEpochMillis"
    )
    suspend fun getCompletedSessionCountBetween(
        sinceEpochMillis: Long,
        untilEpochMillis: Long
    ): Int

    @Query("SELECT * FROM workout_sessions WHERE endTime IS NOT NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastCompletedSession(): WorkoutSessionEntity?

    @Query(
        "SELECT * FROM workout_sessions " +
            "WHERE endTime IS NOT NULL " +
            "AND startTime <= :untilEpochMillis " +
            "ORDER BY startTime DESC LIMIT 1"
    )
    suspend fun getLastCompletedSessionBefore(untilEpochMillis: Long): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    suspend fun getAllCompletedSessionsList(): List<WorkoutSessionEntity>

    /** Only start and deload flag of every completed session, for the weekly goal. */
    @Query("SELECT startTime, isDeload FROM workout_sessions WHERE endTime IS NOT NULL ORDER BY startTime")
    suspend fun getCompletedSessionStarts(): List<CompletedSessionStartRow>

    @Query("""
        SELECT planId, MAX(startTime) AS lastStartTime
        FROM workout_sessions
        WHERE planId IS NOT NULL AND endTime IS NOT NULL
        GROUP BY planId
    """)
    fun observeLastSessionPerPlan(): Flow<List<LastPlanSessionRow>>

    @Query("""
        SELECT planId, metaPlanId, MAX(startTime) AS lastStartTime
        FROM workout_sessions
        WHERE metaPlanId IS NOT NULL AND planId IS NOT NULL AND endTime IS NOT NULL
        GROUP BY planId, metaPlanId
    """)
    fun observeLastSessionPerMetaPlanSubPlan(): Flow<List<LastMetaPlanSessionRow>>
}
