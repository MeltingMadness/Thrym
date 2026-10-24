package com.thrym.shared.workout

import com.thrym.shared.model.UnitSystem
import com.thrym.shared.recognition.StrengthMark
import com.thrym.shared.recognition.StrengthMarks

/**
 * The marker of one set in the running or finished workout. [mark] is set when the set reached a
 * round mark for the first time ("100 kg"); otherwise the set holds a record ("PR").
 */
data class SessionSetBadge(
    val setId: Long,
    val mark: StrengthMark?,
)

/**
 * Which sets of a workout carry a record marker. The rules are those of [SessionRecords] and
 * [StrengthMarks], so the marker in the set row, the completion screen and both platforms agree:
 *
 * - A set gets "PR" when it holds a beaten weight, e1RM or reps record of the workout. Volume
 *   sums the workout and has no set, so it never marks one.
 * - A set gets the mark instead when it is the first work set of the workout that reached a
 *   new round mark. A mark always comes with a weight or reps record, so the mark wins.
 * - The first workout of an exercise, ties, warm-ups and lowered edits mark nothing, and a
 *   marker disappears when an edit takes the record away. Nothing is stored.
 */
object SessionSetBadges {

    /**
     * Markers of the workout [sessionId] for one exercise, in the order of its sets. [sets] holds
     * every set of the exercise in all sessions. [category] is the stored category code,
     * [addedLoad] tells that the weight is an added load (no e1RM record, as in [SessionRecords]).
     */
    fun forExercise(
        sessionId: Long,
        sets: List<RecordSetInput>,
        category: String,
        unitSystem: UnitSystem,
        addedLoad: Boolean,
    ): List<SessionSetBadge> {
        val exerciseId = sets.firstOrNull { it.sessionId == sessionId }?.exerciseId ?: return emptyList()
        val summary = SessionRecords.summarize(
            sessionId = sessionId,
            exerciseIdsInOrder = listOf(exerciseId),
            sets = sets.filter { it.exerciseId == exerciseId },
            addedLoadExerciseIds = if (addedLoad) listOf(exerciseId) else emptyList(),
        )
        val recordSetIds = summary.exercises.flatMap { it.lines }.mapNotNull { it.setId }.toSet()
        val markBySetId = markSets(sessionId, sets, category, unitSystem)
        val current = sets.filter { it.sessionId == sessionId && SessionRecords.isWorkSet(it) }
            .sortedWith(compareBy<RecordSetInput> { it.completedAtEpochMillis }.thenBy { it.id })
        return current.mapNotNull { set ->
            val mark = markBySetId[set.id]
            when {
                mark != null -> SessionSetBadge(set.id, mark)
                set.id in recordSetIds -> SessionSetBadge(set.id, null)
                else -> null
            }
        }
    }

    /** The first set of the workout that reached each of its new marks. */
    private fun markSets(
        sessionId: Long,
        sets: List<RecordSetInput>,
        category: String,
        unitSystem: UnitSystem,
    ): Map<Long, StrengthMark> {
        val marks = StrengthMarks.sessionMarks(sessionId, sets, category, unitSystem)
        if (marks.isEmpty()) return emptyMap()
        val ordered = sets.filter { it.sessionId == sessionId && SessionRecords.isWorkSet(it) }
            .sortedWith(compareBy<RecordSetInput> { it.completedAtEpochMillis }.thenBy { it.id })
        val result = LinkedHashMap<Long, StrengthMark>()
        marks.forEach { mark ->
            val first = ordered.firstOrNull { StrengthMarks.reaches(mark.kind, mark.imperial, it, mark.value) } ?: return@forEach
            // A load mark is listed before a reps mark and wins when one set reaches both.
            result.getOrPut(first.id) { mark }
        }
        return result
    }
}
