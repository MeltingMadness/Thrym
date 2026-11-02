package com.thrym.app.fakes

import com.thrym.app.domain.repository.GamificationRepository
import com.thrym.shared.gamification.GamificationState
import com.thrym.shared.gamification.LevelCurve
import com.thrym.shared.gamification.SessionCelebration

class FakeGamificationRepository(
    var state: GamificationState = GamificationState(0, LevelCurve.progress(0), emptyList()),
    var celebration: SessionCelebration? = null,
) : GamificationRepository {
    val celebratedSessions = mutableListOf<Long>()

    override suspend fun evaluate(): GamificationState = state

    override suspend fun celebrate(sessionId: Long): SessionCelebration? {
        celebratedSessions += sessionId
        return celebration
    }
}
