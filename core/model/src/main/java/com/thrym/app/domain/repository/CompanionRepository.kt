package com.thrym.app.domain.repository

import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionChoice
import kotlinx.coroutines.flow.Flow

/**
 * Settings of the companion (Odin or a valkyrie). [hiddenEpochDay] is the day the user hid the
 * companion on the home screen; it stays away for the rest of that day.
 */
data class CompanionPreferences(
    val choice: CompanionChoice = CompanionChoice.AUTO,
    val hiddenEpochDay: Long? = null
)

/**
 * Stores the companion settings in their own store, apart from the app settings. They are device
 * state like the settings and are not part of the backup.
 */
interface CompanionRepository {
    val preferences: Flow<CompanionPreferences>

    /** The companion the choice resolves to, `null` when it is switched off. */
    val character: Flow<CompanionCharacter?>

    suspend fun updateChoice(choice: CompanionChoice)
    suspend fun hideForDay(epochDay: Long)
}
