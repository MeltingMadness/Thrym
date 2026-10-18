import Foundation
import XCTest
@testable import ThrymIOS

/// The Liquid Glass dock shows every running rest. These tests pin its order,
/// when a timer start opens the full-screen pause, and the stack height cap.
final class RestDockTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    private func timer(_ rowKey: String, startedAfter seconds: TimeInterval = 0, duration: Int = 90) -> IOSWorkoutRestTimer {
        IOSWorkoutRestTimer.make(sessionID: 1, rowKey: rowKey, durationSeconds: duration, now: start.addingTimeInterval(seconds))
    }

    private func running(_ timers: IOSWorkoutRestTimer...) -> [String: IOSWorkoutRestTimer] {
        Dictionary(uniqueKeysWithValues: timers.map { ($0.rowKey, $0) })
    }

    // MARK: - Order

    func testShortestRemainingTimeComesFirst() {
        let long = timer("target-1", startedAfter: 0, duration: 180)
        let short = timer("target-2", startedAfter: 20, duration: 60)
        let middle = timer("target-3", startedAfter: 10, duration: 120)
        XCTAssertEqual(IOSWorkoutRestDock.ordered([long, short, middle]).map(\.rowKey), ["target-2", "target-3", "target-1"])
    }

    func testCountUpTimersFollowCountdownsInStartOrder() {
        let later = timer("adhoc-2", startedAfter: 30, duration: 0)
        let earlier = timer("adhoc-1", startedAfter: 5, duration: 0)
        let countdown = timer("target-1", startedAfter: 60, duration: 600)
        XCTAssertEqual(IOSWorkoutRestDock.ordered([later, countdown, earlier]).map(\.rowKey), ["target-1", "adhoc-1", "adhoc-2"])
    }

    func testEqualDeadlinesKeepAStableOrder() {
        let b = timer("target-b")
        let a = timer("target-a")
        XCTAssertEqual(IOSWorkoutRestDock.ordered([b, a]).map(\.rowKey), ["target-a", "target-b"])
        XCTAssertEqual(IOSWorkoutRestDock.ordered([a, b]).map(\.rowKey), ["target-a", "target-b"])
    }

    // MARK: - Opening the pause

    func testFirstCountdownOfASeriesOpensThePause() {
        let a = timer("target-a")
        XCTAssertTrue(IOSWorkoutRestDock.opensPause(starting: a, running: [:], now: start))
    }

    /// Superset A + B: B starts while A still counts down and stays in the dock.
    func testSecondSupersetExerciseStaysInTheDock() {
        let a = timer("target-a")
        let b = timer("target-b", startedAfter: 30)
        XCTAssertFalse(IOSWorkoutRestDock.opensPause(starting: b, running: running(a), now: start.addingTimeInterval(30)))
    }

    /// The next set of an exercise restarts its timer and opens the pause again,
    /// even while another exercise's rest is running.
    func testNextSetOfTheSameExerciseOpensThePause() {
        let a = timer("target-a")
        let b = timer("target-b", startedAfter: 30)
        let nextA = timer("target-a", startedAfter: 50)
        XCTAssertTrue(IOSWorkoutRestDock.opensPause(starting: nextA, running: running(a, b), now: start.addingTimeInterval(50)))
        XCTAssertTrue(IOSWorkoutRestDock.opensPause(starting: nextA, running: running(a), now: start.addingTimeInterval(50)))
    }

    /// A countdown that already reached zero no longer belongs to the series.
    func testEndedCountdownDoesNotBlockANewSeries() {
        let a = timer("target-a", duration: 60)
        let b = timer("target-b", startedAfter: 61)
        XCTAssertTrue(IOSWorkoutRestDock.opensPause(starting: b, running: running(a), now: start.addingTimeInterval(61)))
    }

    /// A count-up timer never ends by itself, so it does not hold a series open.
    func testCountUpTimerDoesNotBlockACountdown() {
        let stopwatch = timer("adhoc-1", duration: 0)
        let countdown = timer("target-a", startedAfter: 30)
        XCTAssertTrue(IOSWorkoutRestDock.opensPause(starting: countdown, running: running(stopwatch), now: start.addingTimeInterval(30)))
    }

    func testCountUpTimerNeverOpensThePause() {
        let stopwatch = timer("adhoc-1", duration: 0)
        XCTAssertFalse(IOSWorkoutRestDock.opensPause(starting: stopwatch, running: [:], now: start))
        XCTAssertFalse(IOSWorkoutRestDock.opensPause(starting: stopwatch, running: running(timer("adhoc-1", duration: 0)), now: start))
    }

    // MARK: - Stack height

    func testThreeRowsFitAndFourScroll() {
        let cap = IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 760)
        let rows = { (count: CGFloat) in
            count * IOSWorkoutGlassRestDockStack.rowHeight + (count - 1) * IOSWorkoutGlassRestDockStack.rowSpacing
        }
        XCTAssertGreaterThanOrEqual(cap, rows(3))
        XCTAssertLessThan(cap, rows(4))
    }

    func testShortScreensCapTheStackAtFortyPercent() {
        XCTAssertEqual(IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 500), 200)
    }

    func testUnmeasuredViewportUsesThePreferredCap() {
        XCTAssertEqual(
            IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 0),
            IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 10_000)
        )
    }
}
