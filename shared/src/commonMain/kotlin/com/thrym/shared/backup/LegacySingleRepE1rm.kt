package com.thrym.shared.backup

import com.thrym.shared.workout.SessionRecords
import kotlin.math.abs

/** The sets the stored personal records count: no warm-ups, reps and weight not negative. */
internal fun BackupWorkoutSet.countsForPersonalRecords(): Boolean =
    resolvedSetType() != WARMUP_SET_TYPE && reps >= 0 && weightKg.isFinite() && weightKg >= 0.0

/**
 * The best e1RM of [sets] and the set that reached it; on a tie the later set. [estimate] is the
 * rule of Android and the records board, where a single rep counts as the weight itself.
 */
internal fun bestE1rmOf(
    sets: List<BackupWorkoutSet>,
    estimate: (weightKg: Double, reps: Int) -> Double = SessionRecords::estimatedOneRepMaxKg,
): Pair<Double, BackupWorkoutSet>? = sets
    .mapNotNull { set ->
        val value = estimate(set.weightKg, set.reps)
        value.takeIf(Double::isFinite)?.let { value to set }
    }
    .maxWithOrNull(
        compareBy<Pair<Double, BackupWorkoutSet>> { it.first }
            .thenBy { it.second.completedAt }
            .thenBy { it.second.id },
    )

/** Plain Epley, which older iOS builds stored MAX_E1RM with: 100 kg × 1 gave 103.3 kg. */
private fun legacyEpleyKg(weightKg: Double, reps: Int): Double =
    weightKg * (1.0 + reps.toDouble() / 30.0)

private const val LEGACY_E1RM_EPSILON = 1e-9

/**
 * Older IronLog iOS builds stored MAX_E1RM with plain Epley, so a single rep of 100 kg was a best
 * of 103.3 kg. Android and the records board count a single rep as the weight itself. A stored
 * e1RM that is exactly the old best of the exercise's sets gets the value and time of the current
 * best when the old best lies above it. It also gets them when a set of several reps ties the old
 * best (85 kg × 1 and 77.5 kg × 4 both give 87.8 kg) but the record is still dated on the single,
 * which the old rule preferred as the later set. The id stays. Every other record stays as the
 * payload has it.
 *
 * The step is idempotent, so it needs no marker. The iOS store runs it whenever it decodes a
 * payload, Android's backup import before it writes the records. Returns this payload unchanged
 * when no record needs the repair.
 */
fun BackupPayloadV1.repairLegacySingleRepE1rm(): BackupPayloadV1 {
    if (personalRecords.none { it.type == SessionRecords.MAX_E1RM }) return this
    val setsByExercise = workoutSets
        .filter { it.countsForPersonalRecords() }
        .groupBy { it.exerciseId }
    var repaired = false
    val records = personalRecords.map { record ->
        if (record.type != SessionRecords.MAX_E1RM) return@map record
        val sets = setsByExercise[record.exerciseId] ?: return@map record
        val legacy = bestE1rmOf(sets, ::legacyEpleyKg) ?: return@map record
        val current = bestE1rmOf(sets) ?: return@map record
        val isLegacyValue = abs(record.value - legacy.first) <= LEGACY_E1RM_EPSILON
        if (!isLegacyValue) return@map record
        val aboveCurrentBest = current.first < legacy.first - LEGACY_E1RM_EPSILON
        val datedOnOldSingle = legacy.second.reps == 1 && record.achievedAt == legacy.second.completedAt
        if (!aboveCurrentBest && !datedOnOldSingle) return@map record
        val fixed = record.copy(value = current.first, achievedAt = current.second.completedAt)
        if (fixed == record) return@map record
        repaired = true
        fixed
    }
    return if (repaired) copy(personalRecords = records) else this
}
