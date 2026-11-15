package com.thrym.app.presentation.workout

import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.model.WorkoutSet
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class WorkoutFlowParityTest {
    @Test fun finishLeadsOnlyWhenThereAreConfirmedSetsAndNoOpenSlots() {
        assertTrue(finishWorkoutIsPrimary(0, 1))
        assertFalse(finishWorkoutIsPrimary(1, 1))
        assertFalse(finishWorkoutIsPrimary(0, 0))
    }
    @Test fun recentsUseFiveLatestSessionsAndDeduplicate() {
        val start = LocalDateTime.of(2026, 1, 1, 10, 0)
        val sessions = (1L..6L).map { WorkoutSession(id = it, startTime = start.plusDays(it), endTime = start.plusDays(it).plusHours(1)) }
        val sets = sessions.map { WorkoutSet(id = it.id, sessionId = it.id, exerciseId = if (it.id == 5L) 6L else it.id, setNumber = 1, reps = 8, weightKg = 10.0, completedAt = it.startTime) }
        assertEquals(listOf(6L, 4L, 3L, 2L), recentExerciseIds(sessions, sets))
    }
    @Test fun recentsSkipArchivedBeforeApplyingLimit() {
        val exercises = (1L..10L).map { Exercise(id = it, name = "Exercise $it", primaryMuscleGroup = MuscleGroup.BRUST, category = ExerciseCategory.LANGHANTEL, isArchived = it == 1L) }
        assertEquals((2L..9L).toList(), selectRecentExercises((1L..10L).toList(), exercises).map { it.id })
    }
}
