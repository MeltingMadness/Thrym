package com.thrym.shared.gamification

/** Where a chapter stands: behind, the one on the way, or still ahead. */
enum class ChapterStatus { DONE, CURRENT, LOCKED }

/**
 * One chapter as the achievements overview lists it. [tier] is the emblem to show: all three
 * plates for a finished chapter, the reached tier for the current one and the first plate for a
 * chapter still ahead, which the platforms dim. [startXp] is the total XP at which it begins.
 */
data class JourneyChapter(
    val chapter: Int,
    val status: ChapterStatus,
    val tier: Int,
    val startXp: Long,
)

/** What one source brought over the whole history: [count] times, [xp] in total. */
data class XpTotal(
    val source: XpSource,
    val count: Int,
    val xp: Long,
)

/**
 * The hero's journey for the achievements overview, the same on both platforms: the eight
 * chapters with their state and the XP per source.
 */
object HeroJourney {

    /** The order of the overview: the common sources first, the rare ones last. */
    val SOURCE_ORDER: List<XpSource> = listOf(
        XpSource.TRAINING,
        XpSource.RECORD,
        XpSource.MARK,
        XpSource.INCREASE,
        XpSource.WEEKLY_GOAL,
        XpSource.COMEBACK,
    )

    /** All chapters, first to last. With the last level reached, the whole journey is done. */
    fun chapters(progress: LevelProgress): List<JourneyChapter> = (1..LevelCurve.CHAPTERS).map { chapter ->
        val status = when {
            chapter < progress.chapter -> ChapterStatus.DONE
            chapter > progress.chapter -> ChapterStatus.LOCKED
            progress.isLastLevel -> ChapterStatus.DONE
            else -> ChapterStatus.CURRENT
        }
        val tier = when (status) {
            ChapterStatus.DONE -> LevelCurve.TIERS_PER_CHAPTER
            ChapterStatus.CURRENT -> progress.tier
            ChapterStatus.LOCKED -> 1
        }
        JourneyChapter(chapter, status, tier, LevelCurve.chapterStartXp(chapter))
    }

    /**
     * Every source in [SOURCE_ORDER], also those that brought nothing yet. The XP add up to
     * [GamificationState.totalXp]: a capped workout counts with the XP it really got.
     */
    fun xpBySource(state: GamificationState): List<XpTotal> {
        val bySource = state.sessions.flatMap { it.entries }.groupBy { it.source }
        return SOURCE_ORDER.map { source ->
            val entries = bySource[source].orEmpty()
            XpTotal(source, entries.sumOf { it.count }, entries.sumOf { it.xp.toLong() })
        }
    }
}
