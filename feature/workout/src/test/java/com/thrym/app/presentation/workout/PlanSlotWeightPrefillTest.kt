package com.thrym.app.presentation.workout

import com.thrym.shared.plans.PlannedSet
import org.junit.Assert.assertEquals
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
}
