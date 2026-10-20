package com.thrym.shared.companion

import kotlin.random.Random

/**
 * One thing the companion does on its stage. Positions are in sprite pixels: [targetX] is where
 * the centre line of the figure ends up. [facingRight] is the direction during the action.
 * [durationMs] is how long it takes; a walk lasts as long as the way at [CompanionDirector.WALK_SPEED].
 */
data class CompanionAction(
    val animation: CompanionAnimation,
    val targetX: Float,
    val facingRight: Boolean,
    val durationMs: Long
)

/**
 * Decides what the companion does next while it is on screen: walk somewhere, stand, practise a
 * spear thrust or cheer. Both platforms run the same rules and only play the frames.
 */
object CompanionDirector {
    /** Walking speed in sprite pixels per second. */
    const val WALK_SPEED: Float = 20f

    /** Space kept free between the figure's centre line and the edges of the stage. */
    const val EDGE: Float = 14f

    /** Room needed in front of the figure for a spear thrust. */
    const val THRUST_ROOM: Float = 46f

    /** Shortest walk, so a walk is never a shuffle on the spot. */
    const val MIN_WALK: Float = 18f

    /**
     * The next action for a figure at [x] facing [facingRight] on a stage [stageWidth] sprite
     * pixels wide. [previous] is the last action's animation, `null` at the start.
     */
    fun next(
        random: Random,
        x: Float,
        facingRight: Boolean,
        stageWidth: Float,
        previous: CompanionAnimation?
    ): CompanionAction {
        val min = EDGE
        val max = maxOf(EDGE, stageWidth - EDGE)
        val canWalk = max - min >= MIN_WALK * 2
        val roll = random.nextInt(100)
        val animation = when {
            previous == CompanionAnimation.WALK || !canWalk -> when {
                roll < 55 -> CompanionAnimation.IDLE
                roll < 82 -> CompanionAnimation.ATTACK
                else -> CompanionAnimation.CHEER
            }
            roll < 55 -> CompanionAnimation.WALK
            roll < 80 -> CompanionAnimation.IDLE
            roll < 92 -> CompanionAnimation.ATTACK
            else -> CompanionAnimation.CHEER
        }
        return when (animation) {
            CompanionAnimation.WALK -> {
                var target = min + random.nextFloat() * (max - min)
                if (kotlin.math.abs(target - x) < MIN_WALK) {
                    target = if (x - min > max - x) x - MIN_WALK * 1.5f else x + MIN_WALK * 1.5f
                }
                target = target.coerceIn(min, max)
                val millis = (kotlin.math.abs(target - x) / WALK_SPEED * 1000).toLong()
                CompanionAction(animation, target, target >= x, millis)
            }
            CompanionAnimation.ATTACK -> {
                // Thrust towards the side with room, so the spear stays on the stage.
                val roomRight = stageWidth - x
                val right = when {
                    facingRight && roomRight >= THRUST_ROOM -> true
                    !facingRight && x >= THRUST_ROOM -> false
                    else -> roomRight >= x
                }
                CompanionAction(animation, x, right, playMs(animation))
            }
            CompanionAnimation.IDLE -> CompanionAction(animation, x, facingRight, 1_400L + random.nextLong(2_400L))
            else -> CompanionAction(animation, x, facingRight, playMs(animation, loops = 2))
        }
    }

    /** Duration of [loops] full runs of [animation]. */
    fun playMs(animation: CompanionAnimation, loops: Int = 1): Long =
        CompanionSprites.durationsMs(animation).sum() * loops
}
