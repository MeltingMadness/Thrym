import Foundation
import Shared
import XCTest
@testable import ThrymIOS

/// The Swift side of the shared `SessionRecords` rules: the set adapter, order and names on
/// the completion board, the live wrapper and the record number format. The rules themselves
/// are tested in `:shared`.
final class WorkoutRecordsTests: XCTestCase {
    private let bench = ILExercise(id: 1, name: "Bank eng", isCustom: true)
    private let rowing = ILExercise(id: 2, name: "Rudern breit", isCustom: true)
    private let german = Locale(identifier: "de_DE")
    private let english = Locale(identifier: "en_US")

    func testInputsResolveLegacyWarmupsAndTakeFinishedFromTheEndTime() {
        let data = ILTrainingData(
            workoutSessions: [ILWorkoutSession(id: 1, startTime: 0, endTime: 100), ILWorkoutSession(id: 2, startTime: 200)],
            workoutSets: [
                ILWorkoutSet(id: 1, sessionId: 1, exerciseId: 1, reps: 5, weightKg: 60, completedAt: 10, isWarmup: true),
                ILWorkoutSet(id: 2, sessionId: 1, exerciseId: 1, reps: 5, weightKg: 80, setType: "FAILURE", completedAt: 20),
                ILWorkoutSet(id: 3, sessionId: 2, exerciseId: 1, reps: 6, weightKg: 85, completedAt: 210),
                ILWorkoutSet(id: 4, sessionId: 2, exerciseId: 2, reps: 8, weightKg: 50, completedAt: 220)
            ]
        )

        let inputs = IOSWorkoutRecords.recordInputs(exerciseID: 1, data: data)

        XCTAssertEqual(inputs.map(\.id), [1, 2, 3])
        XCTAssertEqual(inputs.map(\.setType), ["WARMUP", "FAILURE", "NORMAL"])
        XCTAssertEqual(inputs.map(\.sessionFinished), [true, true, false])
        XCTAssertEqual(inputs[2].sessionId, 2)
        XCTAssertEqual(inputs[2].weightKg, 85)
        XCTAssertEqual(inputs[2].reps, 6)
        XCTAssertEqual(inputs[2].completedAtEpochMillis, 210)
    }

    func testSummaryFollowsTheRowsWithTheirNamesAndSets() throws {
        let data = trainingData(current: [
            ILWorkoutSet(id: 11, sessionId: 2, exerciseId: 1, setNumber: 1, reps: 5, weightKg: 85, completedAt: 210),
            ILWorkoutSet(id: 12, sessionId: 2, exerciseId: 2, setNumber: 1, reps: 8, weightKg: 50, completedAt: 220),
            ILWorkoutSet(id: 13, sessionId: 2, exerciseId: 2, setNumber: 2, reps: 10, weightKg: 50, completedAt: 230)
        ])
        let session = try XCTUnwrap(data.workoutSessions.first { $0.id == 2 })
        // Rowing is shown first, so it comes first, although bench press holds the records.
        let rows = [row(rowing, sets: data.sets(for: 2).filter { $0.exerciseId == 2 }), row(bench, sets: data.sets(for: 2).filter { $0.exerciseId == 1 })]

        let summary = IOSWorkoutRecords.summary(session: session, rows: rows, data: data)

        XCTAssertEqual(summary.exercises.map(\.exerciseID), [2, 1])
        XCTAssertEqual(summary.exercises.map(\.name), ["Rudern breit", "Bank eng"])

        let first = summary.exercises[0]
        XCTAssertTrue(first.firstTime)
        XCTAssertTrue(first.lines.isEmpty)
        // Same weight: the set with more reps is the first benchmark.
        XCTAssertEqual(first.bestSet?.id, 13)

        let records = summary.exercises[1]
        XCTAssertFalse(records.firstTime)
        // 80 × 5 before (the warm-up of 90 kg does not count); the same reps are no record.
        XCTAssertEqual(records.lines.map(\.type), ["MAX_WEIGHT", "MAX_E1RM", "MAX_VOLUME"])
        XCTAssertEqual(records.lines[0].value, 85)
        XCTAssertEqual(records.lines[0].previous, 80)
        XCTAssertEqual(records.lines[0].set?.id, 11)
        XCTAssertEqual(try XCTUnwrap(records.lines[1].previous), 80 * (1 + 5.0 / 30), accuracy: 1e-9)
        XCTAssertEqual(records.lines[2].value, 425)
        XCTAssertEqual(records.lines[2].previous, 400)
        XCTAssertNil(records.lines[2].set)

        XCTAssertEqual(summary.recordCount, 3)
        XCTAssertEqual(summary.exercisesWithRecords.map(\.exerciseID), [1])
        XCTAssertEqual(summary.firstTimeExercises.map(\.exerciseID), [2])
    }

