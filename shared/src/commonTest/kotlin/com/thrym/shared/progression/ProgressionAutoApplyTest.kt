package com.thrym.shared.progression

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressionAutoApplyTest {

    @Test
    fun proposedIncreasesAreSafe() {
        listOf(
            ProgressionReasonCode.REP_TARGET_ADVANCED,
            ProgressionReasonCode.LOAD_ADVANCED,
            ProgressionReasonCode.TOTAL_REPS_COMPLETED,
            ProgressionReasonCode.RPE_WITHIN_TARGET,
        ).forEach { reason ->
            assertTrue(ProgressionAutoApply.isSafeIncrease(ProgressionOutcomeType.PROPOSE_CHANGE.name, reason.name), reason.name)
        }
    }

    @Test
    fun backoffsAndRepeatsWaitForReview() {
        listOf(
            ProgressionReasonCode.STALL_BACKOFF,
            ProgressionReasonCode.REPEAT_TARGET,
            ProgressionReasonCode.MANUAL_WEIGHT_DEVIATION,
            ProgressionReasonCode.BACKOFF_FLOOR_REACHED,
        ).forEach { reason ->
            assertFalse(ProgressionAutoApply.isSafeIncrease(ProgressionOutcomeType.PROPOSE_CHANGE.name, reason.name), reason.name)
        }
    }

    @Test
    fun onlyProposedChangesAreApplied() {
        ProgressionOutcomeType.entries.filter { it != ProgressionOutcomeType.PROPOSE_CHANGE }.forEach { type ->
            assertFalse(ProgressionAutoApply.isSafeIncrease(type.name, ProgressionReasonCode.LOAD_ADVANCED.name), type.name)
        }
    }
}
