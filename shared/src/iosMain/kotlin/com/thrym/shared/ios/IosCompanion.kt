package com.thrym.shared.ios

import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionFrame
import com.thrym.shared.companion.CompanionPose
import com.thrym.shared.companion.CompanionRules
import com.thrym.shared.companion.CompanionSprites

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
    fun frame(characterName: String, poseName: String): CompanionFrame {
        val character = CompanionCharacter.entries.firstOrNull { it.name == characterName }
            ?: CompanionCharacter.ODIN
        val pose = CompanionPose.entries.firstOrNull { it.name == poseName } ?: CompanionPose.IDLE
        return CompanionSprites.frame(character, pose)
    }
}

class IosCompanionAppearance(
    val characterName: String,
    val lineName: String,
)
