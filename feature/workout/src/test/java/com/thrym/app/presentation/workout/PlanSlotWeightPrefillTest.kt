package com.thrym.app.presentation.workout

import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.shared.plans.PlannedSet
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Weight prefilled for the next planned set, in the same order as iOS
 * `IOSWorkoutExerciseRow.defaultWeightKg`.
 */
class PlanSlotWeightPrefillTest {

    private fun prefill(
        slotWeightKg: Double,
        hasSetTargets: Boolean = false,
        lastWorkSetWeightKg: Double? = null,
        previousWorkoutWeightKg: Double? = null
    ) = planSlotWeightPrefill(
        slot = PlannedSet(reps = 8, weightKg = slotWeightKg),
        hasSetTargets = hasSetTargets,
        lastWorkSetWeightKg = lastWorkSetWeightKg,
        previousWorkoutWeightKg = previousWorkoutWeightKg
    )

    @Test
    fun `zero target offers the load of the last workout`() {
        // Pull-ups planned without weight, last trained with + 10 kg.
        assertEquals(
            PlanSlotWeightPrefill(10.0, PlanSlotWeightSource.LAST_WORKOUT),
            prefill(slotWeightKg = 0.0, previousWorkoutWeightKg = 10.0)
        )
    }

    @Test
    fun `zero target without history stays at bodyweight`() {
        assertEquals(PlanSlotWeightPrefill(0.0, PlanSlotWeightSource.PLAN), prefill(slotWeightKg = 0.0))
    }

    @Test
    fun `plan weight wins over the last workout`() {
        assertEquals(
            PlanSlotWeightPrefill(20.0, PlanSlotWeightSource.PLAN),
            prefill(slotWeightKg = 20.0, previousWorkoutWeightKg = 10.0)
        )
    }

    @Test
    fun `a set logged in this workout wins over plan and history`() {
        assertEquals(
            PlanSlotWeightPrefill(5.0, PlanSlotWeightSource.LAST_SET),
            prefill(slotWeightKg = 0.0, lastWorkSetWeightKg = 5.0, previousWorkoutWeightKg = 10.0)
        )
    }

    @Test
    fun `individual set targets are taken as planned`() {
        assertEquals(
            PlanSlotWeightPrefill(0.0, PlanSlotWeightSource.PLAN),
            prefill(slotWeightKg = 0.0, hasSetTargets = true, lastWorkSetWeightKg = 5.0, previousWorkoutWeightKg = 10.0)
        )
    }

    private fun previousSet(id: Long, weightKg: Double, type: SetType, minute: Int) = WorkoutSet(
        id = id,
        sessionId = 1,
        exerciseId = 7,
        setNumber = id.toInt(),
        reps = 8,
        weightKg = weightKg,
        setType = type,
        completedAt = LocalDateTime.of(2026, 10, 1, 18, minute)
    )

    @Test
    fun `last workout weight skips trailing drop and backoff sets`() {
        // Pull-ups 3 x 8 at + 10 kg, then a backoff at + 5 kg and a drop set at bodyweight.
        val sets = listOf(
            previousSet(1, 10.0, SetType.NORMAL, 0),
            previousSet(2, 10.0, SetType.NORMAL, 3),
            previousSet(3, 10.0, SetType.FAILURE, 6),
            previousSet(4, 5.0, SetType.BACKOFF, 9),
            previousSet(5, 0.0, SetType.DROP_SET, 12)
        )
        assertEquals(10.0, previousWorkoutWorkSetWeightKg(sets)!!, 0.0)
    }

    @Test
    fun `last workout weight takes the newest work set, then the higher id`() {
        val sets = listOf(
            previousSet(2, 12.5, SetType.NORMAL, 5),
            previousSet(1, 10.0, SetType.NORMAL, 5),
            previousSet(3, 2.5, SetType.WARMUP, 9)
        )
        assertEquals(12.5, previousWorkoutWorkSetWeightKg(sets)!!, 0.0)
    }

    @Test
    fun `last workout without a work set gives no weight`() {
        assertNull(previousWorkoutWorkSetWeightKg(listOf(previousSet(1, 5.0, SetType.DROP_SET, 0))))
    }
}
