package com.thrym.app.presentation.dashboard

import com.thrym.shared.readiness.MuscleGroupContext
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardHomeLogicTest {
    @Test fun `open items only include applicable actions in priority order`() {
        assertEquals(emptyList<DashboardOpenItem>(), dashboardOpenItems(0, false, false))
        assertEquals(listOf(DashboardOpenItem.PROGRESSION, DashboardOpenItem.DELOAD, DashboardOpenItem.COMEBACK), dashboardOpenItems(2, true, true))
        assertEquals(listOf(DashboardOpenItem.COMEBACK), dashboardOpenItems(0, false, true))
    }

    @Test fun `planned muscles precede recent muscles and four are visible first`() {
        val muscles = listOf(
            MuscleGroupContext("A", lastTrainedEpochMillis = 100, setsInWindow = 3.0),
            MuscleGroupContext("B", plannedToday = true),
            MuscleGroupContext("C", lastTrainedEpochMillis = 200, soreness = 2),
            MuscleGroupContext("D"),
            MuscleGroupContext("E", plannedToday = true, lastTrainedEpochMillis = 1)
        )
        assertEquals(listOf("E", "B", "C", "A"), orderedMuscles(muscles).take(4).map { it.muscleGroup })
        // "D" has no plan, no soreness and no sets this week: it stays off the card.
        assertEquals(4, orderedMuscles(muscles).size)
    }
}
