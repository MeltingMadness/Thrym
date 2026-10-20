package com.thrym.shared.recognition

import com.thrym.shared.model.WeekStart
import kotlinx.serialization.Serializable

/**
 * A weekly goal that applies from the week holding [effectiveEpochDay] on. Days count from
 * 1970-01-01, in the device's local calendar; the adapter converts timestamps.
 */
@Serializable
data class WeeklyGoalChange(
    val effectiveEpochDay: Long,
    val goal: Int,
)

/** One completed workout as the weekly goal sees it: its local start day and deload flag. */
data class WeeklyGoalSession(
    val epochDay: Long,
    val isDeload: Boolean?,
)

/**
 * How a finished week counts. [MET]: the goal was reached. [DELOAD]: at least one workout ran
 * under the deload mode, planned rest keeps the streak. [BUFFER]: a buffer week covered a miss.
 * [MISSED]: the streak ended here. Runtime only, never stored.
 */
enum class WeekOutcome { MET, DELOAD, BUFFER, MISSED }

data class WeeklyGoalWeek(
    val weekStartEpochDay: Long,
    val goal: Int,
    val completed: Int,
    val outcome: WeekOutcome,
)

/**
 * The weekly goal today. [streakWeeks] counts the held weeks in a row up to the last finished
 * week, plus the running week once its goal is met. The running week never breaks the streak.
 * [buffers] are the buffer weeks in stock. [weeks] lists every finished week from the first
 * workout on, oldest first.
 */
data class WeeklyGoalStatus(
    val goal: Int,
    val completedThisWeek: Int,
    val goalMetThisWeek: Boolean,
    val streakWeeks: Int,
    val buffers: Int,
    val longestStreakWeeks: Int,
    val weeks: List<WeeklyGoalWeek>,
)

/**
 * Rules of the weekly goal: a number of completed workouts per calendar week, 2 to 7.
 *
 * A finished week is held when it reaches its goal, when one of its workouts ran as a deload,
 * or when a buffer week covers it. Every 4 weeks held by goal or deload earn one buffer week,
 * at most 2 in stock; a buffer week earns nothing. The goal is a setting, so a change only
 * applies from the running week on: [WeeklyGoalChange] entries keep the goal each week had.
 * Everything else is derived from the history, so a backup import gives the same result.
 */
object WeeklyGoalRules {
    const val MIN_GOAL = 2
    const val MAX_GOAL = 7
    const val DEFAULT_GOAL = 3
    const val HELD_WEEKS_PER_BUFFER = 4
    const val MAX_BUFFERS = 2

    fun clampGoal(goal: Int): Int = goal.coerceIn(MIN_GOAL, MAX_GOAL)

    /** ISO day of week (Monday = 1) of [epochDay]; 1970-01-01 was a Thursday. */
    fun isoDayOfWeek(epochDay: Long): Int = (((epochDay + 3) % 7 + 7) % 7).toInt() + 1

    /** First day of the week that holds [epochDay]. */
    fun weekStartOf(epochDay: Long, weekStart: WeekStart): Long {
        val anchor = if (weekStart == WeekStart.SUNDAY) 7 else 1
        val offset = ((isoDayOfWeek(epochDay) - anchor) % 7 + 7) % 7
        return epochDay - offset
    }

    /**
     * Goal of the week starting at [weekStartEpochDay]: the last change inside or before that
     * week. A week before the first change takes the first goal, an empty history the default.
     */
    fun goalFor(history: List<WeeklyGoalChange>, weekStartEpochDay: Long): Int {
        val sorted = history.sortedBy { it.effectiveEpochDay }
        val weekEnd = weekStartEpochDay + 6
        val change = sorted.lastOrNull { it.effectiveEpochDay <= weekEnd } ?: sorted.firstOrNull()
        return clampGoal(change?.goal ?: DEFAULT_GOAL)
    }

