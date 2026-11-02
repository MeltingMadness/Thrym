package com.thrym.shared.gamification

import com.thrym.shared.model.UnitSystem
import com.thrym.shared.model.WeekStart
import com.thrym.shared.recognition.MilestoneSession
import com.thrym.shared.recognition.StrengthMarkKind
import com.thrym.shared.recognition.StrengthMarks
import com.thrym.shared.recognition.TrainingMilestones
import com.thrym.shared.recognition.WeeklyGoalChange
import com.thrym.shared.recognition.WeeklyGoalRules
import com.thrym.shared.workout.RecordSetInput
import com.thrym.shared.workout.SessionRecords

/** What a workout earned XP for, in the order the breakdown names it. */
enum class XpSource { TRAINING, COMEBACK, WEEKLY_GOAL, MARK, RECORD, INCREASE }

/** One row of a workout's breakdown: [count] times [source], worth [xp] in total. */
data class XpEntry(
    val source: XpSource,
    val count: Int,
    val xp: Int,
)

/** The XP of one workout. [totalAfter] is the sum of all XP up to and including it. */
data class SessionXp(
    val sessionId: Long,
    val xp: Int,
    val entries: List<XpEntry>,
    val totalAfter: Long,
)

/**
 * The history the XP are derived from. [sessions] are the completed workouts and [sets] their
 * sets; sets of other sessions are ignored. [categoryByExercise] holds the stored category code,
 * [addedLoadExerciseIds] the exercises whose weight is an added load, as in [SessionRecords].
 * [acceptedIncreaseSessionIds] holds the source session once per accepted increase.
 *
 * The goal history counts whether or not the weekly goal is shown: switching it off must not take
 * XP away, and an empty history (after a backup import) means the default goal. The ladders of
 * the marks follow [unitSystem], so changing the unit can move a total by a few XP.
 */
data class GamificationInput(
    val sessions: List<MilestoneSession>,
    val sets: List<RecordSetInput>,
    val categoryByExercise: Map<Long, String>,
    val addedLoadExerciseIds: Set<Long>,
    val acceptedIncreaseSessionIds: List<Long>,
    val weekStart: WeekStart,
    val goalHistory: List<WeeklyGoalChange>,
    val unitSystem: UnitSystem,
)

data class GamificationState(
    val totalXp: Long,
    val progress: LevelProgress,
    val sessions: List<SessionXp>,
) {
    fun session(sessionId: Long): SessionXp? = sessions.firstOrNull { it.sessionId == sessionId }
}

/** [CHAPTER] and [STAR] get a bigger moment than a new [TIER]. */
enum class LevelUpKind { TIER, CHAPTER, STAR }

data class LevelUp(
    val kind: LevelUpKind,
    val progress: LevelProgress,
)

/**
 * What the celebration of a finished workout shows: its XP, the way from [before] to [after] and
 * the level-up, if one is due.
 */
data class SessionCelebration(
    val xp: Int,
    val entries: List<XpEntry>,
    val before: LevelProgress,
    val after: LevelProgress,
    val levelUp: LevelUp?,
)

/**
 * XP of a whole history. Everything is derived, nothing stored: a backup import brings its XP
 * along and both platforms get the same result. Only workouts with a work set earn XP. One pass
 * over the history, oldest first, so a thousand workouts stay fast.
 *
 * A record or mark compares with earlier workouts. The first workout of an exercise brings
 * neither, as in [SessionRecords]. Volume earns nothing by itself: more sets should not pay, more
 * progress should. A workout earns at most [MAX_XP_PER_SESSION] XP.
 */
object GamificationEngine {
    const val TRAINING_XP = 100
    const val FURTHER_TRAINING_SAME_DAY_XP = 25
    const val RECORD_XP = 40
    const val MAX_RECORD_EXERCISES = 3
    const val MARK_XP = 60
    const val INCREASE_XP = 30
    const val MAX_INCREASES = 3
    const val WEEKLY_GOAL_XP = 75
    const val COMEBACK_XP = 150
    const val MAX_XP_PER_SESSION = 400

