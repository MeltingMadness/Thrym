package com.thrym.app.presentation.workout

import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.ProgressionTarget
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.shared.plans.PlannedSet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedSetsDoneTest {

    @Test
    fun `a free workout is never done`() {
        assertFalse(emptyList<ExerciseWithSets>().allPlannedSetsDone())
        assertFalse(listOf(adHoc(7, SetType.NORMAL, SetType.NORMAL)).allPlannedSetsDone())
    }

    @Test
    fun `an open slot keeps the workout open and the last logged slot completes it`() {
        assertFalse(listOf(planned(1, sets = 2, SetType.NORMAL)).allPlannedSetsDone())
        assertFalse(
            listOf(planned(1, sets = 1, SetType.NORMAL), planned(2, sets = 1)).allPlannedSetsDone()
        )
        assertTrue(
            listOf(planned(1, sets = 1, SetType.NORMAL), planned(2, sets = 2, SetType.NORMAL, SetType.NORMAL))
                .allPlannedSetsDone()
        )
    }

    @Test
    fun `sets beyond the plan and ad-hoc rows change nothing`() {
        val done = planned(1, sets = 1, SetType.NORMAL, SetType.NORMAL, SetType.DROP_SET, SetType.WARMUP)
        assertTrue(listOf(done).allPlannedSetsDone())
        // An ad-hoc exercise has no known end, so its sets never count as open.
        assertTrue(listOf(done, adHoc(7)).allPlannedSetsDone())
        assertTrue(listOf(done, adHoc(7, SetType.NORMAL)).allPlannedSetsDone())
    }

    @Test
    fun `an explicit warm-up slot must be filled and a failure set fills a normal slot`() {
        val slots = listOf(PlannedSet(kind = "WARMUP"), PlannedSet(kind = "NORMAL"))
        assertFalse(listOf(planned(1, slots, SetType.NORMAL)).allPlannedSetsDone())
        assertTrue(listOf(planned(1, slots, SetType.WARMUP, SetType.FAILURE)).allPlannedSetsDone())
    }

    @Test
    fun `a set without reps fills no slot`() {
        val row = planned(1, sets = 1).copy(sets = listOf(set(SetType.NORMAL, reps = 0)))
        assertFalse(listOf(row).allPlannedSetsDone())
    }

    @Test
    fun `the deload-adjusted target decides`() {
        // The view model hands the screen the adjusted target: 4 sets become 2.
        val halved = applyDeloadToTarget(target(1, sets = 4), DeloadMode.HALVE_SET_VOLUME)
        val row = ExerciseWithSets(
            key = WorkoutExerciseKey.Planned(1),
            exercise = exercise(101),
            sets = listOf(set(SetType.NORMAL), set(SetType.NORMAL)),
            planTarget = halved,
            originalPlanTarget = target(1, sets = 4)
        )
        assertTrue(listOf(row).allPlannedSetsDone())
        assertFalse(listOf(row.copy(sets = row.sets.take(1))).allPlannedSetsDone())
    }

    @Test
    fun `a target without slots is not done on its own`() {
        assertFalse(listOf(planned(1, sets = 0)).allPlannedSetsDone())
        assertFalse(listOf(planned(1, sets = 0, SetType.NORMAL), adHoc(7, SetType.NORMAL)).allPlannedSetsDone())
    }

    private fun planned(id: Long, sets: Int = 3, vararg logged: SetType) = ExerciseWithSets(
        key = WorkoutExerciseKey.Planned(id),
        exercise = exercise(100 + id),
        sets = logged.map { set(it) },
        planTarget = target(id, sets)
    )

    private fun planned(id: Long, slots: List<PlannedSet>, vararg logged: SetType) = ExerciseWithSets(
        key = WorkoutExerciseKey.Planned(id),
        exercise = exercise(100 + id),
        sets = logged.map { set(it) },
        planTarget = target(id, slots.size).copy(setTargets = slots)
    )

    private fun adHoc(exerciseId: Long, vararg logged: SetType) = ExerciseWithSets(
        key = WorkoutExerciseKey.AdHoc(exerciseId),
        exercise = exercise(exerciseId),
        sets = logged.map { set(it) }
    )

    private fun set(type: SetType, reps: Int = 8) = WorkoutSet(
        sessionId = 1L,
        exerciseId = 101L,
        setNumber = 1,
        reps = reps,
        weightKg = 60.0,
        setType = type
    )

    private fun exercise(id: Long) = Exercise(
        id = id,
        name = "Uebung $id",
        primaryMuscleGroup = MuscleGroup.BRUST,
        category = ExerciseCategory.LANGHANTEL
    )

    private fun target(id: Long, sets: Int) = WorkoutPlanTarget(
        id = id,
        sessionId = 1L,
        planId = 1L,
        exerciseId = 100 + id,
        orderIndex = id.toInt(),
        supersetGroupId = null,
        target = ProgressionTarget(sets = sets, reps = 8, weightKg = 60.0),
        config = ProgressionConfig.Manual()
    )
}
