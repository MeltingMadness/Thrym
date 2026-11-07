package com.thrym.app.presentation.history

/** What the history tab shows: the finished workouts or the achievements. */
enum class HistoryMode { WORKOUTS, ACHIEVEMENTS }

/**
 * What the history shows for a [selected] mode. [mode] is null while the achievements wait for
 * the game-elements setting to load, so that neither the workout list nor the achievements
 * flash for someone who turned game elements off.
 */
internal data class HistoryModeDisplay(
    val mode: HistoryMode?,
    val showSwitcher: Boolean
)

/**
 * The switcher and the achievements only exist with game elements on. Off, the history shows the
 * workouts, also when the achievements were selected or requested. [gamificationEnabled] is null
 * until the setting has loaded.
 */
internal fun resolveHistoryMode(selected: HistoryMode, gamificationEnabled: Boolean?): HistoryModeDisplay =
    when (gamificationEnabled) {
        true -> HistoryModeDisplay(selected, showSwitcher = true)
        false -> HistoryModeDisplay(HistoryMode.WORKOUTS, showSwitcher = false)
        null -> HistoryModeDisplay(
            mode = if (selected == HistoryMode.WORKOUTS) HistoryMode.WORKOUTS else null,
            showSwitcher = false
        )
    }
