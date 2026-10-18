package com.thrym.app.presentation.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.thrym.app.data.local.ThrymDatabase
import com.thrym.app.data.preferences.appPreferencesDataStore
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.PlanExercise
import com.thrym.app.domain.model.TrainingPlan
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.TrainingPlanRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.app.presentation.theme.ThrymTheme
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Logging the last planned set puts "Training beenden" at the end of the list. The card's
 * button opens the finish sheet, and confirming there finishes the workout. The exercise is
 * new, so the completion screen lists its first personal bests.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutFinishCardFlowTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    // Expected texts come from the resources, so the test runs in any device language.
    private fun text(id: Int, vararg args: Any): String = ui.activity.getString(id, *args)
    private fun awaitText(text: String) = waitOrDump(20_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    // Log button and sheet stay locked until the save is done; wait for the enabled node.
    private fun awaitEnabled(matcher: SemanticsMatcher) = waitOrDump(20_000) { ui.onAllNodes(matcher and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    // On a timeout, the semantics tree goes into the failure, so CI failures need no logcat.
    private fun waitOrDump(timeoutMillis: Long, condition: () -> Boolean) {
        try { ui.waitUntil(timeoutMillis) { condition() } } catch (e: ComposeTimeoutException) {
            throw AssertionError("${e.message}\n${ui.onAllNodes(isRoot()).printToString(maxDepth = 60)}", e)
        }
    }

    /** Isolation as in [NavigationSmokeTest]: no active session from earlier tests. */
    @Before fun clearAppDatabaseAndDataStore() {
        runBlocking {
            GlobalContext.get().get<ThrymDatabase>().clearAllTables()
            ApplicationProvider.getApplicationContext<Context>().appPreferencesDataStore.edit { it.clear() }
        }
    }

    @Test fun lastPlannedSetShowsFinishCardThatOpensTheFinishSheet() {
        val plans = GlobalContext.get().get<TrainingPlanRepository>()
        val workouts = GlobalContext.get().get<WorkoutRepository>()
        val exercises = GlobalContext.get().get<ExerciseRepository>()
        val planId = runBlocking {
            val exerciseId = exercises.addCustomExercise(
                Exercise(name = "Finish Bankdrücken", primaryMuscleGroup = MuscleGroup.BRUST, category = ExerciseCategory.LANGHANTEL)
            )
            plans.savePlan(
                TrainingPlan(
                    name = "Finish card",
                    exercises = listOf(PlanExercise(exerciseId = exerciseId, orderIndex = 0, targetSets = 1, targetWeightKg = 60.0))
                )
            )
        }
        val sessionId = runBlocking { workouts.startWorkout("Finish card", planId) }
        ui.setContent {
            ThrymTheme {
                ThrymNavHost(navController = rememberNavController(), startDestination = Screen.ActiveWorkout.createRoute(sessionId, planId))
            }
        }

        val logSet = hasText(text(R.string.workout_log_set_button_short, 1), substring = true)
        awaitEnabled(logSet)
        ui.onAllNodes(logSet)[0].performScrollTo().performClick()
        waitOrDump(20_000) { runBlocking { workouts.getSetsForSessionList(sessionId).size == 1 } }

        // The list scrolls to the card on its own, so it must be on screen without help.
        waitOrDump(20_000) { ui.onAllNodesWithTag("workout_finish_card").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("workout_finish_card").assertIsDisplayed()
        ui.onNodeWithText(ui.activity.resources.getQuantityString(WorkoutR.plurals.workout_finish_card_title, 1, 1)).assertIsDisplayed()

        ui.onNodeWithTag("workout_finish_card_button").performClick()
        awaitText(text(R.string.workout_finish_dialog_title))
        // The sheet's button has the same text as the card's.
        val sheetFinish = hasText(text(R.string.workout_finish_confirm)) and !hasTestTag("workout_finish_card_button")
        awaitEnabled(sheetFinish)
        ui.onAllNodes(sheetFinish and isEnabled()).onFirst().performClick()

        awaitText(text(R.string.workout_summary_headline_complete))
        awaitText(text(WorkoutR.string.workout_records_first_title))
        runBlocking {
            assertNotNull(workouts.getSessionById(sessionId)?.endTime)
            assertEquals(1, workouts.getSetsForSessionList(sessionId).size)
        }
    }
}
