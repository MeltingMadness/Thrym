package com.thrym.shared.recognition

import com.thrym.shared.workout.SessionExerciseRecords
import com.thrym.shared.workout.SessionRecordLine
import com.thrym.shared.workout.SessionRecordSummary
import com.thrym.shared.workout.SessionRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionHighlightsTest {

    private fun line(type: String, value: Double, previous: Double?) = SessionRecordLine(type, value, previous, setId = 1)

    private fun exercise(id: Long, vararg lines: SessionRecordLine, firstTime: Boolean = false) =
        SessionExerciseRecords(id, firstTime, lines.toList(), bestSetId = 1)

    private fun facts(jubilee: Boolean = false, pauseDays: Int? = null, goal: Boolean = false) = CompletionFacts(
        trainingNumber = 100,
        firstTrainingEpochDay = 0,
        isJubilee = jubilee,
        weeklyGoal = if (goal) WeeklyGoalStatus(3, 3, true, 2, 0, 2, emptyList()) else null,
        pauseDays = pauseDays,
    )

    private val load100 = StrengthMark(StrengthMarkKind.LOAD, 100, imperial = false)

    @Test
    fun nothingWonMeansNoHighlights() {
        val summary = SessionRecordSummary(listOf(exercise(1, firstTime = true)))

        val selection = CompletionHighlights.select(summary, emptyMap(), facts())

        assertTrue(selection.isEmpty)
        assertEquals(0, selection.more)
    }

    @Test
    fun eachExerciseShowsItsStrongestMomentOnce() {
        val summary = SessionRecordSummary(
            listOf(
                exercise(1, line(SessionRecords.MAX_WEIGHT, 100.0, 97.5), line(SessionRecords.MAX_E1RM, 116.7, 113.0)),
                exercise(2, line(SessionRecords.MAX_REPS, 12.0, 10.0), line(SessionRecords.MAX_VOLUME, 2000.0, 1800.0)),
            )
        )

        val shown = CompletionHighlights.select(summary, mapOf(1L to listOf(load100)), null).shown

        assertEquals(listOf(CompletionHighlightKind.MARK, CompletionHighlightKind.RECORD), shown.map { it.kind })
        assertEquals(load100, shown[0].mark)
        assertEquals(SessionRecords.MAX_REPS, shown[1].record?.type)
    }

    @Test
    fun orderIsJubileeComebackMarksRecordsWeeklyGoalAndCapsAtThree() {
        val summary = SessionRecordSummary(
            listOf(
                exercise(1, line(SessionRecords.MAX_WEIGHT, 52.5, 50.0)),
                exercise(2, line(SessionRecords.MAX_WEIGHT, 100.0, 95.0)),
            )
        )

        val selection = CompletionHighlights.select(summary, mapOf(3L to listOf(load100)), facts(jubilee = true, pauseDays = 30, goal = true))

        assertEquals(
            listOf(CompletionHighlightKind.JUBILEE, CompletionHighlightKind.COMEBACK, CompletionHighlightKind.MARK),
            selection.shown.map { it.kind },
        )
        assertEquals(3, selection.more)
    }

    @Test
    fun recordsOfTheSameTypeSortByRelativeGainThenWorkoutOrder() {
        val summary = SessionRecordSummary(
            listOf(
                exercise(1, line(SessionRecords.MAX_WEIGHT, 102.5, 100.0)),
                exercise(2, line(SessionRecords.MAX_WEIGHT, 22.5, 20.0)),
                exercise(3, line(SessionRecords.MAX_WEIGHT, 22.5, 20.0)),
                exercise(4, line(SessionRecords.MAX_REPS, 20.0, 10.0)),
            )
        )

        val shown = CompletionHighlights.select(summary, emptyMap(), null).shown

        assertEquals(listOf(2L, 3L, 1L), shown.map { it.exerciseId })
    }
}