    /**
     * History after setting [goal] in the week starting at [currentWeekStartEpochDay]. Earlier
     * weeks keep their goal: an empty history first records the default for the past.
     */
    fun withChange(
        history: List<WeeklyGoalChange>,
        currentWeekStartEpochDay: Long,
        goal: Int,
    ): List<WeeklyGoalChange> {
        val clamped = clampGoal(goal)
        val past = history.filter { it.effectiveEpochDay < currentWeekStartEpochDay }.sortedBy { it.effectiveEpochDay }
        val base = past.ifEmpty { listOf(WeeklyGoalChange(0L, goalFor(history, currentWeekStartEpochDay - 7))) }
        val changed = if (base.last().goal == clamped) base else base + WeeklyGoalChange(currentWeekStartEpochDay, clamped)
        return changed
    }

    /** Compact text for a settings store: "0:3,20380:4". */
    fun encode(history: List<WeeklyGoalChange>): String =
        history.sortedBy { it.effectiveEpochDay }.joinToString(",") { "${it.effectiveEpochDay}:${it.goal}" }

    /** Reads [encode]'s text; unreadable entries are skipped, goals are clamped to 2–7. */
    fun decode(text: String?): List<WeeklyGoalChange> =
        text.orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.trim().split(':')
            if (parts.size != 2) return@mapNotNull null
            val day = parts[0].toLongOrNull() ?: return@mapNotNull null
            val goal = parts[1].toIntOrNull() ?: return@mapNotNull null
            WeeklyGoalChange(day, clampGoal(goal))
        }.sortedBy { it.effectiveEpochDay }
}

object WeeklyGoalEngine {

    /**
     * Evaluates the weekly goal on [todayEpochDay]. [sessions] are the completed workouts;
     * workouts after today are ignored. Pure: no clock, no time zone, no storage.
     */
    fun evaluate(
        todayEpochDay: Long,
        weekStart: WeekStart,
        sessions: List<WeeklyGoalSession>,
        history: List<WeeklyGoalChange>,
    ): WeeklyGoalStatus {
        val currentWeek = WeeklyGoalRules.weekStartOf(todayEpochDay, weekStart)
        val byWeek = sessions
            .filter { it.epochDay <= todayEpochDay }
            .groupBy { WeeklyGoalRules.weekStartOf(it.epochDay, weekStart) }
        val goal = WeeklyGoalRules.goalFor(history, currentWeek)
        val completedThisWeek = byWeek[currentWeek].orEmpty().size
        val goalMetThisWeek = completedThisWeek >= goal

        val weeks = mutableListOf<WeeklyGoalWeek>()
        var run = 0
        var longest = 0
        var buffers = 0
        var heldSinceBuffer = 0
        val firstWeek = byWeek.keys.minOrNull()
        if (firstWeek != null) {
            var week = firstWeek
            while (week < currentWeek) {
                val workouts = byWeek[week].orEmpty()
                val weekGoal = WeeklyGoalRules.goalFor(history, week)
                val outcome = when {
                    workouts.size >= weekGoal -> WeekOutcome.MET
                    workouts.any { it.isDeload == true } -> WeekOutcome.DELOAD
                    buffers > 0 -> WeekOutcome.BUFFER
                    else -> WeekOutcome.MISSED
                }
                when (outcome) {
                    WeekOutcome.MET, WeekOutcome.DELOAD -> {
                        run++
                        heldSinceBuffer++
                        if (heldSinceBuffer == WeeklyGoalRules.HELD_WEEKS_PER_BUFFER) {
                            buffers = minOf(WeeklyGoalRules.MAX_BUFFERS, buffers + 1)
                            heldSinceBuffer = 0
                        }
                    }
                    WeekOutcome.BUFFER -> {
                        run++
                        buffers--
                    }
                    WeekOutcome.MISSED -> {
                        run = 0
                        heldSinceBuffer = 0
                    }
                }
                longest = maxOf(longest, run)
                weeks += WeeklyGoalWeek(week, weekGoal, workouts.size, outcome)
                week += 7
            }
        }
        val streak = run + if (goalMetThisWeek) 1 else 0
        return WeeklyGoalStatus(
            goal = goal,
            completedThisWeek = completedThisWeek,
            goalMetThisWeek = goalMetThisWeek,
            streakWeeks = streak,
            buffers = buffers,
            longestStreakWeeks = maxOf(longest, streak),
            weeks = weeks,
        )
    }
}
