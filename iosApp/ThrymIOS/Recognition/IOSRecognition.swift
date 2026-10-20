import Foundation
import Shared

/// Swift side of the shared recognition rules (`com.thrym.shared.recognition` in `:shared`):
/// weekly goal and streak, workout number, jubilee, comeback and round marks. Android and iOS
/// derive them from the same history with the same rules; this file only converts dates and
/// formats text.
enum IOSRecognition {
    // MARK: - Calendar

    private static let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        return calendar
    }()

    /// Days since 1970-01-01 of the local calendar day that holds `date`.
    static func epochDay(_ date: Date, calendar: Calendar = .current) -> Int64 {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        guard let midnight = utc.date(from: DateComponents(year: parts.year, month: parts.month, day: parts.day)) else {
            return Int64((date.timeIntervalSince1970 / 86_400).rounded(.down))
        }
        return Int64((midnight.timeIntervalSince1970 / 86_400).rounded())
    }

    /// The local date of an epoch day, at its start.
    static func date(epochDay: Int64, calendar: Calendar = .current) -> Date {
        let midnight = Date(timeIntervalSince1970: TimeInterval(epochDay) * 86_400)
        let parts = utc.dateComponents([.year, .month, .day], from: midnight)
        return calendar.date(from: parts) ?? midnight
    }

    static func weekStart(_ setting: String) -> WeekStart {
        setting.uppercased() == "SUNDAY" ? .sunday : .monday
    }

    static func unitSystem(_ setting: String) -> UnitSystem {
        setting.uppercased() == "IMPERIAL" ? .imperial : .metric
    }

    /// First day of the running week as an epoch day.
    static func currentWeekStartEpochDay(weekStart setting: String, now: Date = Date()) -> Int64 {
        WeeklyGoalRules.shared.weekStartOf(epochDay: epochDay(now), weekStart: weekStart(setting))
    }

    // MARK: - Weekly goal

    /// The goal that applies to the running week.
    static func currentGoal(history: String, weekStart: String, now: Date = Date()) -> Int {
        Int(WeeklyGoalRules.shared.goalFor(
            history: WeeklyGoalRules.shared.decode(text: history),
            weekStartEpochDay: currentWeekStartEpochDay(weekStart: weekStart, now: now)
        ))
    }

    /// Weekly goal and streak today; nil when the goal is switched off.
    static func weeklyGoal(
        sessions: [ILWorkoutSession],
        settings: SettingsFormState,
        now: Date = Date()
    ) -> WeeklyGoalStatus? {
        guard settings.weeklyGoalEnabled else { return nil }
        return WeeklyGoalEngine.shared.evaluate(
            todayEpochDay: epochDay(now),
            weekStart: weekStart(settings.weekStart),
            sessions: sessions.filter { $0.endTime != nil }.map { session in
                WeeklyGoalSession(epochDay: epochDay(session.startDate), isDeload: session.isDeload.map { KotlinBoolean(bool: $0) })
            },
            history: WeeklyGoalRules.shared.decode(text: settings.weeklyGoalHistory)
        )
    }

    /// Days since the last completed workout when they make a pause (21 or more), else nil.
    static func pauseDays(sessions: [ILWorkoutSession], now: Date = Date()) -> Int? {
        let last = sessions
            .filter { $0.endTime != nil && $0.startDate <= now }
            .max { $0.startTime < $1.startTime }
        let days = TrainingMilestones.shared.pauseDays(
            lastEpochDay: last.map { KotlinLong(longLong: epochDay($0.startDate)) },
            todayEpochDay: epochDay(now)
        )
        return days.map { Int($0.int32Value) }
    }

    /// Workout number, weekly goal, jubilee and comeback of the finished `sessionID`.
    static func completionFacts(sessionID: Int64, sessions: [ILWorkoutSession], settings: SettingsFormState) -> CompletionFacts? {
        let milestones = sessions.filter { $0.endTime != nil }.map { session in
            MilestoneSession(
                id: session.id,
                epochDay: epochDay(session.startDate),
                orderKey: session.startTime,
                isDeload: session.isDeload.map { KotlinBoolean(bool: $0) }
            )
        }
        return TrainingMilestones.shared.completionFacts(
            sessionId: sessionID,
            sessions: milestones,
            weekStart: weekStart(settings.weekStart),
            goalHistory: settings.weeklyGoalEnabled ? WeeklyGoalRules.shared.decode(text: settings.weeklyGoalHistory) : nil
        )
    }

    // MARK: - Marks

    /// Marks the workout `sessionID` reached for the first time, per exercise id.
    static func sessionMarks(
        sessionID: Int64,
        exercises: [ILExercise],
        data: ILTrainingData,
        unitSystem: String
    ) -> [Int64: [StrengthMark]] {
        var result: [Int64: [StrengthMark]] = [:]
        for exercise in exercises {
            let marks = StrengthMarks.shared.sessionMarks(
                sessionId: sessionID,
                sets: IOSWorkoutRecords.recordInputs(exerciseID: exercise.id, data: data),
                category: exercise.category,
                unitSystem: self.unitSystem(unitSystem)
            )
            if !marks.isEmpty { result[exercise.id] = marks }
        }
        return result
    }

    /// The mark a save just reached, for the title of the live notice.
    static func liveMark(
        sessionID: Int64,
        before: [RecordSetInput],
        after: [RecordSetInput],
        category: String,
        unitSystem: String
    ) -> StrengthMark? {
        StrengthMarks.shared.liveMark(
            sessionId: sessionID,
            setsBefore: before,
            setsAfter: after,
            category: category,
            unitSystem: self.unitSystem(unitSystem)
        )
    }

    /// Mark ladders of an exercise over its finished workouts, warm-ups left out.
    static func markLadders(exercise: ILExercise, data: ILTrainingData, unitSystem: String) -> [StrengthMarkLadder] {
        let finished = Set(data.workoutSessions.filter { $0.endTime != nil }.map(\.id))
        let sets = IOSWorkoutRecords.recordInputs(exerciseID: exercise.id, data: data)
            .filter { finished.contains($0.sessionId) }
        return StrengthMarks.shared.history(sets: sets, category: exercise.category, unitSystem: self.unitSystem(unitSystem))
    }

    // MARK: - Text

    private static func unitLabel(_ mark: StrengthMark) -> String {
        mark.imperial ? String(localized: "lb") : String(localized: "kg")
    }

    /// "100-kg-Marke", "+20-kg-Marke", "20-Wdh.-Marke".
    static func markTitle(_ mark: StrengthMark) -> String {
        let value = Int(mark.value)
        switch mark.kind {
        case .addedLoad: return String(localized: "+\(value)-\(unitLabel(mark))-Marke")
        case .reps: return String(localized: "\(value)-Wdh.-Marke")
        default: return String(localized: "\(value)-\(unitLabel(mark))-Marke")
        }
    }

    /// "100 kg", "+20 kg", "20 Wdh." on the record board.
    static func markValue(_ mark: StrengthMark) -> String {
        let value = Int(mark.value)
        switch mark.kind {
        case .addedLoad: return "+\(value) \(unitLabel(mark))"
        case .reps: return iosRecordRepsText(Double(value))
        default: return "\(value) \(unitLabel(mark))"
        }
    }

    /// Unit of a ladder for the statistics header: "kg", "+kg", "Wdh.".
    static func ladderUnit(_ kind: StrengthMarkKind, unitSystem: String) -> String {
        let unit = self.unitSystem(unitSystem) == .imperial ? String(localized: "lb") : String(localized: "kg")
        switch kind {
        case .addedLoad: return "+\(unit)"
        case .reps: return String(localized: "Wdh.")
        default: return unit
        }
    }

    /// "7 Wochen in Folge", with "· 1 Woche Puffer" when buffer weeks are in stock.
    static func streakText(_ status: WeeklyGoalStatus) -> String {
        var parts = [String(localized: "\(Int(status.streakWeeks)) Wochen in Folge")]
        if status.buffers > 0 { parts.append(String(localized: "\(Int(status.buffers)) Wochen Puffer")) }
        return parts.joined(separator: " · ")
    }

    /// The streak from two weeks on, else "Wochenziel erreicht" once met, else nothing.
    static func weekFooterText(_ status: WeeklyGoalStatus) -> String? {
        if status.streakWeeks >= 2 { return streakText(status) }
        if status.goalMetThisWeek { return String(localized: "Wochenziel erreicht") }
        return nil
    }

    /// "2 von 3".
    static func weekProgressText(_ status: WeeklyGoalStatus) -> String {
        String(localized: "\(Int(status.completedThisWeek)) von \(Int(status.goal))")
    }

    /// "2 von 3 Trainings diese Woche, 7 Wochen in Folge" for VoiceOver.
    static func weekProgressAccessibility(_ status: WeeklyGoalStatus) -> String {
        [String(localized: "\(Int(status.completedThisWeek)) von \(Int(status.goal)) Trainings diese Woche"), weekFooterText(status)]
            .compactMap { $0 }
            .joined(separator: ", ")
    }

    struct FactText: Identifiable, Equatable {
        let id: String
        let symbol: String
        let title: String
        let detail: String
    }

    /// The facts below the summary as text, at most two.
    static func factTexts(_ facts: CompletionFacts) -> [FactText] {
        facts.rows.compactMap { kind -> FactText? in
            switch kind {
            case .weeklyGoal:
                guard let goal = facts.weeklyGoal else { return nil }
                let detail = [weekProgressText(goal), goal.streakWeeks >= 2 ? streakText(goal) : nil]
                    .compactMap { $0 }
                    .joined(separator: " · ")
                return FactText(id: "goal", symbol: "calendar.badge.checkmark", title: String(localized: "Wochenziel erreicht"), detail: detail)
            case .jubilee:
                let since = date(epochDay: facts.firstTrainingEpochDay).formatted(date: .long, time: .omitted)
                return FactText(
                    id: "jubilee",
                    symbol: "flag",
                    title: String(localized: "Dein \(Int(facts.trainingNumber)). Training"),
                    detail: String(localized: "Seit dem \(since)")
                )
            case .comeback:
                guard let days = facts.pauseDays?.intValue else { return nil }
                return FactText(
                    id: "comeback",
                    symbol: "arrow.uturn.backward",
                    title: String(localized: "Wieder dabei"),
                    detail: String(localized: "Nach \(days) Tagen Pause")
                )
            default:
                return nil
            }
        }
    }
}
