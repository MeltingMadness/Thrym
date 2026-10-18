package com.thrym.app.presentation.workout

import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.util.RpeAutoregulation
import com.thrym.app.domain.util.WeightFormatting
import com.thrym.shared.plans.PlannedSet
import kotlinx.coroutines.flow.map

private val submissionIdSequence = java.util.concurrent.atomic.AtomicLong(0L)

internal fun nextSubmissionId(): Long = submissionIdSequence.incrementAndGet()

/**
 * Parses user-typed decimal input (weight/intensity): comma and dot both work as the decimal
 * separator, and native digits are accepted. See [WeightFormatting.parseInputNumber].
 */
fun parseDecimal(text: String): Double? = WeightFormatting.parseInputNumber(text)

fun lastWorkSetReachedTarget(
    planTarget: WorkoutPlanTarget?,
    previousSets: List<WorkoutSet>
): Boolean {
    val target = planTarget ?: return false
    if (target.target.reps <= 0 || target.target.weightKg <= 0.0) return false
    val lastWorkSet = previousSets.lastOrNull { !it.isWarmup } ?: return false
    return lastWorkSet.reps >= target.target.reps &&
        lastWorkSet.weightKg >= target.target.weightKg
}

internal enum class PlanSlotWeightSource { PLAN, LAST_SET, LAST_WORKOUT }

internal data class PlanSlotWeightPrefill(val weightKg: Double, val source: PlanSlotWeightSource)

/**
 * Weight prefilled for the next planned set. Individual set targets are taken as planned. A
 * simple target takes the last work set of this workout first; a target without weight (0 kg)
 * then falls back to the last work set of the previous matching workout, so an added load on a
 * bodyweight exercise (pull-ups + 10 kg) is offered again. Same order as iOS
 * `IOSWorkoutExerciseRow.defaultWeightKg`.
 */
internal fun planSlotWeightPrefill(
    slot: PlannedSet,
    hasSetTargets: Boolean,
    lastWorkSetWeightKg: Double?,
    previousWorkoutWeightKg: Double?
): PlanSlotWeightPrefill = when {
    hasSetTargets -> PlanSlotWeightPrefill(slot.weightKg, PlanSlotWeightSource.PLAN)
    lastWorkSetWeightKg != null -> PlanSlotWeightPrefill(lastWorkSetWeightKg, PlanSlotWeightSource.LAST_SET)
    slot.weightKg <= 0.0 && previousWorkoutWeightKg != null ->
        PlanSlotWeightPrefill(previousWorkoutWeightKg, PlanSlotWeightSource.LAST_WORKOUT)
    else -> PlanSlotWeightPrefill(slot.weightKg, PlanSlotWeightSource.PLAN)
}

/**
 * Weight of the last work set (normal or failure) of the previous matching workout, newest by
 * completion time, then id. Drop and backoff sets do not count. Same rule as iOS
 * `previousWorkWeight`.
 */
internal fun previousWorkoutWorkSetWeightKg(previousSets: List<WorkoutSet>): Double? =
    previousSets
        .filter { it.setType == SetType.NORMAL || it.setType == SetType.FAILURE }
        .maxWithOrNull(compareBy<WorkoutSet>({ it.completedAt }, { it.id }))
        ?.weightKg

/**
 * Passt ein Planziel an den aktiven Deload-Modus an (nur Anzeigeebene — die
 * gespeicherten Planziele und die Progressions-Auswertung bleiben unverändert).
 * Ohne aktiven Modus wird das Ziel unverändert zurückgegeben.
 */
internal fun applyDeloadToTarget(
    target: WorkoutPlanTarget,
    deloadMode: DeloadMode?
): WorkoutPlanTarget = when (deloadMode) {
    null -> target
    DeloadMode.HALVE_SET_VOLUME -> target.copy(
        target = target.target.copy(
            sets = maxOf(1, (target.target.sets + 1) / 2)
        ),
        setTargets = target.setTargets.let { slots ->
            var work = 0
            slots.filter { it.kind == "WARMUP" || ++work <= maxOf(1, (target.target.sets + 1) / 2) }
        }
    )
    DeloadMode.REDUCE_INTENSITY_BY_15_PERCENT -> target.copy(
        target = target.target.copy(
            weightKg = roundToOneDecimal(target.target.weightKg * 0.85)
        ),
        setTargets = target.setTargets.map { it.copy(weightKg = roundToOneDecimal(it.weightKg * 0.85)) }
    )
}

internal fun roundToOneDecimal(value: Double): Double =
    kotlin.math.round(value * 10.0) / 10.0

/**
 * Returns whether logging [newSetType] completes the planned slots for one exact row.
 * NORMAL and FAILURE fulfill work slots; warmups, drops and backoffs do not. The active
 * deload mode changes the effective set target used for this decision.
 */
internal fun isPlannedExerciseComplete(
    planTarget: WorkoutPlanTarget?,
    deloadMode: DeloadMode?,
    previousSets: List<WorkoutSet>,
    newSetType: SetType
): Boolean {
    val effectiveTarget = planTarget?.let { applyDeloadToTarget(it, deloadMode) } ?: return false
    if (effectiveTarget.setTargets.isNotEmpty()) {
        return com.thrym.shared.plans.PlannedSets.matchedIndices(
            effectiveTarget.setTargets, previousSets.map { it.setType.name } + newSetType.name
        ).all { it != null }
    }
    val targetSetCount = effectiveTarget.target.sets
    if (targetSetCount <= 0) return false

    val normalSetCount = previousSets.count { it.setType == SetType.NORMAL || it.setType == SetType.FAILURE } +
        if (newSetType == SetType.NORMAL || newSetType == SetType.FAILURE) 1 else 0
    return normalSetCount >= targetSetCount
}

internal fun configuredBackoffPercent(config: ProgressionConfig?): Double = when (config) {
    is ProgressionConfig.Linear -> config.failurePolicy.backoffPercent
    is ProgressionConfig.DoubleProgression -> config.failurePolicy.backoffPercent
    is ProgressionConfig.TotalReps -> config.failurePolicy.backoffPercent
    is ProgressionConfig.RpeRir -> config.failurePolicy.backoffPercent
    null,
    is ProgressionConfig.Manual,
    is ProgressionConfig.Invalid -> RpeAutoregulation.DEFAULT_BACKOFF_PERCENT
}
