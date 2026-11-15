package com.thrym.app.presentation.progression

import com.thrym.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressionReviewSectionsTest {
    private val target = ProgressionTarget(3, 8, 100.0)
    private fun item(id: Long, status: ProgressionSuggestionStatus, canDecide: Boolean = false, proposed: ProgressionTarget? = null) = ProgressionReviewItemUi(
        id = id, sourceSessionId = 1, planId = 1, exerciseId = id, orderIndex = 0,
        scheme = ProgressionScheme.LINEAR, source = target, proposed = proposed, countedSets = emptyList(),
        reasonCode = ProgressionReasonCode.REPEAT_TARGET, reasonArguments = emptyMap(), status = status,
        canDecide = canDecide, configuredStep = null
    )
    @Test fun globalAndSessionSections() {
        val items = listOf(item(1, ProgressionSuggestionStatus.PENDING, true, target), item(2, ProgressionSuggestionStatus.PENDING), item(3, ProgressionSuggestionStatus.INFORMATIONAL), item(4, ProgressionSuggestionStatus.ACCEPTED), item(5, ProgressionSuggestionStatus.STALE), item(6, ProgressionSuggestionStatus.REJECTED))
        val global = ProgressionReviewUiState(items = items)
        assertEquals(listOf(1L), global.decisionItems.map { it.id })
        assertEquals(listOf(2L, 3L), global.noteItems.map { it.id })
        assertEquals(listOf(4L, 5L, 6L), global.pastItems.map { it.id })
        val session = global.copy(isSessionScoped = true)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), session.noteItems.map { it.id })
        assertEquals(emptyList<ProgressionReviewItemUi>(), session.pastItems)
    }
}
