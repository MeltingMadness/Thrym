package com.thrym.shared.workout

import com.thrym.shared.backup.WARMUP_SET_TYPE
import kotlin.math.abs

/**
 * One logged set as the record rules see it. [setType] is the resolved stored code
 * (`WARMUP` for warm-ups, also for legacy `isWarmup` sets). [sessionFinished] tells whether the
 * set's session has an end time; only finished sessions count for the volume best.
 */
data class RecordSetInput(
    val id: Long,
    val sessionId: Long,
    val exerciseId: Long,
    val weightKg: Double,
    val reps: Int,
    val setType: String,
    val completedAtEpochMillis: Long,
    val sessionFinished: Boolean,
)

/**
 * One beaten record of a workout. [type] is the stored `RecordType` code. [previous] is the best
 * of all other sessions, or null when the exercise has no earlier value of this type.
 * [setId] is the set that holds the record; it is null for `MAX_VOLUME`, which sums the session.
 */
data class SessionRecordLine(
    val type: String,
    val value: Double,
    val previous: Double?,
    val setId: Long?,
)

/**
 * The records of one exercise in a workout. An exercise without any earlier work set is
 * [firstTime]: it has no [lines], its values become the first bests. [bestSetId] is the heaviest
 * work set of the session (ties: more reps, then the earlier set).
 */
data class SessionExerciseRecords(
    val exerciseId: Long,
    val firstTime: Boolean,
    val lines: List<SessionRecordLine>,
    val bestSetId: Long?,
)

data class SessionRecordSummary(
    val exercises: List<SessionExerciseRecords>,
) {
    /** Number of beaten records, not of exercises. */
    val recordCount: Int get() = exercises.sumOf { it.lines.size }

    val exercisesWithRecords: Int get() = exercises.count { it.lines.isNotEmpty() }

    val firstTimeExerciseIds: List<Long> get() = exercises.filter { it.firstTime }.map { it.exerciseId }
}

/**
 * Personal records of one workout, derived from the logged sets alone.
 *
 * The stored `personal_records` rows keep one value per exercise and type and no history, so
 * they cannot say what the best was before this workout. These rules compare the workout's
 * work sets with the work sets of all other sessions. Android and iOS use them for the live
 * notice and for the completion screen, so both show the same records with the same gain.
 *
 * - A work set is a set that is not a warm-up, with reps > 0 and a finite weight >= 0.
 * - `MAX_WEIGHT`, `MAX_REPS` and `MAX_E1RM` are per set, `MAX_VOLUME` is the sum of weight ×
 *   reps over the workout; its earlier best only counts finished sessions.
 * - A value <= 0 is no record, so a bodyweight exercise at 0 kg only has `MAX_REPS`.
 * - A record needs a gain of at least one step ([minStep]); a tie is no record.
 * - An exercise trained for the first time has no records. It is reported as [SessionExerciseRecords.firstTime].
 */
object SessionRecords {

    const val MAX_WEIGHT = "MAX_WEIGHT"
    const val MAX_E1RM = "MAX_E1RM"
    const val MAX_REPS = "MAX_REPS"
    const val MAX_VOLUME = "MAX_VOLUME"

    /** The order of the lines of one exercise. */
    val TYPE_ORDER: List<String> = listOf(MAX_WEIGHT, MAX_E1RM, MAX_REPS, MAX_VOLUME)

    private val LIVE_TYPES = listOf(MAX_WEIGHT, MAX_E1RM, MAX_REPS)
    private const val VALUE_EPSILON = 1e-9

    /** Epley, with a single rep counting as the weight itself, like `WorkoutCalculations.calculateE1RM`. */
    fun estimatedOneRepMaxKg(weightKg: Double, reps: Int): Double =
        if (reps <= 1) weightKg else weightKg * (1.0 + reps / 30.0)

    /** The smallest gain that counts as a record: 0.1 kg for weight and e1RM, 1 rep, 1 kg volume. */
    fun minStep(type: String): Double = when (type) {
        MAX_REPS -> 1.0
        MAX_VOLUME -> 1.0
        else -> 0.1
    }

    fun isWorkSet(set: RecordSetInput): Boolean =
        set.setType != WARMUP_SET_TYPE && set.reps > 0 && set.weightKg.isFinite() && set.weightKg >= 0.0

