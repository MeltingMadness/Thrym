package com.thrym.shared.recognition

import com.thrym.shared.model.WeekStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingMilestonesTest {

    // 2026-10-19 is a Monday.
    private val monday = 20_745L
    private val goal3 = listOf(WeeklyGoalChange(0L, 3))

    private fun session(id: Long, day: Long, order: Long = day * 10, deload: Boolean? = false) =
        MilestoneSession(id = id, epochDay = day, orderKey = order, isDeload = deload)

    @Test
    fun jubileesFollowTheLadder() {
        val jubilees = (1..1500).filter(TrainingMilestones::isJubilee)

        assertEquals(
            listOf(10, 25, 50, 100, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1000, 1250, 1500),
            jubilees
        )
        assertFalse(TrainingMilestones.isJubilee(0))
    }

    @Test
    fun theTrainingNumberCountsEarlierWorkoutsOnly() {
        val sessions = listOf(session(5, monday - 30), session(7, monday), session(6, monday - 10), session(8, monday + 1))

        val facts = TrainingMilestones.completionFacts(7, sessions, WeekStart.MONDAY, null)!!

        assertEquals(3, facts.trainingNumber)
        assertEquals(monday - 30, facts.firstTrainingEpochDay)
        assertNull(facts.weeklyGoal)
    }

    @Test
    fun theWorkoutThatReachesTheGoalNamesIt() {
        val sessions = listOf(
            session(1, monday - 7), session(2, monday - 5), session(3, monday - 3),
            session(4, monday), session(5, monday + 2), session(6, monday + 4), session(7, monday + 5),
        )

        val reaching = TrainingMilestones.completionFacts(6, sessions, WeekStart.MONDAY, goal3)!!
        val beyond = TrainingMilestones.completionFacts(7, sessions, WeekStart.MONDAY, goal3)!!
        val before = TrainingMilestones.completionFacts(5, sessions, WeekStart.MONDAY, goal3)!!

        assertEquals(2, reaching.weeklyGoal?.streakWeeks)
        assertEquals(3, reaching.weeklyGoal?.completedThisWeek)
        assertEquals(listOf(CompletionFactKind.WEEKLY_GOAL), reaching.rows)
        assertNull(beyond.weeklyGoal)
        assertNull(before.weeklyGoal)
    }

    @Test
    fun aPauseOfThreeWeeksIsAComeback() {
        val sessions = listOf(session(1, monday - 21), session(2, monday), session(3, monday + 20))

        assertEquals(21, TrainingMilestones.completionFacts(2, sessions, WeekStart.MONDAY, goal3)!!.pauseDays)
        assertNull(TrainingMilestones.completionFacts(3, sessions, WeekStart.MONDAY, goal3)!!.pauseDays)
        assertNull(TrainingMilestones.completionFacts(1, sessions, WeekStart.MONDAY, goal3)!!.pauseDays)
        assertNull(TrainingMilestones.pauseDays(null, monday))
        assertEquals(26, TrainingMilestones.pauseDays(monday - 26, monday))
    }

    @Test
    fun atMostTwoFactsAreShown() {
        val facts = CompletionFacts(
            trainingNumber = 100,
            firstTrainingEpochDay = 0,
            isJubilee = true,
            weeklyGoal = WeeklyGoalEngine.evaluate(monday, WeekStart.MONDAY, emptyList(), goal3),
            pauseDays = 30,
        )

        assertEquals(listOf(CompletionFactKind.WEEKLY_GOAL, CompletionFactKind.JUBILEE), facts.rows)
    }

    @Test
    fun workoutsOnOneDayFollowTheirStart() {
        val sessions = listOf(session(9, monday, order = 2), session(4, monday, order = 1))

        assertEquals(2, TrainingMilestones.completionFacts(9, sessions, WeekStart.MONDAY, null)!!.trainingNumber)
        assertTrue(TrainingMilestones.completionFacts(42, sessions, WeekStart.MONDAY, null) == null)
    }
}
