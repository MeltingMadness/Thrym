package com.thrym.shared.recognition

import com.thrym.shared.workout.SessionRecordLine
import com.thrym.shared.workout.SessionRecordSummary
import com.thrym.shared.workout.SessionRecords

/**
 * Kind of a highlight. [JUBILEE]: a workout jubilee ("Dein 100. Training"). [COMEBACK]: back after
 * a pause. [MARK]: a round mark reached for the first time. [RECORD]: a beaten record.
 * [WEEKLY_GOAL]: the week's goal reached with this workout.
 */
enum class CompletionHighlightKind { JUBILEE, COMEBACK, MARK, RECORD, WEEKLY_GOAL }

/**
 * One moment the completion celebration names. [exerciseId] is set for [CompletionHighlightKind.MARK]
 * and [CompletionHighlightKind.RECORD]; [mark] and [record] hold what was reached. The workout
 * facts (number, pause, weekly goal) come from [CompletionFacts].
 */
data class CompletionHighlight(
    val kind: CompletionHighlightKind,
    val exerciseId: Long? = null,
    val mark: StrengthMark? = null,
    val record: SessionRecordLine? = null,
)

/** The highlights to celebrate, at most [CompletionHighlights.LIMIT], and how many more there are. */
data class CompletionHighlightSelection(
    val shown: List<CompletionHighlight>,
    val more: Int,
) {
    val isEmpty: Boolean get() = shown.isEmpty()
}

/**
 * The highlights of a finished workout for the celebration after "Beenden". Only wins count:
 * records, marks, a jubilee, a comeback and the weekly goal. A first workout of an exercise is
 * no win and stays in the summary.
 *
 * Each exercise appears once, with its strongest moment: a mark beats a record, a weight record
 * beats an e1RM, reps or volume record. The order is by weight: jubilee, comeback, marks,
 * records, weekly goal; within marks and records the bigger relative gain first, then the
 * workout's order. The celebration shows at most [LIMIT]; the rest stays in the summary.
 */
object CompletionHighlights {
    const val LIMIT = 3

    fun select(
        summary: SessionRecordSummary?,
        marksByExercise: Map<Long, List<StrengthMark>>,
        facts: CompletionFacts?,
        limit: Int = LIMIT,
    ): CompletionHighlightSelection {
        val candidates = mutableListOf<Ranked>()
        if (facts?.isJubilee == true) candidates += Ranked(CompletionHighlight(CompletionHighlightKind.JUBILEE), WEIGHT_JUBILEE, 0.0, 0)
        if (facts?.pauseDays != null) candidates += Ranked(CompletionHighlight(CompletionHighlightKind.COMEBACK), WEIGHT_COMEBACK, 0.0, 0)

        val exerciseOrder = summary?.exercises?.map { it.exerciseId }.orEmpty()
        val orderedExercises = (exerciseOrder + marksByExercise.keys.filter { it !in exerciseOrder }).distinct()
        orderedExercises.forEachIndexed { position, exerciseId ->
            val mark = marksByExercise[exerciseId]?.maxByOrNull { markWeight(it) }
            val lines = summary?.exercises?.firstOrNull { it.exerciseId == exerciseId }?.lines.orEmpty()
            if (mark != null) {
                val gain = lines.firstOrNull { it.type == SessionRecords.MAX_WEIGHT || it.type == SessionRecords.MAX_REPS }?.let(::relativeGain) ?: 0.0
                candidates += Ranked(CompletionHighlight(CompletionHighlightKind.MARK, exerciseId, mark = mark), WEIGHT_MARK, gain, position)
                return@forEachIndexed
            }
            val line = lines.minByOrNull { SessionRecords.TYPE_ORDER.indexOf(it.type).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                ?: return@forEachIndexed
            candidates += Ranked(
                CompletionHighlight(CompletionHighlightKind.RECORD, exerciseId, record = line),
                WEIGHT_RECORD - SessionRecords.TYPE_ORDER.indexOf(line.type).coerceAtLeast(0),
                relativeGain(line),
                position,
            )
        }

        if (facts?.weeklyGoal != null) candidates += Ranked(CompletionHighlight(CompletionHighlightKind.WEEKLY_GOAL), WEIGHT_WEEKLY_GOAL, 0.0, 0)

        val sorted = candidates.sortedWith(
            compareByDescending<Ranked> { it.weight }
                .thenByDescending { it.gain }
                .thenBy { it.position }
        )
        val shown = sorted.take(limit.coerceAtLeast(0)).map { it.highlight }
        return CompletionHighlightSelection(shown, more = sorted.size - shown.size)
    }

    /** Gain over the earlier best as a fraction; 0 when there is no earlier best. */
    private fun relativeGain(line: SessionRecordLine): Double {
        val previous = line.previous ?: return 0.0
        if (previous <= 0.0) return 0.0
        return (line.value - previous) / previous
    }

    /** A load mark weighs more than a reps mark; a higher mark more than a lower one. */
    private fun markWeight(mark: StrengthMark): Int =
        (if (mark.kind == StrengthMarkKind.REPS) 0 else 10_000) + mark.value

    private const val WEIGHT_JUBILEE = 60
    private const val WEIGHT_COMEBACK = 50
    private const val WEIGHT_MARK = 40
    private const val WEIGHT_RECORD = 30
    private const val WEIGHT_WEEKLY_GOAL = 10

    private data class Ranked(
        val highlight: CompletionHighlight,
        val weight: Int,
        val gain: Double,
        val position: Int,
    )
}
