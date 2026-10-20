import Shared
import SwiftUI

/// One day of the current week for the Liquid Glass week strip.
struct IOSDashboardWeekDay: Identifiable, Equatable {
    let date: Date
    let trained: Bool
    let isToday: Bool

    var id: Date { date }

    /// Seven days from `weekStart` ("yyyy-MM-dd"); a day counts as trained when a
    /// completed workout started on it, the same rule as the week counter.
    static func week(startingAt weekStart: String, sessions: [ILWorkoutSession], now: Date = Date()) -> [IOSDashboardWeekDay] {
        let calendar = Calendar.current
        let parser = DateFormatter()
        parser.calendar = calendar
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd"
        guard let start = parser.date(from: weekStart) else { return [] }
        let trainedDays = Set(
            sessions
                .filter { $0.endTime != nil && $0.startDate <= now }
                .map { calendar.startOfDay(for: $0.startDate) }
        )
        return (0..<7).compactMap { offset in
            guard let day = calendar.date(byAdding: .day, value: offset, to: calendar.startOfDay(for: start)) else {
                return nil
            }
            return IOSDashboardWeekDay(
                date: day,
                trained: trainedDays.contains(day),
                isToday: calendar.isDate(day, inSameDayAs: now)
            )
        }
    }
}

/// Date, greeting and a round glass statistics button; replaces the large
/// navigation title in Liquid Glass.
struct IOSDashboardGlassHeader: View {
    let greeting: String
    let onOpenStatistics: () -> Void

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Date().formatted(.dateTime.weekday(.wide).day().month(.wide)).uppercased())
                    .font(.geist(.caption, weight: .bold))
                    .tracking(0.8)
                    .thrymSecondaryText()
                Text(greeting)
                    .font(.geist(.largeTitle, weight: .heavy))
            }
            .accessibilityElement(children: .combine)
            Spacer()
            Button(action: onOpenStatistics) {
                Image(systemName: "chart.xyaxis.line")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(width: 48, height: 48)
                    .liquidGlass(in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Statistiken")
            .accessibilityHint("Öffnet die vollständigen Trainingsstatistiken")
        }
        .padding(.top, 8)
    }
}

/// Hero card in Liquid Glass: next plan with its first three exercises and
/// targets, and the start button as a pill with a colored play circle. Actions
/// and plan choice are the same as in `IOSDashboardCommandCenter`.
struct IOSDashboardGlassCommandCenter: View {
    let activeSession: ILWorkoutSession?
    let suggestion: ILMetaRotationSuggestion?
    let fallbackPlan: ILTrainingPlan?
    let data: ILTrainingData?
    let unitSystem: String
    let isBusy: Bool
    let onResume: () -> Void
    let onStartFree: () -> Void
    let onStartSuggestion: (ILMetaRotationSuggestion) -> Void
    let onRequestSkip: (ILMetaRotationSuggestion) -> Void
    let onStartPlan: (ILTrainingPlan) -> Void
    let onChooseTraining: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme

    private var planId: Int64? {
        suggestion?.nextTrainingPlanId ?? fallbackPlan?.id
    }

    private var planName: String? {
        suggestion?.nextTrainingPlanName ?? fallbackPlan?.name
    }

    private var exercises: [ILPlanExercise] {
        guard let planId, let data else { return [] }
        return data.planExercises
            .filter { $0.planId == planId }
            .sorted { $0.orderIndex < $1.orderIndex }
    }

