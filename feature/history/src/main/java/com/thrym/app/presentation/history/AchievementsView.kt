package com.thrym.app.presentation.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.common.ChapterEmblem
import com.thrym.app.presentation.common.LevelRing
import com.thrym.app.presentation.common.ThrymSurfaceCard
import com.thrym.app.presentation.common.ThrymSurfaceTone
import com.thrym.app.presentation.common.chapterAccent
import com.thrym.app.presentation.common.chapterNameRes
import com.thrym.app.presentation.common.chapterSceneRes
import com.thrym.app.presentation.common.formatXp
import com.thrym.app.presentation.common.hammerDepthText
import com.thrym.app.presentation.common.levelTitle
import com.thrym.app.presentation.common.nextStepText
import com.thrym.app.presentation.common.tierNumeral
import com.thrym.app.presentation.theme.GlassLevel
import com.thrym.app.presentation.theme.ThrymInteractiveColors
import com.thrym.app.presentation.theme.liquidGlass
import com.thrym.app.presentation.theme.thrymDimens
import com.thrym.core.designsystem.R
import com.thrym.feature.history.R as HistoryR
import com.thrym.shared.gamification.ChapterStatus
import com.thrym.shared.gamification.GamificationEngine
import com.thrym.shared.gamification.JourneyChapter
import com.thrym.shared.gamification.LevelCurve
import com.thrym.shared.gamification.LevelProgress
import com.thrym.shared.gamification.XpSource
import com.thrym.shared.gamification.XpTotal
import com.thrym.shared.recognition.TrainingMilestones

/**
 * The achievements of the history: the current stand, the eight chapters of the hero's journey
 * and how XP are earned, with the user's own total per source. [listState] comes from the
 * screen, so the list keeps its position while the history shows the workouts.
 */
@Composable
internal fun AchievementsView(
    content: AchievementsContent,
    glass: Boolean,
    onRetry: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    when (content) {
        AchievementsContent.Loading -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        AchievementsContent.Error -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(thrymDimens.spacingXl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(thrymDimens.spacingSm, Alignment.CenterVertically)
        ) {
            Text(
                text = stringResource(HistoryR.string.achievements_error),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onRetry, colors = ThrymInteractiveColors.textButton()) {
                Text(stringResource(R.string.common_retry))
            }
        }

        is AchievementsContent.Ready -> AchievementsList(content, glass, listState, modifier)
    }
}

@Composable
private fun AchievementsList(
    content: AchievementsContent.Ready,
    glass: Boolean,
    listState: LazyListState,
    modifier: Modifier
) {
    val dims = thrymDimens
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = if (glass) 16.dp else dims.spacingMd, vertical = dims.spacingSm),
        verticalArrangement = Arrangement.spacedBy(dims.spacingSm)
    ) {
        item(key = "hero") {
            AchievementsPanel(glass) { HeroStand(content.progress) }
        }
        item(key = "journey_header") {
            SectionHeader(
                title = stringResource(HistoryR.string.achievements_journey_title),
                intro = stringResource(HistoryR.string.achievements_journey_intro)
            )
        }
        item(key = "journey") {
            AchievementsPanel(glass) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    content.chapters.forEach { chapter -> ChapterRow(chapter) }
                    if (content.progress.isLastLevel) FurtherDeedsRow(content.progress)
                }
            }
        }
        item(key = "xp_header") {
            SectionHeader(title = stringResource(HistoryR.string.achievements_xp_title))
        }
        item(key = "xp") {
            AchievementsPanel(glass) {
                content.xpBySource.forEachIndexed { index, total ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    XpSourceRow(total)
                }
            }
        }
        item(key = "xp_footer") {
            Text(
                text = stringResource(HistoryR.string.achievements_xp_footer, GamificationEngine.MAX_XP_PER_SESSION),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        item {
            Spacer(modifier = Modifier.height(dims.spacingXl))
        }
    }
}

/** Liquid Glass panel or Ember surface card, like the rest of the history. */
@Composable
private fun AchievementsPanel(glass: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (glass) {
        Column(
            Modifier
                .fillMaxWidth()
                .liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(28.dp)),
            content = content
        )
    } else {
        ThrymSurfaceCard(Modifier.fillMaxWidth(), tone = ThrymSurfaceTone.ELEVATED, content = content)
    }
}

