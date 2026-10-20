package com.thrym.shared.recognition

import com.thrym.shared.model.WeekStart

/**
 * One completed workout for the milestones. [orderKey] sorts workouts that share a day (the
 * start in epoch millis); [id] breaks ties.
 */
data class MilestoneSession(
    val id: Long,
    val epochDay: Long,
    val orderKey: Long,
    val isDeload: Boolean?,
)

/** The facts the completion screen may name, at most two of them ([rows]). */
enum class CompletionFactKind { WEEKLY_GOAL, JUBILEE, COMEBACK }

/**
 * Facts about one finished workout. [trainingNumber] is its place among all completed
 * workouts, [firstTrainingEpochDay] the day of the first one. [weeklyGoal] is set when this
 * workout reached the week's goal. [pauseDays] is set when it ended a pause of at least
 * [TrainingMilestones.COMEBACK_MIN_DAYS] days.
 */
data class CompletionFacts(
    val trainingNumber: Int,
    val firstTrainingEpochDay: Long,
    val isJubilee: Boolean,
    val weeklyGoal: WeeklyGoalStatus?,
    val pauseDays: Int?,
) {
    /** The rows to show, in this order, never more than two. */
    val rows: List<CompletionFactKind>
        get() = listOfNotNull(
            CompletionFactKind.WEEKLY_GOAL.takeIf { weeklyGoal != null },
            CompletionFactKind.JUBILEE.takeIf { isJubilee },
            CompletionFactKind.COMEBACK.takeIf { pauseDays != null },
        ).take(2)
}

/**
 * Milestones Thrym names in plain words: the workout number ("Training 100"), workout
 * jubilees, the weekly goal reached and the return after a pause. Only facts from the history,
 * never what is missing.
 */
object TrainingMilestones {
    /** A pause of three weeks or more makes the next workout a comeback. */
    const val COMEBACK_MIN_DAYS = 21

    /** 10, 25, 50, every 50 up to 300, every 100 up to 1000, then every 250. */
    fun isJubilee(trainingNumber: Int): Boolean = when {
        trainingNumber <= 0 -> false
        trainingNumber < 100 -> trainingNumber in setOf(10, 25, 50)
        trainingNumber <= 300 -> trainingNumber % 50 == 0
        trainingNumber <= 1000 -> trainingNumber % 100 == 0
        else -> trainingNumber % 250 == 0
    }

    /** Days between the last workout and [todayEpochDay] when they make a pause, else null. */
    fun pauseDays(lastEpochDay: Long?, todayEpochDay: Long): Int? {
        val last = lastEpochDay ?: return null
        val days = todayEpochDay - last
        return if (days >= COMEBACK_MIN_DAYS) days.toInt() else null
    }

    /**
     * Facts of the workout [sessionId] among [sessions] (all completed workouts, this one
     * included). Later workouts are ignored, so the facts stay the same when the screen opens
     * again. Returns null when [sessionId] is not among [sessions]. [goalHistory] is null when
     * the weekly goal is switched off.
     */
    fun completionFacts(
        sessionId: Long,
        sessions: List<MilestoneSession>,
        weekStart: WeekStart,
        goalHistory: List<WeeklyGoalChange>?,
    ): CompletionFacts? {
        val ordered = sessions.distinctBy { it.id }.sortedWith(compareBy({ it.orderKey }, { it.id }))
        val index = ordered.indexOfFirst { it.id == sessionId }
        if (index < 0) return null
        val session = ordered[index]
        val upToThis = ordered.subList(0, index + 1)
        val number = index + 1

        val weeklyGoal = goalHistory?.let { history ->
            val status = WeeklyGoalEngine.evaluate(
                todayEpochDay = session.epochDay,
                weekStart = weekStart,
                sessions = upToThis.map { WeeklyGoalSession(it.epochDay, it.isDeload) },
                history = history,
            )
            status.takeIf { it.completedThisWeek == it.goal }
        }
        val previous = ordered.getOrNull(index - 1)
        return CompletionFacts(
            trainingNumber = number,
            firstTrainingEpochDay = ordered.first().epochDay,
            isJubilee = isJubilee(number),
            weeklyGoal = weeklyGoal,
            pauseDays = pauseDays(previous?.epochDay, session.epochDay),
        )
    }
}