    var body: some View {
        let accent = theme.palette(for: colorScheme).primary
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Circle()
                    .fill(accent)
                    .frame(width: 8, height: 8)
                    .shadow(color: accent, radius: 5)
                Text(tag.uppercased())
                    .font(.geist(.caption, weight: .bold))
                    .tracking(0.8)
                    .lineLimit(1)
                    .opacity(0.9)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.geist(size: 44, weight: .bold))
                    .tracking(-1.5)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                Text(subtitle)
                    .font(.geist(.subheadline))
                    .thrymSecondaryText()
            }

            if activeSession == nil, !exercises.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(exercises.prefix(3)) { exercise in
                        Divider().overlay(Color.primary.opacity(0.10))
                        HStack(alignment: .firstTextBaseline) {
                            Text(exerciseName(exercise))
                                .font(.geist(.body, weight: .bold))
                                .lineLimit(1)
                            Spacer(minLength: 12)
                            Text(target(exercise))
                                .font(.geist(.subheadline))
                                .monospacedDigit()
                                .thrymSecondaryText()
                        }
                        .padding(.vertical, 9)
                        .accessibilityElement(children: .combine)
                    }
                    if exercises.count > 3 {
                        let more = exercises.count - 3
                        Text("+ \(more) weitere Übungen")
                            .font(.geist(.footnote, weight: .bold))
                            .thrymSecondaryText()
                            .padding(.top, 8)
                    }
                }
            }

            Button(action: primaryAction) {
                HStack(spacing: 12) {
                    Image(systemName: "play.fill")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(accent, in: Circle())
                    Text(primaryTitle)
                        .font(.geist(size: 17, weight: .heavy))
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(8)
                .frame(maxWidth: .infinity, minHeight: 60)
                .foregroundStyle(colorScheme == .dark ? Color(red: 11 / 255, green: 13 / 255, blue: 18 / 255) : .white)
                .background(colorScheme == .dark ? Color.white : Color(red: 11 / 255, green: 13 / 255, blue: 18 / 255), in: Capsule())
                .shadow(color: .black.opacity(0.35), radius: 14, y: 8)
            }
            .buttonStyle(.plain)
            .disabled(isBusy)

            if activeSession == nil {
                HStack(spacing: 18) {
                    Button("Andere Pläne", action: onChooseTraining)
                    if let suggestion, suggestion.canSkip {
                        Button("Teilplan überspringen") { onRequestSkip(suggestion) }
                    }
                    if suggestion != nil || fallbackPlan != nil {
                        Button("Freies Training", action: onStartFree)
                    }
                }
                .font(.geist(.subheadline, weight: .bold))
                .thrymSecondaryText()
                .buttonStyle(.plain)
                .disabled(isBusy)
                .frame(maxWidth: .infinity)
            }
        }
        .padding(EdgeInsets(top: 22, leading: 22, bottom: 18, trailing: 22))
        .liquidGlass(.strong, in: RoundedRectangle(cornerRadius: 34, style: .continuous))
    }

    private var tag: String {
        if activeSession != nil { return String(localized: "Training läuft") }
        if let suggestion { return String(localized: "Heute dran · \(suggestion.metaPlanName)") }
        return String(localized: "Heute dran")
    }

    private var title: String {
        if let activeSession { return activeSession.name.isEmpty ? String(localized: "Training läuft") : activeSession.name }
        return planName ?? String(localized: "Freies Training")
    }

    private var subtitle: String {
        if activeSession != nil { return String(localized: "Ein begonnenes Training wartet auf dich.") }
        if planName != nil {
            return [ilExerciseCountText(exercises.count), lastDoneText].joined(separator: " · ")
        }
        return String(localized: "Starte eine freie Einheit und halte sie sauber fest.")
    }

    /// "zuletzt vor 4 Tagen", from the latest completed session of this plan.
    private var lastDoneText: String {
        let last = data?.workoutSessions
            .filter { $0.endTime != nil && $0.planId == planId }
            .map(\.startDate)
            .max()
        guard let last else { return String(localized: "noch nie trainiert") }
        let calendar = Calendar.current
        let days = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: last),
            to: calendar.startOfDay(for: Date())
        ).day ?? 0
        switch days {
        case ..<1: return String(localized: "zuletzt heute")
        case 1: return String(localized: "zuletzt gestern")
        default: return String(localized: "zuletzt vor \(days) Tagen")
        }
    }

    private var primaryTitle: String {
        activeSession != nil ? String(localized: "Training fortsetzen") : String(localized: "Training starten")
    }

    private func primaryAction() {
        if activeSession != nil {
            onResume()
        } else if let suggestion {
            onStartSuggestion(suggestion)
        } else if let fallbackPlan {
            onStartPlan(fallbackPlan)
        } else {
            onStartFree()
        }
    }

    private func exerciseName(_ exercise: ILPlanExercise) -> String {
        data?.exercises.first { $0.id == exercise.exerciseId }?.displayName ?? String(localized: "Übung")
    }

    /// "3 × 8 · 82,5 kg"; without a target weight only sets and reps.
    private func target(_ exercise: ILPlanExercise) -> String {
        let volume = "\(exercise.targetSets) × \(exercise.targetReps)"
        guard exercise.targetWeightKg > 0 else { return volume }
        return "\(volume) · \(ilWeightText(exercise.targetWeightKg, unitSystem: unitSystem))"
    }
}