    fun evaluate(input: GamificationInput): GamificationState {
        val workSets = input.sets.filter(SessionRecords::isWorkSet).groupBy { it.sessionId }
        val increases = input.acceptedIncreaseSessionIds.groupingBy { it }.eachCount()
        val ordered = input.sessions.distinctBy { it.id }
            .filter { it.id in workSets }
            .sortedWith(compareBy({ it.orderKey }, { it.id }))

        val imperial = input.unitSystem == UnitSystem.IMPERIAL
        val bestsByExercise = HashMap<Long, ExerciseBests>()
        val workoutsByWeek = HashMap<Long, Int>()
        val trainedDays = HashSet<Long>()
        var previousDay: Long? = null
        var total = 0L

        val sessions = ordered.map { session ->
            var records = 0
            var marks = 0
            workSets.getValue(session.id).groupBy { it.exerciseId }.forEach { (exerciseId, sets) ->
                val bests = bestsByExercise.getOrPut(exerciseId) { ExerciseBests() }
                val addedLoad = exerciseId in input.addedLoadExerciseIds
                val category = input.categoryByExercise[exerciseId].orEmpty()
                if (bests.beatenBy(sets, addedLoad)) records++
                if (bests.reachesNewMark(sets, category, imperial)) marks++
                bests.update(sets, category, imperial)
            }

            val week = WeeklyGoalRules.weekStartOf(session.epochDay, input.weekStart)
            val workoutsThisWeek = (workoutsByWeek[week] ?: 0) + 1
            workoutsByWeek[week] = workoutsThisWeek
            val goalReached = workoutsThisWeek == WeeklyGoalRules.goalFor(input.goalHistory, week)
            val comeback = TrainingMilestones.pauseDays(previousDay, session.epochDay) != null
            val firstOfDay = trainedDays.add(session.epochDay)
            previousDay = session.epochDay

            val entries = capped(
                listOfNotNull(
                    XpEntry(XpSource.TRAINING, 1, if (firstOfDay) TRAINING_XP else FURTHER_TRAINING_SAME_DAY_XP),
                    XpEntry(XpSource.COMEBACK, 1, COMEBACK_XP).takeIf { comeback },
                    XpEntry(XpSource.WEEKLY_GOAL, 1, WEEKLY_GOAL_XP).takeIf { goalReached },
                    XpEntry(XpSource.MARK, marks, marks * MARK_XP).takeIf { marks > 0 },
                    records.coerceAtMost(MAX_RECORD_EXERCISES).takeIf { it > 0 }
                        ?.let { XpEntry(XpSource.RECORD, it, it * RECORD_XP) },
                    (increases[session.id] ?: 0).coerceAtMost(MAX_INCREASES).takeIf { it > 0 }
                        ?.let { XpEntry(XpSource.INCREASE, it, it * INCREASE_XP) },
                )
            )
            val xp = entries.sumOf { it.xp }
            total += xp
            SessionXp(session.id, xp, entries, total)
        }
        return GamificationState(total, LevelCurve.progress(total), sessions)
    }

    /**
     * The celebration of the workout [sessionId], or null when it earned no XP. [celebratedXp] is
     * the sum this device celebrated last, so a step passed by XP that came in between (an
     * increase accepted later) is still celebrated, and nothing repeats after a deleted workout.
     * Null (first use, after a backup import) means nothing older is caught up on.
     */
    fun celebration(state: GamificationState, sessionId: Long, celebratedXp: Long?): SessionCelebration? {
        val session = state.session(sessionId) ?: return null
        val before = LevelCurve.progress(session.totalAfter - session.xp)
        val after = LevelCurve.progress(session.totalAfter)
        val bar = celebratedXp?.let(LevelCurve::progress) ?: before
        val kind = when {
            after.chapter > bar.chapter -> LevelUpKind.CHAPTER
            after.level > bar.level -> LevelUpKind.TIER
            after.stars > bar.stars -> LevelUpKind.STAR
            else -> null
        }
        return SessionCelebration(session.xp, session.entries, before, after, kind?.let { LevelUp(it, after) })
    }

    /** Trims the last rows so that the entries add up to at most [MAX_XP_PER_SESSION]. */
    private fun capped(entries: List<XpEntry>): List<XpEntry> {
        var left = MAX_XP_PER_SESSION
        return entries.mapNotNull { entry ->
            val xp = entry.xp.coerceAtMost(left)
            left -= xp
            if (xp > 0) entry.copy(xp = xp) else null
        }
    }

    /** The running bests of one exercise over the workouts seen so far. */
    private class ExerciseBests {
        private var trained = false
        private val bests = HashMap<String, Double>()
        private val highestMarks = HashMap<StrengthMarkKind, Int>()

        fun beatenBy(sets: List<RecordSetInput>, addedLoad: Boolean): Boolean =
            trained && SessionRecords.typesFor(addedLoad).any { type ->
                val value = SessionRecords.sessionBest(type, sets) ?: return@any false
                val previous = bests[type] ?: return@any true
                value - previous >= SessionRecords.minStep(type) - RECORD_EPSILON
            }

        fun reachesNewMark(sets: List<RecordSetInput>, category: String, imperial: Boolean): Boolean =
            trained && StrengthMarks.kindsFor(category).any { kind ->
                val now = StrengthMarks.highestReached(kind, imperial, sets) ?: return@any false
                now > (highestMarks[kind] ?: 0)
            }

        fun update(sets: List<RecordSetInput>, category: String, imperial: Boolean) {
            trained = true
            SessionRecords.TYPE_ORDER.forEach { type ->
                val value = SessionRecords.sessionBest(type, sets) ?: return@forEach
                bests[type] = maxOf(value, bests[type] ?: value)
            }
            StrengthMarks.kindsFor(category).forEach { kind ->
                val now = StrengthMarks.highestReached(kind, imperial, sets) ?: return@forEach
                highestMarks[kind] = maxOf(now, highestMarks[kind] ?: now)
            }
        }
    }

    private const val RECORD_EPSILON = 1e-9
}
