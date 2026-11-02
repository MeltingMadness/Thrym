package com.thrym.app.presentation.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.common.ChapterEmblem
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.chapterAccent
import com.thrym.app.presentation.common.chapterNameRes
import com.thrym.app.presentation.common.chapterSceneRes
import com.thrym.app.presentation.common.formatXp
import com.thrym.app.presentation.common.hammerDepthText
import com.thrym.app.presentation.common.levelTitle
import com.thrym.app.presentation.common.tierNumeral
import com.thrym.core.designsystem.R
import com.thrym.feature.workout.R as WorkoutR
import com.thrym.shared.gamification.LevelCurve
import com.thrym.shared.gamification.LevelProgress
import com.thrym.shared.gamification.LevelUp
import com.thrym.shared.gamification.LevelUpKind
import com.thrym.shared.gamification.SessionCelebration
import com.thrym.shared.gamification.XpEntry
import com.thrym.shared.gamification.XpSource
import com.thrym.shared.recognition.CompletionFacts
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** "+245 XP", the bar towards the next tier and where the XP came from. */
@Composable
internal fun XpStep(celebration: SessionCelebration, reducedMotion: Boolean) {
    val accent = chapterAccent(celebration.after.chapter)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(WorkoutR.string.workout_celebration_xp_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(WorkoutR.string.workout_celebration_xp_gain, formatXp(celebration.xp.toLong())),
                style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                stringResource(WorkoutR.string.workout_celebration_xp_unit),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        XpBar(celebration, accent, reducedMotion)
        ThrymSurfaceCard(modifier = Modifier.fillMaxWidth(), tone = ThrymSurfaceTone.ELEVATED) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                celebration.entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(xpEntryLabel(entry), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(WorkoutR.string.workout_celebration_xp_gain, formatXp(entry.xp.toLong())),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun xpEntryLabel(entry: XpEntry): String = when (entry.source) {
    XpSource.TRAINING -> stringResource(WorkoutR.string.workout_celebration_xp_training)
    XpSource.COMEBACK -> stringResource(WorkoutR.string.workout_celebration_xp_comeback)
    XpSource.WEEKLY_GOAL -> stringResource(WorkoutR.string.workout_celebration_xp_weekly_goal)
    XpSource.MARK -> pluralStringResource(WorkoutR.plurals.workout_celebration_xp_marks, entry.count, entry.count)
    XpSource.RECORD -> pluralStringResource(WorkoutR.plurals.workout_celebration_xp_records, entry.count, entry.count)
    XpSource.INCREASE -> pluralStringResource(WorkoutR.plurals.workout_celebration_xp_increases, entry.count, entry.count)
}

/** The way to the next step: what was there before, and the gain of this workout in a lighter tone. */
@Composable
private fun XpBar(celebration: SessionCelebration, accent: Color, reducedMotion: Boolean) {
    val after = celebration.after
    val sameStep = celebration.before.level == after.level && celebration.before.stars == after.stars
    val start = if (sameStep) celebration.before.fraction.toFloat() else 0f
    val end = after.fraction.toFloat()
    val gain = remember { Animatable(if (reducedMotion) end else start) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) gain.animateTo(end, tween(900))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(levelTitle(after), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            nextStepTitle(after)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
            drawRect(accent.copy(alpha = 0.55f), size = Size(size.width * gain.value, size.height))
            drawRect(accent, size = Size(size.width * start, size.height))
        }
        Text(
            stringResource(WorkoutR.string.workout_celebration_xp_left, formatXp(after.xpToNext)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The title of the step after [progress]; the deeds past the last tier have no name. */
@Composable
private fun nextStepTitle(progress: LevelProgress): String? = when {
    progress.isLastLevel -> null
    progress.tier == LevelCurve.TIERS_PER_CHAPTER -> stringResource(
        R.string.gamification_level_title,
        stringResource(chapterNameRes(progress.chapter + 1)),
        tierNumeral(1)
    )
    else -> stringResource(R.string.gamification_level_title, stringResource(chapterNameRes(progress.chapter)), tierNumeral(progress.tier + 1))
}

/** The new tier gets its plate; a new chapter also tells its scene and how far the hammer rose. */
@Composable
internal fun LevelStep(levelUp: LevelUp, facts: CompletionFacts?, reducedMotion: Boolean) {
    val progress = levelUp.progress
    val accent = chapterAccent(progress.chapter)
    val isChapter = levelUp.kind == LevelUpKind.CHAPTER
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(
                    when (levelUp.kind) {
                        LevelUpKind.TIER -> WorkoutR.string.workout_celebration_level_tier
                        LevelUpKind.CHAPTER -> WorkoutR.string.workout_celebration_level_chapter
                        LevelUpKind.STAR -> WorkoutR.string.workout_celebration_level_star
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val circle = if (isChapter) 220.dp else 180.dp
            Box(
                Modifier
                    .size(circle)
                    .background(accent.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                ChapterEmblem(
                    progress.chapter,
                    progress.tier,
                    contentDescription = stringResource(R.string.gamification_emblem_cd, levelTitle(progress)),
                    modifier = Modifier.size(circle * 0.82f)
                )
            }
            Text(
                if (isChapter) stringResource(chapterNameRes(progress.chapter)) else levelTitle(progress),
                style = if (isChapter) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )
            when (levelUp.kind) {
                LevelUpKind.CHAPTER -> {
                    Text(
                        stringResource(chapterSceneRes(progress.chapter)) + "\n" + hammerRisingText(progress),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LevelChip(stringResource(R.string.gamification_chapter_of, progress.chapter, LevelCurve.CHAPTERS))
                        facts?.let {
                            LevelChip(pluralStringResource(WorkoutR.plurals.workout_celebration_trainings_so_far, it.trainingNumber, it.trainingNumber))
                        }
                    }
                }
                LevelUpKind.TIER -> Text(
                    stringResource(R.string.gamification_line, stringResource(R.string.gamification_chapter_of, progress.chapter, LevelCurve.CHAPTERS), hammerDepthText(progress)),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LevelUpKind.STAR -> Text(
                    stringResource(WorkoutR.string.workout_celebration_star_line),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!reducedMotion) Confetti(Modifier.fillMaxSize())
    }
}

@Composable
private fun hammerRisingText(progress: LevelProgress): String =
    if (progress.hammerDepth > 0) {
        pluralStringResource(WorkoutR.plurals.workout_celebration_hammer_rising, progress.hammerDepth, progress.hammerDepth)
    } else {
        stringResource(WorkoutR.string.workout_celebration_hammer_home)
    }

@Composable
private fun LevelChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

private val CONFETTI_COLORS = listOf(
    Color(0xFFF58B20),
    Color(0xFF2DD4BF),
    Color(0xFFFCD34D),
    Color(0xFFA78BFA),
    Color(0xFF38BDF8)
)

private class ConfettiPiece(
    val x: Float,
    val delay: Float,
    val sway: Float,
    val spin: Float,
    val width: Float,
    val height: Float,
    val color: Color
)

/** Falls once and is gone. It takes no touches and is left out with reduced motion. */
@Composable
private fun Confetti(modifier: Modifier = Modifier) {
    val pieces = remember {
        val random = Random(7)
        List(36) {
            ConfettiPiece(
                x = random.nextFloat(),
                delay = random.nextFloat() * 0.35f,
                sway = 0.02f + random.nextFloat() * 0.05f,
                spin = 180f + random.nextFloat() * 540f,
                width = 6f + random.nextFloat() * 4f,
                height = 10f + random.nextFloat() * 6f,
                color = CONFETTI_COLORS[it % CONFETTI_COLORS.size]
            )
        }
    }
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) { time.animateTo(1f, tween(2600, easing = LinearEasing)) }
    Canvas(modifier) {
        val density = this.density
        pieces.forEach { piece ->
            val fall = ((time.value - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (fall <= 0f || fall >= 1f) return@forEach
            val x = (piece.x + piece.sway * sin(fall * 4f * PI.toFloat())) * size.width
            val y = -20f + fall * (size.height + 40f)
            rotate(piece.spin * fall, pivot = Offset(x, y)) {
                drawRect(
                    piece.color.copy(alpha = (1f - fall).coerceAtMost(0.4f) / 0.4f),
                    topLeft = Offset(x - piece.width * density / 2, y - piece.height * density / 2),
                    size = Size(piece.width * density, piece.height * density)
                )
            }
        }
    }
}
