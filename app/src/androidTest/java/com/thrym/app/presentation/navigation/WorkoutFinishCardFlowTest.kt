package com.thrym.app.presentation.navigation

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.datastore.preferences.core.edit
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thrym.app.data.local.ThrymDatabase
import com.thrym.app.data.preferences.appPreferencesDataStore
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.PlanExercise
import com.thrym.app.domain.model.TrainingPlan
import com.thrym.app.domain.model.WorkoutSet
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.ProgressionRepository
import com.thrym.app.domain.repository.TrainingPlanRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.presentation.theme.ThrymTheme
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Logging the last planned set scrolls the list to "Training beenden" at its end and announces
 * the card once. The plan has so many exercises that the card starts below the fold, so it is
 * on screen only if the list scrolls there by itself. The card's button opens the finish sheet,
 * and confirming there finishes the workout. The exercises are new, so the completion screen
 * lists their first personal bests.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutFinishCardFlowTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    // Expected texts come from the resources, so the test runs in any device language.
    private fun text(id: Int, vararg args: Any): String = ui.activity.getString(id, *args)
    private fun awaitText(text: String) = waitOrDump(20_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    // Log button and sheet stay locked until the save is done; wait for the enabled node.
    private fun awaitEnabled(matcher: SemanticsMatcher) = waitOrDump(20_000) { ui.onAllNodes(matcher and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    private fun isCardDisplayed(): Boolean =
        ui.onAllNodesWithTag(FINISH_CARD_TAG).fetchSemanticsNodes().isNotEmpty() && ui.onNodeWithTag(FINISH_CARD_TAG).isDisplayed()
    // On a timeout, the semantics tree goes into the failure, so CI failures need no logcat.
    private fun waitOrDump(timeoutMillis: Long, condition: () -> Boolean) {
        try { ui.waitUntil(timeoutMillis) { condition() } } catch (e: ComposeTimeoutException) {
            throw AssertionError("${e.message}\n${ui.onAllNodes(isRoot()).printToString(maxDepth = 60)}", e)
        }
    }

    // The test reads the announcements as an accessibility client would.
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val announcements = CopyOnWriteArrayList<String>()

    /** Isolation as in [NavigationSmokeTest]: no active session from earlier tests. */
    @Before fun clearAppDatabaseAndDataStore() {
        runBlocking {
            GlobalContext.get().get<ThrymDatabase>().clearAllTables()
            ApplicationProvider.getApplicationContext<Context>().appPreferencesDataStore.edit { it.clear() }
        }
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == ANNOUNCEMENT) announcements += event.text.joinToString("")
        }
    }

    @After fun stopListening() {
        automation.setOnAccessibilityEventListener(null)
    }

    @Test fun lastPlannedSetScrollsToFinishCardThatOpensTheFinishSheet() {
        val plans = GlobalContext.get().get<TrainingPlanRepository>()
        val workouts = GlobalContext.get().get<WorkoutRepository>()
        val exercises = GlobalContext.get().get<ExerciseRepository>()
        val progression = GlobalContext.get().get<ProgressionRepository>()
        val planId = runBlocking {
            val exerciseIds = (1..EXERCISE_COUNT).map { number ->
                exercises.addCustomExercise(
                    Exercise(name = "Finish Übung $number", primaryMuscleGroup = MuscleGroup.BRUST, category = ExerciseCategory.LANGHANTEL)
                )
            }
            plans.savePlan(
                TrainingPlan(
                    name = "Finish card",
                    exercises = exerciseIds.mapIndexed { index, exerciseId ->
                        PlanExercise(exerciseId = exerciseId, orderIndex = index, targetSets = 1, targetWeightKg = 60.0)
                    }
                )
            )
        }
        val sessionId = runBlocking { workouts.startWorkout("Finish card", planId) }
        // Every exercise but the first is done, so their cards are collapsed and the first one
        // stays open at the top. Even collapsed, they push the card below the fold.
        runBlocking {
            val targets = progression.observeTargetsForSession(sessionId).first().sortedBy { it.orderIndex }
            targets.drop(1).forEach { target ->
                workouts.addSet(
                    WorkoutSet(
                        sessionId = sessionId,
                        exerciseId = target.exerciseId,
                        setNumber = 1,
                        reps = target.target.reps,
                        weightKg = 60.0,
                        planTargetSnapshotId = target.id
                    )
                )
            }
        }
        ui.setContent {
            ThrymTheme {
                ThrymNavHost(navController = rememberNavController(), startDestination = Screen.ActiveWorkout.createRoute(sessionId, planId))
            }
        }

        val logSet = hasText(text(R.string.workout_log_set_button_short, 1), substring = true)
        awaitEnabled(logSet)
        // One planned set is still open, so there is no card yet.
        ui.onNodeWithTag(FINISH_CARD_TAG).assertDoesNotExist()
        ui.onAllNodes(logSet)[0].performScrollTo().performClick()
        waitOrDump(20_000) { runBlocking { workouts.getSetsForSessionList(sessionId).size == EXERCISE_COUNT } }

        // No performScrollTo: the list has to bring the card on screen by itself.
        waitOrDump(20_000) { isCardDisplayed() }
        val title = ui.activity.resources.getQuantityString(WorkoutR.plurals.workout_finish_card_title, EXERCISE_COUNT, EXERCISE_COUNT)
        ui.onNodeWithText(title).assertIsDisplayed()
        ui.onNodeWithTag(FINISH_CARD_BUTTON_TAG).assertIsDisplayed()
        waitOrDump(20_000) { title in announcements }

        ui.onNodeWithTag(FINISH_CARD_BUTTON_TAG).performClick()
        awaitText(text(R.string.workout_finish_dialog_title))
        // The sheet's button has the same text as the card's.
        val sheetFinish = hasText(text(R.string.workout_finish_confirm)) and !hasTestTag(FINISH_CARD_BUTTON_TAG)
        awaitEnabled(sheetFinish)
        ui.onAllNodes(sheetFinish and isEnabled()).onFirst().performClick()

        awaitText(text(R.string.workout_summary_headline_complete))
        awaitText(text(WorkoutR.string.workout_records_first_title))
        // The card was announced once, when the last set completed the plan.
        assertEquals(1, announcements.count { it == title })
        runBlocking {
            assertNotNull(workouts.getSessionById(sessionId)?.endTime)
            assertEquals(EXERCISE_COUNT, workouts.getSetsForSessionList(sessionId).size)
        }
    }

    private companion object {
        // Deprecated from API 36 like View.announceForAccessibility, which the screen uses.
        @Suppress("DEPRECATION")
        const val ANNOUNCEMENT = AccessibilityEvent.TYPE_ANNOUNCEMENT
        const val EXERCISE_COUNT = 10
        const val FINISH_CARD_TAG = "workout_finish_card"
        const val FINISH_CARD_BUTTON_TAG = "workout_finish_card_button"
    }
}