/// Week strip: one lens per day (trained, today, open) plus workouts and volume
/// of the week. Replaces the week/month tile in Liquid Glass.
struct IOSDashboardGlassWeekStrip: View {
    let days: [IOSDashboardWeekDay]
    let workoutsThisWeek: Int
    let volumeKg: Double
    let unitSystem: String
    /// With a weekly goal the header reads "2 von 3 · 4.320 kg" and the streak sits below the days.
    var weeklyGoal: WeeklyGoalStatus? = nil

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme

    var body: some View {
        let dark = colorScheme == .dark
        let ink = dark ? Color.white : Color(red: 11 / 255, green: 13 / 255, blue: 18 / 255)
        let accent = theme.palette(for: colorScheme).primary
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text("DIESE WOCHE")
                    .font(.geist(.caption, weight: .bold))
                    .tracking(0.8)
                    .thrymSecondaryText()
                Spacer()
                Text("\(weeklyGoal.map(IOSRecognition.weekProgressText) ?? ilWorkoutCountText(workoutsThisWeek)) · \(ilVolumeText(volumeKg.rounded(), unitSystem: unitSystem))")
                    .font(.geist(.subheadline, weight: .bold))
                    .monospacedDigit()
                    .accessibilityLabel(weeklyGoal.map {
                        "\(IOSRecognition.weekProgressAccessibility($0)), \(ilVolumeText(volumeKg.rounded(), unitSystem: unitSystem))"
                    } ?? "\(ilWorkoutCountText(workoutsThisWeek)), \(ilVolumeText(volumeKg.rounded(), unitSystem: unitSystem))")
            }
            HStack(spacing: 0) {
                ForEach(days) { day in
                    VStack(spacing: 6) {
                        Text(day.date.formatted(.dateTime.weekday(.abbreviated)).replacingOccurrences(of: ".", with: ""))
                            .font(.geist(size: 12, weight: .bold))
                            .thrymSecondaryText()
                        ZStack {
                            if day.trained {
                                Circle().fill(ink)
                                Image(systemName: "checkmark")
                                    .font(.system(size: 13, weight: .heavy))
                                    .foregroundStyle(dark ? Color(red: 11 / 255, green: 13 / 255, blue: 18 / 255) : .white)
                            } else if day.isToday {
                                Circle()
                                    .strokeBorder(accent, lineWidth: 2)
                                    .shadow(color: accent.opacity(0.6), radius: 8)
                            } else {
                                Circle().fill(ink.opacity(dark ? 0.08 : 0.06))
                            }
                        }
                        .frame(width: 34, height: 34)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(accessibilityText(day))
                }
            }
            if let footer = weeklyGoal.flatMap(IOSRecognition.weekFooterText) {
                IOSWeekGoalFooter(text: footer)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .liquidGlass(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    private func accessibilityText(_ day: IOSDashboardWeekDay) -> String {
        let name = day.date.formatted(.dateTime.weekday(.wide))
        if day.trained { return String(localized: "\(name), trainiert") }
        if day.isToday { return String(localized: "\(name), heute") }
        return String(localized: "\(name), offen")
    }
}
