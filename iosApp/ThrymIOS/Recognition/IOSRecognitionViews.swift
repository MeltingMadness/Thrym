import Shared
import SwiftUI

/// "7 Wochen in Folge · 1 Woche Puffer" under the week strip.
struct IOSWeekGoalFooter: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "calendar.badge.checkmark")
            .font(.geist(.footnote, weight: .semibold))
            .thrymSecondaryText()
    }
}

/// At most two facts under the summary: "Wochenziel erreicht / 3 von 3 · 8 Wochen in Folge",
/// "Dein 100. Training / Seit dem 14. März 2025" or "Wieder dabei / Nach 26 Tagen Pause".
/// Plain rows without animation; each row reads as one sentence. Shows nothing without facts.
struct IOSCompletionFactsBlock: View {
    let facts: CompletionFacts

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme

    var body: some View {
        let texts = IOSRecognition.factTexts(facts)
        if !texts.isEmpty {
            ThrymCard(tone: .elevated) {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(texts.enumerated()), id: \.element.id) { index, text in
                        if index > 0 { Divider() }
                        HStack(spacing: 12) {
                            Image(systemName: text.symbol)
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(theme.palette(for: colorScheme).primary)
                                .frame(width: 26)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(text.title)
                                    .font(.geist(.subheadline, weight: .bold))
                                Text(text.detail)
                                    .font(.geist(.subheadline))
                                    .monospacedDigit()
                                    .thrymSecondaryText()
                            }
                            Spacer(minLength: 0)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
        }
    }
}

/// After a pause of three weeks or more: offers the deload mode for an easier start.
struct IOSComebackCard: View {
    let pauseDays: Int
    let onEaseBackIn: () -> Void

    var body: some View {
        ThrymCard(tone: .elevated) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Nach \(pauseDays) Tagen Pause")
                    .font(.geist(.headline, weight: .bold))
                    .accessibilityAddTraits(.isHeader)
                Text("Nach einer Pause macht der Deload-Modus deine Trainings eine Weile leichter. Du kannst ihn jederzeit beenden.")
                    .font(.geist(.subheadline))
                    .thrymSecondaryText()
                    .fixedSize(horizontal: false, vertical: true)
                Button("Leichter einsteigen", action: onEaseBackIn)
                    .buttonStyle(.bordered)
                    .padding(.top, 4)
            }
        }
    }
}

/// One ladder of round marks: "MARKEN · KG", the last reached marks with month and year, then the
/// next one dimmed. The next mark appears only here, never during a workout, so nobody chases a
/// round number with a single attempt.
struct IOSExerciseMarksCard: View {
    let ladder: StrengthMarkLadder
    let unitSystem: String

    /// How many reached marks the row shows; older ones stay in the history.
    private let visibleMarks = 4

    var body: some View {
        let shown = Array(ladder.reached.suffix(visibleMarks))
        ThrymCard(tone: .muted) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text(String(localized: "Marken · \(IOSRecognition.ladderUnit(ladder.kind, unitSystem: unitSystem))").uppercased())
                        .font(.geist(.caption, weight: .bold))
                        .tracking(0.8)
                        .thrymSecondaryText()
                    Spacer(minLength: 8)
                    if let last = ladder.reached.last {
                        Text("Zuletzt erreicht am \(date(last).formatted(date: .abbreviated, time: .omitted))")
                            .font(.geist(.caption))
                            .thrymSecondaryText()
                    }
                }
                HStack(spacing: 8) {
                    ForEach(Array(shown.enumerated()), id: \.offset) { _, reached in
                        cell(
                            value: "\(Int(reached.mark.value))",
                            caption: date(reached).formatted(.dateTime.month(.abbreviated).year(.twoDigits)),
                            dimmed: false
                        )
                        .accessibilityLabel(String(localized: "\(IOSRecognition.markTitle(reached.mark)), erreicht am \(date(reached).formatted(date: .long, time: .omitted))"))
                    }
                    if let next = ladder.next {
                        cell(value: "\(Int(next.value))", caption: String(localized: "nächste"), dimmed: true)
                            .accessibilityLabel(String(localized: "Nächste: \(IOSRecognition.markTitle(next))"))
                    }
                }
                Text("Eine Marke zählt beim ersten Arbeitssatz, der sie erreicht. Aufwärmsätze zählen nicht.")
                    .font(.geist(.caption))
                    .thrymSecondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func date(_ reached: ReachedStrengthMark) -> Date {
        Date(timeIntervalSince1970: TimeInterval(reached.reachedAtEpochMillis) / 1000)
    }

    private func cell(value: String, caption: String, dimmed: Bool) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.geist(size: 22, weight: dimmed ? .medium : .bold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .foregroundStyle(dimmed ? .secondary : .primary)
            Text(caption)
                .font(.geist(.caption2))
                .thrymSecondaryText()
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
    }
}
