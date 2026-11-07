package com.thrym.app.presentation.navigation

import androidx.navigation.NavController
import com.thrym.app.presentation.history.HistoryMode

/** Key on the history entry's SavedStateHandle: a mode the history shows once when it appears. */
internal const val HISTORY_MODE_REQUEST_KEY = "history_mode_request"

/**
 * Switches to a tab of the bottom navigation: one copy per tab, each tab keeps its own stack and
 * gets it back when it is selected again.
 */
fun NavController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(Screen.Dashboard.route) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Opens the history tab at its root in the achievements mode. The tab comes back like a tap in
 * the bottom navigation, without a detail that was still open there; the route stays the same,
 * so the request travels on the entry's SavedStateHandle.
 */
fun NavController.openAchievements() {
    navigateToTopLevel(Screen.WorkoutHistory.route)
    popBackStack(Screen.WorkoutHistory.route, inclusive = false)
    getBackStackEntry(Screen.WorkoutHistory.route).savedStateHandle[HISTORY_MODE_REQUEST_KEY] =
        HistoryMode.ACHIEVEMENTS.name
}
