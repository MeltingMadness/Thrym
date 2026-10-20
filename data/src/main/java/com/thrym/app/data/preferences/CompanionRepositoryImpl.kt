package com.thrym.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.thrym.app.domain.repository.CompanionPreferences
import com.thrym.app.domain.repository.CompanionRepository
import com.thrym.shared.companion.CompanionCharacter
import com.thrym.shared.companion.CompanionChoice
import com.thrym.shared.companion.CompanionRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

val Context.companionDataStore by preferencesDataStore(name = "companion")

private val COMPANION_CHOICE = stringPreferencesKey("choice")
private val COMPANION_HIDDEN_EPOCH_DAY = longPreferencesKey("hidden_epoch_day")

class CompanionRepositoryImpl(private val context: Context) : CompanionRepository {

    override val preferences: Flow<CompanionPreferences> = context.companionDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            CompanionPreferences(
                choice = CompanionRules.parseChoice(prefs[COMPANION_CHOICE]),
                hiddenEpochDay = prefs[COMPANION_HIDDEN_EPOCH_DAY]
            )
        }

    /**
     * "Automatic" picks Odin until the app knows the user's sex; the optional profile in the
     * settings supplies it.
     */
    override val character: Flow<CompanionCharacter?> = preferences
        .map { CompanionRules.resolve(it.choice, profileFemale = false) }

    override suspend fun updateChoice(choice: CompanionChoice) {
        context.companionDataStore.edit { it[COMPANION_CHOICE] = choice.name }
    }

    override suspend fun hideForDay(epochDay: Long) {
        context.companionDataStore.edit { it[COMPANION_HIDDEN_EPOCH_DAY] = epochDay }
    }
}
