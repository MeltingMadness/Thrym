package com.thrym.app.presentation.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thrym.app.presentation.theme.thrymMotion
import com.thrym.core.designsystem.R
import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionPose
import com.thrym.shared.companion.CompanionSprites
import kotlin.math.floor
import kotlinx.coroutines.delay

private const val BLINK_EVERY_MS = 3_600L
private const val BLINK_FOR_MS = 140L
private const val BOB_EVERY_MS = 520L
private const val HOP_STEP_MS = 90L

/**
 * The companion as 16-bit pixel art from [CompanionSprites]. Pixels stay square and sharp: one
 * sprite pixel covers a whole number of screen pixels, the rest of [size] stays empty.
 *
 * Idle, the companion breathes (a one-pixel bob) and blinks now and then. [cheering] shows the
 * happy pose with a little hop. With reduced motion it stands still and only changes its pose.
 * [contentDescription] `null` hides the sprite from screen readers, for when a text next to it
 * already names the companion.
 */
@Composable
fun CompanionSprite(
    character: CompanionCharacter,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    cheering: Boolean = false,
    contentDescription: String? = companionName(character)
) {
    val reduced = thrymMotion.reduced
    var blinking by remember { mutableStateOf(false) }
    var bob by remember { mutableIntStateOf(0) }
    var hop by remember { mutableIntStateOf(0) }

    LaunchedEffect(reduced, cheering) {
        blinking = false
        bob = 0
        if (reduced || cheering) return@LaunchedEffect
        var elapsed = 0L
        while (true) {
            delay(BOB_EVERY_MS)
            elapsed += BOB_EVERY_MS
            bob = 1 - bob
            if (elapsed >= BLINK_EVERY_MS) {
                elapsed = 0L
                blinking = true
                delay(BLINK_FOR_MS)
                blinking = false
            }
        }
    }

    LaunchedEffect(reduced, cheering) {
        hop = 0
        if (reduced || !cheering) return@LaunchedEffect
        for (height in intArrayOf(1, 2, 3, 2, 1, 0)) {
            hop = height
            delay(HOP_STEP_MS)
        }
    }

    val pose = when {
        cheering -> CompanionPose.CHEER
        blinking -> CompanionPose.BLINK
        else -> CompanionPose.IDLE
    }
    val frame = CompanionSprites.frame(character, pose)
    val colors = remember(frame) { frame.argb.map { Color(it) } }

    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.size(size).then(semanticsModifier)) {
        val sideInPixels = minOf(this.size.width, this.size.height)
        // Room for the hop above the sprite, so it never leaves its box.
        val gridRows = frame.height + 3
        val pixel = floor(sideInPixels / gridRows).coerceAtLeast(1f)
        val left = floor((this.size.width - pixel * frame.width) / 2f)
        val top = floor(this.size.height - pixel * frame.height) - (bob + hop) * pixel
        val cell = Size(pixel, pixel)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val color = colors[y * frame.width + x]
                if (color.alpha == 0f) continue
                drawRect(color = color, topLeft = Offset(left + x * pixel, top + y * pixel), size = cell)
            }
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
