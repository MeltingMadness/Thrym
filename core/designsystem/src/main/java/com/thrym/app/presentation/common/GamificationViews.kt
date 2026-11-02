package com.thrym.app.presentation.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.theme.thrymMotion
import com.thrym.core.designsystem.R
import com.thrym.shared.gamification.LevelCurve
import com.thrym.shared.gamification.LevelProgress
import java.text.NumberFormat

private val TIER_NUMERALS = listOf("I", "II", "III")

@StringRes
fun chapterNameRes(chapter: Int): Int = when (chapter) {
    1 -> R.string.gamification_chapter_1
    2 -> R.string.gamification_chapter_2
    3 -> R.string.gamification_chapter_3
    4 -> R.string.gamification_chapter_4
    5 -> R.string.gamification_chapter_5
    6 -> R.string.gamification_chapter_6
    7 -> R.string.gamification_chapter_7
    else -> R.string.gamification_chapter_8
}

@StringRes
fun chapterSceneRes(chapter: Int): Int = when (chapter) {
    1 -> R.string.gamification_scene_1
    2 -> R.string.gamification_scene_2
    3 -> R.string.gamification_scene_3
    4 -> R.string.gamification_scene_4
    5 -> R.string.gamification_scene_5
    6 -> R.string.gamification_scene_6
    7 -> R.string.gamification_scene_7
    else -> R.string.gamification_scene_8
}

/** "III" for tier 3. */
fun tierNumeral(tier: Int): String = TIER_NUMERALS[(tier - 1).coerceIn(TIER_NUMERALS.indices)]

/** Locale-aware number for XP: "1.240" or "1,240". */
fun formatXp(xp: Long): String = NumberFormat.getIntegerInstance().format(xp)

/** "Die Spur II", or "Der Hammer · 2 ★" with further deeds. The level number never shows. */
@Composable
fun levelTitle(progress: LevelProgress): String {
    val name = stringResource(chapterNameRes(progress.chapter))
    return if (progress.stars > 0) {
        stringResource(R.string.gamification_level_title_stars, name, progress.stars)
    } else {
        stringResource(R.string.gamification_level_title, name, tierNumeral(progress.tier))
    }
}

/** "Hammer noch 5 Rasten tief", or "Hammer heimgeholt" in the last chapter. */
@Composable
fun hammerDepthText(progress: LevelProgress): String =
    if (progress.hammerDepth > 0) {
        pluralStringResource(R.plurals.gamification_hammer_depth, progress.hammerDepth, progress.hammerDepth)
    } else {
        stringResource(R.string.gamification_hammer_home)
    }

/** "1.240 XP bis Stufe III", and for the last step of a chapter or the journey what comes next. */
@Composable
fun nextStepText(progress: LevelProgress): String {
    val xp = formatXp(progress.xpToNext)
    return when {
        progress.isLastLevel -> stringResource(R.string.gamification_xp_to_star, xp)
        progress.tier == LevelCurve.TIERS_PER_CHAPTER -> stringResource(R.string.gamification_xp_to_chapter, xp, progress.chapter + 1)
        else -> stringResource(R.string.gamification_xp_to_tier, xp, tierNumeral(progress.tier + 1))
    }
}

/** The accent of a chapter, darker on a light surface so that the ring keeps its contrast. */
@Composable
fun chapterAccent(chapter: Int): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f || isSystemInDarkTheme() && MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (onDark, onLight) = when (chapter) {
        1 -> Color(0xFFC08A5B) to Color(0xFF8A5A33)
        2 -> Color(0xFFAEB7C2) to Color(0xFF5E6975)
        3 -> Color(0xFFDCE3EA) to Color(0xFF55616E)
        4 -> Color(0xFFE8C468) to Color(0xFF8F6B0C)
        5 -> Color(0xFF2DD4BF) to Color(0xFF0F7F71)
        6 -> Color(0xFFA78BFA) to Color(0xFF6D4FD6)
        7 -> Color(0xFF7DD3FC) to Color(0xFF1B74A3)
        else -> Color(0xFFF58B20) to Color(0xFFB85A06)
    }
    return if (dark) onDark else onLight
}

@DrawableRes
private fun emblemRes(chapter: Int, tier: Int): Int = when (chapter to tier) {
    1 to 1 -> R.drawable.emblem_chapter_1_tier_1
    1 to 2 -> R.drawable.emblem_chapter_1_tier_2
    1 to 3 -> R.drawable.emblem_chapter_1_tier_3
    2 to 1 -> R.drawable.emblem_chapter_2_tier_1
    2 to 2 -> R.drawable.emblem_chapter_2_tier_2
    2 to 3 -> R.drawable.emblem_chapter_2_tier_3
    3 to 1 -> R.drawable.emblem_chapter_3_tier_1
    3 to 2 -> R.drawable.emblem_chapter_3_tier_2
    3 to 3 -> R.drawable.emblem_chapter_3_tier_3
    4 to 1 -> R.drawable.emblem_chapter_4_tier_1
    4 to 2 -> R.drawable.emblem_chapter_4_tier_2
    4 to 3 -> R.drawable.emblem_chapter_4_tier_3
    5 to 1 -> R.drawable.emblem_chapter_5_tier_1
    5 to 2 -> R.drawable.emblem_chapter_5_tier_2
    5 to 3 -> R.drawable.emblem_chapter_5_tier_3
    6 to 1 -> R.drawable.emblem_chapter_6_tier_1
    6 to 2 -> R.drawable.emblem_chapter_6_tier_2
    6 to 3 -> R.drawable.emblem_chapter_6_tier_3
    7 to 1 -> R.drawable.emblem_chapter_7_tier_1
    7 to 2 -> R.drawable.emblem_chapter_7_tier_2
    7 to 3 -> R.drawable.emblem_chapter_7_tier_3
    8 to 1 -> R.drawable.emblem_chapter_8_tier_1
    8 to 2 -> R.drawable.emblem_chapter_8_tier_2
    else -> R.drawable.emblem_chapter_8_tier_3
}

/** The emblem of a tier: one plate per tier on a sleeve, in the material of the chapter. */
@Composable
fun ChapterEmblem(chapter: Int, tier: Int, contentDescription: String?, modifier: Modifier = Modifier) {
    Image(painterResource(emblemRes(chapter, tier)), contentDescription, modifier)
}

/** The emblem inside a ring that shows the way to the next tier. */
@Composable
fun LevelRing(progress: LevelProgress, size: Dp, modifier: Modifier = Modifier, stroke: Dp = 7.dp) {
    val reduced = thrymMotion.reduced
    val sweep by animateFloatAsState(
        targetValue = progress.fraction.toFloat(),
        animationSpec = if (reduced) tween(0) else tween(700),
        label = "levelRing"
    )
    val accent = chapterAccent(progress.chapter)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val inset = width / 2
            val arc = Size(this.size.width - width, this.size.height - width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(width))
            drawArc(accent, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(width, cap = StrokeCap.Round))
        }
        ChapterEmblem(progress.chapter, progress.tier, contentDescription = null, modifier = Modifier.size(size * 0.72f))
    }
}
