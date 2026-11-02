package com.thrym.shared.workout

import com.thrym.shared.model.UnitSystem
import com.thrym.shared.recognition.StrengthMark
import com.thrym.shared.recognition.StrengthMarkKind
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionSetBadgesTest {

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

    private fun badges(sets: List<RecordSetInput>, category: String = "KURZHANTEL", addedLoad: Boolean = false) =
        SessionSetBadges.forExercise(today, sets, category, UnitSystem.METRIC, addedLoad)

    @Test
    fun theSetHoldingARecordGetsAMarker() {
        val sets = listOf(set(1, 1, 30.0), set(2, today, 30.0), set(3, today, 32.5))

        assertEquals(listOf(SessionSetBadge(3, null)), badges(sets))
    }

    @Test
    fun differentRecordsMarkEachHoldingSet() {
        // Set 2 beats the reps, set 3 the weight and the e1RM.
        val sets = listOf(set(1, 1, 30.0, reps = 8), set(2, today, 25.0, reps = 12), set(3, today, 32.5, reps = 6))

        assertEquals(listOf(SessionSetBadge(2, null), SessionSetBadge(3, null)), badges(sets))
    }

    @Test
    fun aMarkReplacesTheRecordMarkerOnItsFirstSet() {
        val sets = listOf(set(1, 1, 95.0), set(2, today, 100.0, reps = 3), set(3, today, 100.0, reps = 6))

        val result = badges(sets, category = "LANGHANTEL")

        assertEquals(
            listOf(
                SessionSetBadge(2, StrengthMark(StrengthMarkKind.LOAD, 100, imperial = false)),
                SessionSetBadge(3, null),
            ),
            result,
        )
    }

    @Test
    fun firstWorkoutTiesAndWarmUpsMarkNothing() {
        assertEquals(emptyList(), badges(listOf(set(1, today, 100.0))))
        assertEquals(emptyList(), badges(listOf(set(1, 1, 100.0), set(2, today, 100.0))))
        assertEquals(emptyList(), badges(listOf(set(1, 1, 100.0), set(2, today, 110.0, warmup = true))))
    }

    @Test
    fun addedLoadHasNoE1rmMarker() {
        // 18 × 8 beats only the e1RM (22.8 over 22), which an added load does not count.
        val sets = listOf(set(1, 1, 20.0, reps = 3), set(2, 1, 10.0, reps = 10), set(3, today, 18.0, reps = 8))

        assertEquals(emptyList(), badges(sets, category = "EIGENGEWICHT", addedLoad = true))
        assertEquals(listOf(SessionSetBadge(3, null)), badges(sets, category = "KURZHANTEL", addedLoad = false))
    }
}
