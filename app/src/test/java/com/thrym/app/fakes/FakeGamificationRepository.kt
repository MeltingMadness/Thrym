package com.thrym.app.fakes

import com.thrym.app.domain.repository.GamificationRepository
import com.thrym.shared.gamification.GamificationState
import com.thrym.shared.gamification.LevelCurve
import com.thrym.shared.gamification.SessionCelebration
import kotlinx.coroutines.CompletableDeferred

class FakeGamificationRepository(
    var state: GamificationState = GamificationState(0, LevelCurve.progress(0), emptyList()),
    var celebration: SessionCelebration? = null,
) : GamificationRepository {
    val celebratedSessions = mutableListOf<Long>()

    /** How often [evaluate] ran, also when it failed. */
    var evaluateCalls = 0
        private set

    /** Thrown by [evaluate] instead of returning [state] while it is set. */
    var evaluateFailure: Throwable? = null

    /** While set, [evaluate] waits for it, so a test can look at the state during an evaluation. */
    var evaluateGate: CompletableDeferred<Unit>? = null

    override suspend fun evaluate(): GamificationState {
        evaluateCalls++
        evaluateGate?.await()
        evaluateFailure?.let { throw it }
        return state
    }

    override suspend fun celebrate(sessionId: Long): SessionCelebration? {
        celebratedSessions += sessionId
        return celebration
    }
}
