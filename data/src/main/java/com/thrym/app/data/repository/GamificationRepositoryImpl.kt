package com.thrym.app.data.repository

import com.thrym.app.domain.model.ExerciseCategory
import com.thrym.app.domain.repository.AppPreferencesRepository
import com.thrym.app.domain.repository.ExerciseRepository
import com.thrym.app.domain.repository.GamificationRepository
import com.thrym.app.domain.repository.ProgressionRepository
import com.thrym.app.domain.repository.StatisticsRepository
import com.thrym.app.domain.repository.WorkoutRepository
import com.thrym.shared.gamification.GamificationEngine
import com.thrym.shared.gamification.GamificationInput
import com.thrym.shared.gamification.GamificationState
import com.thrym.shared.gamification.SessionCelebration
import com.thrym.shared.model.UnitSystem
import com.thrym.shared.model.WeekStart
import com.thrym.shared.recognition.MilestoneSession
import com.thrym.shared.workout.RecordSetInput
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class GamificationRepositoryImpl(
    private val workoutRepository: WorkoutRepository,
    private val statisticsRepository: StatisticsRepository,
    private val exerciseRepository: ExerciseRepository,
    private val progressionRepository: ProgressionRepository,
    private val appPreferencesRepository: AppPreferencesRepository
) : GamificationRepository {

    override suspend fun evaluate(): GamificationState {
        val sessions = workoutRepository.getAllCompletedSessionsList()
        val sets = statisticsRepository.getWorkSetsCompletedBetween(0L, Long.MAX_VALUE)
        val exercises = exerciseRepository.getExercisesByIds(sets.map { it.exerciseId }.distinct())
        val increases = progressionRepository.getAcceptedIncreaseSessionIds()
        val preferences = appPreferencesRepository.preferences.first()
        val zone = ZoneId.systemDefault()
        return withContext(Dispatchers.Default) {
            GamificationEngine.evaluate(
                GamificationInput(
                    sessions = sessions.map { session ->
                        MilestoneSession(
                            id = session.id,
                            epochDay = session.startTime.toLocalDate().toEpochDay(),
                            // Only the order matters, so a fixed offset is enough.
                            orderKey = session.startTime.toInstant(ZoneOffset.UTC).toEpochMilli(),
                            isDeload = session.isDeload
                        )
                    },
                    sets = sets.map { set ->
                        RecordSetInput(
                            id = set.id,
                            sessionId = set.sessionId,
                            exerciseId = set.exerciseId,
                            weightKg = set.weightKg,
                            reps = set.reps,
                            setType = set.setType.name,
                            completedAtEpochMillis = set.completedAt.atZone(zone).toInstant().toEpochMilli(),
                            sessionFinished = true
                        )
                    },
                    categoryByExercise = exercises.associate { it.id to it.category.name },
                    addedLoadExerciseIds = exercises.filter { it.category == ExerciseCategory.EIGENGEWICHT }.mapTo(HashSet()) { it.id },
                    acceptedIncreaseSessionIds = increases,
                    weekStart = WeekStart.valueOf(preferences.weekStart.name),
                    goalHistory = preferences.weeklyGoalHistory,
                    unitSystem = UnitSystem.valueOf(preferences.unitSystem.name)
                )
            )
        }
    }

    override suspend fun celebrate(sessionId: Long): SessionCelebration? {
        val celebrated = appPreferencesRepository.readCelebratedXp()
        val celebration = GamificationEngine.celebration(evaluate(), sessionId, celebrated) ?: return null
        appPreferencesRepository.writeCelebratedXp(maxOf(celebrated ?: 0L, celebration.after.totalXp))
        return celebration
    }
}
