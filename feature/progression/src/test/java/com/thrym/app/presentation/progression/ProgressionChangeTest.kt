package com.thrym.app.presentation.progression

import com.thrym.app.domain.model.ProgressionTarget
import org.junit.Assert.*
import org.junit.Test

class ProgressionChangeTest {
    private val source = ProgressionTarget(3, 8, 100.0)

    @Test fun weightUpAndDown() {
        val up = progressionChange(source, source.copy(weightKg = 102.5))
        assertEquals(ProgressionChangeKind.WEIGHT, up.kind)
        assertEquals(ProgressionChangeDirection.UP, up.direction)
        val down = progressionChange(source, source.copy(weightKg = 95.0))
        assertEquals(ProgressionChangeKind.WEIGHT, down.kind)
        assertEquals(ProgressionChangeDirection.DOWN, down.direction)
    }
    @Test fun repsAndSets() {
        val reps = progressionChange(source, source.copy(reps = 9))
        assertEquals(ProgressionChangeKind.REPS, reps.kind)
        assertEquals(ProgressionChangeDirection.UP, reps.direction)
        assertEquals(8 to 9, reps.fromCount to reps.toCount)
        val sets = progressionChange(source, source.copy(sets = 4))
        assertEquals(ProgressionChangeKind.SETS, sets.kind)
        assertEquals(3 to 4, sets.fromCount to sets.toCount)
    }
    @Test fun unchangedAndTolerance() {
        assertEquals(ProgressionChangeKind.NONE, progressionChange(source, source).kind)
        assertEquals(ProgressionChangeDirection.SAME, progressionChange(source, source.copy(weightKg = 100.005)).direction)
        assertEquals(ProgressionChangeKind.NONE, progressionChange(source, source.copy(weightKg = 100.01)).kind)
        assertEquals(ProgressionChangeKind.WEIGHT, progressionChange(source, source.copy(weightKg = 100.011)).kind)
    }
    @Test fun weightLeadsWithOtherChanges() {
        val result = progressionChange(source, source.copy(weightKg = 102.5, reps = 9, sets = 4))
        assertEquals(ProgressionChangeKind.WEIGHT, result.kind)
        assertEquals(8 to 9, result.alsoReps)
        assertEquals(3 to 4, result.alsoSets)
    }
}
