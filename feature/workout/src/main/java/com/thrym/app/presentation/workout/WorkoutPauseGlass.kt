package com.thrym.app.presentation.workout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thrym.app.presentation.common.currentLocale
import com.thrym.app.presentation.common.rememberHapticFeedback
import com.thrym.app.presentation.theme.AthleticLabel
import com.thrym.app.presentation.theme.GlassLevel
import com.thrym.app.presentation.theme.LiquidBackground
import com.thrym.app.presentation.theme.thrymMotion
import com.thrym.app.presentation.theme.liquidGlass
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val PauseInk = Color(0xFF0B0D12)
private val PauseTeal = Color(0xFF2EC4B6)

/** What the pause screen shows for one running rest timer. */
internal data class GlassPauseInfo(
    val timer: RestTimerUi,
    val exerciseName: String,
    val nextSetNumber: Int
)

/** Seconds since the timer started, ticking once per second. */
@Composable
private fun rememberElapsedSeconds(startTime: Instant): Long {
    var elapsed by remember(startTime) {
        mutableLongStateOf(Duration.between(startTime, Instant.now()).seconds.coerceAtLeast(0))
    }
    LaunchedEffect(startTime) {
        while (isActive) {
            elapsed = Duration.between(startTime, Instant.now()).seconds.coerceAtLeast(0)
            delay(1000)
        }
    }
    return elapsed
}

private fun clock(seconds: Long): String =
    String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

/**
 * Ends a countdown like the Ember chip does: one confirm haptic, then [onComplete] (which
 * dismisses the timer). Liquid Glass shows timers in the dock and pause screen instead of
 * chips, so this carries the same end-of-rest behavior without any visible UI.
 */
@Composable
internal fun RestTimerCompletionWatcher(timer: RestTimerUi, onComplete: () -> Unit) {
    if (timer.durationSeconds <= 0) return
    val haptic = rememberHapticFeedback()
    val currentOnComplete by rememberUpdatedState(onComplete)
    LaunchedEffect(timer.startTime, timer.durationSeconds) {
        val endMillis = timer.startTime.toEpochMilli() + timer.durationSeconds * 1000L
        while (isActive) {
            val left = endMillis - Instant.now().toEpochMilli()
            if (left <= 0) {
                haptic.confirm()
                currentOnComplete()
                break
            }
            delay(minOf(left, 1000L))
        }
    }
}

/**
 * Floating glass dock with a mini ring for the running rest: tap opens the pause screen,
 * "Überspringen" ends the rest right away.
 */
