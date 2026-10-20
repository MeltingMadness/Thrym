package com.thrym.app.presentation.plans

import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.PlanExercise
import com.thrym.app.domain.model.TrainingPlan
import com.thrym.app.fakes.FakeExerciseRepository
import com.thrym.app.fakes.FakeTrainingPlanRepository
import com.thrym.app.fakes.FakeWorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingPlanListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var planRepo: FakeTrainingPlanRepository
    private lateinit var exerciseRepo: FakeExerciseRepository
    private lateinit var workoutRepo: FakeWorkoutRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        planRepo = FakeTrainingPlanRepository()
        exerciseRepo = FakeExerciseRepository()
        workoutRepo = FakeWorkoutRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadPlans erzeugt keine Exercise N+1 Lookups pro Plan`() = runTest {
        exerciseRepo.addExercise(
            Exercise(
                id = 1L,
                name = "Bankdruecken",
                primaryMuscleGroup = MuscleGroup.BRUST,
                category = ExerciseCategory.LANGHANTEL
            )
        )
        exerciseRepo.addExercise(
            Exercise(
                id = 2L,
                name = "Rudern",
                primaryMuscleGroup = MuscleGroup.RUECKEN,
                category = ExerciseCategory.LANGHANTEL
            )
        )

        val sharedExercises = listOf(
            PlanExercise(exerciseId = 1L, orderIndex = 0),
            PlanExercise(exerciseId = 2L, orderIndex = 1)
        )
        planRepo.savePlan(TrainingPlan(name = "Plan A", exercises = sharedExercises))
        planRepo.savePlan(TrainingPlan(name = "Plan B", exercises = sharedExercises))

        val vm = TrainingPlanListViewModel(planRepo, exerciseRepo, workoutRepo, strings = com.thrym.app.fakes.TestUiStrings)

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.uiState.value.plans.size)
        assertEquals(0, exerciseRepo.getExerciseByIdCallCount)
    }

    @Test
    fun `startPlanWorkout startet neue Session wenn keine aktive Session existiert`() = runTest {
        val vm = TrainingPlanListViewModel(planRepo, exerciseRepo, workoutRepo, strings = com.thrym.app.fakes.TestUiStrings)
        testDispatcher.scheduler.advanceUntilIdle()
        val plan = TrainingPlan(id = 101L, name = "Push", exercises = emptyList())
        var createdSessionId: Long? = null
        var createdPlanId: Long? = null

        vm.startPlanWorkout(plan) { sessionId, planId ->
            createdSessionId = sessionId
            createdPlanId = planId
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(createdSessionId)
        assertEquals(101L, createdPlanId)
        val session = workoutRepo.getSessionById(createdSessionId!!)
        assertEquals(101L, session?.planId)
    }

    @Test
    fun `startPlanWorkout blockiert wenn anderes aktives Training existiert`() = runTest {
        workoutRepo.addSession(
            com.thrym.app.domain.model.WorkoutSession(
                id = 7L,
                startTime = LocalDateTime.now(),
                name = "Active Session",
                planId = 999L
            ),
            isActive = true
        )
        val vm = TrainingPlanListViewModel(planRepo, exerciseRepo, workoutRepo, strings = com.thrym.app.fakes.TestUiStrings)
        testDispatcher.scheduler.advanceUntilIdle()
        val plan = TrainingPlan(id = 101L, name = "Push", exercises = emptyList())
        var callbackCalled = false

        vm.startPlanWorkout(plan) { _, _ ->
            callbackCalled = true
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(callbackCalled)
        assertEquals(com.thrym.app.fakes.TestUiStrings.get(com.thrym.feature.plans.R.string.plan_workout_already_active), vm.uiState.value.error)
        assertEquals(7L, workoutRepo.getActiveSession()?.id)
    }

    @Test
    fun `loadPlans liefert zuletzt trainiert`() = runTest {
        val pushId = planRepo.savePlan(TrainingPlan(name = "Push", exercises = emptyList()))
        val pullId = planRepo.savePlan(TrainingPlan(name = "Pull", exercises = emptyList()))
        workoutRepo.addSession(
            com.thrym.app.domain.model.WorkoutSession(
                id = 1L,
                startTime = LocalDateTime.now().minusDays(3),
                endTime = LocalDateTime.now().minusDays(3).plusHours(1),
                name = "Push",
                planId = pushId
            )
        )

        val vm = TrainingPlanListViewModel(planRepo, exerciseRepo, workoutRepo, strings = com.thrym.app.fakes.TestUiStrings)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(3L, state.plans.first { it.plan.id == pushId }.lastDoneDaysAgo)
        assertEquals(null, state.plans.first { it.plan.id == pullId }.lastDoneDaysAgo)
    }
}
