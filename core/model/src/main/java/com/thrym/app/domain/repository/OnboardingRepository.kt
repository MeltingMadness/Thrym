package com.thrym.app.domain.repository

/**
 * Remembers whether the first-start introduction has been finished or skipped.
 *
 * The flag lives in its own store, apart from the app settings. It is device state like the
 * settings and is not part of the backup.
 */
interface OnboardingRepository {
    suspend fun isCompleted(): Boolean
    suspend fun markCompleted()
}
