package com.thrym.app.presentation.workout

/** Timer selection and ordering for the Liquid Glass dock, independent of Compose state. */
internal data class RestDockRow(
    val exerciseKey: WorkoutExerciseKey,
    val stableKey: String,
    val timer: RestTimerUi,
    val remainingSeconds: Long
)

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
    val newestCountUp = rows.filter { it.timer.durationSeconds <= 0 }
        .sortedWith(compareByDescending<RestDockRow> { it.timer.startTime }
            .thenBy { it.stableKey })
        .firstOrNull()
    return if (newestCountUp == null) countdowns else countdowns + newestCountUp
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

/** A hidden count-up can remain pinned; only removal of its actual timer closes the pause. */
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
