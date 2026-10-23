import Foundation
import Shared

/// The display order of one workout, stored next to the rest timers in UserDefaults. It never
/// enters the training graph: `orderIndex` stays the plan-slot identity that the coach,
/// "Satzwerte übernehmen" and readiness read, and the backup does not carry it. Session id and
/// start time guard the payload, so an import that reuses the id finds no order of another
/// workout. The codec and the ordering rules live in `:shared`, so Android computes the same.
enum IOSWorkoutExerciseOrderPersistence {
    static let key = "thrym.workout.exerciseOrder"

    /// The stored row keys of `session`, or nil. A payload of another session or start time,
    /// or one that does not decode, is removed.
    static func load(for session: ILWorkoutSession, defaults: UserDefaults = .standard) -> [String]? {
        guard let raw = defaults.string(forKey: key) else { return nil }
        guard let keys = SessionExerciseOrderCodec.shared.decode(
            raw: raw,
            expectedSessionId: session.id,
            expectedSessionStartEpochMillis: session.startTime
        ) else {
            defaults.removeObject(forKey: key)
            return nil
        }
        return keys
    }

    static func save(_ keys: [String], for session: ILWorkoutSession, defaults: UserDefaults = .standard) {
        defaults.set(
            SessionExerciseOrderCodec.shared.encode(
                sessionId: session.id,
                sessionStartEpochMillis: session.startTime,
                keys: keys
            ),
            forKey: key
        )
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key)
    }
}

/// Rows and render groups of a workout in display order. The rows come in the default order
/// (targets by `(orderIndex, id)`, then ad-hoc exercises); `SessionExerciseOrder.arrange`
/// builds the units from that order, so a stored order can move blocks but never merge, split
/// or hide them. Keys are the row ids `target-<id>` and `adhoc-<exerciseId>`.
struct IOSWorkoutExerciseLayout {
    let rows: [IOSWorkoutExerciseRow]
    let groups: [IOSWorkoutExerciseRenderGroup]
    private let units: [SessionOrderUnit]

    init(rowsInDefaultOrder: [IOSWorkoutExerciseRow], storedKeys: [String]) {
        let orderRows = rowsInDefaultOrder.map { row in
            SessionOrderRow(
                key: row.id,
                supersetGroupId: row.target?.supersetGroupId.map { KotlinInt(int: Int32($0)) },
                planned: row.target != nil
            )
        }
        let rowByID = Dictionary(rowsInDefaultOrder.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        units = SessionExerciseOrder.shared.arrange(rowsInDefaultOrder: orderRows, storedKeys: storedKeys)
        groups = units.map { unit in
            IOSWorkoutExerciseRenderGroup(
                id: unit.key,
                supersetGroupID: unit.supersetGroupId?.intValue,
                rows: unit.memberKeys.compactMap { rowByID[$0] }
            )
        }
        rows = groups.flatMap(\.rows)
    }

    /// The full key list after moving the block that contains `key` (a row id or a group id),
    /// or nil at the edge.
    func move(key: String, direction: SessionOrderMove) -> [String]? {
        SessionExerciseOrder.shared.move(arranged: units, key: key, direction: direction)
    }

    func canMove(groupID: String, direction: SessionOrderMove) -> Bool {
        guard let index = groups.firstIndex(where: { $0.id == groupID }) else { return false }
        return direction == .up ? index > 0 : index < groups.count - 1
    }

    /// The superset member to log next after a set of `rowID`: the following member with open
    /// planned sets, after the last member the first one again for the next round. Nil outside
    /// a superset or when no other member has an open set.
    func nextSupersetRowID(after rowID: String) -> String? {
        guard let group = groups.first(where: { $0.supersetGroupID != nil && $0.rows.contains { $0.id == rowID } }),
              let index = group.rows.firstIndex(where: { $0.id == rowID }) else { return nil }
        let count = group.rows.count
        return (1..<max(count, 1))
            .map { group.rows[(index + $0) % count] }
            .first { ($0.remainingPlannedSets ?? 0) > 0 }?
            .id
    }

    /// Ad-hoc exercises in default order: the ones added in this workout in the order they were
    /// added, then exercises known only from their sets in the order of their first set.
    static func adHocExerciseIDs(added: [Int64], sets: [ILWorkoutSet]) -> [Int64] {
        let fromSets = sets
            .filter { $0.planTargetSnapshotId == nil }
            .sorted { $0.completedAt == $1.completedAt ? $0.id < $1.id : $0.completedAt < $1.completedAt }
            .map(\.exerciseId)
        var seen = Set<Int64>()
        return (added + fromSets).filter { seen.insert($0).inserted }
    }
}
