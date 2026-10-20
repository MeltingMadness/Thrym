import Foundation
import Shared
import XCTest
@testable import ThrymIOS

/// The Swift side of the shared recognition rules: local days, the settings the weekly goal
/// reads, workout facts and marks from the iOS training data. The rules themselves are tested
/// in `:shared`.
final class RecognitionTests: XCTestCase {
    private let calendar = Calendar.current
    private let squat = ILExercise(id: 1, name: "Kniebeuge eng", category: "LANGHANTEL", isCustom: true)

    /// Local noon of a day, so the tests do not depend on the time zone.
    private func noon(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12))!
    }

    private func millis(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }

    private func session(_ id: Int64, _ date: Date, isDeload: Bool? = nil) -> ILWorkoutSession {
        ILWorkoutSession(id: id, startTime: millis(date), endTime: millis(date) + 3_600_000, isDeload: isDeload)
    }

    private func settings(enabled: Bool = true, history: String = "") -> SettingsFormState {
        var state = SettingsFormState.empty
        state.weeklyGoalEnabled = enabled
        state.weeklyGoalHistory = history
        return state
    }

    func testEpochDayKeepsTheLocalDay() {
        let date = noon(2026, 10, 21)
        // 2026-10-21 is day 20747 after 1970-01-01.
        XCTAssertEqual(IOSRecognition.epochDay(date), 20747)
        XCTAssertTrue(calendar.isDate(IOSRecognition.date(epochDay: 20747), inSameDayAs: date))
        // Day 0 is a Thursday, the shared rules count weeks from it.
        XCTAssertEqual(WeeklyGoalRules.shared.isoDayOfWeek(epochDay: 0), 4)
    }

    func testWeeklyGoalCountsTheRunningWeekAndTheWeeksHeld() throws {
        let now = noon(2026, 10, 21) // Wednesday
        let sessions = [
            // Two weeks before, three workouts: held.
            session(1, noon(2026, 10, 5)), session(2, noon(2026, 10, 7)), session(3, noon(2026, 10, 9)),
            // Last week, a deload week with one workout: held as well.
            session(4, noon(2026, 10, 13), isDeload: true),
            // This week, one so far.
            session(5, noon(2026, 10, 19))
        ]

        let status = try XCTUnwrap(IOSRecognition.weeklyGoal(sessions: sessions, settings: settings(), now: now))

        XCTAssertEqual(status.goal, 3)
        XCTAssertEqual(status.completedThisWeek, 1)
        XCTAssertFalse(status.goalMetThisWeek)
        XCTAssertEqual(status.streakWeeks, 2)
        XCTAssertNil(IOSRecognition.weeklyGoal(sessions: sessions, settings: settings(enabled: false), now: now))
    }

    func testWeeklyGoalReadsTheGoalOfTheRunningWeekFromTheHistory() {
        let monday = IOSRecognition.currentWeekStartEpochDay(weekStart: "MONDAY", now: noon(2026, 10, 21))
        // As the settings write it: the default for the past, then the new goal.
        let history = WeeklyGoalRules.shared.encode(
            history: WeeklyGoalRules.shared.withChange(history: [], currentWeekStartEpochDay: monday, goal: 4)
        )
        XCTAssertEqual(history, "0:3,\(monday):4")

        XCTAssertEqual(IOSRecognition.currentGoal(history: history, weekStart: "MONDAY", now: noon(2026, 10, 21)), 4)
        // The week before still has the default goal.
        XCTAssertEqual(IOSRecognition.currentGoal(history: history, weekStart: "MONDAY", now: noon(2026, 10, 14)), 3)
        XCTAssertEqual(IOSRecognition.currentGoal(history: "", weekStart: "SUNDAY", now: noon(2026, 10, 21)), 3)
    }

    func testPauseDaysStartAtThreeWeeks() {
        let now = noon(2026, 10, 21)
        XCTAssertEqual(IOSRecognition.pauseDays(sessions: [session(1, noon(2026, 9, 21))], now: now), 30)
        XCTAssertNil(IOSRecognition.pauseDays(sessions: [session(1, noon(2026, 10, 16))], now: now))
        XCTAssertNil(IOSRecognition.pauseDays(sessions: [], now: now))
    }

    func testCompletionFactsNumberTheWorkoutAndNameTheComeback() throws {
        let sessions = [session(1, noon(2026, 8, 3)), session(2, noon(2026, 9, 28))]

        let facts = try XCTUnwrap(IOSRecognition.completionFacts(sessionID: 2, sessions: sessions, settings: settings()))

        XCTAssertEqual(facts.trainingNumber, 2)
        XCTAssertEqual(facts.pauseDays?.intValue, 56)
        XCTAssertEqual(IOSRecognition.factTexts(facts).map(\.id), ["comeback"])
    }

    func testSessionMarksAndLaddersComeFromTheTrainingData() throws {
        let data = ILTrainingData(
            exercises: [squat],
            workoutSessions: [session(1, noon(2026, 10, 5)), session(2, noon(2026, 10, 12))],
            workoutSets: [
                ILWorkoutSet(id: 1, sessionId: 1, exerciseId: 1, setNumber: 1, reps: 5, weightKg: 92.5, completedAt: millis(noon(2026, 10, 5))),
                ILWorkoutSet(id: 2, sessionId: 2, exerciseId: 1, setNumber: 1, reps: 3, weightKg: 100, completedAt: millis(noon(2026, 10, 12))),
                // A warm-up never reaches a mark.
                ILWorkoutSet(id: 3, sessionId: 2, exerciseId: 1, setNumber: 2, reps: 1, weightKg: 120, completedAt: millis(noon(2026, 10, 12)), isWarmup: true)
            ]
        )

        let marks = IOSRecognition.sessionMarks(sessionID: 2, exercises: [squat], data: data, unitSystem: "METRIC")
        let mark = try XCTUnwrap(marks[1]?.first)
        XCTAssertEqual(marks[1]?.count, 1)
        XCTAssertEqual(mark.kind, .load_)
        XCTAssertEqual(mark.value, 100)
        XCTAssertEqual(IOSRecognition.markValue(mark), "100 \(String(localized: "kg"))")

        let ladder = try XCTUnwrap(IOSRecognition.markLadders(exercise: squat, data: data, unitSystem: "METRIC").first)
        XCTAssertEqual(ladder.reached.map(\.mark.value), [30, 40, 50, 60, 70, 80, 90, 100])
        XCTAssertEqual(ladder.next?.value, 120)
    }

    func testMarkTitlesHaveEnglishTranslations() throws {
        let path = try XCTUnwrap(Bundle.main.path(forResource: "en", ofType: "lproj"))
        let english = try XCTUnwrap(Bundle(path: path))
        XCTAssertEqual(english.localizedString(forKey: "%lld-%@-Marke", value: nil, table: nil), "%1$lld %2$@ mark")
        XCTAssertEqual(english.localizedString(forKey: "%lld-Wdh.-Marke", value: nil, table: nil), "%lld-rep mark")
        XCTAssertEqual(english.localizedString(forKey: "Leichter einsteigen", value: nil, table: nil), "Ease back in")
    }
}
