package com.thrym.shared.gamification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LevelCurveTest {

    @Test
    fun totalsMatchThePlannedCurve() {
        val expected = mapOf(
            1 to 0L,
            2 to 300L,
            4 to 2_100L,
            7 to 8_350L,
            10 to 19_300L,
            13 to 35_300L,
            16 to 56_650L,
            19 to 83_650L,
            22 to 116_400L,
            24 to 141_550L,
        )
        expected.forEach { (level, total) -> assertEquals(total, LevelCurve.totalXpFor(level), "level $level") }
    }

    @Test
    fun levelsMapToChaptersAndTiers() {
        fun place(level: Int) = LevelCurve.progress(LevelCurve.totalXpFor(level)).let { it.chapter to it.tier }

        assertEquals(1 to 1, place(1))
        assertEquals(1 to 3, place(3))
        assertEquals(2 to 1, place(4))
        assertEquals(3 to 2, place(8))
        assertEquals(8 to 1, place(22))
        assertEquals(8 to 3, place(24))
    }

    @Test
    fun progressCountsTheWayToTheNextTier() {
        val progress = LevelCurve.progress(400)

        assertEquals(2, progress.level)
        assertEquals(100, progress.xpIntoStep)
        assertEquals(700, progress.xpForStep)
        assertEquals(600, progress.xpToNext)
        assertEquals(1.0 / 7.0, progress.fraction, 1e-9)
    }

    @Test
    fun aLevelStartsExactlyAtItsTotal() {
        assertEquals(1, LevelCurve.progress(299).level)
        assertEquals(2, LevelCurve.progress(300).level)
        assertEquals(0, LevelCurve.progress(300).xpIntoStep)
    }

    @Test
    fun hammerRisesOneRastPerChapter() {
        assertEquals(7, LevelCurve.progress(0).hammerDepth)
        assertEquals(5, LevelCurve.progress(LevelCurve.totalXpFor(7)).hammerDepth)
        assertEquals(0, LevelCurve.progress(LevelCurve.totalXpFor(22)).hammerDepth)
    }

    @Test
    fun pastTheLastLevelStarsAreCountedSeparately() {
        val top = LevelCurve.totalXpFor(LevelCurve.MAX_LEVEL)

        val noStar = LevelCurve.progress(top + 12_999)
        assertEquals(24, noStar.level)
        assertEquals(0, noStar.stars)
        assertEquals(12_999, noStar.xpIntoStep)
        assertEquals(13_000, noStar.xpForStep)
        assertTrue(noStar.isLastLevel)

        val twoStars = LevelCurve.progress(top + 26_100)
        assertEquals(24, twoStars.level)
        assertEquals(2, twoStars.stars)
        assertEquals(100, twoStars.xpIntoStep)
    }

    @Test
    fun negativeTotalsStayAtTheStart() {
        val progress = LevelCurve.progress(-50)

        assertEquals(1, progress.level)
        assertEquals(0, progress.totalXp)
        assertFalse(progress.isLastLevel)
    }
}
