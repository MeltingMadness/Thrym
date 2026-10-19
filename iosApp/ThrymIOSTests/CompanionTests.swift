import XCTest
@testable import ThrymIOS

/// The companion on iOS: choice, day numbers, lines and the pixel art from the shared core.
final class CompanionTests: XCTestCase {
    func testChoiceResolvesToACharacter() {
        XCTAssertEqual(IOSCompanion.characterName(choice: "AUTO"), "ODIN")
        XCTAssertEqual(IOSCompanion.characterName(choice: "ODIN"), "ODIN")
        XCTAssertEqual(IOSCompanion.characterName(choice: "VALKYRIE"), "VALKYRIE")
        XCTAssertNil(IOSCompanion.characterName(choice: "OFF"))
        XCTAssertEqual(IOSCompanion.characterName(choice: "unknown"), "ODIN")
    }

    func testEpochDayCountsLocalCalendarDays() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Berlin")!
        let morning = calendar.date(from: DateComponents(year: 1970, month: 1, day: 2, hour: 0, minute: 30))!
        let evening = calendar.date(from: DateComponents(year: 1970, month: 1, day: 2, hour: 23, minute: 30))!
        XCTAssertEqual(IOSCompanion.epochDay(for: morning, calendar: calendar), 1)
        XCTAssertEqual(IOSCompanion.epochDay(for: evening, calendar: calendar), 1)
    }

    func testDaysSinceLastWorkoutIgnoresRunningSessions() {
        let calendar = Calendar.current
        let now = calendar.date(from: DateComponents(year: 2026, month: 10, day: 19, hour: 18))!
        let threeDaysAgo = calendar.date(byAdding: .day, value: -3, to: now)!
        let millis = Int64(threeDaysAgo.timeIntervalSince1970 * 1000)
        let finished = ILWorkoutSession(id: 1, startTime: millis, endTime: millis + 3_600_000)
        let running = ILWorkoutSession(id: 2, startTime: Int64(now.timeIntervalSince1970 * 1000), endTime: nil)

        XCTAssertNil(IOSCompanion.daysSinceLastWorkout(sessions: [], now: now))
        XCTAssertEqual(IOSCompanion.daysSinceLastWorkout(sessions: [finished, running], now: now), 3)
    }

    func testFirstStepsAlwaysGreetAndHidingKeepsTheDayQuiet() {
        let appearance = IOSCompanion.homeAppearance(choice: "AUTO", epochDay: 20_000, sessions: [], hiddenDay: -1)
        XCTAssertEqual(appearance?.character, "ODIN")
        XCTAssertEqual(appearance?.line, "FIRST_STEPS_WELCOME")
        XCTAssertNil(IOSCompanion.homeAppearance(choice: "AUTO", epochDay: 20_000, sessions: [], hiddenDay: 20_000))
        XCTAssertNil(IOSCompanion.homeAppearance(choice: "OFF", epochDay: 20_000, sessions: [], hiddenDay: -1))
    }

    func testEveryLineHasAText() {
        let lines = [
            "ONBOARDING_WELCOME", "ONBOARDING_UNITS", "ONBOARDING_START", "FIRST_STEPS_WELCOME",
            "TRAINED_TODAY_REST", "TRAINED_TODAY_SLEEP", "WELCOME_BACK_ONE_SET", "EVERYDAY_YGGDRASIL",
            "EVERYDAY_WARMUP", "EVERYDAY_TECHNIQUE", "EVERYDAY_RECOVERY", "EVERYDAY_SMALL_PLATES"
        ]
        for line in lines {
            XCTAssertFalse(IOSCompanion.lineText(line, character: "ODIN").isEmpty, line)
        }
        XCTAssertNotEqual(
            IOSCompanion.lineText("ONBOARDING_WELCOME", character: "ODIN"),
            IOSCompanion.lineText("ONBOARDING_WELCOME", character: "VALKYRIE")
        )
    }

    @MainActor
    func testSpriteFramesBecome32PixelImages() {
        for character in ["ODIN", "VALKYRIE"] {
            for pose in ["IDLE", "BLINK", "CHEER"] {
                let image = IOSCompanion.image(character: character, pose: pose)
                XCTAssertEqual(image?.width, 32, "\(character) \(pose)")
                XCTAssertEqual(image?.height, 32, "\(character) \(pose)")
            }
        }
    }
}