    func testSummaryLeavesOutRowsWithoutLoggedReps() throws {
        let data = trainingData(current: [ILWorkoutSet(id: 11, sessionId: 2, exerciseId: 1, setNumber: 1, reps: 0, weightKg: 85, completedAt: 210)])
        let session = try XCTUnwrap(data.workoutSessions.first { $0.id == 2 })

        let summary = IOSWorkoutRecords.summary(session: session, rows: [row(bench, sets: data.sets(for: 2))], data: data)

        XCTAssertTrue(summary.exercises.isEmpty)
    }

    func testLiveWrapperStaysQuietForAFirstTimeExerciseAndForTies() {
        let before = trainingData(current: [ILWorkoutSet(id: 11, sessionId: 2, exerciseId: 2, reps: 8, weightKg: 50, completedAt: 210)])
        var after = before
        after.workoutSets.append(ILWorkoutSet(id: 12, sessionId: 2, exerciseId: 2, reps: 8, weightKg: 60, completedAt: 220))

        // Rowing has no earlier workout: its first sets set benchmarks, not records.
        XCTAssertTrue(live(exerciseID: 2, before: before, after: after).isEmpty)

        let heavier = appending(ILWorkoutSet(id: 12, sessionId: 2, exerciseId: 1, reps: 5, weightKg: 85, completedAt: 220), to: before)
        let tie = appending(ILWorkoutSet(id: 13, sessionId: 2, exerciseId: 1, reps: 5, weightKg: 85, completedAt: 230), to: heavier)
        XCTAssertTrue(live(exerciseID: 1, before: heavier, after: tie).isEmpty)
    }

    func testLiveWrapperReportsTheGainOverTheBestBeforeTheWorkout() throws {
        let before = trainingData(current: [])
        let after = appending(ILWorkoutSet(id: 12, sessionId: 2, exerciseId: 1, reps: 5, weightKg: 85, completedAt: 220), to: before)

        let lines = live(exerciseID: 1, before: before, after: after)

        XCTAssertEqual(lines.map(\.type), ["MAX_WEIGHT", "MAX_E1RM"])
        XCTAssertEqual(lines[0].value, 85)
        XCTAssertEqual(lines[0].previous, 80)
        XCTAssertEqual(lines[0].set?.id, 12)

        let notice = try XCTUnwrap(IOSWorkoutRecords.notice(sessionID: 2, exerciseName: "Bank eng", lines: lines, unitSystem: "METRIC"))
        XCTAssertEqual(notice.sessionID, 2)
        XCTAssertEqual(notice.title, String(localized: "\(2) neue Rekorde · \("Bank eng")"))
        XCTAssertEqual(
            notice.detail,
            "\(iosRecordLabel("MAX_WEIGHT")) \(iosRecordWeightText(85, unitSystem: "METRIC")) (\(iosRecordWeightText(5, unitSystem: "METRIC", signed: true)))"
        )
        XCTAssertNil(IOSWorkoutRecords.notice(sessionID: 2, exerciseName: "Bank eng", lines: [], unitSystem: "METRIC"))
    }

    func testRecordWeightKeepsQuarterStepsAndRoundsComputedValues() {
        XCTAssertEqual(iosRecordWeightText(82.25, unitSystem: "METRIC", locale: german), "82,25 kg")
        XCTAssertEqual(iosRecordWeightText(82.25, unitSystem: "METRIC", locale: english), "82.25 kg")
        XCTAssertEqual(iosRecordWeightText(100, unitSystem: "METRIC", locale: german), "100 kg")
        XCTAssertEqual(iosRecordWeightText(100 * (1 + 5.0 / 30), unitSystem: "METRIC", locale: german), "116,7 kg")
        XCTAssertEqual(iosRecordWeightText(100, unitSystem: "IMPERIAL", locale: german), "220,5 lb")
        XCTAssertEqual(iosRecordWeightText(100, unitSystem: "IMPERIAL", locale: english), "220.5 lb")
    }

