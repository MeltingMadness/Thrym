import Foundation
import Shared
import UIKit
import XCTest
@testable import ThrymIOS

/// The Swift side of the shared XP rules: which data the engine gets from the iOS store, the state
/// this device celebrated, the emblems and the titles. The rules themselves are tested in `:shared`.
final class GamificationTests: XCTestCase {
    private let press = ILExercise(id: 1, name: "Bankdrücken eng", category: "LANGHANTEL", isCustom: true)

    override func setUp() {
        super.setUp()
        IOSGamification.resetCelebrated()
    }

    override func tearDown() {
        IOSGamification.resetCelebrated()
        super.tearDown()
    }

    private func settings(enabled: Bool = true, history: String = "") -> SettingsFormState {
        var state = SettingsFormState.empty
        state.weeklyGoalEnabled = enabled
        state.weeklyGoalHistory = history
        return state
    }

    private func millis(daysAgo: Int) -> Int64 {
        Int64(Date().addingTimeInterval(-Double(daysAgo) * 86_400).timeIntervalSince1970 * 1_000)
    }

    /// One finished workout per entry, each with a set of the given weight.
    private func data(_ workouts: [(id: Int64, daysAgo: Int, weight: Double)]) -> ILTrainingData {
        ILTrainingData(
            exercises: [press],
            workoutSessions: workouts.map {
                ILWorkoutSession(id: $0.id, startTime: millis(daysAgo: $0.daysAgo), endTime: millis(daysAgo: $0.daysAgo) + 3_600_000)
            },
            workoutSets: workouts.map {
                ILWorkoutSet(id: $0.id, sessionId: $0.id, exerciseId: 1, setNumber: 1, reps: 5, weightKg: $0.weight, completedAt: millis(daysAgo: $0.daysAgo))
            }
        )
    }

    func testEveryEmblemIsInTheAppBundle() {
        for chapter in 1...8 {
            for tier in 1...3 {
                XCTAssertNotNil(UIImage(named: "emblem-\(chapter)-\(tier)"), "emblem-\(chapter)-\(tier)")
            }
        }
    }

    func testOnlyFinishedWorkoutsWithWorkSetsCount() {
        var training = data([(id: 1, daysAgo: 3, weight: 40)])
        training.workoutSessions.append(ILWorkoutSession(id: 2, startTime: millis(daysAgo: 0), endTime: nil))
        training.workoutSets.append(
            ILWorkoutSet(id: 2, sessionId: 2, exerciseId: 1, setNumber: 1, reps: 5, weightKg: 200, completedAt: millis(daysAgo: 0))
        )

        let state = IOSGamification.evaluate(data: training, settings: settings())

        XCTAssertEqual(state.totalXp, 100)
        XCTAssertNil(state.session(sessionId: 2))
    }

    func testAcceptedIncreaseCountsAndAPendingOneDoesNot() {
        var training = data([(id: 1, daysAgo: 3, weight: 40)])
        training.progressionSuggestions = [
            ILProgressionSuggestion(sourceSessionId: 1, outcomeType: "PROPOSE_CHANGE", reasonCode: "REP_TARGET_ADVANCED", status: "ACCEPTED"),
            ILProgressionSuggestion(id: 2, sourceSessionId: 1, outcomeType: "PROPOSE_CHANGE", reasonCode: "REP_TARGET_ADVANCED", status: "PENDING")
        ]

        XCTAssertEqual(IOSGamification.evaluate(data: training, settings: settings()).totalXp, 130)
    }

    func testALevelUpIsCelebratedOnce() throws {
        // Far enough apart for the XP to pass the first tier with the last workout.
        let workouts = (1...3).map { (id: Int64($0), daysAgo: (4 - $0) * 8, weight: 40.0) }
        let training = data(workouts)

        let first = try XCTUnwrap(IOSGamification.celebrate(sessionID: 3, data: training, settings: settings()))
        XCTAssertEqual(first.levelUp?.kind, LevelUpKind.tier)

        let second = try XCTUnwrap(IOSGamification.celebrate(sessionID: 3, data: training, settings: settings()))
        XCTAssertNil(second.levelUp)
    }

    func testAResetDoesNotCatchUpOnTheImportedHistory() throws {
        let training = data((1...30).map { (id: Int64($0), daysAgo: (31 - $0) * 8, weight: 40.0) })

        IOSGamification.resetCelebrated()
        let celebration = try XCTUnwrap(IOSGamification.celebrate(sessionID: 30, data: training, settings: settings()))

        XCTAssertLessThanOrEqual(celebration.after.totalXp - celebration.before.totalXp, 100)
        XCTAssertNil(celebration.levelUp)
    }

    func testTitlesNameTheTierAndTheFurtherDeeds() {
        let curve = LevelCurve.shared
        XCTAssertTrue(IOSGamification.title(curve.progress(totalXp: 0)).hasSuffix(" I"))
        let deeds = curve.totalXpFor(level: curve.MAX_LEVEL) + 2 * curve.XP_PER_STAR
        let last = curve.progress(totalXp: deeds)
        XCTAssertTrue(IOSGamification.title(last).hasSuffix("· 2 ★"))
        XCTAssertNil(IOSGamification.nextStepTitle(last))
        XCTAssertEqual(IOSGamification.nextStepTitle(curve.progress(totalXp: 0)), "\(IOSGamification.chapterName(1)) II")
    }
}
