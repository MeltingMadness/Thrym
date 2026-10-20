package com.thrym.shared.recognition

import com.thrym.shared.model.UnitSystem
import com.thrym.shared.workout.RecordSetInput
import com.thrym.shared.workout.SessionRecords

/**
 * Kind of a mark. [LOAD]: the weight of a barbell work set. [REPS]: the reps of a bodyweight
 * work set. [ADDED_LOAD]: the load on top of the body in a bodyweight work set.
 */
enum class StrengthMarkKind { LOAD, REPS, ADDED_LOAD }

/**
 * A round number reached for the first time. [value] is in the display unit: kg or lb for the
 * loads (as [imperial] says), reps for [StrengthMarkKind.REPS].
 */
data class StrengthMark(
    val kind: StrengthMarkKind,
    val value: Int,
    val imperial: Boolean,
)

/** A mark of an exercise's history with the time of the first work set that reached it. */
data class ReachedStrengthMark(
    val mark: StrengthMark,
    val reachedAtEpochMillis: Long,
)

/** The marks of one ladder in an exercise's history, oldest first, and the next one up. */
data class StrengthMarkLadder(
    val kind: StrengthMarkKind,
    val reached: List<ReachedStrengthMark>,
    val next: StrengthMark?,
)

/**
 * Round marks for barbell and bodyweight lifts: 30 to 100 kg in steps of 10, then 120 to 300
 * in steps of 20 (lb: the plate loads 65, 95, 135, 185, 225 …), 5 to 50 bodyweight reps and
 * +10 kg steps of added load (lb: +25).
 *
 * A mark counts with the first work set (no warm-up) that reaches it, compared with all other
 * workouts like the records in [SessionRecords]. So the first workout of an exercise sets no
 * mark, and a mark that a later edit lowers simply disappears. Dumbbells and machines have no
 * marks: their numbers are not comparable across gyms.
 */
object StrengthMarks {
    const val KG_PER_LB = 0.45359237

    val LOAD_KG: List<Int> = (30..100 step 10) + (120..300 step 20)
    val LOAD_LB: List<Int> = listOf(65, 95, 135, 185, 225, 275, 315, 365, 405, 455, 495, 545, 585, 635)
    val REPS: List<Int> = listOf(5, 10, 15, 20, 25, 30, 40, 50)
    val ADDED_LOAD_KG: List<Int> = (10..100 step 10).toList()
    val ADDED_LOAD_LB: List<Int> = (25..225 step 25).toList()

    /** Logged pounds are converted to kg and may round; this keeps 225 lb at 225. */
    private const val LB_TOLERANCE = 0.2
    private const val KG_TOLERANCE = 1e-6

    /** The ladders an exercise of [category] (stored code) has, in display order. */
    fun kindsFor(category: String): List<StrengthMarkKind> = when (category.trim().uppercase()) {
        "LANGHANTEL" -> listOf(StrengthMarkKind.LOAD)
        "EIGENGEWICHT" -> listOf(StrengthMarkKind.ADDED_LOAD, StrengthMarkKind.REPS)
        else -> emptyList()
    }

    fun ladder(kind: StrengthMarkKind, imperial: Boolean): List<Int> = when (kind) {
        StrengthMarkKind.LOAD -> if (imperial) LOAD_LB else LOAD_KG
        StrengthMarkKind.REPS -> REPS
        StrengthMarkKind.ADDED_LOAD -> if (imperial) ADDED_LOAD_LB else ADDED_LOAD_KG
    }

    /**
     * Marks the workout [sessionId] reached for the first time, the highest per ladder. [sets]
     * holds every set of the exercise in all workouts.
     */
    fun sessionMarks(
        sessionId: Long,
        sets: List<RecordSetInput>,
        category: String,
        unitSystem: UnitSystem,
    ): List<StrengthMark> {
        val imperial = unitSystem == UnitSystem.IMPERIAL
        val workSets = sets.filter(SessionRecords::isWorkSet)
        val current = workSets.filter { it.sessionId == sessionId }
        val other = workSets.filter { it.sessionId != sessionId }
        if (current.isEmpty() || other.isEmpty()) return emptyList()
        return kindsFor(category).mapNotNull { kind ->
            val now = highestReached(kind, imperial, current) ?: return@mapNotNull null
            val before = highestReached(kind, imperial, other)
            if (before != null && before >= now) null else StrengthMark(kind, now, imperial)
        }
    }

    /**
     * The mark a log or an edit in the workout [sessionId] just reached, for the title of the
     * live record notice, or null. Needs an earlier workout like the live records; a mark the
     * workout already held before the change stays quiet. A load mark comes before a reps mark.
     */
    fun liveMark(
        sessionId: Long,
        setsBefore: List<RecordSetInput>,
        setsAfter: List<RecordSetInput>,
        category: String,
        unitSystem: UnitSystem,
    ): StrengthMark? {
        val imperial = unitSystem == UnitSystem.IMPERIAL
        val after = setsAfter.filter(SessionRecords::isWorkSet)
        val other = after.filter { it.sessionId != sessionId }
        if (other.isEmpty()) return null
        val currentAfter = after.filter { it.sessionId == sessionId }
        val currentBefore = setsBefore.filter { SessionRecords.isWorkSet(it) && it.sessionId == sessionId }
        return kindsFor(category).firstNotNullOfOrNull { kind ->
            val now = highestReached(kind, imperial, currentAfter) ?: return@firstNotNullOfOrNull null
            val bar = listOfNotNull(highestReached(kind, imperial, other), highestReached(kind, imperial, currentBefore)).maxOrNull()
            if (bar != null && bar >= now) null else StrengthMark(kind, now, imperial)
        }
    }

    /** The exercise's marks over its whole history, one ladder per kind that has a mark. */
    fun history(
        sets: List<RecordSetInput>,
        category: String,
        unitSystem: UnitSystem,
    ): List<StrengthMarkLadder> {
        val imperial = unitSystem == UnitSystem.IMPERIAL
        val ordered = sets.filter(SessionRecords::isWorkSet)
            .sortedWith(compareBy<RecordSetInput> { it.completedAtEpochMillis }.thenBy { it.id })
        return kindsFor(category).mapNotNull { kind ->
            val marks = ladder(kind, imperial)
            val reached = marks.mapNotNull { mark ->
                val first = ordered.firstOrNull { reaches(kind, imperial, it, mark) } ?: return@mapNotNull null
                ReachedStrengthMark(StrengthMark(kind, mark, imperial), first.completedAtEpochMillis)
            }
            if (reached.isEmpty()) return@mapNotNull null
            val next = marks.firstOrNull { mark -> reached.none { it.mark.value == mark } }
            StrengthMarkLadder(kind, reached, next?.let { StrengthMark(kind, it, imperial) })
        }
    }

    private fun highestReached(kind: StrengthMarkKind, imperial: Boolean, sets: List<RecordSetInput>): Int? =
        ladder(kind, imperial).lastOrNull { mark -> sets.any { reaches(kind, imperial, it, mark) } }

    private fun reaches(kind: StrengthMarkKind, imperial: Boolean, set: RecordSetInput, mark: Int): Boolean =
        when (kind) {
            StrengthMarkKind.REPS -> set.reps >= mark
            StrengthMarkKind.LOAD, StrengthMarkKind.ADDED_LOAD -> {
                val value = if (imperial) set.weightKg / KG_PER_LB else set.weightKg
                val tolerance = if (imperial) LB_TOLERANCE else KG_TOLERANCE
                value + tolerance >= mark
            }
        }
}
