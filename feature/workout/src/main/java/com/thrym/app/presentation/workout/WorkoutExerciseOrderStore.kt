package com.thrym.app.presentation.workout

import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.shared.workout.SessionExerciseOrderCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Display order of the exercises in one active session: the stored row keys
 * (`planned-<snapshotId>` / `adhoc-<exerciseId>`) in memory plus their durable copy in the app
 * preferences, so a moved exercise stays in place across ViewModel recreation and a cold start.
 *
 * The order lives outside Room and never touches `workout_plan_targets.orderIndex`. The payload
 * is guarded by session id and start time through [SessionExerciseOrderCodec].
 */
internal class WorkoutExerciseOrderStore(
    private val sessionId: Long,
    private val workoutRepository: WorkoutRepository,
    private val appPreferencesRepository: AppPreferencesRepository
) {
    private val _keys = MutableStateFlow<List<String>?>(null)

    /** Stored row keys; null until [restore] finished, empty for the default order. */
    val keys: StateFlow<List<String>?> = _keys.asStateFlow()

    /**
     * Serializes restore, in-memory updates and their durable writes, like the rest timers. The
     * generation tells a restore that finished late that a fresh update already won.
     */
    private val mutex = Mutex()
    private var generation = 0L
    private var sessionStartEpochMillis: Long? = null

    /**
     * Loads the stored order of this session. [beforePublish] receives the ad-hoc exercise ids of
     * the restored order and runs before the order becomes visible, so restored ad-hoc rows are
     * already known when the first arranged list is built.
     *
     * A payload of another session or start time is ignored. It is removed only while this
     * session is still active: a finished or missing session must not delete the single entry
     * that may belong to the session running now.
     */
    suspend fun restore(beforePublish: suspend (adHocExerciseIds: List<Long>) -> Unit) {
        mutex.withLock {
            val hasLiveUpdate = generation > 0L
            val sessionResult = runCatchingNonCancellation { workoutRepository.getSessionById(sessionId) }
            sessionResult.exceptionOrNull()?.let { e ->
                AppLogger.w(
                    "ActiveWorkoutVM",
                    "Aktive Session fuer die Uebungsreihenfolge konnte nicht geladen werden: ${e.message}",
                    e
                )
            }
            val session = sessionResult.getOrNull()
            sessionStartEpochMillis = session?.startEpochMillis()
            val sessionStart = sessionStartEpochMillis

            val rawResult = runCatchingNonCancellation { appPreferencesRepository.readWorkoutExerciseOrder() }
            rawResult.exceptionOrNull()?.let { e ->
                AppLogger.w(
                    "ActiveWorkoutVM",
                    "Uebungsreihenfolge konnte nicht gelesen werden: ${e.message}",
                    e
                )
            }
            val raw = rawResult.getOrNull()

            val decoded = if (session != null && sessionStart != null && raw != null) {
                SessionExerciseOrderCodec.decode(raw, sessionId, sessionStart)
            } else {
                null
            }
            if (raw != null && decoded == null && session != null && session.endTime == null) {
                // Foreign, stale or malformed payload: drop it so it is not reconsidered on every
                // ViewModel recreation of this session.
                persist(null)
            }

            val restoredKeys = decoded.orEmpty()
            val adHocIds = restoredKeys.mapNotNull { key ->
                key.removePrefix(AD_HOC_PREFIX).takeIf { key.startsWith(AD_HOC_PREFIX) }?.toLongOrNull()
            }
            if (adHocIds.isNotEmpty()) {
                try {
                    beforePublish(adHocIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w(
                        "ActiveWorkoutVM",
                        "Zusatzuebungen der Reihenfolge konnten nicht geladen werden: ${e.message}",
                        e
                    )
                }
            }

            if (hasLiveUpdate) {
                // An update won the mutex before this restore. Keep that live order and make it
                // the durable copy instead of replacing it with an older payload.
                _keys.value?.let { persist(it) }
            } else {
                _keys.value = restoredKeys
            }
        }
    }

    /**
     * Applies [transform] to the order in memory. A null result means "no change" (edge of the
     * list, unknown key) and writes nothing. [precondition] runs under the same mutex, so queued
     * updates keep the order in which they were requested. Write failures are logged; the order
     * in memory stays valid for the rest of this ViewModel.
     */
    suspend fun update(
        precondition: suspend () -> Boolean = { true },
        transform: (List<String>) -> List<String>?
    ) {
        mutex.withLock {
            if (!precondition()) return@withLock
            val next = transform(_keys.value.orEmpty()) ?: return@withLock
            generation += 1L
            _keys.value = next
            persist(next)
        }
    }

    /** Forgets the order of this session, for example when an empty session is discarded. */
    suspend fun clear() {
        mutex.withLock {
            generation += 1L
            _keys.value = emptyList()
            persist(null)
        }
    }

    private suspend fun persist(keys: List<String>?) {
        try {
            val encoded = keys?.let {
                val sessionStart = sessionStartEpochMillis ?: loadSessionStart() ?: return
                SessionExerciseOrderCodec.encode(sessionId, sessionStart, it)
            }
            appPreferencesRepository.writeWorkoutExerciseOrder(encoded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(
                "ActiveWorkoutVM",
                "Uebungsreihenfolge konnte nicht gespeichert werden: ${e.message}",
                e
            )
        }
    }

    /** Retries the session read when restore could not load it, so later updates still persist. */
    private suspend fun loadSessionStart(): Long? {
        val start = workoutRepository.getSessionById(sessionId)?.startEpochMillis()
        sessionStartEpochMillis = start
        return start
    }

    private fun WorkoutSession.startEpochMillis(): Long =
        startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val AD_HOC_PREFIX = "adhoc-"
    }
}
