import Foundation
import XCTest
@testable import ThrymIOS

/// The Liquid Glass dock follows the same rules as Android's. These tests pin its
/// rows, when a timer start opens the full-screen pause, and the stack height cap.
final class RestDockTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    private func timer(_ rowKey: String, startedAfter seconds: TimeInterval = 0, duration: Int = 90) -> IOSWorkoutRestTimer {
        IOSWorkoutRestTimer.make(sessionID: 1, rowKey: rowKey, durationSeconds: duration, now: start.addingTimeInterval(seconds))
    }

    private func running(_ timers: IOSWorkoutRestTimer...) -> [String: IOSWorkoutRestTimer] {
        Dictionary(uniqueKeysWithValues: timers.map { ($0.rowKey, $0) })
    }

    // MARK: - Rows

    func testShortestRemainingTimeComesFirst() {
        let long = timer("target-1", startedAfter: 0, duration: 180)
        let short = timer("target-2", startedAfter: 20, duration: 60)
        let middle = timer("target-3", startedAfter: 10, duration: 120)
        XCTAssertEqual(IOSWorkoutRestDock.dockTimers([long, short, middle]).map(\.rowKey), ["target-2", "target-3", "target-1"])
    }

    func testEqualDeadlinesGoToTheEarlierStartThenTheKey() {
        let early = timer("target-z", startedAfter: 0, duration: 90)
        let late = timer("target-a", startedAfter: 30, duration: 60)
        XCTAssertEqual(IOSWorkoutRestDock.dockTimers([late, early]).map(\.rowKey), ["target-z", "target-a"])

        let b = timer("target-b")
        let a = timer("target-a")
        XCTAssertEqual(IOSWorkoutRestDock.dockTimers([b, a]).map(\.rowKey), ["target-a", "target-b"])
        XCTAssertEqual(IOSWorkoutRestDock.dockTimers([a, b]).map(\.rowKey), ["target-a", "target-b"])
    }

    /// Count-up timers share one row: the newest, after all countdowns.
    func testOnlyTheNewestCountUpTimerShowsAsLastRow() {
        let older = timer("adhoc-1", startedAfter: 5, duration: 0)
        let newer = timer("adhoc-2", startedAfter: 30, duration: 0)
        let countdown = timer("target-1", startedAfter: 60, duration: 600)
        XCTAssertEqual(IOSWorkoutRestDock.dockTimers([newer, countdown, older]).map(\.rowKey), ["target-1", "adhoc-2"])
        XCTAssertEqual(IOSWorkoutRestDock.dockTimers([older, newer]).map(\.rowKey), ["adhoc-2"])
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

    /// The next set restarts the exercise's timer; with no other timer running
    /// it opens the pause again.
    func testNextSetOfALoneExerciseOpensThePause() {
        let a = timer("target-a")
        let nextA = timer("target-a", startedAfter: 50)
        XCTAssertTrue(IOSWorkoutRestDock.opensPause(starting: nextA, running: running(a), now: start.addingTimeInterval(50)))
    }

    /// While another exercise rests, the next set stays in the dock, as on Android.
    func testNextSetWhileAnotherExerciseRestsStaysInTheDock() {
        let a = timer("target-a")
        let b = timer("target-b", startedAfter: 30)
        let nextA = timer("target-a", startedAfter: 50)
        XCTAssertFalse(IOSWorkoutRestDock.opensPause(starting: nextA, running: running(a, b), now: start.addingTimeInterval(50)))
    }

    /// A countdown that already reached zero no longer runs.
    func testEndedCountdownDoesNotBlockANewSeries() {
        let a = timer("target-a", duration: 60)
        let b = timer("target-b", startedAfter: 61)
        XCTAssertTrue(IOSWorkoutRestDock.opensPause(starting: b, running: running(a), now: start.addingTimeInterval(61)))
    }

    /// A count-up timer runs until it is removed, so the countdown is not alone.
    func testRunningCountUpTimerKeepsACountdownInTheDock() {
        let stopwatch = timer("adhoc-1", duration: 0)
        let countdown = timer("target-a", startedAfter: 30)
        XCTAssertFalse(IOSWorkoutRestDock.opensPause(starting: countdown, running: running(stopwatch), now: start.addingTimeInterval(30)))
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
        XCTAssertEqual(cap, rows(3))
        XCTAssertLessThan(cap, rows(4))
    }

    func testShortScreensCapTheStackAtFortyPercent() {
        XCTAssertEqual(IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 500), 200)
    }

    func testOneRowStackIsOnlyAsTallAsTheRow() {
        let cap = IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 760)
        let oneRow = IOSWorkoutGlassRestDockStack.estimatedContentHeight(rowCount: 1)
        XCTAssertEqual(oneRow, IOSWorkoutGlassRestDockStack.rowHeight)
        // Taking the cap here would float the single row in the middle of three rows.
        XCTAssertEqual(IOSWorkoutGlassRestDockStack.height(contentHeight: oneRow, maxHeight: cap), oneRow)
    }

    func testManyRowsStopAtTheCap() {
        let cap = IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 760)
        let fiveRows = IOSWorkoutGlassRestDockStack.estimatedContentHeight(rowCount: 5)
        XCTAssertEqual(IOSWorkoutGlassRestDockStack.height(contentHeight: fiveRows, maxHeight: cap), cap)
        XCTAssertEqual(IOSWorkoutGlassRestDockStack.estimatedContentHeight(rowCount: 0), 0)
    }

    func testUnmeasuredViewportUsesThePreferredCap() {
        XCTAssertEqual(
            IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 0),
            IOSWorkoutGlassRestDockStack.maxHeight(viewportHeight: 10_000)
        )
    }
}
