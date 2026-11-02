package com.thrym.app.domain.repository

import com.thrym.shared.gamification.GamificationState
import com.thrym.shared.gamification.SessionCelebration

/** XP and level, derived from the history with the rules in `GamificationEngine`. */
interface GamificationRepository {
    suspend fun evaluate(): GamificationState

    /**
     * The celebration of the finished workout [sessionId], or null when it earned no XP. Moves
     * the sum this device celebrated forward, so a level-up shows once.
     */
    suspend fun celebrate(sessionId: Long): SessionCelebration?
}
