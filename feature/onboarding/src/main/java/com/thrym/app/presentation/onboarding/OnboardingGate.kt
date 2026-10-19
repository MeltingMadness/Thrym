package com.thrym.app.presentation.onboarding

import com.thrym.app.domain.repository.OnboardingRepository

/**
 * Decides at app start whether the introduction is shown.
 *
 * It appears once on a fresh install. Someone who already has training data (an update from a
 * build before the onboarding existed, or data restored by other means) never sees it; the gate
 * marks it as completed for them so the answer stays stable.
 */
class OnboardingGate(
    private val onboardingRepository: OnboardingRepository,
    private val hasTrainingData: suspend () -> Boolean
) {
    suspend fun shouldShow(): Boolean {
        if (onboardingRepository.isCompleted()) return false
        if (hasTrainingData()) {
            onboardingRepository.markCompleted()
            return false
        }
        return true
    }
}
