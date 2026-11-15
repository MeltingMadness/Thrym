import XCTest
@testable import ThrymIOS

final class ProgressionChangeTests: XCTestCase {
    private func change(_ old: ILProgressionTarget, _ new: ILProgressionTarget) -> IOSProgressionChange {
        IOSProgressionChange.between(old, new, unitSystem: "METRIC")
    }

    func testWeightIncreaseLeadsAndCarriesTheReps() {
        let result = change(ILProgressionTarget(sets: 3, reps: 10, weightKg: 60), ILProgressionTarget(sets: 3, reps: 8, weightKg: 62.5))
        XCTAssertEqual(result.direction, .up)
        XCTAssertEqual(result.from, IOSWeightFormatter.format(60, unitSystem: "METRIC"))
        XCTAssertEqual(result.to, IOSWeightFormatter.format(62.5, unitSystem: "METRIC"))
        XCTAssertEqual(result.delta, "+" + IOSWeightFormatter.format(2.5, unitSystem: "METRIC"))
        XCTAssertNotNil(result.also)
    }

    func testBackoffPointsDown() {
        let result = change(ILProgressionTarget(sets: 3, reps: 10, weightKg: 20), ILProgressionTarget(sets: 3, reps: 10, weightKg: 17.5))
        XCTAssertEqual(result.direction, .down)
        XCTAssertEqual(result.delta, "−" + IOSWeightFormatter.format(2.5, unitSystem: "METRIC"))
        XCTAssertNil(result.also)
    }

    func testRepsLeadWhenTheWeightStays() {
        let result = change(ILProgressionTarget(sets: 3, reps: 8, weightKg: 60), ILProgressionTarget(sets: 3, reps: 9, weightKg: 60))
        XCTAssertEqual(result.direction, .up)
        XCTAssertEqual(result.from, "8")
        XCTAssertNil(result.also)
    }

    func testUnchangedTargetHasNoDelta() {
        let target = ILProgressionTarget(sets: 3, reps: 8, weightKg: 60)
        let result = change(target, target)
        XCTAssertEqual(result.direction, .same)
        XCTAssertNil(result.delta)
    }
}
