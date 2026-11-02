package com.thrym.shared.gamification

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Where a sum of XP stands on the hero's journey. [level] is 1 to [LevelCurve.MAX_LEVEL] and is
 * never shown as a number: people see [chapter] (1 to 8) with [tier] (1 to 3). Past the last
 * level, every [LevelCurve.XP_PER_STAR] XP earn one of the "further deeds" ([stars]).
 * [xpIntoStep] of [xpForStep] is the way to the next tier or star.
 */
data class LevelProgress(
    val totalXp: Long,
    val level: Int,
    val chapter: Int,
    val tier: Int,
    val stars: Int,
    val xpIntoStep: Long,
    val xpForStep: Long,
) {
    val xpToNext: Long get() = xpForStep - xpIntoStep

    /** Share of the way to the next tier or star, 0 to below 1. */
    val fraction: Double get() = xpIntoStep.toDouble() / xpForStep

    /** Rasts the hammer still lies below the earth: 7 in the first chapter, 0 in the last. */
    val hammerDepth: Int get() = LevelCurve.CHAPTERS - chapter

    val isLastLevel: Boolean get() = level == LevelCurve.MAX_LEVEL
}

/**
 * The level curve: eight chapters of three tiers each, 24 levels. The step from level n to n + 1
 * costs `round(300 * n^1.2 / 50) * 50` XP, so the first levels come within weeks and the later
 * ones months apart. XP, tiers and stars only follow from the history; a pause takes nothing away.
 */
object LevelCurve {
    const val CHAPTERS = 8
    const val TIERS_PER_CHAPTER = 3
    const val MAX_LEVEL = CHAPTERS * TIERS_PER_CHAPTER
    const val XP_PER_STAR = 13_000L

    /** Total XP at which each level starts: index 0 is level 1. */
    private val levelStarts: List<Long> = buildList {
        var total = 0L
        add(total)
        for (level in 1 until MAX_LEVEL) {
            total += stepXp(level)
            add(total)
        }
    }

    /** XP from [level] to the next one. */
    fun stepXp(level: Int): Long = (300.0 * level.toDouble().pow(1.2) / 50.0).roundToLong() * 50L

    /** Total XP at which [level] starts. */
    fun totalXpFor(level: Int): Long = levelStarts[level.coerceIn(1, MAX_LEVEL) - 1]

    fun progress(totalXp: Long): LevelProgress {
        val total = totalXp.coerceAtLeast(0L)
        val level = levelStarts.indexOfLast { it <= total } + 1
        val chapter = (level - 1) / TIERS_PER_CHAPTER + 1
        val tier = (level - 1) % TIERS_PER_CHAPTER + 1
        val into = total - totalXpFor(level)
        return if (level == MAX_LEVEL) {
            LevelProgress(total, level, chapter, tier, (into / XP_PER_STAR).toInt(), into % XP_PER_STAR, XP_PER_STAR)
        } else {
            LevelProgress(total, level, chapter, tier, 0, into, stepXp(level))
        }
    }
}
