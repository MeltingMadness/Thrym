package com.thrym.shared.workout

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionExerciseOrderTest {

    @Test
    fun withoutStoredOrderUnitsMatchTodaysGrouping() {
        val rows = listOf(
            planned(1),
            planned(2, group = 1),
            planned(3, group = 1),
            planned(4, group = 1),
            planned(5),
            planned(6, group = 2),
            adHoc(7),
            adHoc(8),
        )

        val units = SessionExerciseOrder.units(rows)

        assertEquals(
            listOf(
                SessionOrderUnit("single-planned-1", null, listOf("planned-1")),
                SessionOrderUnit(
                    "superset-1-planned-2-planned-3-planned-4",
                    1,
                    listOf("planned-2", "planned-3", "planned-4"),
                ),
                SessionOrderUnit("single-planned-5", null, listOf("planned-5")),
                SessionOrderUnit("single-planned-6", null, listOf("planned-6")),
                SessionOrderUnit("single-adhoc-7", null, listOf("adhoc-7")),
                SessionOrderUnit("single-adhoc-8", null, listOf("adhoc-8")),
            ),
            units,
        )
        assertEquals(units, SessionExerciseOrder.arrange(rows, emptyList()))
    }

    @Test
    fun unitsMatchReferenceGroupingForRandomRows() {
        val random = Random(20261018)
        repeat(500) {
            val rows = randomRows(random)
            assertEquals(referenceRenderGroups(rows), SessionExerciseOrder.units(rows))
            assertEquals(referenceRenderGroups(rows), SessionExerciseOrder.arrange(rows, emptyList()))
        }
    }

    @Test
    fun adHocUnitsWithoutStoredRankFollowPlannedUnits() {
        val rows = listOf(planned(1), planned(2), adHoc(5), adHoc(6))

        val arranged = SessionExerciseOrder.arrange(rows, listOf("planned-2", "planned-1"))

        assertEquals(
            listOf("planned-2", "planned-1", "adhoc-5", "adhoc-6"),
            arranged.flatMap { it.memberKeys },
        )
    }

    @Test
    fun storedOrderIsAppliedAndSupersetTakesSmallestMemberRank() {
        val rows = listOf(planned(1), planned(2, group = 1), planned(3, group = 1), planned(4))

        val arranged = SessionExerciseOrder.arrange(
            rows,
            listOf("planned-3", "planned-4", "planned-1", "planned-2"),
        )

        assertEquals(
            listOf("superset-1-planned-2-planned-3", "single-planned-4", "single-planned-1"),
            arranged.map { it.key },
        )
        assertEquals(listOf("planned-2", "planned-3"), arranged.first().memberKeys)
    }

    @Test
    fun unknownAndDuplicateStoredKeysAreIgnored() {
        val rows = listOf(planned(1), planned(2, group = 1), planned(3, group = 1), planned(4))

        val arranged = SessionExerciseOrder.arrange(
            rows,
            listOf("planned-99", "planned-4", "garbage", "planned-1", "planned-4", "planned-2"),
        )

        assertEquals(
            listOf("single-planned-4", "single-planned-1", "superset-1-planned-2-planned-3"),
            arranged.map { it.key },
        )
        assertEveryRowOnce(rows, arranged)
    }

    @Test
    fun everyMoveOfFixtureKeepsEachRowExactlyOnce() {
        val rows = listOf(
            planned(1),
            planned(2, group = 1),
            planned(3, group = 1),
            planned(4, group = 2),
            planned(5),
            planned(6, group = 2),
            planned(7, group = 3),
            planned(8, group = 3),
            planned(9, group = 3),
            adHoc(10),
            adHoc(11),
        )
        val defaultUnitKeys = SessionExerciseOrder.units(rows).map { it.key }.toSet()
        val starts = listOf(
            emptyList<String>(),
            listOf("adhoc-11", "planned-8", "planned-5", "planned-1"),
            listOf("planned-99", "planned-6", "planned-6", "adhoc-10", "x", "planned-2"),
        )

        for (stored in starts) {
            val arranged = SessionExerciseOrder.arrange(rows, stored)
            assertEveryRowOnce(rows, arranged)
            for (key in rows.map { it.key } + arranged.map { it.key }) {
                for (direction in SessionOrderMove.entries) {
                    val moved = SessionExerciseOrder.move(arranged, key, direction) ?: continue
                    assertEquals(rows.map { it.key }.sorted(), moved.sorted())
                    val rearranged = SessionExerciseOrder.arrange(rows, moved)
                    assertEveryRowOnce(rows, rearranged)
                    assertEquals(defaultUnitKeys, rearranged.map { it.key }.toSet())
                    assertEquals(moved, rearranged.flatMap { it.memberKeys })
                }
            }
        }

        val random = Random(7)
        var stored: List<String> = emptyList()
        repeat(300) {
            val arranged = SessionExerciseOrder.arrange(rows, stored)
            assertEveryRowOnce(rows, arranged)
            assertEquals(defaultUnitKeys, arranged.map { it.key }.toSet())
            val key = rows[random.nextInt(rows.size)].key
            val direction = SessionOrderMove.entries[random.nextInt(2)]
            stored = SessionExerciseOrder.move(arranged, key, direction) ?: stored
        }
    }

    @Test
    fun missingPlannedUnitFollowsItsPlacedPredecessor() {
        val rows = listOf(planned(1), planned(2), planned(3), planned(4), adHoc(5))

        val arranged = SessionExerciseOrder.arrange(
            rows,
            listOf("planned-3", "planned-1", "adhoc-5", "planned-4"),
        )

        assertEquals(
            listOf("planned-3", "planned-1", "planned-2", "adhoc-5", "planned-4"),
            arranged.flatMap { it.memberKeys },
        )
    }

    @Test
    fun consecutiveMissingPlannedUnitsKeepTheirDefaultOrder() {
        val rows = listOf(planned(1), planned(2), planned(3), planned(4))

        val arranged = SessionExerciseOrder.arrange(rows, listOf("planned-4", "planned-1"))

        assertEquals(
            listOf("planned-4", "planned-1", "planned-2", "planned-3"),
            arranged.flatMap { it.memberKeys },
        )
    }

    @Test
    fun missingPlannedUnitWithoutPlacedPredecessorGoesFirst() {
        val rows = listOf(planned(1, group = 4), planned(2, group = 4), planned(3), planned(5))

        val arranged = SessionExerciseOrder.arrange(rows, listOf("planned-5", "planned-3"))

        assertEquals(
            listOf("superset-4-planned-1-planned-2", "single-planned-5", "single-planned-3"),
            arranged.map { it.key },
        )
    }

    @Test
    fun missingAdHocUnitIsAppended() {
        val rows = listOf(planned(1), planned(2), adHoc(5), adHoc(6))

        val arranged = SessionExerciseOrder.arrange(rows, listOf("planned-2", "adhoc-6", "planned-1"))

        assertEquals(
            listOf("planned-2", "adhoc-6", "planned-1", "adhoc-5"),
            arranged.flatMap { it.memberKeys },
        )
    }

    @Test
    fun interruptedGroupIdNeverMerges() {
        val rows = listOf(planned(1, group = 1), planned(2), planned(3, group = 1))
        val singles = setOf("single-planned-1", "single-planned-2", "single-planned-3")

        val storedOrders = listOf(
            emptyList<String>(),
            listOf("planned-1", "planned-3", "planned-2"),
            listOf("planned-2", "planned-1", "planned-3"),
            listOf("planned-3", "planned-1", "planned-2"),
        )
        for (stored in storedOrders) {
            var arranged = SessionExerciseOrder.arrange(rows, stored)
            repeat(4) {
                assertEquals(singles, arranged.map { it.key }.toSet())
                assertTrue(arranged.all { it.supersetGroupId == null && it.memberKeys.size == 1 })
                val moved = SessionExerciseOrder.move(arranged, "planned-2", SessionOrderMove.DOWN)
                    ?: SessionExerciseOrder.move(arranged, "planned-2", SessionOrderMove.UP)
                arranged = SessionExerciseOrder.arrange(rows, requireNotNull(moved))
            }
        }
    }

    @Test
    fun moveSwapsWholeUnits() {
        val rows = listOf(planned(1), planned(2, group = 1), planned(3, group = 1), planned(4))
        val arranged = SessionExerciseOrder.arrange(rows, emptyList())

        assertEquals(
            listOf("planned-2", "planned-3", "planned-1", "planned-4"),
            SessionExerciseOrder.move(arranged, "planned-3", SessionOrderMove.UP),
        )
        val down = listOf("planned-1", "planned-4", "planned-2", "planned-3")
        assertEquals(down, SessionExerciseOrder.move(arranged, "planned-2", SessionOrderMove.DOWN))
        assertEquals(down, SessionExerciseOrder.move(arranged, "planned-3", SessionOrderMove.DOWN))
        assertEquals(
            down,
            SessionExerciseOrder.move(arranged, "superset-1-planned-2-planned-3", SessionOrderMove.DOWN),
        )
        assertEquals(
            listOf("planned-1", "planned-2", "planned-3", "planned-4"),
            SessionExerciseOrder.move(
                SessionExerciseOrder.arrange(rows, down),
                "planned-4",
                SessionOrderMove.DOWN,
            ),
        )

        val keysBefore = arranged.map { it.key }.toSet()
        val keysAfter = SessionExerciseOrder.arrange(rows, down).map { it.key }.toSet()
        assertEquals(keysBefore, keysAfter)
    }

    @Test
    fun moveAtEdgeOrWithUnknownKeyReturnsNull() {
        val rows = listOf(planned(1), planned(2, group = 1), planned(3, group = 1), adHoc(4))
        val arranged = SessionExerciseOrder.arrange(rows, emptyList())

        assertNull(SessionExerciseOrder.move(arranged, "planned-1", SessionOrderMove.UP))
        assertNull(SessionExerciseOrder.move(arranged, "adhoc-4", SessionOrderMove.DOWN))
        assertNull(SessionExerciseOrder.move(arranged, "planned-99", SessionOrderMove.UP))
        assertNull(SessionExerciseOrder.move(arranged, "single-planned-2", SessionOrderMove.UP))
        assertNull(SessionExerciseOrder.move(emptyList(), "planned-1", SessionOrderMove.DOWN))
    }

    @Test
    fun orderIndexGapsDoNotChangeTheOrder() {
        val dense = listOf(
            Target(id = 10, orderIndex = 0, group = null),
            Target(id = 11, orderIndex = 1, group = 5),
            Target(id = 12, orderIndex = 2, group = 5),
        )
        val gapped = listOf(
            Target(id = 10, orderIndex = 0, group = null),
            Target(id = 11, orderIndex = 2, group = 5),
            Target(id = 12, orderIndex = 4, group = 5),
        )
        val denseRows = defaultRows(dense, adHocExerciseIds = listOf(30))
        val gappedRows = defaultRows(gapped.shuffled(Random(3)), adHocExerciseIds = listOf(30))

        val stored = listOf("adhoc-30", "planned-12", "planned-10")
        for (keys in listOf(emptyList<String>(), stored)) {
            val fromDense = SessionExerciseOrder.arrange(denseRows, keys)
            val fromGapped = SessionExerciseOrder.arrange(gappedRows, keys)
            assertEquals(fromDense, fromGapped)
            for (direction in SessionOrderMove.entries) {
                assertEquals(
                    SessionExerciseOrder.move(fromDense, "planned-11", direction),
                    SessionExerciseOrder.move(fromGapped, "planned-11", direction),
                )
            }
        }
    }

    private data class Target(val id: Long, val orderIndex: Int, val group: Int?)

    /** Default order as the apps compute it: targets by (orderIndex, id), then ad-hoc rows. */
    private fun defaultRows(targets: List<Target>, adHocExerciseIds: List<Long>): List<SessionOrderRow> =
        targets.sortedWith(compareBy<Target> { it.orderIndex }.thenBy { it.id })
            .map { planned(it.id, it.group) } + adHocExerciseIds.map { adHoc(it) }

    private fun planned(id: Long, group: Int? = null) = SessionOrderRow("planned-$id", group, planned = true)

    private fun adHoc(exerciseId: Long) = SessionOrderRow("adhoc-$exerciseId", null, planned = false)

    private fun assertEveryRowOnce(rows: List<SessionOrderRow>, arranged: List<SessionOrderUnit>) {
        assertEquals(rows.map { it.key }.sorted(), arranged.flatMap { it.memberKeys }.sorted())
    }

    private fun randomRows(random: Random): List<SessionOrderRow> {
        val plannedCount = random.nextInt(0, 9)
        val adHocCount = random.nextInt(0, 3)
        return (1..plannedCount).map { id ->
            planned(id.toLong(), if (random.nextInt(3) == 0) null else random.nextInt(1, 4))
        } + (1..adHocCount).map { adHoc(100L + it) }
    }

    /** Copy of `buildExerciseRenderGroups` / `exerciseRenderGroups` before the custom order. */
    private fun referenceRenderGroups(rows: List<SessionOrderRow>): List<SessionOrderUnit> {
        val groups = mutableListOf<SessionOrderUnit>()
        var cursor = 0
        while (cursor < rows.size) {
            val groupId = rows[cursor].supersetGroupId
            if (groupId == null) {
                groups += SessionOrderUnit("single-${rows[cursor].key}", null, listOf(rows[cursor].key))
                cursor++
                continue
            }
            var endExclusive = cursor + 1
            while (endExclusive < rows.size && rows[endExclusive].supersetGroupId == groupId) {
                endExclusive++
            }
            val run = rows.subList(cursor, endExclusive)
            groups += if (run.size < 2) {
                SessionOrderUnit("single-${run[0].key}", null, listOf(run[0].key))
            } else {
                SessionOrderUnit(
                    "superset-$groupId-${run.joinToString("-") { it.key }}",
                    groupId,
                    run.map { it.key },
                )
            }
            cursor = endExclusive
        }
        return groups
    }
}
