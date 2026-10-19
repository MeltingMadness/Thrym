package com.thrym.shared.companion

/** The companion who shows up in the app. Persisted only through [CompanionChoice]. */
enum class CompanionCharacter {
    ODIN,
    VALKYRIE
}

/**
 * What the user picked in the settings. Persisted by name, so the constants are storage keys and
 * are never renamed. [AUTO] follows the profile: a valkyrie for women, Odin otherwise.
 */
enum class CompanionChoice {
    AUTO,
    ODIN,
    VALKYRIE,
    OFF
}

/** The frames of the pixel art. [BLINK] is part of the idle loop, [CHEER] answers a tap or a win. */
enum class CompanionPose {
    IDLE,
    BLINK,
    CHEER
}

/** Why the companion speaks on the home screen. Every moment has its own lines. */
enum class CompanionMoment {
    /** No finished workout yet. */
    FIRST_STEPS,

    /** A workout was finished today. */
    TRAINED_TODAY,

    /** The last workout is [CompanionRules.WELCOME_BACK_AFTER_DAYS] days or more ago. */
    WELCOME_BACK,

    /** Any other day. */
    EVERYDAY
}

/**
 * What the companion says, as a language-neutral code. The platforms map each code to their
 * localized text. A line never blames the user for a missed workout.
 */
enum class CompanionLine {
    /** Introduction on the first onboarding page; the text names the character. */
    ONBOARDING_WELCOME,
    ONBOARDING_UNITS,
    ONBOARDING_START,
    FIRST_STEPS_WELCOME,
    TRAINED_TODAY_REST,
    TRAINED_TODAY_SLEEP,
    WELCOME_BACK_ONE_SET,
    EVERYDAY_YGGDRASIL,
    EVERYDAY_WARMUP,
    EVERYDAY_TECHNIQUE,
    EVERYDAY_RECOVERY,
    EVERYDAY_SMALL_PLATES
}

/** One appearance on the home screen. */
data class CompanionAppearance(
    val character: CompanionCharacter,
    val line: CompanionLine
)

/**
 * When and how the companion appears. Everything is derived from the date, the history and the
 * settings, so nothing extra is stored apart from the choice and the day a user hid it.
 *
 * The companion turns up irregularly: on a pseudo-random share of days that looks unplanned but is
 * stable for the whole day, so it does not flicker in and out when the screen reloads.
 */
object CompanionRules {
    /** Days without a workout after which the companion welcomes the user back. */
    const val WELCOME_BACK_AFTER_DAYS: Long = 10

    /** Share of ordinary days with an appearance, as one in N. */
    const val EVERYDAY_ONE_IN: Int = 4

    /** Share of training days with an appearance, as one in N. */
    const val TRAINED_TODAY_ONE_IN: Int = 2

    private val linesByMoment: Map<CompanionMoment, List<CompanionLine>> = mapOf(
        CompanionMoment.FIRST_STEPS to listOf(CompanionLine.FIRST_STEPS_WELCOME),
        CompanionMoment.TRAINED_TODAY to listOf(
            CompanionLine.TRAINED_TODAY_REST,
            CompanionLine.TRAINED_TODAY_SLEEP
        ),
        CompanionMoment.WELCOME_BACK to listOf(CompanionLine.WELCOME_BACK_ONE_SET),
        CompanionMoment.EVERYDAY to listOf(
            CompanionLine.EVERYDAY_YGGDRASIL,
            CompanionLine.EVERYDAY_WARMUP,
            CompanionLine.EVERYDAY_TECHNIQUE,
            CompanionLine.EVERYDAY_RECOVERY,
            CompanionLine.EVERYDAY_SMALL_PLATES
        )
    )

    /**
     * The companion for [choice], or `null` when the user switched it off. [profileFemale] is
     * true when the profile says the user is a woman; only [CompanionChoice.AUTO] looks at it.
     */
    fun resolve(choice: CompanionChoice, profileFemale: Boolean): CompanionCharacter? = when (choice) {
        CompanionChoice.AUTO -> if (profileFemale) CompanionCharacter.VALKYRIE else CompanionCharacter.ODIN
        CompanionChoice.ODIN -> CompanionCharacter.ODIN
        CompanionChoice.VALKYRIE -> CompanionCharacter.VALKYRIE
        CompanionChoice.OFF -> null
    }

    /** Reads a stored choice; unknown or missing values fall back to [CompanionChoice.AUTO]. */
    fun parseChoice(name: String?): CompanionChoice =
        CompanionChoice.entries.firstOrNull { it.name == name } ?: CompanionChoice.AUTO

    /**
     * The moment for today. [daysSinceLastWorkout] counts calendar days since the last finished
     * workout and is `null` when there is none; `0` means today.
     */
    fun moment(daysSinceLastWorkout: Long?): CompanionMoment = when {
        daysSinceLastWorkout == null -> CompanionMoment.FIRST_STEPS
        daysSinceLastWorkout <= 0L -> CompanionMoment.TRAINED_TODAY
        daysSinceLastWorkout >= WELCOME_BACK_AFTER_DAYS -> CompanionMoment.WELCOME_BACK
        else -> CompanionMoment.EVERYDAY
    }

    /**
     * The appearance on the home screen for [epochDay], or `null` when the companion stays away
     * today. [character] is the resolved companion, `null` when it is switched off. First steps
     * and a comeback always get a greeting; training days and ordinary days only on some days.
     * [hiddenEpochDay] is the day the user hid the companion, which keeps it away for the rest of
     * that day.
     */
    fun homeAppearance(
        character: CompanionCharacter?,
        epochDay: Long,
        daysSinceLastWorkout: Long?,
        hiddenEpochDay: Long?
    ): CompanionAppearance? {
        if (character == null || hiddenEpochDay == epochDay) return null
        val moment = moment(daysSinceLastWorkout)
        val roll = dayHash(epochDay)
        val appears = when (moment) {
            CompanionMoment.FIRST_STEPS, CompanionMoment.WELCOME_BACK -> true
            CompanionMoment.TRAINED_TODAY -> roll % TRAINED_TODAY_ONE_IN == 0
            CompanionMoment.EVERYDAY -> roll % EVERYDAY_ONE_IN == 0
        }
        if (!appears) return null
        return CompanionAppearance(character, lineFor(moment, epochDay))
    }

    /** The line for [moment] on [epochDay]; it changes from day to day, never within a day. */
    fun lineFor(moment: CompanionMoment, epochDay: Long): CompanionLine {
        val lines = linesByMoment.getValue(moment)
        // A second hash, so the line does not follow the appearance roll.
        return lines[dayHash(epochDay xor LINE_SALT) % lines.size]
    }

    /** All lines of [moment], for tests and previews. */
    fun lines(moment: CompanionMoment): List<CompanionLine> = linesByMoment.getValue(moment)

    /** Non-negative, well mixed value per day (SplitMix64 finalizer). */
    internal fun dayHash(epochDay: Long): Int {
        var z = epochDay + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 33).toInt()) and Int.MAX_VALUE
    }

    private const val LINE_SALT: Long = 0x5EED_0D1EL
}
