package com.thrym.app.domain.model

import java.time.LocalDateTime

/** Start and deload flag of a completed session; all the weekly goal needs. */
data class CompletedSessionStart(
    val startTime: LocalDateTime,
    val isDeload: Boolean?
)
