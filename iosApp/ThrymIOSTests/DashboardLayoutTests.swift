import XCTest
@testable import ThrymIOS

final class DashboardLayoutTests: XCTestCase {
    func testOpenItemsAreOrderedAndAbsentWhenNothingIsPending() {
        XCTAssertEqual(IOSDashboardLayout.openItems(pendingProgressions: 0, deloadSuggested: false, deloadActive: false, activeDeloadMode: "NONE", hasTrend: true, hasComeback: false), [])
        XCTAssertEqual(IOSDashboardLayout.openItems(pendingProgressions: 2, deloadSuggested: true, deloadActive: false, activeDeloadMode: "NONE", hasTrend: true, hasComeback: true), [.progression, .deload, .comeback])
    }

    func testDeloadIncludesActiveModeAndTrendButRequiresTrendForSheet() {
        for (suggested, active, mode) in [(true, false, "NONE"), (false, true, "NONE"), (false, false, "HALVE_SET_VOLUME")] {
            XCTAssertEqual(IOSDashboardLayout.openItems(pendingProgressions: 0, deloadSuggested: suggested, deloadActive: active, activeDeloadMode: mode, hasTrend: true, hasComeback: false), [.deload])
            XCTAssertEqual(IOSDashboardLayout.openItems(pendingProgressions: 0, deloadSuggested: suggested, deloadActive: active, activeDeloadMode: mode, hasTrend: false, hasComeback: false), [])
        }
    }

    func testMusclesPutPlannedFirstAndExpandWithoutLosingAny() {
        func muscle(_ name: String, _ planned: Bool?) -> ILMuscleGroupContext {
            ILMuscleGroupContext(muscleGroup: name, lastTrainedEpochMillis: nil, setsInWindow: 3, setsToday: 0, soreness: nil, flags: [], plannedToday: planned)
        }
        let muscles = [muscle("a", false), muscle("b", true), muscle("c", nil), muscle("d", true), muscle("e", false), muscle("f", true)]
        XCTAssertEqual(IOSDashboardLayout.orderedMuscles(muscles, expanded: false).map(\.muscleGroup), ["b", "d", "f", "a"])
        XCTAssertEqual(IOSDashboardLayout.orderedMuscles(muscles, expanded: true).map(\.muscleGroup), ["b", "d", "f", "a", "c", "e"])
        XCTAssertTrue(IOSDashboardLayout.orderedMuscles([], expanded: false).isEmpty)
        let stale = ILMuscleGroupContext(muscleGroup: "stale", lastTrainedEpochMillis: 1, setsInWindow: 0, setsToday: 0, soreness: nil, flags: [], plannedToday: false)
        XCTAssertTrue(IOSDashboardLayout.orderedMuscles([stale], expanded: true).isEmpty)
        XCTAssertEqual(IOSDashboardLayout.orderedMuscles(Array(muscles.prefix(2)), expanded: false).count, 2)
    }
}
