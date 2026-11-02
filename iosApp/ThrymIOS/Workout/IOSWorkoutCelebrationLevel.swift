import Shared
import SwiftUI

/// "+245 XP", the bar towards the next tier and where the XP came from.
struct IOSCelebrationXPStep: View {
    let celebration: SessionCelebration
    let reducedMotion: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var gain: Double?

    private var after: LevelProgress { celebration.after }
    private var start: Double {
        let before = celebration.before
        return before.level == after.level && before.stars == after.stars ? before.fraction : 0
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 2) {
                    Text("ERFAHRUNG")
                        .font(.geist(.subheadline, weight: .semibold))
                        .thrymSecondaryText()
                    Text("+\(IOSGamification.formatXP(Int64(celebration.xp)))")
                        .font(.geist(size: 56, weight: .heavy).monospacedDigit())
                        .accessibilityAddTraits(.isHeader)
                    Text(verbatim: "XP")
                        .font(.geist(.headline, weight: .semibold))
                        .thrymSecondaryText()
                }
                .accessibilityElement(children: .combine)
                bar
                ThrymCard(tone: .elevated) {
                    VStack(spacing: 12) {
                        ForEach(Array(celebration.entries.enumerated()), id: \.offset) { index, entry in
                            if index > 0 { Divider() }
                            HStack {
                                Text(Self.label(entry)).font(.geist(.body))
                                Spacer()
                                Text("+\(IOSGamification.formatXP(Int64(entry.xp)))")
                                    .font(.geist(.body, weight: .bold).monospacedDigit())
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 48)
        }
        .onAppear {
            guard gain == nil else { return }
            gain = reducedMotion ? after.fraction : start
            guard !reducedMotion else { return }
            withAnimation(.easeOut(duration: 0.9)) { gain = after.fraction }
        }
    }

    private var bar: some View {
        let accent = IOSGamification.accent(after.chapter, dark: colorScheme == .dark)
        return VStack(spacing: 8) {
            HStack {
                Text(IOSGamification.title(after)).font(.geist(.subheadline, weight: .bold))
                Spacer()
                if let next = IOSGamification.nextStepTitle(after) {
                    Text(next).font(.geist(.subheadline)).thrymSecondaryText()
                }
            }
            .accessibilityElement(children: .combine)
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.12))
                    Capsule().fill(accent.opacity(0.55)).frame(width: proxy.size.width * (gain ?? start))
                    Capsule().fill(accent).frame(width: proxy.size.width * start)
                }
            }
            .frame(height: 14)
            .accessibilityHidden(true)
            Text("noch \(IOSGamification.formatXP(after.xpToNext)) XP")
                .font(.geist(.footnote))
                .monospacedDigit()
                .thrymSecondaryText()
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    private static func label(_ entry: XpEntry) -> String {
        let count = Int(entry.count)
        switch entry.source {
        case .comeback: return String(localized: "Comeback")
        case .weeklyGoal: return String(localized: "Wochenziel erreicht")
        case .mark: return String(localized: "\(count) Marken")
        case .record: return String(localized: "\(count) Rekorde")
        case .increase: return String(localized: "\(count) Steigerungen")
        default: return String(localized: "Training")
        }
    }
}

/// The new tier gets its plate; a new chapter also tells its scene and how far the hammer rose.
struct IOSCelebrationLevelStep: View {
    let levelUp: LevelUp
    let facts: CompletionFacts?
    let reducedMotion: Bool

    @Environment(\.colorScheme) private var colorScheme

    private var progress: LevelProgress { levelUp.progress }
    private var isChapter: Bool { levelUp.kind == .chapter }

