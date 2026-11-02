package com.thrym.app.presentation.navigation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thrym.app.domain.model.*
import com.thrym.app.domain.repository.*
import com.thrym.app.presentation.theme.ThrymTheme
import com.thrym.core.designsystem.R
import com.thrym.feature.plans.R as PlansR
import com.thrym.feature.workout.R as WorkoutR
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.thrym.app.data.local.ThrymDatabase
import com.thrym.app.data.preferences.appPreferencesDataStore
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

@RunWith(AndroidJUnit4::class)
class ApprovedWorkoutFlowTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun awaitText(text: String) = ui.waitUntil(20000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    // Erwartete Texte kommen aus den Ressourcen, damit der Test in jeder Geraetesprache laeuft.
    private fun text(id: Int, vararg args: Any): String = ui.activity.getString(id, *args)
    // Nach dem Loggen bleiben Log-Button, Eingaben und Abschlussdialog gesperrt, bis der
    // Speichervorgang ganz abgeschlossen ist. Auf langsamen Emulatoren wuerde ein Klick
    // davor ignoriert, daher erst auf den aktivierten Knoten warten.
    private fun awaitEnabled(matcher: SemanticsMatcher) = ui.waitUntil(20000) { ui.onAllNodes(matcher and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    // Bei einem Timeout den Semantik-Baum in die Fehlermeldung schreiben, damit CI-Fehler
    // ohne Emulator-Logcat nachvollziehbar sind.
    private fun waitOrDump(timeoutMillis: Long, condition: () -> Boolean) {
        try { ui.waitUntil(timeoutMillis) { condition() } } catch (e: ComposeTimeoutException) {
            throw AssertionError("${e.message}\n${ui.onAllNodes(isRoot()).printToString(maxDepth = 60)}", e)
        }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    /** Isolation wie in [NavigationSmokeTest]: keine aktive Session aus vorherigen Tests uebernehmen. */
    @Before fun clearAppDatabaseAndDataStore() {
        runBlocking {
            GlobalContext.get().get<ThrymDatabase>().clearAllTables()
            ApplicationProvider.getApplicationContext<Context>().appPreferencesDataStore.edit { it.clear() }
        }
    }
    @Test fun logPartialWorkoutReviewAndApplyOnlyPerformedSets() {
        val plans = GlobalContext.get().get<TrainingPlanRepository>()
        val workouts = GlobalContext.get().get<WorkoutRepository>()
        val exercises = GlobalContext.get().get<ExerciseRepository>()
        val planId = runBlocking {
            val ids = listOf("Review Bankdrücken", "Review Rudern", "Review Kniebeuge").map { name -> exercises.addCustomExercise(Exercise(name=name,primaryMuscleGroup=MuscleGroup.BRUST,category=ExerciseCategory.LANGHANTEL)) }
            plans.savePlan(TrainingPlan(name="Designprüfung 15.09.", exercises=ids.mapIndexed { index,id -> PlanExercise(exerciseId=id,orderIndex=index,targetWeightKg=60.0) }))
        }
        val sessionId = runBlocking { workouts.startWorkout("Designprüfung 15.09.", planId) }
        lateinit var nav: NavHostController
        ui.setContent { ThrymTheme { nav=rememberNavController(); ThrymNavHost(navController=nav, startDestination=Screen.ActiveWorkout.createRoute(sessionId,planId)) } }
        val logSet1 = text(R.string.workout_log_set_button_short, 1)
        val logSet2 = text(R.string.workout_log_set_button_short, 2)
        val finish = text(R.string.workout_finish_action)
        ui.waitUntil(20000) { ui.onAllNodes(hasText(logSet1,substring=true)).fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasText(logSet1,substring=true))[0].performScrollTo().performClick()
        ui.waitUntil(20000) { runBlocking { workouts.getSetsForSessionList(sessionId).size == 1 } }
        awaitEnabled(hasText(logSet2,substring=true))
        ui.onAllNodes(hasSetTextAction())[1].performTextReplacement("9")
        capture("android-logging")
        ui.onNode(hasText(logSet2,substring=true)).performScrollTo().performClick()
        waitOrDump(20000) { runBlocking { workouts.getSetsForSessionList(sessionId).size == 2 } }
        ui.onNodeWithText(finish).performClick()
        awaitText(text(R.string.workout_finish_dialog_title))
        ui.onNodeWithText(ui.activity.resources.getQuantityString(R.plurals.workout_finish_remaining, 7, 7, 2, 9)).assertExists()
        capture("android-partial-finish")
        val keepTraining = text(R.string.workout_finish_dialog_cancel)
        awaitEnabled(hasText(keepTraining))
        ui.onNodeWithText(keepTraining).performClick()
        ui.onNodeWithText(finish).performClick()
        val finishAnyway = text(R.string.workout_finish_confirm_partial)
        awaitEnabled(hasText(finishAnyway))
        ui.onNodeWithText(finishAnyway).performClick()
        // Every finished workout opens with the celebration; the summary comes after it.
        val skipCelebration = text(WorkoutR.string.workout_celebration_skip)
        awaitText(skipCelebration)
        ui.onNodeWithText(skipCelebration).performClick()
        awaitText(text(R.string.workout_summary_headline_partial))
        capture("android-summary")
        runBlocking {
            assertNotNull(workouts.getSessionById(sessionId)?.endTime)
            assertEquals(listOf(10,9),workouts.getSetsForSessionList(sessionId).map { it.reps })
            assertEquals(1140.0,workouts.getSetsForSessionList(sessionId).sumOf { it.reps*it.weightKg },0.0)
            assertTrue(plans.getPlanById(planId)!!.exercises.all { it.setTargets.isEmpty() })
        }
        val reviewPlanChanges = text(R.string.workout_summary_review_plan_changes)
        val keepPlan = text(R.string.workout_plan_changes_confirm_keep)
        ui.onNodeWithText(reviewPlanChanges).performScrollTo().performClick()
        awaitText(keepPlan)
        capture("android-plan-changes")
        ui.onAllNodesWithText(keepPlan).filterToOne(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        runBlocking { assertTrue(plans.getPlanById(planId)!!.exercises.all { it.setTargets.isEmpty() }) }
        ui.onNodeWithText(reviewPlanChanges).performScrollTo().performClick()
        ui.onNodeWithText(text(R.string.workout_plan_changes_option_apply)).performScrollTo().performClick()
        ui.onAllNodesWithText(text(R.string.workout_plan_changes_confirm_apply)).filterToOne(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        awaitText(text(R.string.workout_summary_plan_changes_applied))
        runBlocking {
            val updated=plans.getPlanById(planId)!!.exercises
            assertEquals(listOf(10,9,10),updated[0].setTargets.map { it.reps })
            assertTrue(updated.drop(1).all { it.setTargets.isEmpty() })
        }
        ui.runOnIdle { nav.navigate(Screen.PlanEditor.createRoute(planId)) }
        awaitText(text(PlansR.string.plan_targets_individual))
        capture("android-plan-editor")
    }
}
