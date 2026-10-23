import Foundation
import Shared
import XCTest
@testable import ThrymIOS

/// The per-workout display order: the UserDefaults guard and the Swift side of the shared
/// `SessionExerciseOrder` rules. The rules themselves are tested in `:shared`.
final class WorkoutExerciseOrderTests: XCTestCase {
    private let suiteName = "WorkoutExerciseOrderTests"
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        defaults = UserDefaults(suiteName: suiteName)
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    func testStoredOrderRoundTripsForTheSameSession() {
        let session = ILWorkoutSession(id: 7, startTime: 1_760_000_000_000)

        IOSWorkoutExerciseOrderPersistence.save(["target-3", "adhoc-9", "target-1"], for: session, defaults: defaults)

        XCTAssertEqual(
            IOSWorkoutExerciseOrderPersistence.load(for: session, defaults: defaults),
            ["target-3", "adhoc-9", "target-1"]
        )
    }

    func testOrderOfAnotherSessionOrStartTimeIsDroppedAndRemoved() {
        let session = ILWorkoutSession(id: 7, startTime: 1_760_000_000_000)
        let key = IOSWorkoutExerciseOrderPersistence.key

        IOSWorkoutExerciseOrderPersistence.save(["target-1"], for: session, defaults: defaults)
        XCTAssertNil(IOSWorkoutExerciseOrderPersistence.load(for: ILWorkoutSession(id: 8, startTime: session.startTime), defaults: defaults))
        XCTAssertNil(defaults.object(forKey: key))

        // An import can reuse the id; the start time still tells the workouts apart.
        IOSWorkoutExerciseOrderPersistence.save(["target-1"], for: session, defaults: defaults)
        XCTAssertNil(IOSWorkoutExerciseOrderPersistence.load(for: ILWorkoutSession(id: 7, startTime: session.startTime + 1), defaults: defaults))
        XCTAssertNil(defaults.object(forKey: key))
        XCTAssertNil(IOSWorkoutExerciseOrderPersistence.load(for: session, defaults: defaults))
    }

    func testMalformedOrderIsRemovedAndClearDropsTheKey() {
        let session = ILWorkoutSession(id: 7, startTime: 1_000)
        let key = IOSWorkoutExerciseOrderPersistence.key

        defaults.set("v1|7|1000|target-1,single-target-2", forKey: key)
        XCTAssertNil(IOSWorkoutExerciseOrderPersistence.load(for: session, defaults: defaults))
        XCTAssertNil(defaults.object(forKey: key))

        IOSWorkoutExerciseOrderPersistence.save([], for: session, defaults: defaults)
        XCTAssertEqual(IOSWorkoutExerciseOrderPersistence.load(for: session, defaults: defaults), [])
        IOSWorkoutExerciseOrderPersistence.clear(defaults: defaults)
        XCTAssertNil(defaults.object(forKey: key))
    }

    func testDefaultLayoutKeepsTheSupersetBlockAndTodaysGroupIDs() {
        let rows = [
            row("target-1"),
            row("target-2", group: 1),
            row("target-3", group: 1),
            row("target-4", group: 2),
            row("adhoc-9", planned: false),
        ]

        let layout = IOSWorkoutExerciseLayout(rowsInDefaultOrder: rows, storedKeys: [])

        XCTAssertEqual(
            layout.groups.map(\.id),
            ["single-target-1", "superset-1-target-2-target-3", "single-target-4", "single-adhoc-9"]
        )
        XCTAssertEqual(layout.groups.map(\.supersetGroupID), [nil, 1, nil, nil])
        XCTAssertEqual(layout.groups[1].rows.map(\.id), ["target-2", "target-3"])
        XCTAssertEqual(layout.rows.map(\.id), rows.map(\.id))
    }

    func testStoredOrderMovesTheSupersetAsOneBlock() {
        let rows = [row("target-1"), row("target-2", group: 1), row("target-3", group: 1), row("adhoc-9", planned: false)]

        let layout = IOSWorkoutExerciseLayout(rowsInDefaultOrder: rows, storedKeys: ["target-3", "adhoc-9", "target-1"])

        XCTAssertEqual(layout.groups.map(\.id), ["superset-1-target-2-target-3", "single-adhoc-9", "single-target-1"])
        // Members keep their order inside the block.
        XCTAssertEqual(layout.rows.map(\.id), ["target-2", "target-3", "adhoc-9", "target-1"])
    }

    func testMoveSwapsBlocksAndStopsAtTheEdges() throws {
        let rows = [row("target-1"), row("target-2", group: 1), row("target-3", group: 1), row("adhoc-9", planned: false)]
        let layout = IOSWorkoutExerciseLayout(rowsInDefaultOrder: rows, storedKeys: [])

        XCTAssertNil(layout.move(key: "target-1", direction: .up))
        XCTAssertNil(layout.move(key: "adhoc-9", direction: .down))
        XCTAssertNil(layout.move(key: "target-99", direction: .down))
        XCTAssertFalse(layout.canMove(groupID: "single-target-1", direction: .up))
        XCTAssertTrue(layout.canMove(groupID: "single-target-1", direction: .down))
        XCTAssertFalse(layout.canMove(groupID: "single-adhoc-9", direction: .down))

        // Any member moves the whole superset.
        let supersetUp = try XCTUnwrap(layout.move(key: "target-3", direction: .up))
        XCTAssertEqual(supersetUp, ["target-2", "target-3", "target-1", "adhoc-9"])

        // An ad-hoc exercise can move above planned ones.
        let adHocUp = try XCTUnwrap(layout.move(key: "adhoc-9", direction: .up))
        XCTAssertEqual(adHocUp, ["target-1", "adhoc-9", "target-2", "target-3"])
        let moved = IOSWorkoutExerciseLayout(rowsInDefaultOrder: rows, storedKeys: adHocUp)
        XCTAssertEqual(moved.groups.map(\.id), ["single-target-1", "single-adhoc-9", "superset-1-target-2-target-3"])
        XCTAssertEqual(moved.move(key: "adhoc-9", direction: .up), ["adhoc-9", "target-1", "target-2", "target-3"])
        XCTAssertTrue(adHocUp.allSatisfy { SessionExerciseOrderCodec.shared.isValidKey(key: $0) })
    }

