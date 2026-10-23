package com.thrym.app.presentation.workout

/** Timer selection and ordering for the Liquid Glass dock, independent of Compose state. */
internal data class RestDockRow(
    val exerciseKey: WorkoutExerciseKey,
    val stableKey: String,
    val timer: RestTimerUi,
    val remainingSeconds: Long
)

// One dock row is at least 60 dp plus 8 dp padding on both sides, with 8 dp between rows.
internal const val REST_DOCK_ROW_HEIGHT_DP = 76f
internal const val REST_DOCK_ROW_SPACING_DP = 8f
private const val MAX_VISIBLE_REST_DOCK_ROWS = 3

internal data class RestDockStartDecision(
    val seenStarts: List<Long>,
    val openKey: WorkoutExerciseKey?
)

internal fun buildRestDockRows(
    timers: Map<WorkoutExerciseKey, RestTimerUi>,
    nowMillis: Long
): List<RestDockRow> {
    val rows = timers.map { (key, timer) -> restDockRow(key, timer, nowMillis) }
    val countdowns = rows.filter { it.timer.durationSeconds > 0 }
        .sortedWith(compareBy<RestDockRow> { it.remainingSeconds }
            .thenBy { it.timer.startTime }
            .thenBy { it.stableKey })
    // Every count-up keeps its own row, the longest running first, so a superset without an
    // automatic pause shows both rests.
    val countUps = rows.filter { it.timer.durationSeconds <= 0 }
        .sortedWith(compareBy<RestDockRow> { it.timer.startTime }
            .thenBy { it.stableKey })
    return countdowns + countUps
}

/** Record every current start, including starts that did not open the pause. */
internal fun decideRestDockStart(
    previouslySeenStarts: List<Long>,
    timers: Map<WorkoutExerciseKey, RestTimerUi>
): RestDockStartDecision {
    val currentStarts = timers.values.map { it.startTime.toEpochMilli() }.distinct().sorted()
    val newStarts = currentStarts.toSet() - previouslySeenStarts.toSet()
    val onlyTimer = timers.entries.singleOrNull()
    return RestDockStartDecision(
        seenStarts = currentStarts,
        openKey = onlyTimer?.takeIf { (_, timer) ->
            timer.durationSeconds > 0 && timer.startTime.toEpochMilli() in newStarts
        }?.key
    )
}

/** A pinned row stays open until its actual timer is removed. */
internal fun resolvePinnedRestRow(
    pinnedKey: String?,
    timers: Map<WorkoutExerciseKey, RestTimerUi>,
    nowMillis: Long
): RestDockRow? {
    if (pinnedKey == null) return null
    val (key, timer) = timers.entries.firstOrNull { it.key.stableListKey() == pinnedKey }
        ?: return null
    return restDockRow(key, timer, nowMillis)
}

private fun restDockRow(key: WorkoutExerciseKey, timer: RestTimerUi, nowMillis: Long): RestDockRow =
    RestDockRow(
        exerciseKey = key,
        stableKey = key.stableListKey(),
        timer = timer,
        remainingSeconds = if (timer.durationSeconds > 0) {
            (timer.durationSeconds.toLong() - (nowMillis - timer.startTime.toEpochMilli()) / 1000)
                .coerceAtLeast(0)
        } else {
            Long.MAX_VALUE
        }
    )

/**
 * How many dock rows fit at once. The dock covers at most half of the workout list, so a short
 * window (a phone in landscape) still leaves room to log a set; further rows scroll in the dock.
 */
internal fun visibleRestDockRows(listHeightDp: Float, outerPaddingDp: Float): Int {
    val available = listHeightDp / 2f - outerPaddingDp * 2f
    val rows = ((available + REST_DOCK_ROW_SPACING_DP) / (REST_DOCK_ROW_HEIGHT_DP + REST_DOCK_ROW_SPACING_DP)).toInt()
    return rows.coerceIn(1, MAX_VISIBLE_REST_DOCK_ROWS)
}
