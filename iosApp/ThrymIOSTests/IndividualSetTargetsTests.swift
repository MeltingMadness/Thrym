import XCTest
@testable import ThrymIOS

final class IndividualSetTargetsTests: XCTestCase {
    func testLegacyPlanAndIndividualTargetsRoundTrip() throws {
        let legacy = Data(#"{"id":1,"planId":1,"exerciseId":1,"orderIndex":0,"targetSets":3,"targetReps":10,"targetWeightKg":60}"#.utf8)
        XCTAssertTrue(try JSONDecoder().decode(ILPlanExercise.self, from: legacy).setTargets.isEmpty)
        let plan = ILPlanExercise(id: 1, setTargets: [ILPlannedSet(kind: "WARMUP", reps: 10, weightKg: 20), ILPlannedSet(kind: "BACKOFF", reps: 8, weightKg: 55)])
        XCTAssertEqual(try JSONDecoder().decode(ILPlanExercise.self, from: JSONEncoder().encode(plan)), plan)
    }

    func testPrefillAndMatchingKeepWarmupSeparateFromWork() {
        let row = makeRow(sets: [ILWorkoutSet(id: 1, reps: 9, weightKg: 21, setType: "WARMUP")])
        XCTAssertEqual(row.remainingPlannedSets, 3)
        XCTAssertEqual(row.defaultWeightKg, 60)
        XCTAssertEqual(row.targetReps, 8)
        XCTAssertEqual(row.nextPlannedSet?.kind, "NORMAL")
    }

    func testIndividualTargetsRespectDeloadWithoutChangingSnapshot() {
        var row = makeRow()
        let raw = row.target
        row.deloadMode = "REDUCE_INTENSITY_BY_15_PERCENT"
        XCTAssertEqual(row.loggingSlots[0].weightKg, 17)
        XCTAssertEqual(row.loggingSlots[1].weightKg, 51)
        XCTAssertEqual(row.target, raw)
        row = makeRow(displaySets: 2)
        row.deloadMode = "HALVE_SET_VOLUME"
        XCTAssertEqual(row.loggingSlots.map(\.kind), ["WARMUP", "NORMAL", "NORMAL"])
        XCTAssertEqual(row.target?.setTargets.count, 4)
    }

    func testFailureWorkAndExplicitBackoffCompleteTheirOwnSlots() {
        let sets = [
            ILWorkoutSet(id: 1, setType: "WARMUP"),
            ILWorkoutSet(id: 2, setType: "NORMAL"),
            ILWorkoutSet(id: 3, setType: "FAILURE")
        ]
        let unfinished = makeRow(sets: sets)
        XCTAssertEqual(unfinished.nextPlannedSet?.kind, "BACKOFF")
        XCTAssertFalse(unfinished.isComplete)
        let completed = makeRow(sets: sets + [ILWorkoutSet(id: 4, setType: "BACKOFF")])
        XCTAssertTrue(completed.isComplete)
        XCTAssertEqual(completed.completedNormalSets, 2)
        XCTAssertTrue(makeRow(sets: sets + [ILWorkoutSet(id: 4, setType: "NORMAL")]).isComplete)
    }

    func testAllPlannedSetsDoneNeedsPlannedSetsAndNoOpenSlot() {
        let allSlots = [
            ILWorkoutSet(id: 1, setType: "WARMUP"),
            ILWorkoutSet(id: 2, setType: "NORMAL"),
            ILWorkoutSet(id: 3, setType: "FAILURE"),
            ILWorkoutSet(id: 4, setType: "BACKOFF")
        ]
        let adHoc = IOSWorkoutExerciseRow(id: "adhoc-2", exercise: ILExercise(id: 2), target: nil, displayTarget: nil,
            sets: [ILWorkoutSet(id: 9, reps: 8, weightKg: 40)], previousWorkWeightKg: nil, nextSetRecommendation: nil)

        // A free workout has nothing planned, also with logged sets.
        XCTAssertFalse([IOSWorkoutExerciseRow]().allPlannedSetsDone)
        XCTAssertFalse([adHoc].allPlannedSetsDone)
        XCTAssertFalse([makeRow()].allPlannedSetsDone)
        XCTAssertFalse([makeRow(sets: Array(allSlots.dropLast()))].allPlannedSetsDone)
        XCTAssertTrue([makeRow(sets: allSlots)].allPlannedSetsDone)
        XCTAssertEqual([makeRow(sets: allSlots)].plannedSetCount, 4)

        // Extra and drop sets keep the plan done; an ad-hoc row never counts as open.
        XCTAssertTrue([makeRow(sets: allSlots + [ILWorkoutSet(id: 5, setType: "DROP_SET"), ILWorkoutSet(id: 6, setType: "NORMAL")])].allPlannedSetsDone)
        XCTAssertTrue([makeRow(sets: allSlots), adHoc].allPlannedSetsDone)

        // The planned warm-up slot must be filled; a failure set fills a normal slot.
        XCTAssertFalse([makeRow(sets: Array(allSlots.dropFirst()) + [ILWorkoutSet(id: 5, setType: "NORMAL")])].allPlannedSetsDone)

        // A target without slots contributes nothing and is not done on its own.
        let empty = IOSWorkoutExerciseRow(id: "target-3", exercise: ILExercise(id: 3),
            target: ILWorkoutPlanTarget(id: 3, target: ILProgressionTarget(sets: 0, reps: 8, weightKg: 60)),
            displayTarget: ILProgressionTarget(sets: 0, reps: 8, weightKg: 60), sets: [], previousWorkWeightKg: nil, nextSetRecommendation: nil)
        XCTAssertTrue(empty.isComplete)
        XCTAssertFalse([empty].allPlannedSetsDone)
        XCTAssertTrue([empty, makeRow(sets: allSlots)].allPlannedSetsDone)
    }

    func testAllPlannedSetsDoneFollowsTheDeloadTargets() {
        let sets = [ILWorkoutSet(id: 1, setType: "WARMUP"), ILWorkoutSet(id: 2, setType: "NORMAL"), ILWorkoutSet(id: 3, setType: "NORMAL")]
        // The back-off slot is still open without a deload.
        XCTAssertFalse([makeRow(sets: sets, displaySets: 3)].allPlannedSetsDone)
        // Halved volume drops the last work slot, so the same sets complete the plan.
        var halved = makeRow(sets: sets, displaySets: 2)
        halved.deloadMode = "HALVE_SET_VOLUME"
        XCTAssertEqual(halved.loggingSlots.count, 3)
        XCTAssertTrue([halved].allPlannedSetsDone)
    }

    private func makeRow(sets: [ILWorkoutSet] = [], displaySets: Int = 3) -> IOSWorkoutExerciseRow {
        let target = ILWorkoutPlanTarget(id: 1, target: ILProgressionTarget(sets: 3, reps: 8, weightKg: 60), setTargets: [
            ILPlannedSet(kind: "WARMUP", reps: 10, weightKg: 20),
            ILPlannedSet(reps: 8, weightKg: 60), ILPlannedSet(reps: 8, weightKg: 60), ILPlannedSet(kind: "BACKOFF", reps: 10, weightKg: 55)
        ])
        return IOSWorkoutExerciseRow(id: "test", exercise: ILExercise(), target: target, displayTarget: ILProgressionTarget(sets: displaySets, reps: 8, weightKg: 60), sets: sets, previousWorkWeightKg: nil, nextSetRecommendation: nil)
    }
}