/** The current stand: ring, chapter, title, scene, the way to the next step and all XP so far. */
@Composable
private fun HeroStand(progress: LevelProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LevelRing(progress, size = 132.dp, modifier = Modifier.padding(bottom = 6.dp))
        Text(
            text = stringResource(R.string.gamification_chapter_of, progress.chapter, LevelCurve.CHAPTERS).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = chapterAccent(progress.chapter),
            textAlign = TextAlign.Center
        )
        Text(
            text = levelTitle(progress),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(chapterSceneRes(progress.chapter)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.gamification_line, hammerDepthText(progress), nextStepText(progress)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = stringResource(HistoryR.string.achievements_total_xp, formatXp(progress.totalXp)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SectionHeader(title: String, intro: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() }
        )
        intro?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One chapter: emblem, number, name and how far it is. The current chapter carries a tint of its
 * accent; a chapter still ahead shows a dimmed, grey emblem, its number and threshold in the
 * secondary text color and no scene yet.
 */
@Composable
private fun ChapterRow(chapter: JourneyChapter) {
    val accent = chapterAccent(chapter.chapter)
    val locked = chapter.status == ChapterStatus.LOCKED
    val shape = RoundedCornerShape(20.dp)
    val highlight = if (chapter.status == ChapterStatus.CURRENT) {
        Modifier
            .background(accent.copy(alpha = 0.12f), shape)
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
    } else {
        Modifier
    }
    val greyscale = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    val detail = when (chapter.status) {
        ChapterStatus.DONE -> if (chapter.chapter == LevelCurve.CHAPTERS) {
            stringResource(R.string.gamification_hammer_home)
        } else {
            stringResource(HistoryR.string.achievements_chapter_done)
        }
        ChapterStatus.CURRENT -> stringResource(
            HistoryR.string.achievements_chapter_tier,
            tierNumeral(chapter.tier),
            tierNumeral(LevelCurve.TIERS_PER_CHAPTER)
        )
        ChapterStatus.LOCKED -> stringResource(HistoryR.string.achievements_chapter_from, formatXp(chapter.startXp))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(highlight)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ChapterEmblem(
            chapter = chapter.chapter,
            tier = chapter.tier,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .alpha(if (locked) 0.35f else 1f),
            colorFilter = if (locked) greyscale else null
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(HistoryR.string.achievements_chapter_label, chapter.chapter).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else accent
            )
            Text(
                text = stringResource(chapterNameRes(chapter.chapter)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            )
            if (!locked) {
                Text(
                    text = stringResource(chapterSceneRes(chapter.chapter)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * After the last chapter: the deeds that follow, one star each. Built like a chapter row, with a
 * star in place of the emblem and without a highlight.
 */
@Composable
private fun FurtherDeedsRow(progress: LevelProgress) {
    val accent = chapterAccent(LevelCurve.CHAPTERS)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Star, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(HistoryR.string.achievements_stars, formatXp(progress.stars.toLong())).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
            Text(
                text = stringResource(HistoryR.string.achievements_further_deeds),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = nextStepText(progress), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One XP source: what it is called, its rule and what it brought so far. */
@Composable
private fun XpSourceRow(total: XpTotal) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = xpSourceTitle(total.source),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = xpSourceRule(total.source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(HistoryR.string.achievements_xp_amount, formatXp(total.xp)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (total.count > 0) {
                Text(
                    text = stringResource(HistoryR.string.achievements_count, formatXp(total.count.toLong())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun xpSourceTitle(source: XpSource): String = stringResource(
    when (source) {
        XpSource.TRAINING -> HistoryR.string.achievements_source_training
        XpSource.RECORD -> HistoryR.string.achievements_source_record
        XpSource.MARK -> HistoryR.string.achievements_source_mark
        XpSource.INCREASE -> HistoryR.string.achievements_source_increase
        XpSource.WEEKLY_GOAL -> HistoryR.string.achievements_source_weekly_goal
        XpSource.COMEBACK -> HistoryR.string.achievements_source_comeback
    }
)

/** The rule of a source; the numbers come from the engine, so the text never drifts from it. */
@Composable
private fun xpSourceRule(source: XpSource): String = when (source) {
    XpSource.TRAINING -> stringResource(
        HistoryR.string.achievements_rule_training,
        GamificationEngine.TRAINING_XP,
        GamificationEngine.FURTHER_TRAINING_SAME_DAY_XP
    )
    XpSource.RECORD -> stringResource(
        HistoryR.string.achievements_rule_record,
        GamificationEngine.RECORD_XP,
        GamificationEngine.MAX_RECORD_EXERCISES
    )
    XpSource.MARK -> stringResource(HistoryR.string.achievements_rule_mark, GamificationEngine.MARK_XP)
    XpSource.INCREASE -> stringResource(
        HistoryR.string.achievements_rule_increase,
        GamificationEngine.INCREASE_XP,
        GamificationEngine.MAX_INCREASES
    )
    XpSource.WEEKLY_GOAL -> stringResource(HistoryR.string.achievements_rule_weekly_goal, GamificationEngine.WEEKLY_GOAL_XP)
    XpSource.COMEBACK -> stringResource(
        HistoryR.string.achievements_rule_comeback,
        GamificationEngine.COMEBACK_XP,
        TrainingMilestones.COMEBACK_MIN_DAYS
    )
}