@Composable
internal fun GlassRestDock(
    info: GlassPauseInfo,
    pauseLabel: String,
    skipLabel: String,
    openLabel: String,
    onOpen: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = if (dark) Color.White else PauseInk
    val elapsed = rememberElapsedSeconds(info.timer.startTime)
    val countdown = info.timer.durationSeconds > 0
    val remaining = if (countdown) (info.timer.durationSeconds - elapsed).coerceAtLeast(0) else elapsed
    val fraction = if (countdown) remaining.toFloat() / info.timer.durationSeconds else 1f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(GlassLevel.STRONG, RoundedCornerShape(38.dp))
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                drawCircle(ink.copy(alpha = 0.18f), radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
                drawArc(
                    color = PauseTeal,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(stroke, stroke),
                    size = androidx.compose.ui.geometry.Size(size.width - 2 * stroke, size.height - 2 * stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            Text(
                text = clock(remaining),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(pauseLabel.uppercase(currentLocale()), style = AthleticLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = info.exerciseName,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (dark) Color.White else PauseInk)
                .clickable(role = Role.Button, onClick = onSkip)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(skipLabel, color = if (dark) PauseInk else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
    }
}

/**
 * Displays the selected dock rows in a viewport three rows high. Additional countdowns scroll
 * inside the dock instead of covering the last workout card.
 */
@Composable
internal fun GlassRestDockStack(
    rows: List<Pair<String, GlassPauseInfo>>,
    pauseLabel: String,
    skipLabel: String,
    openLabel: String,
    onOpen: (String) -> Unit,
    onSkip: (String) -> Unit,
    visibleRows: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = REST_DOCK_ROW_HEIGHT_DP.dp * visibleRows + REST_DOCK_ROW_SPACING_DP.dp * (visibleRows - 1))
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(REST_DOCK_ROW_SPACING_DP.dp)
    ) {
        rows.forEach { (rowKey, info) ->
            key(rowKey) {
                GlassRestDock(
                    info = info,
                    pauseLabel = pauseLabel,
                    skipLabel = skipLabel,
                    openLabel = openLabel,
                    onOpen = { onOpen(rowKey) },
                    onSkip = { onSkip(rowKey) }
                )
            }
        }
    }
}

/**
 * Pause as a full-screen lens: the glass "empties" with the remaining time, −15 s / +30 s,
 * what comes next and "Pause überspringen". Back or "Zum Training" returns to the workout
 * while the timer keeps running in the dock. [topNotice] (record and undo messages) sits right
 * below the top row, over the free space above the lens, so it covers neither "Zum Training"
 * nor the actions below.
 */
@Composable
internal fun GlassPauseScreen(
    info: GlassPauseInfo,
    progressText: String,
    texts: GlassPauseTexts,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
    topNotice: @Composable () -> Unit = {}
) {
    val density = LocalDensity.current
    // Starts at the usual height of the top row, so the first frame is already in place.
    var topRowBottom by remember { mutableStateOf(PAUSE_PADDING_TOP + 44.dp) }
    Box(Modifier.fillMaxSize()) {
        GlassPauseContent(info, progressText, texts, onMinus, onPlus, onSkip, onClose) { rowHeight ->
            topRowBottom = PAUSE_PADDING_TOP + with(density) { rowHeight.toDp() }
        }
        // Outside the cover, which merges its content for TalkBack: a snackbar is a pane and
        // cannot be merged into another node.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = PAUSE_PADDING_HORIZONTAL, end = PAUSE_PADDING_HORIZONTAL, top = topRowBottom)
        ) {
            topNotice()
        }
    }
}

private val PAUSE_PADDING_TOP = 16.dp
private val PAUSE_PADDING_HORIZONTAL = 24.dp

@Composable
private fun GlassPauseContent(
    info: GlassPauseInfo,
    progressText: String,
    texts: GlassPauseTexts,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
    onTopRowHeight: (Int) -> Unit
) {
    BackHandler(onBack = onClose)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = if (dark) Color.White else PauseInk
    val elapsed = rememberElapsedSeconds(info.timer.startTime)
    val countdown = info.timer.durationSeconds > 0
    val remaining = if (countdown) (info.timer.durationSeconds - elapsed).coerceAtLeast(0) else elapsed
    val level = if (countdown) remaining.toFloat() / info.timer.durationSeconds else 0.5f
    val reduced = thrymMotion.reduced

    Box(
        Modifier
            .fillMaxSize()
            // Swallow touches so the workout below cannot be operated through the pause screen.
            .clickable(enabled = true, onClickLabel = null, role = null, indication = null, interactionSource = null) {}
    ) {
        LiquidBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PAUSE_PADDING_HORIZONTAL, vertical = PAUSE_PADDING_TOP),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { onTopRowHeight(it.height) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = progressText.uppercase(currentLocale()),
                    style = AthleticLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button, onClick = onClose)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(texts.close, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.weight(0.4f))

            // The lens: strong glass circle with a sinking water line.
            Box(
                modifier = Modifier
                    .widthIn(max = 296.dp)
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .liquidGlass(GlassLevel.STRONG, CircleShape)
                    .semantics(mergeDescendants = true) {
                        contentDescription = texts.remainingDescription(remaining)
                        liveRegion = LiveRegionMode.Polite
                    },
                contentAlignment = Alignment.Center
            ) {
                val phase = if (reduced) {
                    0f
                } else {
                    val transition = rememberInfiniteTransition(label = "pause-wave")
                    val animated by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = (2 * PI).toFloat(),
                        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)),
                        label = "pause-wave-phase"
                    )
                    animated
                }
                Canvas(Modifier.fillMaxSize().clip(CircleShape)) {
                    fun wave(offsetY: Float, amplitude: Float, shift: Float, color: Color) {
                        val baseY = size.height * (1f - level.coerceIn(0f, 1f)) + offsetY
                        val path = Path().apply {
                            moveTo(0f, baseY)
                            val steps = 48
                            for (i in 0..steps) {
                                val x = size.width * i / steps
                                val y = baseY + amplitude * sin((x / size.width) * 2 * PI.toFloat() + phase + shift)
                                lineTo(x, y)
                            }
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, color)
                    }
                    wave(0f, 9.dp.toPx(), 0f, ink.copy(alpha = 0.16f))
                    wave(6.dp.toPx(), 8.dp.toPx(), 1.6f, PauseTeal.copy(alpha = 0.28f))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        texts.pause.uppercase(currentLocale()),
                        style = AthleticLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = clock(remaining),
                        fontSize = 88.sp,
                        lineHeight = 90.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-3).sp,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                    if (countdown) {
                        Text(
                            texts.ofTotal(clock(info.timer.durationSeconds.toLong())),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (countdown) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PauseGlassButton(texts.minus, texts.minusDescription, onMinus)
                    PauseGlassButton(texts.plus, texts.plusDescription, onPlus)
                }
            }

            Spacer(Modifier.weight(0.6f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(30.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(texts.next.uppercase(currentLocale()), style = AthleticLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = texts.nextSet(info.exerciseName, info.nextSetNumber),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            GlassPrimaryButton(text = texts.skip, enabled = true, onClick = onSkip)
        }
    }
}

/** Resolved texts for [GlassPauseScreen], so the screen stays free of resource lookups. */
internal class GlassPauseTexts(
    val pause: String,
    val close: String,
    val minus: String,
    val plus: String,
    val minusDescription: String,
    val plusDescription: String,
    val next: String,
    val skip: String,
    val ofTotal: (String) -> String,
    val nextSet: (String, Int) -> String,
    val remainingDescription: (Long) -> String
)

@Composable
private fun PauseGlassButton(text: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 52.dp)
            .liquidGlass(GlassLevel.STANDARD, RoundedCornerShape(26.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

