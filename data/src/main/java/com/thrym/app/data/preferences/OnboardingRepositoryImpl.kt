package com.thrym.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.thrym.app.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")

private val ONBOARDING_COMPLETED = booleanPreferencesKey("completed")

class OnboardingRepositoryImpl(private val context: Context) : OnboardingRepository {

    override suspend fun isCompleted(): Boolean =
        context.onboardingDataStore.data
            .catch { emit(emptyPreferences()) }
            .first()[ONBOARDING_COMPLETED] ?: false

    override suspend fun markCompleted() {
        context.onboardingDataStore.edit { it[ONBOARDING_COMPLETED] = true }
    }
}
