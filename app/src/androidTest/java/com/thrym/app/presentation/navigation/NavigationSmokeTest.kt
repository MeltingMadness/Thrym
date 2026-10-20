package com.thrym.app.presentation.navigation

import com.thrym.shared.recognition.WeeklyGoalChange
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thrym.app.data.local.ThrymDatabase
import com.thrym.app.data.preferences.appPreferencesDataStore
import com.thrym.app.data.preferences.onboardingDataStore
import com.thrym.app.domain.model.AppPreferences
import com.thrym.app.domain.model.Exercise
import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.model.IntensitySystem
import com.thrym.app.domain.model.MuscleGroup
import com.thrym.app.domain.model.ProgressionDecisionResult
import com.thrym.app.domain.model.ProgressionGenerationResult
import com.thrym.app.domain.model.ProgressionSuggestion
import com.thrym.app.domain.model.ProgressionTarget
import com.thrym.app.domain.model.ReminderConfig
import com.thrym.app.domain.model.ThemeMode
import com.thrym.app.domain.model.ThemeScheme
import com.thrym.app.domain.model.UnitSystem
import com.thrym.app.domain.model.WeekStart
import com.thrym.app.domain.model.WorkoutPlanTarget
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.OnboardingRepository
import com.thrym.app.domain.repository.ProgressionRepository
import com.thrym.app.domain.util.DateFormatting
import com.thrym.app.presentation.common.uiLocale
import com.thrym.app.presentation.progression.ProgressionReviewViewModel
import com.thrym.app.presentation.theme.ThrymTheme
import com.thrym.core.designsystem.R
import com.thrym.feature.onboarding.R as OnboardingR
import com.thrym.feature.workout.R as WorkoutR
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinIsolatedContext
import org.koin.core.context.GlobalContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val progressionRepository = NavigationSmokeProgressionRepository()
    private val preferencesRepository = NavigationSmokePreferencesRepository()
    private val progressionReviewModule = module {
        single<ProgressionRepository> { progressionRepository }
        single<AppPreferencesRepository> { preferencesRepository }
        single<ExerciseRepository> { NavigationSmokeExerciseRepository() }
        single<com.thrym.app.domain.repository.TrainingPlanRepository> {
            object : com.thrym.app.domain.repository.TrainingPlanRepository {
                override fun getAllPlans() = kotlinx.coroutines.flow.flowOf(emptyList<com.thrym.app.domain.model.TrainingPlan>())
                override suspend fun getPlanById(id: Long): com.thrym.app.domain.model.TrainingPlan? = null
                override suspend fun savePlan(plan: com.thrym.app.domain.model.TrainingPlan): Long = error("Read-only fixture")
                override suspend fun deletePlan(planId: Long) = Unit
            }
        }
        viewModelOf(::ProgressionReviewViewModel)
    }

    /**
     * Isolation: Vor JEDEM Test App-DB und DataStore leeren. Der Workout-E2E-Test
     * schreibt ueber die echten Repositories in die echte Datenbank; ohne das
     * Leeren wuerde er die anderen Smoke-Tests vergiften (z.B. eine uebrige
     * aktive Session wuerde aus "Training starten" ein "Training fortsetzen"
     * machen). Die DB wird nur einmal pro Prozess erzeugt (Koin-Single), daher
     * reicht clearAllTables(); der Seed-Callback laeuft nur bei der Erzeugung.
     */
    @Before
    fun clearAppDatabaseAndDataStore() {
        runBlocking {
            GlobalContext.get().get<ThrymDatabase>().clearAllTables()
            ApplicationProvider.getApplicationContext<Context>()
                .appPreferencesDataStore.edit { it.clear() }
            ApplicationProvider.getApplicationContext<Context>()
                .onboardingDataStore.edit { it.clear() }
        }
    }

    /** UI-Text aus den Ressourcen, wie die App ihn aufloest: der Test laeuft in jeder Geraetesprache. */
    private fun text(@StringRes id: Int, vararg args: Any): String = composeRule.activity.getString(id, *args)

    private fun quantityText(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
        composeRule.activity.resources.getQuantityString(id, quantity, *args)

    /** Wie waitUntil, schreibt bei einem Timeout aber den Semantik-Baum in die Fehlermeldung. */
    private fun waitOrDump(timeoutMillis: Long, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis) { condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "${e.message}\n${composeRule.onAllNodes(isRoot()).printToString(maxDepth = 60)}",
                e
            )
        }
    }

    @Test
    fun bottom_nav_clicks_switch_top_level_destinations() {
        lateinit var navController: NavHostController

        composeRule.setContent {
            ThrymTheme {
                navController = rememberNavController()
                val navBackStackEntry = navController.currentBackStackEntryAsState().value
                val currentRoute = navBackStackEntry?.destination?.route
                val showBottomBar = currentRoute in listOf(
                    Screen.Dashboard.route,
                    Screen.WorkoutHistory.route,
                    Screen.ExerciseLibrary.route,
                    Screen.TrainingPlanList.route
                )

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            BottomNavBar(navController)
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        ThrymNavHost(navController = navController)
                    }
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(Screen.Dashboard.route, navController.currentBackStackEntry?.destination?.route)
        }

        composeRule.onNodeWithTag("bottom_nav_plans", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.TrainingPlanList.route, navController.currentBackStackEntry?.destination?.route)
        }

        composeRule.onNodeWithTag("bottom_nav_history", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.WorkoutHistory.route, navController.currentBackStackEntry?.destination?.route)
        }

        composeRule.onNodeWithTag("bottom_nav_exercises", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.ExerciseLibrary.route, navController.currentBackStackEntry?.destination?.route)
        }

        composeRule.onNodeWithTag("bottom_nav_home", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.Dashboard.route, navController.currentBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun dashboard_to_history_detail_stats_smoke_with_shared_transition_path() {
        lateinit var navController: NavHostController

        composeRule.setContent {
            ThrymTheme {
                navController = rememberNavController()
                ThrymNavHost(navController = navController)
            }
        }

        composeRule.runOnIdle {
            assertEquals(Screen.Dashboard.route, navController.currentBackStackEntry?.destination?.route)
        }

        composeRule.runOnIdle {
            navController.navigate(Screen.WorkoutHistory.route)
        }
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText(text(R.string.history_title)).fetchSemanticsNodes().isNotEmpty()
        )
        composeRule.runOnIdle {
            assertEquals(Screen.WorkoutHistory.route, navController.currentBackStackEntry?.destination?.route)
        }

        composeRule.runOnIdle {
            navController.navigate(Screen.WorkoutDetail.createRoute(sessionId = 1L))
        }
        composeRule.waitForIdle()
        assertTrue(
            composeRule.onAllNodesWithText(text(R.string.workout_detail_title)).fetchSemanticsNodes().isNotEmpty()
        )
        composeRule.runOnIdle {
            assertEquals(Screen.WorkoutDetail.route, navController.currentBackStackEntry?.destination?.route)
        }

        composeRule.runOnIdle {
            navController.navigate(Screen.ExerciseStats.createRoute(exerciseId = 1L))
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.ExerciseStats.route, navController.currentBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun onboarding_sets_unit_and_finishes_on_dashboard() {
        lateinit var navController: NavHostController

        composeRule.setContent {
            ThrymTheme {
                navController = rememberNavController()
                ThrymNavHost(navController = navController, startDestination = Screen.Onboarding.route)
            }
        }

        composeRule.onNodeWithText(text(OnboardingR.string.onboarding_next)).assertIsDisplayed().performClick()
        waitOrDump(5_000) {
            composeRule.onAllNodesWithText(text(OnboardingR.string.onboarding_units_imperial))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(OnboardingR.string.onboarding_units_imperial)).performClick()
        waitOrDump(5_000) {
            runBlocking {
                GlobalContext.get().get<AppPreferencesRepository>().preferences.first().unitSystem
            } == UnitSystem.IMPERIAL
        }

        composeRule.onNodeWithText(text(OnboardingR.string.onboarding_next)).performClick()
        waitOrDump(5_000) {
            composeRule.onAllNodesWithText(text(OnboardingR.string.onboarding_start_button))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(OnboardingR.string.onboarding_start_button)).performClick()

        waitOrDump(5_000) {
            navController.currentBackStackEntry?.destination?.route == Screen.Dashboard.route
        }
        composeRule.runOnIdle {
            // The onboarding left the back stack: back from the dashboard closes the app.
            assertTrue(navController.previousBackStackEntry == null)
            assertTrue(runBlocking { GlobalContext.get().get<OnboardingRepository>().isCompleted() })
        }
    }

    @Test
    fun dashboard_to_settings_smoke() {
        lateinit var navController: NavHostController

        composeRule.setContent {
            ThrymTheme {
                navController = rememberNavController()
                ThrymNavHost(navController = navController)
            }
        }

        composeRule.runOnIdle {
            assertEquals(Screen.Dashboard.route, navController.currentBackStackEntry?.destination?.route)
        }

        composeRule.runOnIdle {
            navController.navigate(Screen.Settings.route)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(Screen.Settings.route, navController.currentBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun dashboard_has_primary_action_and_settings_accessibility_label() {
        composeRule.setContent {
            ThrymTheme {
                val navController = rememberNavController()
                ThrymNavHost(navController = navController)
            }
        }

        composeRule.onNodeWithContentDescription(text(R.string.settings_title)).assertIsDisplayed()

        // Die Isolation (DB + DataStore werden vor jedem Test geleert) garantiert:
        // es gibt keine aktive Session, also MUSS "Training starten" erscheinen.
        // Ein schreibender Test darf hier kein "Training fortsetzen" hinterlassen.
        val start = text(R.string.dashboard_hero_start)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText(start).fetchSemanticsNodes().isNotEmpty()
        }

        val hasContinue =
            composeRule.onAllNodesWithText(text(R.string.dashboard_hero_continue)).fetchSemanticsNodes().isNotEmpty()
        assertFalse("Nach dem Leeren der App-DB darf keine aktive Session existieren", hasContinue)
    }

    @Test
    fun settings_shows_appearance_controls() {
        lateinit var navController: NavHostController

        composeRule.setContent {
            ThrymTheme {
                navController = rememberNavController()
                ThrymNavHost(navController = navController)
            }
        }

        composeRule.runOnIdle {
            navController.navigate(Screen.Settings.route)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(text(R.string.settings_theme_mode_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_dynamic_color_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_reduced_motion_title)).assertIsDisplayed()
    }

    @Test
    fun workout_flow_starts_logs_set_finishes_and_appears_in_history() {
        // Echte App-Pfade: DB + DataStore wurden im @Before geleert, daher gibt es
        // keine Uebungen mehr (der Seed laeuft nur bei DB-Erzeugung). Eine eigene
        // Uebung ueber das echte Repository anlegen, damit der Picker etwas zeigt.
        val exerciseId = runBlocking {
            GlobalContext.get().get<ExerciseRepository>().addCustomExercise(
                Exercise(
                    name = "Kniebeuge",
                    primaryMuscleGroup = MuscleGroup.BEINE,
                    category = ExerciseCategory.LANGHANTEL
                )
            )
        }
        assertTrue(exerciseId > 0L)

        lateinit var navController: NavHostController

        composeRule.setContent {
            ThrymTheme {
                navController = rememberNavController()
                ThrymNavHost(navController = navController)
            }
        }

        // 1) Dashboard: Training starten (dank Isolation garantiert "starten",
        //    nicht "fortsetzen").
        val start = text(R.string.dashboard_hero_start)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText(start).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(start).performClick()
        composeRule.waitForIdle()

        // 2) Plan-Auswahl-Sheet: Freies Training waehlen -> Session startet.
        val freeWorkout = text(R.string.plan_selection_free_workout)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText(freeWorkout).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(freeWorkout).performClick()
        composeRule.waitForIdle()

        // 3) Workout-Screen: Uebung hinzufuegen -> Picker oeffnet.
        val addExercise = text(R.string.workout_add_exercise)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText(addExercise).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(addExercise).performClick()
        composeRule.waitForIdle()

        // 4) Picker: angelegte Uebung waehlen.
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText("Kniebeuge").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Kniebeuge").performClick()
        composeRule.waitForIdle()
        // Der Picker ist eine Mehrfachauswahl: Auswahl explizit uebernehmen.
        composeRule.onNodeWithText(quantityText(WorkoutR.plurals.workout_picker_add_selected, 1, 1)).performClick()
        composeRule.waitForIdle()

        // 5) Satz loggen: exakt 2 Eingabefelder (Gewicht, Wdh) - RPE/RIR steckt
        //    eingeklappt unter den Zusatzangaben. Der Picker muss zu sein (kein
        //    Suchfeld mehr), sonst waere die Reihenfolge nicht determiniert.
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 2
        }
        val setInputs = composeRule.onAllNodes(hasSetTextAction())
        setInputs[0].performTextInput("100") // Gewicht in kg
        setInputs[1].performTextInput("10") // Wiederholungen
        // Der Log-Button ist erst aktiv, wenn beide Eingaben uebernommen sind.
        val logFirstSet = text(R.string.workout_log_set_button_short, 1)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodes(hasText(logFirstSet, substring = true) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Die Tastatur aus der Eingabe verschiebt das Layout; erst nach dem Idle in den
        // sichtbaren Bereich scrollen und klicken, dann auf den gespeicherten Satz warten
        // (eine Session ohne Satz wuerde beim Beenden still verworfen).
        composeRule.waitForIdle()
        composeRule.onNode(hasText(logFirstSet, substring = true)).performScrollTo().performClick()
        val oneSetLogged = quantityText(R.plurals.workout_header_progress_free, 1, 1)
        waitOrDump(30_000L) {
            composeRule.onAllNodesWithText(oneSetLogged).fetchSemanticsNodes().isNotEmpty()
        }

        // 6) Beenden: Top-Bar-Aktion, dann im Dialog "Training beenden" bestaetigen
        //    und die Zusammenfassung ueber "Fertig" schliessen.
        composeRule.onNodeWithText(text(R.string.workout_finish_action)).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText(text(R.string.workout_finish_dialog_title)).fetchSemanticsNodes().isNotEmpty()
        }
        // Der Dialog bleibt gesperrt ("Speichert …"), bis der geloggte Satz ganz
        // gespeichert ist; erst dann erscheint der aktive Bestaetigen-Button.
        val finishConfirm = text(R.string.workout_finish_confirm)
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodes(hasText(finishConfirm) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(finishConfirm).performClick()
        waitOrDump(30_000L) {
            composeRule.onAllNodesWithText(text(R.string.workout_summary_headline_complete)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(R.string.workout_summary_done)).performClick()
        composeRule.waitForIdle()

        // 7) Zurueck auf dem Dashboard (Finish poppt zum Dashboard zurueck).
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText(start).fetchSemanticsNodes().isNotEmpty()
        }

        // 8) Verlauf pruefen: die Test-Komposition zeichnet wie alle Smoke-Tests
        //    nur ThrymNavHost ohne Scaffold/Bottom-Bar (die Bar existiert nur in
        //    MainActivity), daher direkt zur History-Route navigieren und die
        //    abgeschlossene Session mit heutigem Datum verifizieren.
        composeRule.runOnIdle {
            navController.navigate(Screen.WorkoutHistory.route)
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText(text(R.string.history_title)).fetchSemanticsNodes().isNotEmpty()
        }
        val locale = composeRule.activity.resources.uiLocale()
        val expectedDate = LocalDateTime.now().format(DateFormatting.dateFull(locale))
        composeRule.waitUntil(timeoutMillis = 30_000L) {
            composeRule.onAllNodesWithText(expectedDate).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            "Der Verlauf darf nach dem Workout nicht leer sein",
            0,
            composeRule.onAllNodesWithText(text(R.string.history_empty_title)).fetchSemanticsNodes().size
        )
    }

    @Test
    fun progression_review_route_shows_review_top_bar_without_stopping_application_koin() {
        lateinit var navController: NavHostController
        val showReview = mutableStateOf(true)
        val applicationKoin = GlobalContext.get()
        val progressionReviewKoin = koinApplication {
            modules(progressionReviewModule)
        }

        composeRule.setContent {
            if (showReview.value) {
                KoinIsolatedContext(context = progressionReviewKoin) {
                    ThrymTheme {
                        navController = rememberNavController()
                        ThrymNavHost(
                            navController = navController,
                            startDestination = Screen.ProgressionReview.createRoute(0L)
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag("progression_review_top_bar").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(
                Screen.ProgressionReview.route,
                navController.currentBackStackEntry?.destination?.route
            )
        }

        composeRule.runOnIdle {
            showReview.value = false
        }
        composeRule.waitForIdle()

        assertSame(applicationKoin, GlobalContext.get())
        progressionReviewKoin.close()
    }
}

private class NavigationSmokeExerciseRepository : ExerciseRepository {
    private val exercises = MutableStateFlow<List<Exercise>>(emptyList())

    override fun getAllExercises(): Flow<List<Exercise>> = exercises

    override fun getExercisesByMuscleGroup(muscleGroup: MuscleGroup): Flow<List<Exercise>> =
        exercises

    override fun searchExercises(query: String): Flow<List<Exercise>> = exercises

    override suspend fun getExerciseById(id: Long): Exercise? =
        exercises.value.firstOrNull { it.id == id }

    override suspend fun getExercisesByIds(ids: List<Long>): List<Exercise> =
        exercises.value.filter { it.id in ids }

    override suspend fun addCustomExercise(exercise: Exercise): Long {
        val nextId = (exercises.value.maxOfOrNull { it.id } ?: 0L) + 1L
        exercises.value = exercises.value + exercise.copy(id = nextId)
        return nextId
    }

    override suspend fun updateCustomExercise(exercise: Exercise) {
        exercises.value = exercises.value.map { if (it.id == exercise.id) exercise else it }
    }

    override suspend fun deleteCustomExercise(id: Long) {
        exercises.value = exercises.value.filterNot { it.id == id }
    }
}

private class NavigationSmokeProgressionRepository : ProgressionRepository {
    override fun observeTargetsForSession(sessionId: Long): Flow<List<WorkoutPlanTarget>> =
        MutableStateFlow(emptyList())

    override fun observeReviewItems(sessionId: Long?): Flow<List<ProgressionSuggestion>> =
        MutableStateFlow(emptyList())

    override fun observePendingCount(): Flow<Int> = MutableStateFlow(0)

    override suspend fun generateOutcomesForSession(sessionId: Long) =
        ProgressionGenerationResult(insertedCount = 0, reviewItemCount = 0, pendingCount = 0)

    override suspend fun generateMissingOutcomes(): Int = 0

    override suspend fun reconcileOutstandingSuggestions(): Set<Long> = emptySet()

    override suspend fun acceptSuggestions(
        finalTargetsBySuggestionId: Map<Long, ProgressionTarget>
    ): ProgressionDecisionResult = ProgressionDecisionResult.Accepted(finalTargetsBySuggestionId.keys)

    override suspend fun rejectSuggestion(suggestionId: Long) = Unit
}

private class NavigationSmokePreferencesRepository : AppPreferencesRepository {
    private val state = MutableStateFlow(AppPreferences())
    override val preferences: Flow<AppPreferences> = state

    override suspend fun updateUnitSystem(unitSystem: UnitSystem) {
        state.value = state.value.copy(unitSystem = unitSystem)
    }

    override suspend fun updateWeekStart(weekStart: WeekStart) {
        state.value = state.value.copy(weekStart = weekStart)
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        state.value = state.value.copy(themeMode = themeMode)
    }

    override suspend fun updateThemeScheme(themeScheme: ThemeScheme) {
        state.value = state.value.copy(themeScheme = themeScheme)
    }

    override suspend fun updateAppearanceStyle(appearanceStyle: com.thrym.app.domain.model.AppearanceStyle) {
        state.value = state.value.copy(appearanceStyle = appearanceStyle)
    }

    override suspend fun updateUseDynamicColor(enabled: Boolean) {
        state.value = state.value.copy(useDynamicColor = enabled)
    }

    override suspend fun updateReducedMotion(enabled: Boolean) {
        state.value = state.value.copy(reducedMotion = enabled)
    }

    override suspend fun updateDefaultWarmupFlag(enabled: Boolean) {
        state.value = state.value.copy(defaultWarmupFlag = enabled)
    }

    override suspend fun updateTimerKeepScreenOn(enabled: Boolean) {
        state.value = state.value.copy(timerKeepScreenOn = enabled)
    }

    override suspend fun updateBetaDiagnosticsOptIn(enabled: Boolean) {
        state.value = state.value.copy(betaDiagnosticsOptIn = enabled)
    }

    override suspend fun updateReminderConfig(config: ReminderConfig) {
        state.value = state.value.copy(reminderConfig = config)
    }

    override suspend fun updateIntensitySystem(intensitySystem: IntensitySystem) {
        state.value = state.value.copy(intensitySystem = intensitySystem)
    }

    override suspend fun updateShareWeightHistoryAcrossContexts(enabled: Boolean) {
        state.value = state.value.copy(shareWeightHistoryAcrossContexts = enabled)
    }

    override suspend fun updateAutoRestTimerEnabled(enabled: Boolean) {
        state.value = state.value.copy(autoRestTimerEnabled = enabled)
    }

    override suspend fun updateDefaultRestTimeSeconds(seconds: Int) {
        state.value = state.value.copy(defaultRestTimeSeconds = seconds)
    }

    override suspend fun updatePlateCalculatorEnabled(enabled: Boolean) {
        state.value = state.value.copy(plateCalculatorEnabled = enabled)
    }

    override suspend fun updateAvailablePlates(plates: List<Double>) {
        state.value = state.value.copy(availablePlates = plates)
    }

    override suspend fun updateBarbellWeightKg(weightKg: Double) {
        state.value = state.value.copy(barbellWeightKg = weightKg)
    }

    override suspend fun updateDeloadMode(mode: com.thrym.app.domain.model.DeloadMode?) {
        state.value = state.value.copy(deloadMode = mode)
    }

    override suspend fun updateLastSuccessfulExportEpochMillis(timestampMillis: Long?) {
        state.value = state.value.copy(lastSuccessfulExportEpochMillis = timestampMillis)
    }

    override suspend fun updateBackupReminderEnabled(enabled: Boolean) {
        state.value = state.value.copy(backupReminderEnabled = enabled)
    }

    override suspend fun updateWeeklyGoalEnabled(enabled: Boolean) {
        state.value = state.value.copy(weeklyGoalEnabled = enabled)
    }

    override suspend fun updateWeeklyGoalHistory(history: List<WeeklyGoalChange>) {
        state.value = state.value.copy(weeklyGoalHistory = history)
    }
}
