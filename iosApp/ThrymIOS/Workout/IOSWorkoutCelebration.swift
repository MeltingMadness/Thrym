import Shared
import SwiftUI

/// A highlight of the celebration as text. `warm` highlights (records, marks) use the warning
/// tone, facts the accent.
struct IOSWorkoutHighlightText: Identifiable, Equatable {
    let id: String
    let symbol: String
    let title: String
    let detail: String
    let warm: Bool
}

extension IOSWorkoutRecords {
    /// The highlights of a finished workout for the celebration (`CompletionHighlights` in
    /// `:shared`): at most three wins, and how many more the summary holds.
    static func highlights(
        summary: IOSWorkoutRecordSummary,
        marks: [Int64: [StrengthMark]],
        facts: CompletionFacts?
    ) -> CompletionHighlightSelection {
        CompletionHighlights.shared.select(
            summary: sharedSummary(summary),
            marksByExercise: Dictionary(uniqueKeysWithValues: marks.map { (KotlinLong(longLong: $0.key), $0.value) }),
            facts: facts,
            limit: CompletionHighlights.shared.LIMIT
        )
    }

    /// One highlight as text: "Neuer Rekord" with "Bankdrücken · Gewicht 102,5 kg (+2,5 kg)",
    /// a mark with the exercise, or a fact of the workout. Nil when its data is missing.
    static func highlightText(
        _ highlight: CompletionHighlight,
        summary: IOSWorkoutRecordSummary,
        facts: CompletionFacts?,
        unitSystem: String
    ) -> IOSWorkoutHighlightText? {
        let exercise = highlight.exerciseId.flatMap { id in summary.exercises.first { $0.exerciseID == id.int64Value } }
        switch highlight.kind {
        case .record:
            guard let record = highlight.record,
                  let exercise,
                  let line = exercise.lines.first(where: { $0.type == record.type }) else { return nil }
            return IOSWorkoutHighlightText(
                id: "record-\(exercise.exerciseID)",
                symbol: "trophy",
                title: String(localized: "Neuer Rekord"),
                detail: "\(exercise.name) · \(detailText(line, unitSystem: unitSystem))",
                warm: true
            )
        case .mark:
            guard let mark = highlight.mark, let id = highlight.exerciseId?.int64Value else { return nil }
            return IOSWorkoutHighlightText(
                id: "mark-\(id)",
                symbol: "medal",
                title: IOSRecognition.markTitle(mark),
                detail: exercise?.name ?? String(localized: "Übung"),
                warm: true
            )
        case .jubilee: return factHighlight(facts, kind: .jubilee)
        case .comeback: return factHighlight(facts, kind: .comeback)
        case .weeklyGoal: return factHighlight(facts, kind: .weeklyGoal)
        default: return nil
        }
    }

    private static func factHighlight(_ facts: CompletionFacts?, kind: CompletionFactKind) -> IOSWorkoutHighlightText? {
        guard let facts, let text = IOSRecognition.factText(facts, kind: kind) else { return nil }
        return IOSWorkoutHighlightText(id: text.id, symbol: text.symbol, title: text.title, detail: text.detail, warm: false)
    }
}

/// The celebration after "Beenden", before the known summary: "Geschafft!" with the key numbers,
/// then at most three highlights. A tap on the content or "Weiter" goes on, "Überspringen" goes
/// straight to the summary. With reduced motion the steps change without animation. The caller
/// shows it once per workout and only with highlights.
struct IOSWorkoutCelebration: View {
    let session: ILWorkoutSession
    let sets: [ILWorkoutSet]
    let facts: CompletionFacts?
    let highlights: [IOSWorkoutHighlightText]
    let more: Int
    let unitSystem: String
    let onFinish: () -> Void

    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var step = 0
    @State private var revealed = 0

