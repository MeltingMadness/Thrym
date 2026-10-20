package com.thrym.shared.recognition

import com.thrym.shared.model.UnitSystem
import com.thrym.shared.workout.RecordSetInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StrengthMarksTest {

    private val today = 9L

    private fun set(id: Long, session: Long, weight: Double, reps: Int = 5, warmup: Boolean = false, at: Long = id) =
        RecordSetInput(
            id = id,
            sessionId = session,
            exerciseId = 1,
            weightKg = weight,
            reps = reps,
            setType = if (warmup) "WARMUP" else "WORKING",
            completedAtEpochMillis = at,
            sessionFinished = session != today,
        )

    @Test
    fun aBarbellWorkoutCrossingAMarkNamesTheHighest() {
        val sets = listOf(set(1, 1, 87.5), set(2, today, 92.5), set(3, today, 100.0, reps = 3))

        val marks = StrengthMarks.sessionMarks(today, sets, "LANGHANTEL", UnitSystem.METRIC)

        assertEquals(listOf(StrengthMark(StrengthMarkKind.LOAD, 100, imperial = false)), marks)
    }

    @Test
    fun warmUpsTiesAndFirstWorkoutsSetNoMark() {
        val warmup = listOf(set(1, 1, 95.0), set(2, today, 100.0, warmup = true))
        val tie = listOf(set(1, 1, 100.0), set(2, today, 102.5))
        val first = listOf(set(1, today, 100.0))

        assertEquals(emptyList(), StrengthMarks.sessionMarks(today, warmup, "LANGHANTEL", UnitSystem.METRIC))
        assertEquals(emptyList(), StrengthMarks.sessionMarks(today, tie, "LANGHANTEL", UnitSystem.METRIC))
        assertEquals(emptyList(), StrengthMarks.sessionMarks(today, first, "LANGHANTEL", UnitSystem.METRIC))
    }

    @Test
    fun dumbbellsAndMachinesHaveNoMarks() {
        val sets = listOf(set(1, 1, 20.0), set(2, today, 40.0))

        assertEquals(emptyList(), StrengthMarks.sessionMarks(today, sets, "KURZHANTEL", UnitSystem.METRIC))
        assertEquals(emptyList(), StrengthMarks.sessionMarks(today, sets, "MASCHINE", UnitSystem.METRIC))
    }

    @Test
    fun poundsUseThePlateLadderAndSurviveRounding() {
        // 225 lb logged and stored as 102.06 kg.
        val sets = listOf(set(1, 1, 84.0), set(2, today, 102.06))

        val marks = StrengthMarks.sessionMarks(today, sets, "LANGHANTEL", UnitSystem.IMPERIAL)

        assertEquals(listOf(StrengthMark(StrengthMarkKind.LOAD, 225, imperial = true)), marks)
    }

    @Test
    fun bodyweightHasRepsAndAddedLoadLadders() {
        val sets = listOf(
            set(1, 1, 0.0, reps = 8), set(2, 1, 5.0, reps = 5),
            set(3, today, 0.0, reps = 10), set(4, today, 10.0, reps = 4),
        )

        val marks = StrengthMarks.sessionMarks(today, sets, "EIGENGEWICHT", UnitSystem.METRIC)

        assertEquals(
            listOf(
                StrengthMark(StrengthMarkKind.ADDED_LOAD, 10, imperial = false),
                StrengthMark(StrengthMarkKind.REPS, 10, imperial = false),
            ),
            marks
        )
    }

    @Test
    fun theLiveMarkOnlyAppearsOnce() {
        val history = set(1, 1, 95.0)
        val before = listOf(history, set(2, today, 97.5))
        val after = before + set(3, today, 100.0)
        val again = after + set(4, today, 100.0)

        assertEquals(
            StrengthMark(StrengthMarkKind.LOAD, 100, imperial = false),
            StrengthMarks.liveMark(today, before, after, "LANGHANTEL", UnitSystem.METRIC)
        )
        assertNull(StrengthMarks.liveMark(today, after, again, "LANGHANTEL", UnitSystem.METRIC))
        assertNull(StrengthMarks.liveMark(today, emptyList(), listOf(set(5, today, 100.0)), "LANGHANTEL", UnitSystem.METRIC))
    }

    @Test
    fun theHistoryDatesEachMarkByItsFirstWorkSet() {
        val sets = listOf(
            set(1, 1, 60.0, warmup = true, at = 100),
            set(2, 1, 75.0, at = 110),
            set(3, 2, 82.5, at = 200),
            set(4, 3, 80.0, at = 300),
        )

        val ladder = StrengthMarks.history(sets, "LANGHANTEL", UnitSystem.METRIC).single()

        assertEquals(listOf(30, 40, 50, 60, 70, 80), ladder.reached.map { it.mark.value })
        assertEquals(listOf(110L, 110L, 110L, 110L, 110L, 200L), ladder.reached.map { it.reachedAtEpochMillis })
        assertEquals(90, ladder.next?.value)
        assertEquals(emptyList(), StrengthMarks.history(listOf(set(1, 1, 20.0)), "LANGHANTEL", UnitSystem.METRIC))
    }
}
