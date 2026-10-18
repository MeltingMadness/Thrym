package com.thrym.app.presentation.workout

import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.ProgressionConfig
import com.thrym.app.domain.model.ProgressionTarget
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.shared.workout.SessionExerciseOrder
import com.thrym.shared.workout.SessionOrderMove
import com.thrym.shared.workout.SessionOrderRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseGroupArrangementTest {

    @Test
    fun `without a stored order the groups follow the default adjacency grouping`() {
        val rows = listOf(
            planned(1),
            planned(2, group = 1),
            planned(3, group = 1),
            planned(4, group = 2),
            adHoc(7),
        )

        val groups = arrangeExerciseGroups(rows, emptyList())

        assertEquals(
            listOf(
                "single-planned-1",
                "superset-1-planned-2-planned-3",
                "single-planned-4",
                "single-adhoc-7",
            ),
            groups.map { it.key }
        )
        assertEquals(listOf(null, 1, null, null), groups.map { it.supersetGroupId })
        assertEquals(
            listOf(
                listOf(WorkoutExerciseKey.Planned(1)),
                listOf(WorkoutExerciseKey.Planned(2), WorkoutExerciseKey.Planned(3)),
                listOf(WorkoutExerciseKey.Planned(4)),
                listOf(WorkoutExerciseKey.AdHoc(7)),
            ),
            groups.map { group -> group.exercises.map { it.key } }
        )
    }

    @Test
    fun `a stored order moves whole blocks and maps every key back to its row`() {
        val rows = listOf(planned(1), planned(2, group = 1), planned(3, group = 1), adHoc(7))

        val groups = arrangeExerciseGroups(
            rows,
            listOf("adhoc-7", "planned-3", "planned-2", "planned-1")
        )

        assertEquals(
            listOf("single-adhoc-7", "superset-1-planned-2-planned-3", "single-planned-1"),
            groups.map { it.key }
        )
        // The superset keeps its inner order and its rows are the same objects as the input.
        assertEquals(listOf(rows[1], rows[2]), groups[1].exercises)
        assertEquals(1, groups[1].supersetGroupId)
        assertEquals(rows, groups.flatMap { it.exercises }.sortedBy { rows.indexOf(it) })
    }

    @Test
    fun `session order rows sort planned targets by order index and id then add ad hoc exercises once`() {
        val targets = listOf(
            target(id = 30, orderIndex = 1, group = 4),
            target(id = 20, orderIndex = 0),
            target(id = 10, orderIndex = 1, group = 4),
        )
        val added = listOf(exercise(8), exercise(5), exercise(8))

        val rows = sessionOrderRows(targets, added)

        assertEquals(
            listOf(
                SessionOrderRow("planned-20", null, planned = true),
                SessionOrderRow("planned-10", 4, planned = true),
                SessionOrderRow("planned-30", 4, planned = true),
                SessionOrderRow("adhoc-8", null, planned = false),
                SessionOrderRow("adhoc-5", null, planned = false),
            ),
            rows
        )
    }

    @Test
    fun `moves computed from the targets match the groups the screen shows`() {
        val targets = listOf(
            target(id = 1, orderIndex = 0),
            target(id = 2, orderIndex = 1, group = 1),
            target(id = 3, orderIndex = 2, group = 1),
            target(id = 4, orderIndex = 3),
        )
        val added = listOf(exercise(9))
        val screenRows = targets.map { planned(it.id, group = it.supersetGroupId) } + adHoc(9)

        var stored = emptyList<String>()
        repeat(2) {
            stored = SessionExerciseOrder.move(
                SessionExerciseOrder.arrange(sessionOrderRows(targets, added), stored),
                key = "adhoc-9",
                direction = SessionOrderMove.UP
            ) ?: error("adhoc-9 should still be movable")
        }

        assertEquals(
            listOf("single-planned-1", "single-adhoc-9", "superset-1-planned-2-planned-3", "single-planned-4"),
            arrangeExerciseGroups(screenRows, stored).map { it.key }
        )
        assertNull(
            SessionExerciseOrder.move(
                SessionExerciseOrder.arrange(sessionOrderRows(targets, added), stored),
                key = "planned-1",
                direction = SessionOrderMove.UP
            )
        )
    }

    private fun planned(id: Long, group: Int? = null) = ExerciseWithSets(
        key = WorkoutExerciseKey.Planned(id),
        exercise = exercise(100 + id),
        sets = emptyList(),
        planTarget = target(id = id, orderIndex = id.toInt(), group = group)
    )

    private fun adHoc(exerciseId: Long) = ExerciseWithSets(
        key = WorkoutExerciseKey.AdHoc(exerciseId),
        exercise = exercise(exerciseId),
        sets = emptyList()
    )

    private fun exercise(id: Long) = Exercise(
        id = id,
        name = "Uebung $id",
        primaryMuscleGroup = MuscleGroup.BRUST,
        category = ExerciseCategory.LANGHANTEL
    )

    private fun target(id: Long, orderIndex: Int, group: Int? = null) = WorkoutPlanTarget(
        id = id,
        sessionId = 1L,
        planId = 1L,
        exerciseId = 100 + id,
        orderIndex = orderIndex,
        supersetGroupId = group,
        target = ProgressionTarget(sets = 3, reps = 8, weightKg = 60.0),
        config = ProgressionConfig.Manual()
    )
}
