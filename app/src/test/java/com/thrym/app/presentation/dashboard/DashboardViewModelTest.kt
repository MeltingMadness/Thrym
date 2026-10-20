package com.thrym.app.presentation.dashboard

import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.SetType
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.repository.StatisticsRepository
import com.thrym.app.domain.model.MetaTrainingPlan
import com.thrym.app.domain.model.MetaTrainingPlanItem
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.DeloadAssessment
import com.thrym.app.domain.model.DeloadMode
import com.thrym.app.domain.model.DeloadSignal
import com.thrym.app.domain.model.MetaPlanRotationEvent
import com.thrym.app.domain.model.ProgressionDecisionResult
import com.thrym.app.domain.model.ProgressionGenerationResult
import com.thrym.app.domain.model.ProgressionSuggestion
import com.thrym.app.domain.model.ProgressionTarget
import com.thrym.app.domain.model.TrainingPlan
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.app.domain.model.WorkoutSession
import com.thrym.app.domain.repository.MetaTrainingPlanRepository
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.ProgressionRepository
import com.thrym.app.domain.repository.ReadinessProjectionSource
import com.thrym.app.domain.repository.ReadinessRepository
import com.thrym.app.domain.util.AppLogger
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.fakes.FakeAppPreferencesRepository
import com.thrym.app.fakes.FakeDeloadRepository
import com.thrym.app.fakes.FakeExerciseRepository
import com.thrym.app.fakes.FakeMetaTrainingPlanRepository
import com.thrym.app.fakes.FakeStatisticsRepository
import com.thrym.app.fakes.FakeTrainingPlanRepository
import com.thrym.app.fakes.FakeWorkoutRepository
import com.thrym.app.fakes.TestUiStrings
import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.backup.CURRENT_BACKUP_SCHEMA_VERSION
import com.thrym.shared.readinessdata.ReadinessCheckIn
import com.thrym.shared.readinessdata.ReadinessData
import com.thrym.shared.readinessdata.SetIntention
import com.thrym.shared.readinessdata.SetIntentionRecord
import com.thrym.shared.readiness.TrainingTrendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import io.mockk.every
import kotlinx.datetime.LocalDate as KxLocalDate
import io.mockk.mockkObject
import io.mockk.unmockkObject

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var workoutRepo: FakeWorkoutRepository
    private lateinit var exerciseRepo: FakeExerciseRepository
    private lateinit var statsRepo: FakeStatisticsRepository
    private lateinit var preferencesRepo: FakeAppPreferencesRepository
    private lateinit var planRepo: FakeTrainingPlanRepository
    private lateinit var metaPlanRepo: MetaTrainingPlanRepository
    private lateinit var progressionRepository: FakeDashboardProgressionRepository
    private lateinit var readinessSource: FakeReadinessProjectionSource
    private lateinit var readinessRepo: FakeReadinessRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        workoutRepo = FakeWorkoutRepository()
        exerciseRepo = FakeExerciseRepository()
        statsRepo = FakeStatisticsRepository()
        preferencesRepo = FakeAppPreferencesRepository()
        planRepo = FakeTrainingPlanRepository()
        metaPlanRepo = FakeMetaTrainingPlanRepository(workoutRepo)
        progressionRepository = FakeDashboardProgressionRepository()
        readinessSource = FakeReadinessProjectionSource()
        readinessRepo = FakeReadinessRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        statistics: StatisticsRepository = statsRepo,
        strings: com.thrym.app.presentation.common.UiStrings = TestUiStrings
    ) = DashboardViewModel(
        workoutRepo,
        statistics,
        exerciseRepo,
        preferencesRepo,
        planRepo,
        metaPlanRepo,
        progressionRepository,
        FakeDeloadRepository(),
        readinessSource,
        readinessRepo,
        strings = strings
    )

    @Test
    fun `free workout gets a dated name in the UI language`() = runTest {
        val today = LocalDate.now()
        for ((strings, datePattern) in listOf(TestUiStrings to "M/d", TestUiStrings.german to "dd.MM.")) {
            workoutRepo = FakeWorkoutRepository()
            val vm = createViewModel(strings = strings)
            testDispatcher.scheduler.advanceUntilIdle()

            var startedId: Long? = null
            vm.startNewWorkout { sessionId, _ -> startedId = sessionId }
            testDispatcher.scheduler.advanceUntilIdle()

            val expected = strings.get(
                com.thrym.feature.dashboard.R.string.dashboard_workout_auto_name,
                today.format(java.time.format.DateTimeFormatter.ofPattern(datePattern))
            )
            assertEquals(expected, workoutRepo.getActiveSession()?.name)
            assertEquals(startedId, workoutRepo.getActiveSession()?.id)
        }
        // German keeps the name format of earlier versions ("Training 10.02.").
        assertTrue(workoutRepo.getActiveSession()!!.name.startsWith("Training "))
    }

    @Test
    fun `loadDashboard nutzt Batch-Query statt N einzelne getExerciseById Calls`() = runTest {
        exerciseRepo.addExercise(com.thrym.app.domain.model.Exercise(id = 1L, name = "Kniebeuge", primaryMuscleGroup = com.thrym.app.domain.model.MuscleGroup.BEINE, category = com.thrym.app.domain.model.ExerciseCategory.LANGHANTEL))
        exerciseRepo.addExercise(com.thrym.app.domain.model.Exercise(id = 2L, name = "Bankdrücken", primaryMuscleGroup = com.thrym.app.domain.model.MuscleGroup.BRUST, category = com.thrym.app.domain.model.ExerciseCategory.LANGHANTEL))
        val now = LocalDateTime.now()
        statsRepo.addRecord(com.thrym.app.domain.model.PersonalRecord(id = 1L, exerciseId = 1L, type = com.thrym.app.domain.model.RecordType.MAX_WEIGHT, value = 100.0, achievedAt = now.minusHours(1)))
        statsRepo.addRecord(com.thrym.app.domain.model.PersonalRecord(id = 2L, exerciseId = 2L, type = com.thrym.app.domain.model.RecordType.MAX_WEIGHT, value = 80.0, achievedAt = now.minusHours(2)))

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, exerciseRepo.getExerciseByIdCallCount) // Must be 0 — batch used instead
        assertEquals(2, vm.uiState.value.recentRecords.size)
    }

    @Test
    fun `Dashboard laedt initial mit isLoading true`() = runTest {
        val vm = createViewModel()
        assertTrue(vm.uiState.value.isLoading)
    }

    @Test
    fun `Dashboard laedt erfolgreich`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `pending suggestion count updates live in dashboard state`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.uiState.value.pendingProgressionCount)

        progressionRepository.pendingCount.value = 3
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, vm.uiState.value.pendingProgressionCount)

        progressionRepository.pendingCount.value = 0
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, vm.uiState.value.pendingProgressionCount)
    }

    @Test
    fun `dashboard startup reconciles stale rows before retrying missing outcomes`() = runTest {
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("reconcile", "generateMissing"), progressionRepository.callLog.take(2))
    }

    @Test
    fun `coach startup failure does not hide ordinary dashboard data`() = runTest {
        workoutRepo.addSession(
            WorkoutSession(
                id = 501L,
                startTime = LocalDateTime.now().minusHours(1),
                endTime = LocalDateTime.now(),
                durationSeconds = 3600
            )
        )
        progressionRepository.startupError = IOException("disk")

        mockkObject(AppLogger)
        val state = try {
            every { AppLogger.w(any(), any(), any()) } returns Unit
            val vm = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()
            vm.uiState.first { !it.isLoading }
        } finally {
            unmockkObject(AppLogger)
        }

        assertEquals(1, state.workoutsThisWeek)
        assertNotNull(state.lastWorkout)
        assertNull(state.error)
        assertEquals(0, state.pendingProgressionCount)
    }

    @Test
    fun `metaplan combine streamt keine vollen Sessions sondern Aggregate`() = runTest {
        val planId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val metaId = metaPlanRepo.saveMetaPlan(MetaTrainingPlan(name = "Meta",
            items = listOf(MetaTrainingPlanItem(trainingPlanId = planId, orderIndex = 0))))
        val yesterday = LocalDateTime.of(LocalDate.now().minusDays(1), LocalTime.of(9, 0))
        workoutRepo.addSession(WorkoutSession(id = 99L, startTime = yesterday,
            endTime = yesterday.plusHours(1), durationSeconds = 3600,
            planId = planId, metaPlanId = metaId), isActive = false)

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, workoutRepo.getAllCompletedSessionsListCallCount)
        assertNotNull(vm.uiState.value.metaPlanOptions.firstOrNull { it.metaPlanId == metaId })
    }

    @Test
    fun `startNewWorkout erstellt Session und ruft Callback`() = runTest {
        val vm = createViewModel()
        var receivedId: Long? = null

        vm.startNewWorkout { id, planId -> receivedId = id }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue((receivedId ?: 0L) > 0L)
    }

    @Test
    fun `startNewWorkoutWithPlan creates session with plan name`() = runTest {
        val vm = createViewModel()
        var createdSessionId: Long? = null
        var planIdPass: Long? = null
        
        val testPlan = TrainingPlan(id = 99L, name = "My Test Plan", exercises = emptyList())
        
        vm.startNewWorkoutWithPlan(testPlan) { sessionId, planId ->
            createdSessionId = sessionId
            planIdPass = planId
        }
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertTrue(createdSessionId != null)
        assertEquals(99L, planIdPass)
        val session = workoutRepo.getSessionById(createdSessionId!!)
        assertEquals("My Test Plan", session?.name)
    }

    @Test
    fun `training plan options show last done across standalone and meta plan sessions`() = runTest {
        val planId = planRepo.savePlan(TrainingPlan(name = "Push A"))
        val standaloneDoneAt = LocalDateTime.of(LocalDate.now().minusDays(1), LocalTime.of(18, 0))
        val metaDoneAt = LocalDateTime.of(LocalDate.now().minusDays(4), LocalTime.of(7, 0))

        workoutRepo.addSession(
            WorkoutSession(
                id = 301L,
                startTime = metaDoneAt,
                endTime = metaDoneAt.plusHours(1),
                durationSeconds = 3600,
                planId = planId,
                metaPlanId = 900L
            ),
            isActive = false
        )
        workoutRepo.addSession(
            WorkoutSession(
                id = 302L,
                startTime = standaloneDoneAt,
                endTime = standaloneDoneAt.plusHours(1),
                durationSeconds = 3600,
                planId = planId
            ),
            isActive = false
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val planStatus = vm.uiState.value.trainingPlans.firstOrNull { it.plan.id == planId }
        assertNotNull(planStatus)
        assertEquals(1L, planStatus!!.lastDoneDaysAgo)
    }

    @Test
    fun `startNewWorkoutWithMetaPlan startet Session mit plan und meta ids`() = runTest {
        val planId = planRepo.savePlan(TrainingPlan(name = "Meta Subplan"))
        val metaPlanId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Meta 1",
                items = listOf(MetaTrainingPlanItem(trainingPlanId = planId, orderIndex = 0))
            )
        )
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        var createdSessionId: Long? = null
        var callbackPlanId: Long? = null
        var callbackMetaPlanId: Long? = null

        vm.startNewWorkoutWithMetaPlan(metaPlanId) { sessionId, planFromCallback, metaFromCallback ->
            createdSessionId = sessionId
            callbackPlanId = planFromCallback
            callbackMetaPlanId = metaFromCallback
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(createdSessionId)
        assertEquals(planId, callbackPlanId)
        assertEquals(metaPlanId, callbackMetaPlanId)
        val session = workoutRepo.getSessionById(createdSessionId!!)
        assertEquals(planId, session?.planId)
        assertEquals(metaPlanId, session?.metaPlanId)
    }

    @Test
    fun `startNewWorkoutWithMetaPlan setzt Fehler wenn kein gueltiger Unterplan vorhanden ist`() = runTest {
        val metaPlanId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Broken Meta",
                items = listOf(MetaTrainingPlanItem(trainingPlanId = 9999L, orderIndex = 0))
            )
        )
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        var callbackCalled = false

        vm.startNewWorkoutWithMetaPlan(metaPlanId) { _, _, _ ->
            callbackCalled = true
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(callbackCalled)
        assertEquals(com.thrym.app.fakes.TestUiStrings.get(com.thrym.feature.dashboard.R.string.dashboard_meta_plan_invalid), vm.uiState.value.error)
        assertNull(workoutRepo.getActiveSession())
    }

    @Test
    fun `week strip marks today as trained after a completed workout`() = runTest {
        val start = LocalDate.now().atStartOfDay()
        workoutRepo.addSession(
            WorkoutSession(
                id = 700L,
                startTime = start,
                endTime = start.plusHours(1),
                durationSeconds = 3600,
                name = "Heute"
            ),
            isActive = false
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val days = vm.uiState.value.weekDays
        assertEquals(7, days.size)
        val today = days.single { it.isToday }
        assertEquals(LocalDate.now(), today.date)
        assertTrue(today.trained)
        assertEquals(1, days.count { it.trained })
    }

    @Test
    fun `dashboard reloads stats when active workout is finished`() = runTest {
        val start = LocalDate.now().atStartOfDay()
        workoutRepo.addSession(
            WorkoutSession(
                id = 123L,
                startTime = start,
                endTime = null,
                durationSeconds = 0,
                name = "Active"
            ),
            isActive = true
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.activeSession)
        assertEquals(0, vm.uiState.value.workoutsThisWeek)

        workoutRepo.finishWorkout(123L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.activeSession)
        assertEquals(1, vm.uiState.value.workoutsThisWeek)
        assertEquals(1, vm.uiState.value.workoutsThisMonth)
        assertFalse(vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.lastWorkout)
    }

    @Test
    fun `dashboard reloads stats when completed sessions are deleted without active-session transition`() = runTest {
        val completed = LocalDate.now().atStartOfDay()
        workoutRepo.addSession(
            WorkoutSession(
                id = 900L,
                startTime = completed,
                endTime = completed.plusHours(1),
                durationSeconds = 3600,
                name = "Done"
            ),
            isActive = false
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.uiState.value.workoutsThisWeek)
        assertNotNull(vm.uiState.value.lastWorkout)

        workoutRepo.deleteSession(900L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.uiState.value.workoutsThisWeek)
        assertEquals(0, vm.uiState.value.workoutsThisMonth)
        assertNull(vm.uiState.value.lastWorkout)
    }

    @Test
    fun `meta plan rotation uses last completed subplan as anchor`() = runTest {
        val planAId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val planBId = planRepo.savePlan(TrainingPlan(name = "Plan B"))
        val planCId = planRepo.savePlan(TrainingPlan(name = "Plan C"))
        val metaId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Meta 1",
                items = listOf(
                    MetaTrainingPlanItem(trainingPlanId = planAId, orderIndex = 0),
                    MetaTrainingPlanItem(trainingPlanId = planBId, orderIndex = 1),
                    MetaTrainingPlanItem(trainingPlanId = planCId, orderIndex = 2)
                )
            )
        )

        val twoDaysAgo = LocalDateTime.of(LocalDate.now().minusDays(2), LocalTime.of(9, 0))
        val oneDayAgo = LocalDateTime.of(LocalDate.now().minusDays(1), LocalTime.of(9, 0))
        workoutRepo.addSession(
            WorkoutSession(
                id = 1,
                startTime = twoDaysAgo,
                endTime = twoDaysAgo.plusHours(1),
                durationSeconds = 3600,
                planId = planAId,
                metaPlanId = metaId
            ),
            isActive = false
        )
        workoutRepo.addSession(
            WorkoutSession(
                id = 2,
                startTime = oneDayAgo,
                endTime = oneDayAgo.plusHours(1),
                durationSeconds = 3600,
                planId = planBId,
                metaPlanId = metaId
            ),
            isActive = false
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val option = vm.uiState.value.metaPlanOptions.firstOrNull { it.metaPlanId == metaId }
        assertNotNull(option)
        assertEquals(planCId, option?.nextPlan?.id)
        assertEquals(listOf(planCId, planAId, planBId), option?.rotationPlans?.map { it.plan.id })
    }

    @Test
    fun `weekly volume returns eight anchored bins with gaps zero and date labels`() = runTest {
        val anchor = DayOfWeek.MONDAY
        val currentWeekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(anchor))
        val populatedWeek = currentWeekStart.minusWeeks(2)
        val earlierWeek = currentWeekStart.minusWeeks(5)
        val windowStart = currentWeekStart.minusWeeks(7)

        statsRepo.markSessionCompleted(1L)
        addWeeklySet(1L, populatedWeek.atStartOfDay(), sessionId = 1L)
        addWeeklySet(2L, populatedWeek.plusDays(2).atStartOfDay(), sessionId = 1L)
        addWeeklySet(3L, earlierWeek.plusDays(6).atStartOfDay(), sessionId = 1L)
        // A future week and a set before the eight-week window must not leak
        // into any bin, even though the fake repository returns completed rows.
        addWeeklySet(4L, currentWeekStart.plusWeeks(1).atStartOfDay(), sessionId = 1L)
        addWeeklySet(5L, windowStart.minusDays(1).atStartOfDay(), sessionId = 1L)

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val entries = vm.uiState.value.weeklyVolume
        val expectedStarts = (0L until 8L).map { windowStart.plusWeeks(it) }
        assertEquals(8, entries.size)
        assertEquals(expectedStarts, entries.map { it.first })
        assertEquals(1000.0, entries[expectedStarts.indexOf(populatedWeek)].second, 0.0001)
        assertEquals(500.0, entries[expectedStarts.indexOf(earlierWeek)].second, 0.0001)
        assertTrue(entries.filterIndexed { index, _ ->
            expectedStarts[index] != populatedWeek && expectedStarts[index] != earlierWeek
        }.all { it.second == 0.0 })
    }

    @Test
    fun `weekly volume follows Sunday week anchor`() = runTest {
        preferencesRepo.updateWeekStart(WeekStart.SUNDAY)
        val currentWeekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val populatedWeek = currentWeekStart.minusWeeks(1)
        val windowStart = currentWeekStart.minusWeeks(7)

        statsRepo.markSessionCompleted(1L)
        addWeeklySet(1L, populatedWeek.plusDays(1).atStartOfDay(), sessionId = 1L)

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val entries = vm.uiState.value.weeklyVolume
        assertEquals(8, entries.size)
        assertEquals(
            (0L until 8L).map { windowStart.plusWeeks(it) },
            entries.map { it.first }
        )
        assertEquals(500.0, entries[6].second, 0.0001)
    }

    @Test
    fun `startNewWorkout navigates to existing active session instead of assuming new plan`() = runTest {
        val active = WorkoutSession(id = 555L, startTime = LocalDateTime.now(), planId = 42L)
        workoutRepo.addSession(active, isActive = true)

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        var receivedId: Long? = null
        var receivedPlanId: Long? = null
        vm.startNewWorkout { id, planId ->
            receivedId = id
            receivedPlanId = planId
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(555L, receivedId)
        assertEquals(42L, receivedPlanId)
    }

    @Test
    fun `startNewWorkoutWithPlan navigates to matching active session instead of starting a new one`() = runTest {
        val plan = TrainingPlan(id = 42L, name = "Push")
        val active = WorkoutSession(id = 555L, startTime = LocalDateTime.now(), planId = plan.id)
        workoutRepo.addSession(active, isActive = true)

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        var receivedId: Long? = null
        vm.startNewWorkoutWithPlan(plan) { id, _ -> receivedId = id }
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(555L, receivedId)
    }

    @Test
    fun `startNewWorkoutWithPlan shows error when a different plan is already active`() = runTest {
        val plan = TrainingPlan(id = 42L, name = "Push")
        val active = WorkoutSession(id = 555L, startTime = LocalDateTime.now(), planId = 99L)
        workoutRepo.addSession(active, isActive = true)

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        var callbackCalled = false
        vm.startNewWorkoutWithPlan(plan) { _, _ -> callbackCalled = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(callbackCalled)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun `meta plan rotation exposes per-subplan last done days`() = runTest {
        val planAId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val planBId = planRepo.savePlan(TrainingPlan(name = "Plan B"))
        val metaId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Meta 2",
                items = listOf(
                    MetaTrainingPlanItem(trainingPlanId = planAId, orderIndex = 0),
                    MetaTrainingPlanItem(trainingPlanId = planBId, orderIndex = 1)
                )
            )
        )

        val threeDaysAgo = LocalDateTime.of(LocalDate.now().minusDays(3), LocalTime.of(9, 0))
        workoutRepo.addSession(
            WorkoutSession(
                id = 10,
                startTime = threeDaysAgo,
                endTime = threeDaysAgo.plusHours(1),
                durationSeconds = 3600,
                planId = planAId,
                metaPlanId = metaId
            ),
            isActive = false
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val option = vm.uiState.value.metaPlanOptions.firstOrNull { it.metaPlanId == metaId }
        assertNotNull(option)
        val statusByPlan = option!!.rotationPlans.associateBy({ it.plan.id }, { it.lastDoneDaysAgo })
        assertEquals(3L, statusByPlan[planAId])
        assertNull(statusByPlan[planBId])
    }

    @Test
    fun `skipCurrentMetaSubPlan wechselt zum naechsten Teilplan ohne Session`() = runTest {
        val planAId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val planBId = planRepo.savePlan(TrainingPlan(name = "Plan B"))
        val metaId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Meta Skip",
                items = listOf(
                    MetaTrainingPlanItem(trainingPlanId = planAId, orderIndex = 0),
                    MetaTrainingPlanItem(trainingPlanId = planBId, orderIndex = 1)
                )
            )
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val before = vm.uiState.value.metaPlanOptions.single()
        assertEquals(planAId, before.nextPlan?.id)
        assertTrue(before.canSkip)

        vm.skipCurrentMetaSubPlan(metaId)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(planBId, vm.uiState.value.metaPlanOptions.single().nextPlan?.id)
        assertNull(workoutRepo.getActiveSession())
        assertTrue(workoutRepo.getAllCompletedSessionsList().isEmpty())
    }

    @Test
    fun `skipCurrentMetaSubPlan ist bei genau einem Teilplan deaktiviert`() = runTest {
        val planAId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val metaId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Single Meta",
                items = listOf(MetaTrainingPlanItem(trainingPlanId = planAId, orderIndex = 0))
            )
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val option = vm.uiState.value.metaPlanOptions.single()
        assertFalse(option.canSkip)

        vm.skipCurrentMetaSubPlan(metaId)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(planAId, vm.uiState.value.metaPlanOptions.single().nextPlan?.id)
        assertTrue(metaPlanRepo.observeLastRotationEventPerMetaPlanSubPlan().first().isEmpty())
        assertNull(workoutRepo.getActiveSession())
    }

    @Test
    fun `skipCurrentMetaSubPlan blockiert bei aktiver Session`() = runTest {
        val planAId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val planBId = planRepo.savePlan(TrainingPlan(name = "Plan B"))
        val metaId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Meta Blocked",
                items = listOf(
                    MetaTrainingPlanItem(trainingPlanId = planAId, orderIndex = 0),
                    MetaTrainingPlanItem(trainingPlanId = planBId, orderIndex = 1)
                )
            )
        )
        workoutRepo.addSession(
            WorkoutSession(id = 555L, startTime = LocalDateTime.now(), planId = 99L),
            isActive = true
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.skipCurrentMetaSubPlan(metaId)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(555L, workoutRepo.getActiveSession()?.id)
        assertEquals(com.thrym.app.fakes.TestUiStrings.get(com.thrym.feature.dashboard.R.string.dashboard_workout_already_active), vm.uiState.value.error)
        assertTrue(metaPlanRepo.observeLastRotationEventPerMetaPlanSubPlan().first().isEmpty())
    }

    @Test
    fun `skipCurrentMetaSubPlan sperrt waehrend des Schreibvorgangs`() = runTest {
        metaPlanRepo = DelayingMetaPlanRepo(FakeMetaTrainingPlanRepository(workoutRepo))
        val planAId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val planBId = planRepo.savePlan(TrainingPlan(name = "Plan B"))
        val metaId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Meta Lock",
                items = listOf(
                    MetaTrainingPlanItem(trainingPlanId = planAId, orderIndex = 0),
                    MetaTrainingPlanItem(trainingPlanId = planBId, orderIndex = 1)
                )
            )
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.skipCurrentMetaSubPlan(metaId)
        testDispatcher.scheduler.runCurrent()

        assertEquals(metaId, vm.uiState.value.skippingMetaPlanId)

        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.skippingMetaPlanId)
        assertEquals(planBId, vm.uiState.value.metaPlanOptions.single().nextPlan?.id)
    }

    @Test
    fun `start waehrend laufendem Skip erzeugt keine Session`() = runTest {
        metaPlanRepo = DelayingMetaPlanRepo(FakeMetaTrainingPlanRepository(workoutRepo))
        val planAId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val planBId = planRepo.savePlan(TrainingPlan(name = "Plan B"))
        val metaId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Meta Start Lock",
                items = listOf(
                    MetaTrainingPlanItem(trainingPlanId = planAId, orderIndex = 0),
                    MetaTrainingPlanItem(trainingPlanId = planBId, orderIndex = 1)
                )
            )
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.skipCurrentMetaSubPlan(metaId)
        testDispatcher.scheduler.runCurrent()
        assertEquals(metaId, vm.uiState.value.skippingMetaPlanId)

        var started = false
        vm.startNewWorkoutWithMetaPlan(metaId) { _, _, _ -> started = true }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(started)
        assertNull(workoutRepo.getActiveSession())
        assertTrue(workoutRepo.getAllCompletedSessionsList().isEmpty())
        assertEquals(planBId, vm.uiState.value.metaPlanOptions.single().nextPlan?.id)
    }

    @Test
    fun `skipCurrentMetaSubPlan behandelt veralteten Vorschlag ohne Mutation`() = runTest {
        metaPlanRepo = RejectingMetaPlanRepo(FakeMetaTrainingPlanRepository(workoutRepo))
        val planAId = planRepo.savePlan(TrainingPlan(name = "Plan A"))
        val planBId = planRepo.savePlan(TrainingPlan(name = "Plan B"))
        val metaId = metaPlanRepo.saveMetaPlan(
            MetaTrainingPlan(
                name = "Meta Stale",
                items = listOf(
                    MetaTrainingPlanItem(trainingPlanId = planAId, orderIndex = 0),
                    MetaTrainingPlanItem(trainingPlanId = planBId, orderIndex = 1)
                )
            )
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.skipCurrentMetaSubPlan(metaId)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(planAId, vm.uiState.value.metaPlanOptions.single().nextPlan?.id)
        assertEquals(com.thrym.app.fakes.TestUiStrings.get(com.thrym.feature.dashboard.R.string.dashboard_plan_changed), vm.uiState.value.error)
        assertTrue(metaPlanRepo.observeLastRotationEventPerMetaPlanSubPlan().first().isEmpty())
        assertNull(workoutRepo.getActiveSession())
    }

    private class RejectingMetaPlanRepo(
        private val delegate: FakeMetaTrainingPlanRepository
    ) : MetaTrainingPlanRepository {
        override fun getAllMetaPlans(): Flow<List<MetaTrainingPlan>> = delegate.getAllMetaPlans()
        override fun observeLastRotationEventPerMetaPlanSubPlan(): Flow<List<MetaPlanRotationEvent>> =
            delegate.observeLastRotationEventPerMetaPlanSubPlan()
        override suspend fun getMetaPlanById(id: Long): MetaTrainingPlan? = delegate.getMetaPlanById(id)
        override suspend fun saveMetaPlan(plan: MetaTrainingPlan): Long = delegate.saveMetaPlan(plan)
        override suspend fun deleteMetaPlan(metaPlanId: Long) = delegate.deleteMetaPlan(metaPlanId)
        override suspend fun skipCurrentSubPlan(
            metaPlanId: Long,
            expectedTrainingPlanId: Long
        ): Boolean = false
    }

    private class DelayingMetaPlanRepo(
        private val delegate: FakeMetaTrainingPlanRepository
    ) : MetaTrainingPlanRepository {
        override fun getAllMetaPlans(): Flow<List<MetaTrainingPlan>> = delegate.getAllMetaPlans()
        override fun observeLastRotationEventPerMetaPlanSubPlan(): Flow<List<MetaPlanRotationEvent>> =
            delegate.observeLastRotationEventPerMetaPlanSubPlan()
        override suspend fun getMetaPlanById(id: Long): MetaTrainingPlan? = delegate.getMetaPlanById(id)
        override suspend fun saveMetaPlan(plan: MetaTrainingPlan): Long = delegate.saveMetaPlan(plan)
        override suspend fun deleteMetaPlan(metaPlanId: Long) = delegate.deleteMetaPlan(metaPlanId)
        override suspend fun skipCurrentSubPlan(
            metaPlanId: Long,
            expectedTrainingPlanId: Long
        ): Boolean {
            kotlinx.coroutines.delay(1000)
            return delegate.skipCurrentSubPlan(metaPlanId, expectedTrainingPlanId)
        }
    }

    private class FakeDashboardProgressionRepository : ProgressionRepository {
        val pendingCount = MutableStateFlow(0)
        val callLog = mutableListOf<String>()
        var startupError: IOException? = null

        override fun observeTargetsForSession(sessionId: Long): Flow<List<WorkoutPlanTarget>> =
            MutableStateFlow(emptyList())

        override fun observeReviewItems(sessionId: Long?): Flow<List<ProgressionSuggestion>> =
            MutableStateFlow(emptyList())

        override fun observePendingCount(): Flow<Int> = pendingCount

        override suspend fun generateOutcomesForSession(
            sessionId: Long
        ): ProgressionGenerationResult =
            ProgressionGenerationResult(insertedCount = 0, reviewItemCount = 0, pendingCount = 0)

        override suspend fun generateMissingOutcomes(): Int {
            callLog += "generateMissing"
            startupError?.let { throw it }
            return 0
        }

        override suspend fun reconcileOutstandingSuggestions(): Set<Long> {
            callLog += "reconcile"
            startupError?.let { throw it }
            return emptySet()
        }

        override suspend fun acceptSuggestions(
            finalTargetsBySuggestionId: Map<Long, ProgressionTarget>
        ): ProgressionDecisionResult =
            ProgressionDecisionResult.Accepted(finalTargetsBySuggestionId.keys)

        override suspend fun rejectSuggestion(suggestionId: Long) = Unit
    }

    // --- Deload ---

    private fun recommendedDeload() = DeloadAssessment(
        recommended = true,
        fatigueScore = 75,
        signals = listOf(DeloadSignal.E1RM_DROP, DeloadSignal.FAILURE_FREQUENCY),
        windowStart = java.time.LocalDate.now().minusWeeks(4),
        windowEnd = java.time.LocalDate.now(),
        sessionCount = 6,
        strongestExerciseId = 1L,
        strongestExerciseChangePercent = -4.0
    )

    @Test
    fun `deload recommendation is loaded into dashboard state with exercise name`() = runTest {
        exerciseRepo.addExercise(
            com.thrym.app.domain.model.Exercise(
                id = 1L,
                name = "Kniebeuge",
                primaryMuscleGroup = com.thrym.app.domain.model.MuscleGroup.BEINE,
                category = com.thrym.app.domain.model.ExerciseCategory.LANGHANTEL
            )
        )
        val deloadRepo = FakeDeloadRepository(assessment = recommendedDeload())
        val vm = DashboardViewModel(
            workoutRepo,
            statsRepo,
            exerciseRepo,
            preferencesRepo,
            planRepo,
            metaPlanRepo,
            progressionRepository,
            deloadRepo,
            readinessSource,
            readinessRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(true, state.deload?.recommended)
        assertEquals(75, state.deload?.fatigueScore)
        assertEquals("Kniebeuge", state.deloadExercise?.name)
        assertEquals(null, state.deloadMode)
    }

    @Test
    fun `activateDeloadMode persists the mode and updates the state`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.activateDeloadMode(DeloadMode.HALVE_SET_VOLUME)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeloadMode.HALVE_SET_VOLUME, preferencesRepo.current.deloadMode)
        assertEquals(DeloadMode.HALVE_SET_VOLUME, vm.uiState.value.deloadMode)
    }

    @Test
    fun `deactivateDeloadMode clears the persisted mode`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.activateDeloadMode(DeloadMode.REDUCE_INTENSITY_BY_15_PERCENT)
        testDispatcher.scheduler.advanceUntilIdle()
        vm.deactivateDeloadMode()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, preferencesRepo.current.deloadMode)
        assertEquals(null, vm.uiState.value.deloadMode)
    }

    @Test
    fun `deload activation keeps the previous UI mode when persistence fails`() = runTest {
        val preferences = FailingDeloadPreferencesRepository(
            initial = AppPreferences(),
            failure = IOException("disk full")
        )
        val vm = DashboardViewModel(
            workoutRepo,
            statsRepo,
            exerciseRepo,
            preferences,
            planRepo,
            metaPlanRepo,
            progressionRepository,
            FakeDeloadRepository(),
            readinessSource,
            readinessRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.activateDeloadMode(DeloadMode.HALVE_SET_VOLUME)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.deloadMode)
        assertFalse(vm.uiState.value.isDeloadModeUpdating)
        assertEquals(com.thrym.app.fakes.TestUiStrings.get(com.thrym.feature.dashboard.R.string.dashboard_deload_start_failed), vm.uiState.value.error)
    }

    @Test
    fun `deload deactivation keeps the active UI mode when persistence fails`() = runTest {
        val preferences = FailingDeloadPreferencesRepository(
            initial = AppPreferences(deloadMode = DeloadMode.HALVE_SET_VOLUME),
            failure = IOException("read only")
        )
        val vm = DashboardViewModel(
            workoutRepo,
            statsRepo,
            exerciseRepo,
            preferences,
            planRepo,
            metaPlanRepo,
            progressionRepository,
            FakeDeloadRepository(),
            readinessSource,
            readinessRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeloadMode.HALVE_SET_VOLUME, vm.uiState.value.deloadMode)
        vm.deactivateDeloadMode()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeloadMode.HALVE_SET_VOLUME, vm.uiState.value.deloadMode)
        assertFalse(vm.uiState.value.isDeloadModeUpdating)
        assertEquals(com.thrym.app.fakes.TestUiStrings.get(com.thrym.feature.dashboard.R.string.dashboard_deload_end_failed), vm.uiState.value.error)
    }

    @Test
    fun `duplicate deload mode changes are ignored while persistence is in flight`() = runTest {
        val preferences = BlockingDeloadPreferencesRepository()
        val vm = DashboardViewModel(
            workoutRepo,
            statsRepo,
            exerciseRepo,
            preferences,
            planRepo,
            metaPlanRepo,
            progressionRepository,
            FakeDeloadRepository(),
            readinessSource,
            readinessRepo,
            strings = com.thrym.app.fakes.TestUiStrings
        )
        testDispatcher.scheduler.advanceUntilIdle()

        vm.activateDeloadMode(DeloadMode.HALVE_SET_VOLUME)
        testDispatcher.scheduler.runCurrent()
        vm.deactivateDeloadMode()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(DeloadMode.HALVE_SET_VOLUME), preferences.updateCalls)
        assertTrue(vm.uiState.value.isDeloadModeUpdating)

        preferences.gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeloadMode.HALVE_SET_VOLUME, vm.uiState.value.deloadMode)
        assertFalse(vm.uiState.value.isDeloadModeUpdating)
    }

    private fun seedWeeklyExercise() {
        exerciseRepo.addExercise(Exercise(
            id = 1L, name = "Bankdrücken", primaryMuscleGroup = MuscleGroup.BRUST,
            secondaryMuscleGroups = listOf(MuscleGroup.TRIZEPS, MuscleGroup.SCHULTERN),
            category = ExerciseCategory.LANGHANTEL
        ))
    }

    private fun addWeeklySet(id: Long, date: LocalDateTime, type: SetType = SetType.NORMAL, sessionId: Long = 1L) {
        statsRepo.addExerciseSet(WorkoutSet(
            id = id, sessionId = sessionId, exerciseId = 1L, setNumber = id.toInt(),
            reps = 10, weightKg = 50.0, setType = type, completedAt = date
        ))
    }

    @Test
    fun `muscle overview counts completed work sets with secondary weighting and exact week bounds`() = runTest {
        seedWeeklyExercise()
        val start = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        statsRepo.markSessionCompleted(1L)
        addWeeklySet(1, start.atStartOfDay())
        addWeeklySet(2, start.atStartOfDay(), SetType.DROP_SET)
        addWeeklySet(3, start.atStartOfDay(), SetType.FAILURE)
        addWeeklySet(4, start.atStartOfDay(), SetType.WARMUP)
        addWeeklySet(5, start.atStartOfDay().minusNanos(1))
        addWeeklySet(6, start.plusWeeks(1).atStartOfDay())
        addWeeklySet(7, start.atStartOfDay(), sessionId = 99L) // still active
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val weekly = vm.uiState.value.weeklyMuscleVolume
        assertFalse(weekly.isLoading)
        assertNull(weekly.error)
        assertEquals(10, weekly.volumes.size)
        assertEquals(1, weekly.completedWorkoutCount)
        val values = weekly.volumes.associate { it.muscleGroup to it.weeklySets }
        assertEquals(3.0, values.getValue(MuscleGroup.BRUST), 0.0)
        assertEquals(1.5, values.getValue(MuscleGroup.TRIZEPS), 0.0)
        assertEquals(1.5, values.getValue(MuscleGroup.SCHULTERN), 0.0)
        assertEquals(0.0, values.getValue(MuscleGroup.BEINE), 0.0)
        assertEquals(
            listOf(
                MuscleGroup.BRUST,
                MuscleGroup.RUECKEN,
                MuscleGroup.BEINE,
                MuscleGroup.SCHULTERN,
                MuscleGroup.BIZEPS,
                MuscleGroup.TRIZEPS,
                MuscleGroup.GESAESS,
                MuscleGroup.CORE,
                MuscleGroup.UNTERARME,
                MuscleGroup.WADEN
            ),
            weekly.volumes.map { it.muscleGroup }
        )
    }

    @Test
    fun `empty muscle week has ten genuine zero values and no load error`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val weekly = vm.uiState.value.weeklyMuscleVolume
        assertFalse(weekly.isLoading)
        assertNull(weekly.error)
        assertEquals(MuscleGroup.entries.toSet(), weekly.volumes.map { it.muscleGroup }.toSet())
        assertTrue(weekly.volumes.all { it.weeklySets == 0.0 })
    }

    @Test
    fun `muscle week navigation preserves selection on refresh and stops at current week`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val current = vm.uiState.value.weeklyMuscleVolume.weekStart!!
        vm.showPreviousMuscleVolumeWeek()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.loadDashboard()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(current.minusWeeks(1), vm.uiState.value.weeklyMuscleVolume.weekStart)
        vm.showNextMuscleVolumeWeek()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.showNextMuscleVolumeWeek()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(current, vm.uiState.value.weeklyMuscleVolume.weekStart)
    }

    @Test
    fun `muscle overview realigns to Sunday after week preference changes`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.showPreviousMuscleVolumeWeek()
        testDispatcher.scheduler.advanceUntilIdle()
        preferencesRepo.updateWeekStart(WeekStart.SUNDAY)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)),
            vm.uiState.value.weeklyMuscleVolume.weekStart
        )
    }

    @Test
    fun `muscle week query failure is not zero volume and can be retried`() = runTest {
        var fail = false
        val repository = object : StatisticsRepository by statsRepo {
            override suspend fun getWorkSetsCompletedSince(sinceEpochMillis: Long): List<WorkoutSet> {
                if (fail) throw IOException("private database details")
                return statsRepo.getWorkSetsCompletedSince(sinceEpochMillis)
            }

            override suspend fun getWorkSetsCompletedBetween(
                sinceEpochMillis: Long,
                untilEpochMillis: Long
            ): List<WorkoutSet> {
                if (fail) throw IOException("private database details")
                return statsRepo.getWorkSetsCompletedBetween(sinceEpochMillis, untilEpochMillis)
            }
        }
        val vm = createViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        fail = true
        vm.showPreviousMuscleVolumeWeek()
        testDispatcher.scheduler.advanceUntilIdle()
        val failed = vm.uiState.value.weeklyMuscleVolume
        assertFalse(failed.isLoading)
        assertNotNull(failed.error)
        assertFalse(failed.error!!.contains("private database details"))
        assertTrue(failed.volumes.isEmpty())
        fail = false
        vm.reloadWeeklyMuscleVolume()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.weeklyMuscleVolume.error)
        assertEquals(10, vm.uiState.value.weeklyMuscleVolume.volumes.size)
    }

    @Test
    fun `missing exercise mapping reports incomplete muscle data rather than zero`() = runTest {
        statsRepo.markSessionCompleted(1L)
        addWeeklySet(1L, LocalDate.now().atStartOfDay())
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.uiState.value.weeklyMuscleVolume.error)
        assertTrue(vm.uiState.value.weeklyMuscleVolume.volumes.isEmpty())
    }

    @Test
    fun `slow previous muscle week cannot overwrite newer navigation`() = runTest {
        var block = false
        val gate = CompletableDeferred<Unit>()
        val repository = object : StatisticsRepository by statsRepo {
            override suspend fun getWorkSetsCompletedSince(sinceEpochMillis: Long): List<WorkoutSet> {
                if (block) {
                    block = false
                    gate.await()
                }
                return statsRepo.getWorkSetsCompletedSince(sinceEpochMillis)
            }
        }
        val vm = createViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        val current = vm.uiState.value.weeklyMuscleVolume.weekStart
        block = true
        vm.showPreviousMuscleVolumeWeek()
        testDispatcher.scheduler.runCurrent()
        vm.showNextMuscleVolumeWeek()
        testDispatcher.scheduler.runCurrent()
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(current, vm.uiState.value.weeklyMuscleVolume.weekStart)
        assertFalse(vm.uiState.value.weeklyMuscleVolume.isLoading)
    }

    @Test
    fun `muscle navigation during dashboard refresh leaves both loads complete`() = runTest {
        var block = false
        val gate = CompletableDeferred<Unit>()
        val repository = object : StatisticsRepository by statsRepo {
            override suspend fun getRecentRecordsList(limit: Int): List<com.thrym.app.domain.model.PersonalRecord> {
                if (block) gate.await()
                return statsRepo.getRecentRecordsList(limit)
            }
        }
        val vm = createViewModel(repository)
        testDispatcher.scheduler.advanceUntilIdle()
        val current = vm.uiState.value.weeklyMuscleVolume.weekStart!!
        block = true
        vm.loadDashboard()
        testDispatcher.scheduler.runCurrent()
        vm.showPreviousMuscleVolumeWeek()
        testDispatcher.scheduler.runCurrent()
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.weeklyMuscleVolume.isLoading)
        assertEquals(current.minusWeeks(1), vm.uiState.value.weeklyMuscleVolume.weekStart)
    }

    @Test
    fun `preferences failure ends initial muscle loading without fabricated zeros`() = runTest {
        val preferences = object : AppPreferencesRepository by preferencesRepo {
            override val preferences: Flow<AppPreferences> = kotlinx.coroutines.flow.flow {
                throw IOException("preferences unavailable")
            }
        }
        mockkObject(AppLogger)
        try {
            every { AppLogger.e(any(), any(), any()) } returns Unit
            val vm = DashboardViewModel(
                workoutRepo, statsRepo, exerciseRepo, preferences, planRepo,
                metaPlanRepo, progressionRepository, FakeDeloadRepository(),
                readinessSource, readinessRepo,
                strings = com.thrym.app.fakes.TestUiStrings
            )
            testDispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(vm.uiState.value.weeklyMuscleVolume.isLoading)
            assertNotNull(vm.uiState.value.weeklyMuscleVolume.error)
            assertTrue(vm.uiState.value.weeklyMuscleVolume.volumes.isEmpty())
        } finally {
            unmockkObject(AppLogger)
        }
    }

    @Test
    fun `training trend is computed from the projection source without a fake index`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val trend = vm.uiState.value.trainingTrend
        assertFalse(trend.isLoading)
        assertNull(trend.error)
        val assessment = trend.assessment
        assertNotNull(assessment)
        // An empty graph must not fabricate a good-looking index.
        assertEquals(TrainingTrendStatus.INSUFFICIENT_DATA, assessment!!.trainingTrend.status)
        assertNull(assessment.trainingTrend.trainingIndex)
    }

    @Test
    fun `saving a check-in persists the answers and leaves edit mode`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startCheckInEdit()
        vm.updateCheckInSleepQuality(4)
        vm.updateCheckInEnergy(3)
        vm.updateCheckInStress(2)
        vm.updateCheckInSoreness(MuscleGroup.BRUST, 2)
        vm.saveCheckIn()
        testDispatcher.scheduler.advanceUntilIdle()

        val stored = readinessRepo.storedCheckIns.single()
        assertEquals(4, stored.sleepQuality)
        assertEquals(3, stored.energy)
        assertEquals(2, stored.stress)
        assertEquals(2, stored.muscleSoreness[com.thrym.shared.model.MuscleGroup.BRUST])

        val state = vm.uiState.value.checkIn
        assertFalse(state.isEditing)
        assertFalse(state.isSaving)
        val storedDay = state.stored
        assertNotNull(storedDay)
        assertTrue(storedDay?.hasAnyAnswer == true)
        assertEquals(DashboardCheckInNotice.SAVED, vm.uiState.value.checkInNotice)
    }

    @Test
    fun `an unanswered check-in is rejected instead of stored as a blank row`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startCheckInEdit()
        vm.saveCheckIn()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(readinessRepo.storedCheckIns.isEmpty())
        assertEquals(DashboardCheckInNotice.NEEDS_ANSWER, vm.uiState.value.checkInNotice)
        assertTrue(vm.uiState.value.checkIn.isEditing)
    }

    @Test
    fun `deleting a check-in removes only the stored day`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startCheckInEdit()
        vm.updateCheckInEnergy(5)
        vm.saveCheckIn()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, readinessRepo.storedCheckIns.size)

        vm.deleteCheckIn()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(readinessRepo.storedCheckIns.isEmpty())
        assertEquals(DashboardCheckInNotice.DELETED, vm.uiState.value.checkInNotice)
    }

    @Test
    fun `cancelling an edit discards the draft and keeps the stored day`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startCheckInEdit()
        vm.updateCheckInSleepQuality(4)
        vm.updateCheckInEnergy(3)
        vm.saveCheckIn()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startCheckInEdit()
        vm.updateCheckInSleepQuality(1)
        vm.updateCheckInStress(5)
        vm.cancelCheckInEdit()

        val state = vm.uiState.value.checkIn
        assertFalse(state.isEditing)
        assertEquals(4, state.stored?.sleepQuality)
        assertEquals(3, state.stored?.energy)
        assertNull(state.stored?.stress)
        // Der Entwurf zeigt wieder den gespeicherten Stand, nicht die verworfenen Eingaben.
        assertEquals(4, state.draft.sleepQuality)
        assertNull(state.draft.stress)
        assertTrue(state.draft.sorenessByMuscle.isEmpty())
    }

    @Test
    fun `cancelling an edit without stored answers leaves the day empty`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startCheckInEdit()
        vm.updateCheckInSleepQuality(5)
        vm.cancelCheckInEdit()

        val state = vm.uiState.value.checkIn
        assertFalse(state.isEditing)
        assertNull(state.stored)
        assertFalse(state.draft.hasAnyAnswer)
        assertTrue(readinessRepo.storedCheckIns.isEmpty())
    }

    @Test
    fun `a failing projection stream clears the stale trend and surfaces an error`() = runTest {
        mockkObject(AppLogger)
        try {
            every { AppLogger.w(any(), any(), any()) } returns Unit

            val vm = createViewModel()
            testDispatcher.scheduler.advanceUntilIdle()
            assertNotNull(vm.uiState.value.trainingTrend.assessment)

            readinessSource.fail()
            testDispatcher.scheduler.advanceUntilIdle()

            val trend = vm.uiState.value.trainingTrend
            assertNull(trend.assessment)
            assertFalse(trend.isLoading)
            assertNotNull(trend.error)
        } finally {
            unmockkObject(AppLogger)
        }
    }

    @Test
    fun `a draft started before midnight is discarded on the day change`() = runTest {
        val yesterday = LocalDate.of(2026, 9, 10)
        val today = LocalDate.of(2026, 9, 11)
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.todayProvider = { yesterday }
        vm.refreshCheckInDay()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startCheckInEdit()
        vm.updateCheckInSleepQuality(3)
        assertEquals(yesterday, vm.uiState.value.checkIn.draftDate)

        vm.todayProvider = { today }
        vm.refreshCheckInDay()
        testDispatcher.scheduler.advanceUntilIdle()

        // Der offene Entwurf gehört zu gestern und wird verworfen, nicht umdatiert.
        val state = vm.uiState.value.checkIn
        assertFalse(state.isEditing)
        assertNull(state.draftDate)
        assertFalse(state.draft.hasAnyAnswer)

        vm.saveCheckIn()
        testDispatcher.scheduler.advanceUntilIdle()

        // Nichts wird stillschweigend unter dem neuen Tag gespeichert.
        assertTrue(readinessRepo.storedCheckIns.isEmpty())
    }

    @Test
    fun `a plan arriving after the first trend snapshot triggers a re-projection`() = runTest {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, readinessSource.currentPayloadCalls)

        planRepo.savePlan(TrainingPlan(name = "Plan A"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(readinessSource.currentPayloadCalls >= 1)
    }

    @Test
    fun `a day change discards the open draft and re-projects the trend`() = runTest {
        val yesterday = LocalDate.of(2026, 9, 10)
        val today = LocalDate.of(2026, 9, 11)
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.todayProvider = { yesterday }
        vm.refreshCheckInDay()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startCheckInEdit()
        vm.updateCheckInSleepQuality(2)
        assertTrue(vm.uiState.value.checkIn.isEditing)

        val callsBefore = readinessSource.currentPayloadCalls
        vm.todayProvider = { today }
        vm.refreshCheckInDay()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value.checkIn
        assertFalse(state.isEditing)
        assertNull(state.draftDate)
        assertFalse(state.draft.hasAnyAnswer)
        assertEquals(today, state.today)
        // Ein neuer Tag verschiebt das Trendfenster, also wird neu projiziert.
        assertTrue(readinessSource.currentPayloadCalls > callsBefore)
    }

    @Test
    fun `startCheckInEdit never prefills yesterday's answers as today`() = runTest {
        val yesterday = LocalDate.of(2026, 9, 10)
        val today = LocalDate.of(2026, 9, 11)
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.todayProvider = { yesterday }
        vm.refreshCheckInDay()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.startCheckInEdit()
        vm.updateCheckInSleepQuality(4)
        vm.saveCheckIn()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, readinessRepo.storedCheckIns.size)

        // Der Tag wechselt, ohne dass vorher ein Tageswechsel-Refresh lief.
        vm.todayProvider = { today }
        vm.startCheckInEdit()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value.checkIn
        assertTrue(state.isEditing)
        assertNull(state.stored)
        assertFalse(state.draft.hasAnyAnswer)
        assertEquals(today, state.draftDate)
    }

    @Test
    fun `wochenziel zaehlt die laufende woche und die gehaltenen wochen davor`() = runTest {
        val weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var nextId = 500L
        fun addCompleted(day: LocalDate) {
            val start = LocalDateTime.of(day, LocalTime.of(7, 0))
            workoutRepo.addSession(
                WorkoutSession(id = nextId++, startTime = start, endTime = start.plusHours(1), durationSeconds = 3600),
                isActive = false
            )
        }
        listOf(1L, 2L).forEach { weeksAgo -> (0L..2L).forEach { addCompleted(weekStart.minusWeeks(weeksAgo).plusDays(it)) } }
        addCompleted(weekStart)

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val goal = vm.uiState.value.weeklyGoal
        assertNotNull(goal)
        assertEquals(3, goal!!.goal)
        assertEquals(1, goal.completedThisWeek)
        assertEquals(2, goal.streakWeeks)
        assertNull(vm.uiState.value.pauseDays)
        assertEquals(0, workoutRepo.getAllCompletedSessionsListCallCount)
    }

    @Test
    fun `ausgeschaltetes wochenziel liefert keinen status und eine pause wird erkannt`() = runTest {
        preferencesRepo.updateWeeklyGoalEnabled(false)
        val start = LocalDateTime.of(LocalDate.now().minusDays(30), LocalTime.of(18, 0))
        workoutRepo.addSession(
            WorkoutSession(id = 600L, startTime = start, endTime = start.plusHours(1), durationSeconds = 3600),
            isActive = false
        )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.weeklyGoal)
        assertEquals(30, vm.uiState.value.pauseDays)
    }

    private class FailingDeloadPreferencesRepository(
        initial: AppPreferences,
        private val failure: Throwable
    ) : AppPreferencesRepository by FakeAppPreferencesRepository(initial) {
        override suspend fun updateDeloadMode(mode: DeloadMode?) {
            throw failure
        }
    }

    private class BlockingDeloadPreferencesRepository : AppPreferencesRepository by FakeAppPreferencesRepository() {
        val gate = CompletableDeferred<Unit>()
        val updateCalls = mutableListOf<DeloadMode?>()

        override suspend fun updateDeloadMode(mode: DeloadMode?) {
            updateCalls += mode
            gate.await()
        }
    }

    private class FakeReadinessProjectionSource : ReadinessProjectionSource {
        /** `null` steht für einen fehlgeschlagenen Datenstrom. */
        private val state = MutableStateFlow<BackupPayloadV1?>(emptyBackupPayload())

        var currentPayloadCalls = 0
            private set

        fun fail() {
            state.value = null
        }

        override suspend fun currentPayload(): BackupPayloadV1 {
            currentPayloadCalls++
            return state.value ?: throw IOException("Readiness-Payload nicht verfügbar")
        }

        override fun observePayload(): Flow<BackupPayloadV1> = state.map { payload ->
            payload ?: throw IOException("Readiness-Payload nicht verfügbar")
        }
    }

    /**
     * In-memory readiness document for dashboard tests.
     *
     * It mirrors the repository contract: a missing check-in has no row, an
     * unknown intention clears the record instead of storing a placeholder.
     */
    private class FakeReadinessRepository : ReadinessRepository {
        private val document = MutableStateFlow(ReadinessData())

        val storedCheckIns: List<ReadinessCheckIn> get() = document.value.checkIns

        override fun observeReadinessData(): Flow<ReadinessData> = document

        override suspend fun getReadinessData(): ReadinessData = document.value

        override fun observeCheckIns(): Flow<Map<KxLocalDate, ReadinessCheckIn>> =
            document.map { data -> data.checkIns.associateBy { it.localDate } }

        override fun observeSetIntentions(): Flow<Map<Long, SetIntention>> =
            document.map { data -> data.setIntentions.associate { it.setId to it.intention } }

        override suspend fun upsertCheckIn(checkIn: ReadinessCheckIn) {
            val others = document.value.checkIns.filterNot { it.localDate == checkIn.localDate }
            document.value = document.value.copy(checkIns = others + checkIn)
        }

        override suspend fun deleteCheckIn(localDate: KxLocalDate) {
            document.value = document.value.copy(
                checkIns = document.value.checkIns.filterNot { it.localDate == localDate }
            )
        }

        override suspend fun setSetIntention(setId: Long, intention: SetIntention, note: String) {
            val others = document.value.setIntentions.filterNot { it.setId == setId }
            val records = if (intention == SetIntention.UNKNOWN) {
                others
            } else {
                others + SetIntentionRecord(setId = setId, intention = intention, note = note)
            }
            document.value = document.value.copy(setIntentions = records)
        }

        override suspend fun clearSetIntention(setId: Long) {
            document.value = document.value.copy(
                setIntentions = document.value.setIntentions.filterNot { it.setId == setId }
            )
        }

        override suspend fun pruneSetIntentions(setIds: Collection<Long>) {
            document.value = document.value.copy(
                setIntentions = document.value.setIntentions.filterNot { it.setId in setIds }
            )
        }

        override suspend fun replaceReadinessData(data: ReadinessData) {
            document.value = data
        }

        override suspend fun clearReadinessData() {
            document.value = ReadinessData()
        }
    }
}

private fun emptyBackupPayload(): BackupPayloadV1 = BackupPayloadV1(
    formatVersion = 1,
    schemaVersion = CURRENT_BACKUP_SCHEMA_VERSION,
    appVersion = "test",
    exportedAtEpochMillis = 0L,
    exercises = emptyList(),
    workoutSessions = emptyList(),
    workoutSets = emptyList(),
    trainingPlans = emptyList(),
    planExercises = emptyList(),
    personalRecords = emptyList()
)
