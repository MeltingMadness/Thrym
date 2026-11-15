package com.thrym.app.presentation.progression

import com.thrym.app.domain.model.ProgressionTarget
import kotlin.math.abs

enum class ProgressionChangeKind { WEIGHT, REPS, SETS, NONE }
enum class ProgressionChangeDirection { UP, DOWN, SAME }

data class ProgressionChange(
    val kind: ProgressionChangeKind,
    val direction: ProgressionChangeDirection,
    val fromWeightKg: Double,
    val toWeightKg: Double,
    val fromCount: Int,
    val toCount: Int,
    val alsoReps: Pair<Int, Int>?,
    val alsoSets: Pair<Int, Int>?
)

fun progressionChange(source: ProgressionTarget, proposed: ProgressionTarget): ProgressionChange {
    val kind = when {
        abs(proposed.weightKg - source.weightKg) > 0.01 + 1e-9 -> ProgressionChangeKind.WEIGHT
        proposed.reps != source.reps -> ProgressionChangeKind.REPS
        proposed.sets != source.sets -> ProgressionChangeKind.SETS
        else -> ProgressionChangeKind.NONE
    }
    val fromCount = if (kind == ProgressionChangeKind.SETS) source.sets else source.reps
    val toCount = if (kind == ProgressionChangeKind.SETS) proposed.sets else proposed.reps
    val delta = if (kind == ProgressionChangeKind.WEIGHT) proposed.weightKg - source.weightKg else (toCount - fromCount).toDouble()
    val direction = when {
        kind == ProgressionChangeKind.NONE -> ProgressionChangeDirection.SAME
        delta > 0 -> ProgressionChangeDirection.UP
        else -> ProgressionChangeDirection.DOWN
    }
    return ProgressionChange(kind, direction, source.weightKg, proposed.weightKg, fromCount, toCount,
        (source.reps to proposed.reps).takeIf { kind == ProgressionChangeKind.WEIGHT && source.reps != proposed.reps },
        (source.sets to proposed.sets).takeIf { kind != ProgressionChangeKind.SETS && source.sets != proposed.sets })
}
