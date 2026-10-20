package com.thrym.app.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector
import com.thrym.core.designsystem.R

sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Onboarding : Screen("onboarding")
    data object Settings : Screen("settings")
    data object ActiveWorkout : Screen("active_workout/{sessionId}?planId={planId}&metaPlanId={metaPlanId}") {
        fun createRoute(sessionId: Long, planId: Long = 0L, metaPlanId: Long = 0L): String {
            return if (planId > 0L || metaPlanId > 0L) {
                "active_workout/$sessionId?planId=$planId&metaPlanId=$metaPlanId"
            } else {
                "active_workout/$sessionId?planId=0&metaPlanId=0"
            }
        }
    }
    data object ProgressionReview : Screen("progression_review?sessionId={sessionId}") {
        fun createRoute(sessionId: Long? = null): String =
            "progression_review?sessionId=${sessionId ?: 0L}"
    }
    data object ExerciseLibrary : Screen("exercises")
    data object WorkoutHistory : Screen("history")
    data object WorkoutDetail : Screen("workout_detail/{sessionId}") {
        fun createRoute(sessionId: Long) = "workout_detail/$sessionId"
    }
    data object ExerciseStats : Screen("exercise_stats/{exerciseId}") {
        fun createRoute(exerciseId: Long) = "exercise_stats/$exerciseId"
    }
    data object TrainingPlanList : Screen("plans")
    data object PlanEditorNew : Screen("plans/new")
    data object PlanEditor : Screen("plans/edit/{planId}") {
        fun createRoute(planId: Long) = "plans/edit/$planId"
    }
    data object MetaPlanEditorNew : Screen("meta_plans/new")
    data object MetaPlanEditor : Screen("meta_plans/edit/{metaPlanId}") {
        fun createRoute(metaPlanId: Long) = "meta_plans/edit/$metaPlanId"
    }
}

enum class BottomNavItem(
    val screen: Screen,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val testTag: String
) {
    HOME(Screen.Dashboard, R.string.nav_home, Icons.Default.Home, "bottom_nav_home"),
    PLANS(Screen.TrainingPlanList, R.string.nav_plans, Icons.AutoMirrored.Filled.List, "bottom_nav_plans"),
    HISTORY(Screen.WorkoutHistory, R.string.nav_history, Icons.Default.History, "bottom_nav_history"),
    EXERCISES(Screen.ExerciseLibrary, R.string.nav_exercises, Icons.Default.FitnessCenter, "bottom_nav_exercises"),
}