    func testRecordGainsAreSignedOnlyWhenAsked() {
        XCTAssertEqual(iosRecordWeightText(2.5, unitSystem: "METRIC", signed: true, locale: german), "+2,5 kg")
        XCTAssertEqual(iosRecordWeightText(1.25, unitSystem: "METRIC", signed: true, locale: german), "+1,25 kg")
        XCTAssertEqual(iosRecordWeightText(2.5, unitSystem: "IMPERIAL", signed: true, locale: english), "+5.5 lb")
        XCTAssertEqual(iosRecordWeightText(2.5, unitSystem: "METRIC", locale: german), "2,5 kg")
        XCTAssertEqual(iosRecordWeightText(-2.5, unitSystem: "METRIC", signed: true, locale: german), "-2,5 kg")
        XCTAssertEqual(iosRecordDeltaText(type: "MAX_E1RM", delta: 2.9166, unitSystem: "METRIC", locale: english), "+2.9 kg")
    }

    func testRecordVolumeHasNoDecimals() {
        XCTAssertEqual(iosRecordVolumeText(4319.6, unitSystem: "METRIC", locale: german), "4.320 kg")
        XCTAssertEqual(iosRecordVolumeText(4320, unitSystem: "METRIC", locale: english), "4,320 kg")
        XCTAssertEqual(iosRecordVolumeText(140.6, unitSystem: "IMPERIAL", signed: true, locale: english), "+310 lb")
        XCTAssertEqual(iosRecordValueText(type: "MAX_VOLUME", value: 310, unitSystem: "METRIC", locale: german), "310 kg")
    }

    func testRecordContextNamesTheSet() {
        let weight = IOSWorkoutRecordLine(type: "MAX_WEIGHT", value: 100, previous: 97.5, set: ILWorkoutSet(id: 1, reps: 5, weightKg: 100))
        XCTAssertEqual(iosRecordContext(weight, unitSystem: "METRIC"), "× 5")
        let bodyweightReps = IOSWorkoutRecordLine(type: "MAX_REPS", value: 12, previous: 10, set: ILWorkoutSet(id: 2, reps: 12, weightKg: 0))
        XCTAssertNil(iosRecordContext(bodyweightReps, unitSystem: "METRIC"))
        let volume = IOSWorkoutRecordLine(type: "MAX_VOLUME", value: 4320, previous: 4010, set: nil)
        XCTAssertNil(iosRecordContext(volume, unitSystem: "METRIC"))
        let weightedReps = IOSWorkoutRecordLine(type: "MAX_REPS", value: 12, previous: 10, set: ILWorkoutSet(id: 3, reps: 12, weightKg: 60))
        XCTAssertEqual(
            iosRecordContext(weightedReps, unitSystem: "METRIC", locale: german),
            String(localized: "bei \(iosRecordWeightText(60, unitSystem: "METRIC", locale: german))")
        )
    }

    func testRepsRecordsShowTheBareNumberOnTheBoard() {
        // The "WDH." label names the unit, so value and gain carry none.
        XCTAssertEqual(iosRecordValueText(type: "MAX_REPS", value: 12, unitSystem: "METRIC", locale: german), "12")
        XCTAssertEqual(iosRecordDeltaText(type: "MAX_REPS", delta: 2, unitSystem: "METRIC", locale: german), "+2")
        XCTAssertEqual(iosRecordDeltaText(type: "MAX_REPS", delta: 2, unitSystem: "IMPERIAL", locale: english), "+2")
        XCTAssertEqual(iosRecordDeltaText(type: "MAX_REPS", delta: 2, unitSystem: "METRIC", signed: false, locale: german), "2")
        XCTAssertEqual(iosRecordLabel("MAX_REPS"), String(localized: "Wdh."))
    }

    func testRepsNoticeNamesTheUnitInsteadOfALabel() throws {
        let line = IOSWorkoutRecordLine(type: "MAX_REPS", value: 12, previous: 10, set: ILWorkoutSet(id: 3, reps: 12, weightKg: 60))

        let notice = try XCTUnwrap(IOSWorkoutRecords.notice(sessionID: 2, exerciseName: "Klimmzüge", lines: [line], unitSystem: "METRIC"))

        XCTAssertEqual(notice.title, String(localized: "\(1) neue Rekorde · \("Klimmzüge")"))
        XCTAssertEqual(notice.detail, "\(String(localized: "\(12) Wdh.")) (+2)")
    }

