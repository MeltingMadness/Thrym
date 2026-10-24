package com.thrym.shared.progression

/**
 * Which coach results may land in the plan without a confirmation when the setting "Steigerungen
 * automatisch übernehmen" is on: only a proposed step up in load or reps after a success. Backoffs,
 * repeated targets and hints still wait for the athlete in the review.
 */
object ProgressionAutoApply {
    private val INCREASE_REASONS = setOf(
        ProgressionReasonCode.REP_TARGET_ADVANCED,
        ProgressionReasonCode.LOAD_ADVANCED,
        ProgressionReasonCode.TOTAL_REPS_COMPLETED,
        ProgressionReasonCode.RPE_WITHIN_TARGET
    ).map { it.name }.toSet()

    /** [outcomeType] and [reasonCode] are the stored enum names, as both platforms keep them. */
    fun isSafeIncrease(outcomeType: String, reasonCode: String): Boolean =
        outcomeType == ProgressionOutcomeType.PROPOSE_CHANGE.name && reasonCode in INCREASE_REASONS
}
