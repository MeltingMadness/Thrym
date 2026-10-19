package com.thrym.app.presentation.onboarding

import com.thrym.app.domain.repository.OnboardingRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGateTest {

    private class FakeOnboardingRepository(var completed: Boolean = false) : OnboardingRepository {
        var markCount = 0
        override suspend fun isCompleted(): Boolean = completed
        override suspend fun markCompleted() {
            markCount++
            completed = true
        }
    }

    @Test
    fun `frische Installation zeigt das Onboarding`() = runTest {
        val repository = FakeOnboardingRepository()
        val gate = OnboardingGate(repository) { false }

        assertTrue(gate.shouldShow())
        assertFalse(repository.completed)
    }

    @Test
    fun `erledigtes Onboarding erscheint nicht wieder`() = runTest {
        var dataChecked = false
        val gate = OnboardingGate(FakeOnboardingRepository(completed = true)) {
            dataChecked = true
            false
        }

        assertFalse(gate.shouldShow())
        assertFalse(dataChecked)
    }

    @Test
    fun `vorhandene Trainingsdaten ueberspringen das Onboarding dauerhaft`() = runTest {
        val repository = FakeOnboardingRepository()
        val gate = OnboardingGate(repository) { true }

        assertFalse(gate.shouldShow())
        assertTrue(repository.completed)
        assertEquals(1, repository.markCount)
    }
}