    /**
     * Records of the workout [sessionId] for the exercises in [exerciseIdsInOrder] (duplicates
     * count once). [sets] holds every set of these exercises in all sessions. Exercises without a
     * work set in the workout, and exercises with history but no beaten record, are left out.
     */
    fun summarize(
        sessionId: Long,
        exerciseIdsInOrder: List<Long>,
        sets: List<RecordSetInput>,
    ): SessionRecordSummary {
        val workSetsByExercise = sets.filter(::isWorkSet).groupBy { it.exerciseId }
        val exercises = exerciseIdsInOrder.distinct().mapNotNull { exerciseId ->
            val workSets = workSetsByExercise[exerciseId].orEmpty()
            val current = workSets.filter { it.sessionId == sessionId }
            if (current.isEmpty()) return@mapNotNull null
            val other = workSets.filter { it.sessionId != sessionId }
            val bestSetId = current.minWithOrNull(heaviestFirst)?.id
            if (other.isEmpty()) {
                return@mapNotNull SessionExerciseRecords(exerciseId, firstTime = true, lines = emptyList(), bestSetId = bestSetId)
            }
            val lines = TYPE_ORDER.mapNotNull { type ->
                val value = sessionBest(type, current) ?: return@mapNotNull null
                val previous = historyBest(type, other)
                if (previous != null && value - previous < minStep(type) - VALUE_EPSILON) return@mapNotNull null
                SessionRecordLine(type, value, previous, recordSetId(type, value, current))
            }
            if (lines.isEmpty()) null else SessionExerciseRecords(exerciseId, firstTime = false, lines = lines, bestSetId = bestSetId)
        }
        return SessionRecordSummary(exercises)
    }

    /**
     * Records that a log or an edit in the workout [sessionId] just beat. [setsBefore] and
     * [setsAfter] hold every set of one exercise in all sessions, before and after the change.
     *
     * A line needs an earlier best from another session, and the new session best must beat both
     * that and the session best before the change by one step. So the first workout of an
     * exercise, a tie, a lowered edit and a deletion stay quiet. [SessionRecordLine.previous] is
     * the best before the workout, so the gain matches the completion screen. Volume is never
     * live, because the running workout does not count for the stored volume best.
     */
    fun liveImprovements(
        sessionId: Long,
        setsBefore: List<RecordSetInput>,
        setsAfter: List<RecordSetInput>,
    ): List<SessionRecordLine> {
        val after = setsAfter.filter(::isWorkSet)
        val other = after.filter { it.sessionId != sessionId }
        if (other.isEmpty()) return emptyList()
        val currentAfter = after.filter { it.sessionId == sessionId }
        val currentBefore = setsBefore.filter { isWorkSet(it) && it.sessionId == sessionId }
        return LIVE_TYPES.mapNotNull { type ->
            val previous = historyBest(type, other) ?: return@mapNotNull null
            val value = sessionBest(type, currentAfter) ?: return@mapNotNull null
            val bar = maxOf(previous, sessionBest(type, currentBefore) ?: previous)
            if (value - bar < minStep(type) - VALUE_EPSILON) return@mapNotNull null
            SessionRecordLine(type, value, previous, recordSetId(type, value, currentAfter))
        }
    }

    private fun sessionBest(type: String, sets: List<RecordSetInput>): Double? = when (type) {
        MAX_VOLUME -> volumeOf(sets)
        else -> sets.maxOfOrNull { perSetValue(type, it) }
    }?.takeIf { it.isFinite() && it > 0.0 }

    private fun historyBest(type: String, sets: List<RecordSetInput>): Double? = when (type) {
        MAX_VOLUME -> sets.filter { it.sessionFinished }
            .groupBy { it.sessionId }
            .values
            .mapNotNull(::volumeOf)
            .maxOrNull()
        else -> sets.maxOfOrNull { perSetValue(type, it) }
    }?.takeIf { it.isFinite() && it > 0.0 }

    private fun perSetValue(type: String, set: RecordSetInput): Double = when (type) {
        MAX_WEIGHT -> set.weightKg
        MAX_REPS -> set.reps.toDouble()
        MAX_E1RM -> estimatedOneRepMaxKg(set.weightKg, set.reps).takeIf { it.isFinite() } ?: 0.0
        else -> 0.0
    }

    private fun volumeOf(sets: List<RecordSetInput>): Double? =
        sets.map { it.weightKg * it.reps }.filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.sum()

    private fun recordSetId(type: String, value: Double, sets: List<RecordSetInput>): Long? {
        if (type == MAX_VOLUME) return null
        val holders = sets.filter { abs(perSetValue(type, it) - value) < VALUE_EPSILON }
        val order = when (type) {
            MAX_REPS -> compareByDescending<RecordSetInput> { it.weightKg }.then(earliestFirst)
            MAX_WEIGHT -> compareByDescending<RecordSetInput> { it.reps }.then(earliestFirst)
            else -> earliestFirst
        }
        return holders.minWithOrNull(order)?.id
    }

    private val earliestFirst: Comparator<RecordSetInput> =
        compareBy<RecordSetInput> { it.completedAtEpochMillis }.thenBy { it.id }

    private val heaviestFirst: Comparator<RecordSetInput> =
        compareByDescending<RecordSetInput> { it.weightKg }
            .thenByDescending { it.reps }
            .then(earliestFirst)
}