    func testAdHocDefaultOrderFollowsInsertionThenFirstSet() {
        let sets = [
            ILWorkoutSet(id: 1, exerciseId: 2, completedAt: 50, planTargetSnapshotId: 4),
            ILWorkoutSet(id: 2, exerciseId: 8, completedAt: 300),
            ILWorkoutSet(id: 3, exerciseId: 3, completedAt: 100),
            ILWorkoutSet(id: 5, exerciseId: 6, completedAt: 200),
            ILWorkoutSet(id: 4, exerciseId: 7, completedAt: 200),
            ILWorkoutSet(id: 6, exerciseId: 8, completedAt: 10),
        ]

        let ids = IOSWorkoutExerciseLayout.adHocExerciseIDs(added: [5, 3], sets: sets)

        // Added first, as added (not sorted by id); then the rest by their first set,
        // equal times by set id. Planned sets do not add ad-hoc rows.
        XCTAssertEqual(ids, [5, 3, 8, 7, 6])
    }

    func testOrderStoredAtFinishKeepsTheAddedOrderOnTheCompletionScreen() {
        // B (5) added before A (3), A logged first, nothing moved.
        let sets = [
            ILWorkoutSet(id: 1, exerciseId: 3, completedAt: 100),
            ILWorkoutSet(id: 2, exerciseId: 5, completedAt: 200),
        ]
        let adHocRows: ([Int64]) -> [IOSWorkoutExerciseRow] = { added in
            IOSWorkoutExerciseLayout.adHocExerciseIDs(added: added, sets: sets)
                .map { self.row("adhoc-\($0)", planned: false) }
        }
        let running = IOSWorkoutExerciseLayout(rowsInDefaultOrder: [row("target-1")] + adHocRows([5, 3]), storedKeys: [])
        let storedAtFinish = running.rows.map(\.id)
        XCTAssertEqual(storedAtFinish, ["target-1", "adhoc-5", "adhoc-3"])

        // The completion screen no longer knows the added order and falls back to the first set.
        let completion = IOSWorkoutExerciseLayout(rowsInDefaultOrder: [row("target-1")] + adHocRows([]), storedKeys: storedAtFinish)
        XCTAssertEqual(completion.rows.map(\.id), storedAtFinish)
    }

    /// Superset A + B + C: after a set the next member with open sets comes, after the last
    /// member the first one again; members without open sets and plain cards are skipped.
    func testNextSupersetMemberFollowsTheRoundAndSkipsFinishedMembers() {
        let layout = IOSWorkoutExerciseLayout(
            rowsInDefaultOrder: [
                row("target-1", group: 1, sets: 3, logged: 1),
                row("target-2", group: 1, sets: 3, logged: 0),
                row("target-3", group: 1, sets: 2, logged: 2),
                row("target-4", sets: 3, logged: 1)
            ],
            storedKeys: []
        )

        XCTAssertEqual(layout.nextSupersetRowID(after: "target-1"), "target-2")
        // C is done, so B goes back to A for the next round.
        XCTAssertEqual(layout.nextSupersetRowID(after: "target-2"), "target-1")
        XCTAssertEqual(layout.nextSupersetRowID(after: "target-3"), "target-1")
        XCTAssertNil(layout.nextSupersetRowID(after: "target-4"))
    }

    func testNoJumpWhenTheOtherMembersAreDone() {
        let layout = IOSWorkoutExerciseLayout(
            rowsInDefaultOrder: [row("target-1", group: 1, sets: 3, logged: 2), row("target-2", group: 1, sets: 3, logged: 3)],
            storedKeys: []
        )
        XCTAssertNil(layout.nextSupersetRowID(after: "target-1"))
    }

    private func row(_ id: String, group: Int? = nil, sets: Int, logged: Int) -> IOSWorkoutExerciseRow {
        let numericID = Int64(id.split(separator: "-").last ?? "0") ?? 0
        let target = ILProgressionTarget(sets: sets, reps: 8, weightKg: 50)
        return IOSWorkoutExerciseRow(
            id: id,
            exercise: ILExercise(id: numericID, name: id),
            target: ILWorkoutPlanTarget(id: numericID, supersetGroupId: group, target: target),
            displayTarget: target,
            sets: (0..<logged).map { index in
                ILWorkoutSet(
                    id: numericID * 10 + Int64(index),
                    sessionId: 1,
                    exerciseId: numericID,
                    setNumber: index + 1,
                    reps: 8,
                    weightKg: 50,
                    completedAt: 1_000,
                    planTargetSnapshotId: numericID
                )
            },
            previousWorkWeightKg: nil,
            nextSetRecommendation: nil
        )
    }

    private func row(_ id: String, group: Int? = nil, planned: Bool = true) -> IOSWorkoutExerciseRow {
        let numericID = Int64(id.split(separator: "-").last ?? "0") ?? 0
        return IOSWorkoutExerciseRow(
            id: id,
            exercise: ILExercise(id: numericID, name: id),
            target: planned ? ILWorkoutPlanTarget(id: numericID, supersetGroupId: group) : nil,
            displayTarget: nil,
            sets: [],
            previousWorkWeightKg: nil,
            nextSetRecommendation: nil
        )
    }
}