    var body: some View {
        let accent = IOSGamification.accent(progress.chapter, dark: colorScheme == .dark)
        let circle: CGFloat = isChapter ? 220 : 180
        ZStack {
            ScrollView {
                VStack(spacing: 14) {
                    Text(kindLabel)
                        .font(.geist(.subheadline, weight: .bold))
                        .thrymSecondaryText()
                    IOSChapterEmblem(chapter: progress.chapter, tier: progress.tier)
                        .padding(circle * 0.09)
                        .frame(width: circle, height: circle)
                        .background(accent.opacity(0.12), in: Circle())
                        .overlay(Circle().stroke(accent.opacity(0.4), lineWidth: 1))
                        .accessibilityElement()
                        .accessibilityLabel(String(localized: "Emblem \(IOSGamification.title(progress))"))
                    Text(isChapter ? IOSGamification.chapterName(progress.chapter) : IOSGamification.title(progress))
                        .font(.geist(size: isChapter ? 38 : 30, weight: .heavy))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    detail
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 40)
            }
            if !reducedMotion {
                IOSConfetti()
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
    }

    private var kindLabel: String {
        switch levelUp.kind {
        case .chapter: String(localized: "NEUES KAPITEL")
        case .star: String(localized: "WEITERE TAT")
        default: String(localized: "NEUE STUFE")
        }
    }

    @ViewBuilder
    private var detail: some View {
        switch levelUp.kind {
        case .chapter:
            Text("\(IOSGamification.chapterScene(progress.chapter))\n\(hammerRising)")
                .font(.geist(.body))
                .multilineTextAlignment(.center)
                .thrymSecondaryText()
            HStack(spacing: 10) {
                chip(IOSGamification.chapterOf(progress.chapter))
                if let facts {
                    chip(String(localized: "\(Int(facts.trainingNumber)) Trainings bis hierhin"))
                }
            }
        case .star:
            Text("Der Hammer ist daheim, die Taten gehen weiter.")
                .font(.geist(.body))
                .multilineTextAlignment(.center)
                .thrymSecondaryText()
        default:
            Text("\(IOSGamification.chapterOf(progress.chapter)) · \(IOSGamification.hammerDepth(progress))")
                .font(.geist(.body))
                .multilineTextAlignment(.center)
                .thrymSecondaryText()
        }
    }

    private var hammerRising: String {
        progress.hammerDepth > 0
            ? String(localized: "Der Hammer liegt nur noch \(Int(progress.hammerDepth)) Rasten tief.")
            : String(localized: "Der Hammer ist zurück.")
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.geist(.footnote, weight: .medium))
            .thrymSecondaryText()
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.primary.opacity(0.14), lineWidth: 1))
    }
}

/// Falls once and is gone. The caller leaves it out with reduced motion.
private struct IOSConfetti: View {
    private struct Piece {
        let x: Double
        let delay: Double
        let sway: Double
        let spin: Double
        let width: Double
        let height: Double
        let color: Color
    }

    private static let duration = 2.6
    private static let colors: [Color] = [
        Color(red: 0.961, green: 0.545, blue: 0.125),
        Color(red: 0.176, green: 0.831, blue: 0.749),
        Color(red: 0.988, green: 0.827, blue: 0.302),
        Color(red: 0.655, green: 0.545, blue: 0.980),
        Color(red: 0.220, green: 0.741, blue: 0.973)
    ]
    private static let pieces: [Piece] = {
        // A fixed sequence (linear congruential), the same fall on every level-up.
        var state: UInt64 = 7
        func next() -> Double {
            state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return Double(state >> 33) / Double(UInt64(1) << 31)
        }
        return (0..<36).map { index in
            Piece(
                x: next(),
                delay: next() * 0.35,
                sway: 0.02 + next() * 0.05,
                spin: 180 + next() * 540,
                width: 6 + next() * 4,
                height: 10 + next() * 6,
                color: colors[index % colors.count]
            )
        }
    }()

    @State private var start = Date()

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30)) { timeline in
            Canvas { context, size in
                let time = timeline.date.timeIntervalSince(start) / Self.duration
                guard time < 1 else { return }
                for piece in Self.pieces {
                    let fall = min(max((time - piece.delay) / (1 - piece.delay), 0), 1)
                    guard fall > 0, fall < 1 else { continue }
                    let x = (piece.x + piece.sway * sin(fall * 4 * .pi)) * size.width
                    let y = -20 + fall * (size.height + 40)
                    var layer = context
                    layer.translateBy(x: x, y: y)
                    layer.rotate(by: .degrees(piece.spin * fall))
                    layer.opacity = min(1 - fall, 0.4) / 0.4
                    layer.fill(
                        Path(CGRect(x: -piece.width / 2, y: -piece.height / 2, width: piece.width, height: piece.height)),
                        with: .color(piece.color)
                    )
                }
            }
        }
    }
}