    private static let steps = 2
    private var reducedMotion: Bool { reduceMotion || theme.reducedMotion }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                stepIndicator
                Spacer()
                Button("Überspringen", action: onFinish)
                    .font(.geist(.body, weight: .semibold))
            }
            ZStack {
                if step == 0 {
                    doneStep.transition(stepTransition)
                } else {
                    highlightsStep.transition(stepTransition)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .contentShape(Rectangle())
            .onTapGesture(perform: advance)
            Button(action: advance) {
                Text(step < Self.steps - 1 ? String(localized: "Weiter") : String(localized: "Zur Zusammenfassung"))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .thrymButtonText()
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.palette(for: colorScheme).background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .sensoryFeedback(.success, trigger: step) { _, new in new == Self.steps - 1 }
    }

    private var stepTransition: AnyTransition {
        reducedMotion ? .identity : .opacity.combined(with: .offset(y: 24))
    }

    private func advance() {
        guard step < Self.steps - 1 else {
            onFinish()
            return
        }
        withAnimation(reducedMotion ? nil : .easeOut(duration: 0.26)) { step += 1 }
        revealHighlights()
    }

    /// The highlights come in one after another; with reduced motion they are there at once.
    private func revealHighlights() {
        guard !reducedMotion else {
            revealed = highlights.count
            return
        }
        for index in highlights.indices {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.14 * Double(index) + 0.1) {
                withAnimation(.easeOut(duration: 0.28)) { revealed = max(revealed, index + 1) }
            }
        }
    }

    private var stepIndicator: some View {
        HStack(spacing: 6) {
            ForEach(0..<Self.steps, id: \.self) { index in
                Capsule()
                    .fill(Color.primary.opacity(index <= step ? 1 : 0.2))
                    .frame(width: 24, height: 5)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Schritt \(step + 1) von \(Self.steps)"))
    }

    private var doneStep: some View {
        let volume = sets.reduce(0) { $0 + $1.weightKg * Double($1.reps) }
        let subtitle = [
            session.name.isEmpty ? nil : session.name,
            facts.map { String(localized: "Training \(Int($0.trainingNumber))") }
        ].compactMap { $0 }.joined(separator: " · ")
        return ScrollView {
            VStack(spacing: 20) {
                Text("Geschafft!")
                    .font(.geist(size: 44, weight: .heavy))
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.geist(.title3))
                        .thrymSecondaryText()
                        .multilineTextAlignment(.center)
                }
                HStack(spacing: 12) {
                    metric(
                        String(localized: "Dauer"),
                        session.durationSeconds < 60 ? String(localized: "< 1 min") : String(localized: "\(session.durationSeconds / 60) min")
                    )
                    metric(String(localized: "Sätze"), "\(sets.count)")
                }
                metric(String(localized: "Volumen"), iosWorkoutDisplayWeight(kilograms: volume, unitSystem: unitSystem))
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 48)
        }
    }

    private var highlightsStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Höhepunkte")
                    .font(.geist(size: 36, weight: .heavy))
                    .accessibilityAddTraits(.isHeader)
                ForEach(Array(highlights.enumerated()), id: \.element.id) { index, highlight in
                    highlightCard(highlight)
                        .opacity(index < revealed ? 1 : 0)
                        .offset(y: index < revealed ? 0 : 16)
                }
                if more > 0 {
                    Text("\(more) weitere in der Zusammenfassung")
                        .font(.geist(.subheadline))
                        .thrymSecondaryText()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 48)
        }
    }

    private func metric(_ title: String, _ value: String) -> some View {
        ThrymCard(tone: .elevated) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.geist(.caption)).thrymSecondaryText()
                Text(value).font(.geist(.title2, weight: .bold).monospacedDigit())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }

    private func highlightCard(_ highlight: IOSWorkoutHighlightText) -> some View {
        let palette = theme.palette(for: colorScheme)
        let ink = ThrymSemanticInk.forDark(colorScheme == .dark)
        let tint = highlight.warm ? ink.warning : palette.primary
        let fill = highlight.warm ? ink.warningFill.opacity(0.22) : palette.primary.opacity(0.16)
        return ThrymCard(tone: .elevated) {
            HStack(spacing: 14) {
                Image(systemName: highlight.symbol)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(tint)
                    .frame(width: 48, height: 48)
                    .background(fill, in: Circle())
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(highlight.title).font(.geist(.headline, weight: .bold))
                    Text(highlight.detail)
                        .font(.geist(.subheadline))
                        .monospacedDigit()
                        .thrymSecondaryText()
                }
                Spacer(minLength: 0)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
