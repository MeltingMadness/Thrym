package com.thrym.app.presentation.companion

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.theme.thrymMotion
import com.thrym.core.designsystem.R
import com.thrym.shared.companion.CompanionAnimation
import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionDirector
import com.thrym.shared.companion.CompanionSprites
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Lowest row of the feet in a frame; the shadow sits just below. */
private const val FEET_ROW = 78
private const val SHADOW_HALF_WIDTH = 11
private val ShadowColor = Color(0x40000000)

/**
 * A strip in which the companion lives: it walks around, stands, practises spear thrusts and
 * cheers, as [CompanionDirector] decides. The figure is 16-bit pixel art from [CompanionSprites];
 * one sprite pixel is always a whole number of screen pixels, so the art stays sharp.
 *
 * [entrance] plays once when the stage appears, for example [CompanionAnimation.CELEBRATE] for a
 * record. A tap makes the companion celebrate. With reduced motion it stands still in the middle,
 * in the last frame of [entrance] or idle. The stage is decorative for screen readers; the text
 * next to it speaks for the companion.
 */
@Composable
fun CompanionStage(
    character: CompanionCharacter,
    modifier: Modifier = Modifier,
    height: Dp = 112.dp,
    entrance: CompanionAnimation? = null
) {
    val reduced = thrymMotion.reduced
    var stageWidthPx by remember { mutableIntStateOf(0) }
    var pixel by remember { mutableIntStateOf(1) }
    var x by remember { mutableFloatStateOf(Float.NaN) }
    var facingRight by remember { mutableStateOf(true) }
    var animation by remember { mutableStateOf(CompanionAnimation.IDLE) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var celebrations by remember { mutableIntStateOf(0) }
    var entrancePlayed by remember { mutableStateOf(false) }
    val bitmaps = remember(character) { mutableMapOf<Pair<CompanionAnimation, Int>, ImageBitmap>() }
    val random = remember { Random(System.nanoTime()) }

    val stageWidth = if (pixel > 0) stageWidthPx / pixel else 0
    LaunchedEffect(character, reduced, stageWidth > 0, celebrations) {
        if (stageWidth <= 0) return@LaunchedEffect
        val width = stageWidth.toFloat()
        if (x.isNaN()) x = if (entrance != null) width / 2 else CompanionDirector.EDGE + random.nextFloat() * (width - 2 * CompanionDirector.EDGE)
        x = x.coerceIn(0f, width)

        suspend fun play(next: CompanionAnimation, loops: Int = 1) {
            animation = next
            repeat(loops) {
                CompanionSprites.durationsMs(next).forEachIndexed { index, millis ->
                    frameIndex = index
                    delay(millis)
                }
            }
        }

        if (reduced) {
            x = width / 2
            facingRight = true
            val still = if (celebrations > 0) CompanionAnimation.CELEBRATE else entrance ?: CompanionAnimation.IDLE
            animation = still
            frameIndex = CompanionSprites.frameCount(still) - 1
            return@LaunchedEffect
        }
        suspend fun perform() {
            if (celebrations > 0) {
                play(CompanionAnimation.CELEBRATE)
            } else if (entrance != null && !entrancePlayed) {
                entrancePlayed = true
                play(entrance, loops = if (entrance == CompanionAnimation.CHEER) 3 else 1)
            }
            var previous: CompanionAnimation? = animation
            while (true) {
                val action = CompanionDirector.next(random, x, facingRight, width, previous)
                facingRight = action.facingRight
                when (action.animation) {
                    CompanionAnimation.WALK -> {
                        animation = CompanionAnimation.WALK
                        val frameMs = CompanionSprites.durationsMs(CompanionAnimation.WALK).first()
                        val step = CompanionDirector.WALK_SPEED * frameMs / 1000f
                        var index = 0
                        while (abs(action.targetX - x) > 0.01f) {
                            frameIndex = index++ % CompanionSprites.frameCount(CompanionAnimation.WALK)
                            delay(frameMs)
                            x = if (action.targetX > x) minOf(action.targetX, x + step) else maxOf(action.targetX, x - step)
                        }
                    }
                    CompanionAnimation.IDLE -> {
                        animation = CompanionAnimation.IDLE
                        var left = action.durationMs
                        var index = 0
                        val durations = CompanionSprites.durationsMs(CompanionAnimation.IDLE)
                        while (left > 0) {
                            frameIndex = index % durations.size
                            delay(durations[frameIndex])
                            left -= durations[frameIndex]
                            index++
                        }
                    }
                    CompanionAnimation.CHEER -> play(CompanionAnimation.CHEER, loops = 2)
                    else -> play(action.animation)
                }
                previous = action.animation
            }
        }

        // UI tests replace endless work with a no-op through this policy; the figure then stands still.
        val policy = coroutineContext[InfiniteAnimationPolicy]
        if (policy != null) policy.onInfiniteOperation { perform() } else perform()
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .onSizeChanged { size ->
                stageWidthPx = size.width
                pixel = maxOf(1, floor(size.height.toFloat() / CompanionSprites.SIZE).toInt())
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { celebrations++ }
            )
            .clearAndSetSemantics { }
    ) {
        if (x.isNaN()) return@Canvas
        val p = pixel
        val spriteSize = CompanionSprites.SIZE * p
        val anchor = x.toInt() * p
        val left = anchor - CompanionSprites.ANCHOR_X * p
        val top = size.height.toInt() - spriteSize
        val feet = top + FEET_ROW * p
        drawOval(
            color = ShadowColor,
            topLeft = Offset((anchor - SHADOW_HALF_WIDTH * p).toFloat(), (feet - p).toFloat()),
            size = Size((2 * SHADOW_HALF_WIDTH * p).toFloat(), (3 * p).toFloat())
        )
        val frame = bitmaps.getOrPut(animation to frameIndex) {
            val data = CompanionSprites.frame(character, animation, frameIndex)
            Bitmap.createBitmap(data.argb, data.width, data.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        val pivot = Offset(left + (CompanionSprites.ANCHOR_X + 0.5f) * p, 0f)
        scale(scaleX = if (facingRight) 1f else -1f, scaleY = 1f, pivot = pivot) {
            drawImage(
                image = frame,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(frame.width, frame.height),
                dstOffset = IntOffset(left, top),
                dstSize = IntSize(spriteSize, spriteSize),
                filterQuality = FilterQuality.None
            )
        }
    }
}

/** Display name of [character], also used for screen readers. */
@Composable
fun companionName(character: CompanionCharacter): String = stringResource(
    when (character) {
        CompanionCharacter.ODIN -> R.string.companion_name_odin
        CompanionCharacter.VALKYRIE -> R.string.companion_name_valkyrie
    }
)
