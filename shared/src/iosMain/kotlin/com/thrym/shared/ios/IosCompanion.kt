package com.thrym.shared.ios

import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionFrame
import com.thrym.shared.companion.CompanionAnimation
import com.thrym.shared.companion.CompanionDirector
import com.thrym.shared.companion.CompanionRules
import com.thrym.shared.companion.CompanionSprites
import kotlin.random.Random

/**
 * Swift access to the companion. Enums travel as their names and a missing value as `-1` or an
 * empty string, so Swift needs neither Kotlin enum cases nor optional Kotlin numbers.
 */
object IosCompanion {

    /** Name of the companion the stored choice resolves to, or an empty string when it is off. */
    fun characterName(choiceName: String?, profileFemale: Boolean): String =
        CompanionRules.resolve(CompanionRules.parseChoice(choiceName), profileFemale)?.name.orEmpty()

    /**
     * The home screen appearance for [epochDay], or `null`. [daysSinceLastWorkout] and
     * [hiddenEpochDay] use `-1` for "none".
     */
    fun homeAppearance(
        choiceName: String?,
        profileFemale: Boolean,
        epochDay: Long,
        daysSinceLastWorkout: Long,
        hiddenEpochDay: Long
    ): IosCompanionAppearance? {
        val appearance = CompanionRules.homeAppearance(
            character = CompanionRules.resolve(CompanionRules.parseChoice(choiceName), profileFemale),
            epochDay = epochDay,
            daysSinceLastWorkout = daysSinceLastWorkout.takeIf { it >= 0 },
            hiddenEpochDay = hiddenEpochDay.takeIf { it >= 0 }
        ) ?: return null
        return IosCompanionAppearance(
            characterName = appearance.character.name,
            lineName = appearance.line.name
        )
    }

    /** The pixels of one frame; unknown names fall back to Odin standing idle. */
    fun frame(characterName: String, animationName: String, index: Int): CompanionFrame {
        val animation = animation(animationName)
        val count = CompanionSprites.frameCount(animation)
        return CompanionSprites.frame(character(characterName), animation, index.mod(count))
    }

    /** Frame durations of an animation in milliseconds. */
    fun durationsMs(animationName: String): List<Long> = CompanionSprites.durationsMs(animation(animationName))

    /** The next action on the stage, see [CompanionDirector.next]. [previousName] is empty at the start. */
    fun nextAction(seed: Int, x: Float, facingRight: Boolean, stageWidth: Float, previousName: String): IosCompanionAction {
        val previous = CompanionAnimation.entries.firstOrNull { it.name == previousName }
        val action = CompanionDirector.next(Random(seed), x, facingRight, stageWidth, previous)
        return IosCompanionAction(action.animation.name, action.targetX, action.facingRight, action.durationMs)
    }

    /** Line and animation for the workout summary. */
    fun completion(sessionId: Long, recordCount: Int): IosCompanionCompletion =
        IosCompanionCompletion(
            animationName = CompanionRules.completionAnimation(recordCount).name,
            lineName = CompanionRules.completionLine(sessionId, recordCount).name
        )

    private fun character(name: String) = CompanionCharacter.entries.firstOrNull { it.name == name } ?: CompanionCharacter.ODIN
    private fun animation(name: String) = CompanionAnimation.entries.firstOrNull { it.name == name } ?: CompanionAnimation.IDLE
}

class IosCompanionCompletion(
    val animationName: String,
    val lineName: String,
)

class IosCompanionAction(
    val animationName: String,
    val targetX: Float,
    val facingRight: Boolean,
    val durationMs: Long,
)

class IosCompanionAppearance(
    val characterName: String,
    val lineName: String,
)