    func testRepsRowReadsInFullWordsWithoutALabel() {
        let weighted = IOSWorkoutRecordLine(type: "MAX_REPS", value: 12, previous: 10, set: ILWorkoutSet(id: 3, reps: 12, weightKg: 60))
        let repsAtWeight = String(localized: "\(12) Wiederholungen") + " " + String(localized: "bei \(iosRecordWeightText(60, unitSystem: "METRIC"))")
        XCTAssertEqual(
            iosRecordAccessibilityText(weighted, unitSystem: "METRIC"),
            String(localized: "\(repsAtWeight), \(2) mehr als bisher")
        )

        let bodyweight = IOSWorkoutRecordLine(type: "MAX_REPS", value: 1, previous: 0, set: ILWorkoutSet(id: 4, reps: 1, weightKg: 0))
        XCTAssertEqual(
            iosRecordAccessibilityText(bodyweight, unitSystem: "METRIC"),
            String(localized: "\(String(localized: "\(1) Wiederholungen")), \(1) mehr als bisher")
        )

        let firstTime = IOSWorkoutRecordLine(type: "MAX_REPS", value: 12, previous: nil, set: ILWorkoutSet(id: 5, reps: 12, weightKg: 0))
        XCTAssertEqual(
            iosRecordAccessibilityText(firstTime, unitSystem: "METRIC"),
            String(localized: "\(String(localized: "\(12) Wiederholungen")), zum ersten Mal")
        )

        // Other types keep their label and the gain with its unit.
        let weight = IOSWorkoutRecordLine(type: "MAX_WEIGHT", value: 100, previous: 97.5, set: ILWorkoutSet(id: 1, reps: 5, weightKg: 100))
        let weightValue = iosRecordWeightText(100, unitSystem: "METRIC") + " × 5"
        XCTAssertEqual(
            iosRecordAccessibilityText(weight, unitSystem: "METRIC"),
            String(localized: "\(iosRecordLabel("MAX_WEIGHT")) \(weightValue), \(iosRecordWeightText(2.5, unitSystem: "METRIC")) mehr als bisher")
        )
    }

    func testNoticeCountsItsTimeFromWhenItAppeared() {
        let shownAt = Date(timeIntervalSinceReferenceDate: 1_000)
        let line = IOSWorkoutRecordLine(type: "MAX_WEIGHT", value: 85, previous: 80, set: nil)
        guard let notice = IOSWorkoutRecords.notice(sessionID: 2, exerciseName: "Bank eng", lines: [line], unitSystem: "METRIC", shownAt: shownAt) else {
            return XCTFail("A beaten best shows a notice")
        }

        XCTAssertEqual(notice.shownAt, shownAt)
        XCTAssertEqual(notice.remainingDuration(at: shownAt), IOSWorkoutRecordNotice.duration)
        // The pause cover opened 1.5 s later: only the rest of the time is left.
        XCTAssertEqual(notice.remainingDuration(at: shownAt.addingTimeInterval(1.5)), IOSWorkoutRecordNotice.duration - 1.5, accuracy: 1e-9)
        XCTAssertEqual(notice.remainingDuration(at: shownAt.addingTimeInterval(60)), 0)
    }

    // MARK: - Helpers

    /// Session 1 is an earlier, finished workout with bench press only: a warm-up of 90 kg and
    /// 80 × 5. Session 2 is today's workout with `current`.
    private func trainingData(current: [ILWorkoutSet]) -> ILTrainingData {
        ILTrainingData(
            exercises: [bench, rowing],
            workoutSessions: [ILWorkoutSession(id: 1, startTime: 0, endTime: 100), ILWorkoutSession(id: 2, startTime: 200, endTime: 300)],
            workoutSets: [
                ILWorkoutSet(id: 1, sessionId: 1, exerciseId: 1, setNumber: 1, reps: 3, weightKg: 90, setType: "WARMUP", completedAt: 10),
                ILWorkoutSet(id: 2, sessionId: 1, exerciseId: 1, setNumber: 2, reps: 5, weightKg: 80, completedAt: 20)
            ] + current
        )
    }

    private func appending(_ set: ILWorkoutSet, to data: ILTrainingData) -> ILTrainingData {
        var copy = data
        copy.workoutSets.append(set)
        return copy
    }

    private func live(exerciseID: Int64, before: ILTrainingData, after: ILTrainingData) -> [IOSWorkoutRecordLine] {
        IOSWorkoutRecords.liveImprovements(
            sessionID: 2,
            before: IOSWorkoutRecords.recordInputs(exerciseID: exerciseID, data: before),
            after: IOSWorkoutRecords.recordInputs(exerciseID: exerciseID, data: after),
            data: after
        )
    }

    private func row(_ exercise: ILExercise, sets: [ILWorkoutSet]) -> IOSWorkoutExerciseRow {
        IOSWorkoutExerciseRow(id: "adhoc-\(exercise.id)", exercise: exercise, target: nil, displayTarget: nil,
            sets: sets, previousWorkWeightKg: nil, nextSetRecommendation: nil)
    }
}
