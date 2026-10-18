package com.thrym.shared.workout

/**
 * One exercise row of an active workout, in the default order the caller computed:
 * planned target snapshots by `(orderIndex, id)`, then ad-hoc exercises in insertion order.
 *
 * [key] is opaque to these rules. Android passes `planned-<snapshotId>` / `adhoc-<exerciseId>`,
 * iOS `target-<targetId>` / `adhoc-<exerciseId>`.
 */
data class SessionOrderRow(
    val key: String,
    val supersetGroupId: Int?,
    val planned: Boolean,
)

/**
 * A block that moves as a whole: a single exercise or a contiguous superset run.
 *
 * [key] matches the list keys both apps use today: `single-<rowKey>` or
 * `superset-<groupId>-<rowKey1>-<rowKey2>...`. [supersetGroupId] is null for single exercises.
 */
data class SessionOrderUnit(
    val key: String,
    val supersetGroupId: Int?,
    val memberKeys: List<String>,
)

enum class SessionOrderMove { UP, DOWN }

/**
 * Display order of the exercises in an active workout.
 *
 * The order is stored per session outside the database and never touches
 * `workout_plan_targets.orderIndex`, which stays part of the plan-slot identity used by the
 * coach, plan write-back and readiness. Units are always built from the default order, so a
 * stored order can reorder blocks but never merge, split or hide them.
 */
object SessionExerciseOrder {

    /**
     * Groups rows by adjacency in the default order. A run of at least two neighbouring rows
     * with the same non-null superset group becomes one superset unit; a lone or interrupted
     * group id yields single exercises.
     */
    fun units(rowsInDefaultOrder: List<SessionOrderRow>): List<SessionOrderUnit> =
        runs(rowsInDefaultOrder).map(::unitOf)

    /**
     * Builds the units from the default order and sorts them by [storedKeys].
     *
     * - A unit ranks at the smallest stored position of its members. Unknown stored keys are
     *   ignored; for duplicates the first position wins.
     * - A planned unit without a stored position follows the nearest unit before it in the
     *   default order that is already placed, or goes first when there is none.
     * - An ad-hoc unit without a stored position is appended in default order.
     *
     * Every input row appears exactly once in the result.
     */
    fun arrange(
        rowsInDefaultOrder: List<SessionOrderRow>,
        storedKeys: List<String>,
    ): List<SessionOrderUnit> {
        val runs = runs(rowsInDefaultOrder)
        val defaultUnits = runs.map(::unitOf)

        val storedRank = HashMap<String, Int>()
        storedKeys.forEachIndexed { index, key -> storedRank.getOrPut(key) { index } }

        val placed = defaultUnits.indices
            .mapNotNull { index ->
                defaultUnits[index].memberKeys
                    .mapNotNull { storedRank[it] }
                    .minOrNull()
                    ?.let { rank -> index to rank }
            }
            .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .mapTo(mutableListOf()) { it.first }
        val isPlaced = BooleanArray(defaultUnits.size)
        placed.forEach { isPlaced[it] = true }

        val unplaced = defaultUnits.indices.filterNot { isPlaced[it] }
        val (unplacedPlanned, unplacedAdHoc) = unplaced.partition { index ->
            runs[index].any { it.planned }
        }
        for (index in unplacedPlanned) {
            val predecessor = (index - 1 downTo 0).firstOrNull { isPlaced[it] }
            val insertAt = if (predecessor == null) 0 else placed.indexOf(predecessor) + 1
            placed.add(insertAt, index)
            isPlaced[index] = true
        }
        placed += unplacedAdHoc

        return placed.map { defaultUnits[it] }
    }

    /**
     * Swaps the unit containing [key] with its neighbour in [direction]. [key] may be any member
     * key or the unit key, so every member moves the whole block.
     *
     * Returns the new flat key list to store, or null when the unit is already at that edge or
     * [key] is unknown.
     */
    fun move(
        arranged: List<SessionOrderUnit>,
        key: String,
        direction: SessionOrderMove,
    ): List<String>? {
        val index = arranged.indexOfFirst { key in it.memberKeys }
            .takeIf { it >= 0 }
            ?: arranged.indexOfFirst { it.key == key }.takeIf { it >= 0 }
            ?: return null
        val neighbour = when (direction) {
            SessionOrderMove.UP -> index - 1
            SessionOrderMove.DOWN -> index + 1
        }
        if (neighbour !in arranged.indices) return null

        val reordered = arranged.toMutableList()
        reordered[index] = arranged[neighbour]
        reordered[neighbour] = arranged[index]
        return reordered.flatMap { it.memberKeys }
    }

    /** Same adjacency rule as the list grouping on both apps before this order existed. */
    private fun runs(rows: List<SessionOrderRow>): List<List<SessionOrderRow>> {
        val runs = mutableListOf<List<SessionOrderRow>>()
        var cursor = 0
        while (cursor < rows.size) {
            val groupId = rows[cursor].supersetGroupId
            var endExclusive = cursor + 1
            if (groupId != null) {
                while (endExclusive < rows.size && rows[endExclusive].supersetGroupId == groupId) {
                    endExclusive++
                }
            }
            runs += rows.subList(cursor, endExclusive)
            cursor = endExclusive
        }
        return runs
    }

    private fun unitOf(run: List<SessionOrderRow>): SessionOrderUnit {
        val groupId = run[0].supersetGroupId
        return if (groupId == null || run.size < 2) {
            SessionOrderUnit(
                key = "single-${run[0].key}",
                supersetGroupId = null,
                memberKeys = listOf(run[0].key),
            )
        } else {
            SessionOrderUnit(
                key = "superset-$groupId-${run.joinToString("-") { it.key }}",
                supersetGroupId = groupId,
                memberKeys = run.map { it.key },
            )
        }
    }
}
