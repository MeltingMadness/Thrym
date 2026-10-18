import XCTest
@testable import ThrymIOS

/// Bodyweight exercises store the added load as the set weight. Expectations come from the
/// catalog of the running language; `LocalizationTests` pins the English and German wording.
final class BodyweightLoadTests: XCTestCase {
    func testOnlyBodyweightCategoryTracksAddedLoad() {
        XCTAssertTrue(ILExercise(category: "EIGENGEWICHT").tracksAddedLoad)
        XCTAssertFalse(ILExercise(category: "LANGHANTEL").tracksAddedLoad)
        XCTAssertFalse(ILExercise(category: "KURZHANTEL").tracksAddedLoad)
    }

    func testZeroKilogramsReadsAsBodyweight() {
        let bodyweight = String(localized: "Körpergewicht")
        XCTAssertEqual(iosSetLoadText(weightKg: 0, unitSystem: "METRIC", addedLoad: true), bodyweight)
        XCTAssertEqual(iosSetLoadText(weightKg: 0, unitSystem: "METRIC", addedLoad: false), bodyweight)
    }

    func testAddedLoadReadsAsBodyweightPlusLoad() {
        let load = iosWorkoutDisplayWeight(kilograms: 10, unitSystem: "METRIC")
        let expected = String(localized: "Körpergewicht + \(load)")
        XCTAssertEqual(iosSetLoadText(weightKg: 10, unitSystem: "METRIC", addedLoad: true), expected)
        XCTAssertEqual(
            iosSetValueText(weightKg: 10, reps: 8, unitSystem: "METRIC", addedLoad: true),
            String(localized: "\(expected) × \(8) Wdh")
        )
        XCTAssertTrue(expected.contains(load))
    }

    func testOtherExercisesKeepThePlainWeight() {
        XCTAssertEqual(
            iosSetLoadText(weightKg: 82.5, unitSystem: "METRIC"),
            iosWorkoutDisplayWeight(kilograms: 82.5, unitSystem: "METRIC")
        )
        XCTAssertEqual(
            iosSetLoadText(weightKg: 10, unitSystem: "IMPERIAL", addedLoad: true),
            String(localized: "Körpergewicht + \(iosWorkoutDisplayWeight(kilograms: 10, unitSystem: "IMPERIAL"))")
        )
    }

    func testEmptyPlanWeightMeansNoLoad() {
        var draft = IOSPlanExerciseDraft(exerciseID: 1, unitSystem: "METRIC")
        draft.targetWeight = ""
        XCTAssertEqual(draft.targetWeightValue, 0)
        draft.targetWeight = "  "
        XCTAssertEqual(draft.targetWeightValue, 0)
        draft.targetWeight = "12,5"
        XCTAssertEqual(draft.targetWeightValue, 12.5)
        draft.targetWeight = "abc"
        XCTAssertNil(draft.targetWeightValue)
    }

    /// A plan target without weight (0 kg) offers the load of the last matching workout, so
    /// pull-ups trained with + 10 kg start at + 10 kg next time. Android matches this order.
    func testZeroTargetPrefillsTheLastWorkoutLoad() {
        XCTAssertEqual(row(targetWeightKg: 0, previousWorkWeightKg: 10).defaultWeightKg, 10)
        XCTAssertEqual(row(targetWeightKg: 0, previousWorkWeightKg: nil).defaultWeightKg, 0)
        XCTAssertEqual(row(targetWeightKg: 20, previousWorkWeightKg: 10).defaultWeightKg, 20)
        let logged = ILWorkoutSet(setNumber: 1, reps: 8, weightKg: 5, setType: "NORMAL")
        XCTAssertEqual(row(targetWeightKg: 0, previousWorkWeightKg: 10, sets: [logged]).defaultWeightKg, 5)
    }

    private func row(targetWeightKg: Double, previousWorkWeightKg: Double?, sets: [ILWorkoutSet] = []) -> IOSWorkoutExerciseRow {
        let target = ILProgressionTarget(sets: 3, reps: 8, weightKg: targetWeightKg)
        return IOSWorkoutExerciseRow(
            id: "pull-ups",
            exercise: ILExercise(category: "EIGENGEWICHT"),
            target: ILWorkoutPlanTarget(id: 1, target: target),
            displayTarget: target,
            sets: sets,
            previousWorkWeightKg: previousWorkWeightKg,
            nextSetRecommendation: nil
        )
    }
}
