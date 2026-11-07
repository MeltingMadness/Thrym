package com.thrym.shared.gamification

import kotlin.test.Test
import kotlin.test.assertEquals

class HeroJourneyTest {

    private fun journeyAt(level: Int, extraXp: Long = 0) =
        HeroJourney.chapters(LevelCurve.progress(LevelCurve.totalXpFor(level) + extraXp))

    @Test
    fun chaptersStartWhereTheirFirstTierBegins() {
        assertEquals(0L, LevelCurve.chapterStartXp(1))
        assertEquals(2_100L, LevelCurve.chapterStartXp(2))
        assertEquals(8_350L, LevelCurve.chapterStartXp(3))
        assertEquals(116_400L, LevelCurve.chapterStartXp(8))
        assertEquals(
            (1..LevelCurve.CHAPTERS).map { LevelCurve.chapterStartXp(it) },
            journeyAt(1).map { it.startXp },
        )
    }

    @Test
    fun aNewJourneyHasTheFirstChapterOpenAndTheRestAhead() {
        val journey = journeyAt(1)

        assertEquals((1..8).toList(), journey.map { it.chapter })
        assertEquals(JourneyChapter(1, ChapterStatus.CURRENT, 1, 0L), journey.first())
        journey.drop(1).forEach {
            assertEquals(ChapterStatus.LOCKED, it.status)
            assertEquals(1, it.tier)
        }
    }

    @Test
    fun finishedChaptersShowAllPlatesAndTheCurrentOneItsTier() {
        // Level 8 is chapter 3, tier II.
        val journey = journeyAt(8, extraXp = 10)

        assertEquals(listOf(ChapterStatus.DONE, ChapterStatus.DONE), journey.take(2).map { it.status })
        assertEquals(listOf(3, 3), journey.take(2).map { it.tier })
        assertEquals(JourneyChapter(3, ChapterStatus.CURRENT, 2, 8_350L), journey[2])
        assertEquals(ChapterStatus.LOCKED, journey[3].status)
    }

    @Test
    fun theLastChapterStaysCurrentUntilItsLastTier() {
        assertEquals(ChapterStatus.CURRENT, journeyAt(23).last().status)
        assertEquals(2, journeyAt(23).last().tier)
    }

    @Test
    fun withTheLastLevelTheWholeJourneyIsDoneAlsoWithStars() {
        listOf(0L, 13_000L * 2 + 5).forEach { extra ->
            val journey = journeyAt(LevelCurve.MAX_LEVEL, extra)
            assertEquals(List(8) { ChapterStatus.DONE }, journey.map { it.status }, "extra $extra")
            assertEquals(List(8) { 3 }, journey.map { it.tier })
        }
    }

    @Test
    fun xpBySourceListsEverySourceInOrderAndAddsUpToTheTotal() {
        val sessions = listOf(
            SessionXp(1, 100, listOf(XpEntry(XpSource.TRAINING, 1, 100)), 100),
            SessionXp(
                2,
                400,
                listOf(
                    XpEntry(XpSource.TRAINING, 1, 100),
                    XpEntry(XpSource.COMEBACK, 1, 150),
                    XpEntry(XpSource.WEEKLY_GOAL, 1, 75),
                    // Capped: three records, but only the XP that were left.
                    XpEntry(XpSource.RECORD, 3, 75),
                ),
                500,
            ),
            SessionXp(3, 85, listOf(XpEntry(XpSource.TRAINING, 1, 25), XpEntry(XpSource.MARK, 1, 60)), 585),
        )
        val state = GamificationState(585, LevelCurve.progress(585), sessions)

        val totals = HeroJourney.xpBySource(state)

        assertEquals(HeroJourney.SOURCE_ORDER, totals.map { it.source })
        assertEquals(XpTotal(XpSource.TRAINING, 3, 225), totals[0])
        assertEquals(XpTotal(XpSource.RECORD, 3, 75), totals[1])
        assertEquals(XpTotal(XpSource.MARK, 1, 60), totals[2])
        assertEquals(XpTotal(XpSource.INCREASE, 0, 0), totals[3])
        assertEquals(XpTotal(XpSource.WEEKLY_GOAL, 1, 75), totals[4])
        assertEquals(XpTotal(XpSource.COMEBACK, 1, 150), totals[5])
        assertEquals(state.totalXp, totals.sumOf { it.xp })
    }

    @Test
    fun anEmptyHistoryBringsNothingFromAnySource() {
        val totals = HeroJourney.xpBySource(GamificationState(0, LevelCurve.progress(0), emptyList()))

        assertEquals(6, totals.size)
        totals.forEach { assertEquals(0L, it.xp); assertEquals(0, it.count) }
    }
}
