import Shared
import SwiftUI

/// What the achievements show, derived from the same state as the level card on the dashboard.
struct IOSAchievementsSnapshot {
    let progress: LevelProgress
    let chapters: [JourneyChapter]
    let sources: [XpTotal]

    init(state: GamificationState) {
        progress = state.progress
        chapters = HeroJourney.shared.chapters(progress: state.progress)
        sources = HeroJourney.shared.xpBySource(state: state)
    }
}

/// "Verlauf › Erfolge": where the hero's journey stands, its eight chapters and how XP are earned.
/// The same content as on Android; it only shows with the game elements on.
struct IOSHistoryAchievements: View {
    /// Whether the content has scrolled under the mode switcher of History.
    @Binding var contentScrolled: Bool

    @Environment(IOSTrainingStore.self) private var store
    @EnvironmentObject private var settings: IOSSettingsViewModel
    @Environment(\.thrymAppearance) private var appearance

    @State private var phase: Phase = .loading
    @State private var attempt = 0

    private enum Phase {
        case loading
        case loaded(IOSAchievementsSnapshot)
        case failed
    }

    /// The same input as the dashboard; a retry computes again.
    private struct Key: Equatable {
        let input: IOSGamificationInput
        let attempt: Int
    }

    var body: some View {
        Group {
            switch phase {
            case .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed:
                ContentUnavailableView {
                    Label("Erfolge konnten nicht berechnet werden.", systemImage: "exclamationmark.triangle")
                } actions: {
                    Button("Erneut versuchen", action: retry)
                }
            case .loaded(let snapshot):
                content(snapshot)
            }
        }
        .task(id: Key(input: IOSGamificationInput(data: store.data, settings: settings.state), attempt: attempt)) {
            await update()
        }
    }

    private func content(_ snapshot: IOSAchievementsSnapshot) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                IOSAchievementsHero(progress: snapshot.progress)

                VStack(alignment: .leading, spacing: 10) {
                    IOSAchievementsSectionHeader(
                        title: String(localized: "Die Heldenreise"),
                        intro: String(localized: "Thrym hat Thors Hammer acht Rasten unter der Erde versteckt. Jedes Kapitel holt ihn eine Rast höher.")
                    )
                    ForEach(snapshot.chapters, id: \.chapter) { chapter in
                        IOSJourneyChapterRow(chapter: chapter)
                    }
                    if snapshot.progress.isLastLevel {
                        IOSJourneyDeedsRow(progress: snapshot.progress)
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    IOSAchievementsSectionHeader(title: String(localized: "So sammelst du XP"))
                    VStack(spacing: 0) {
                        ForEach(Array(snapshot.sources.enumerated()), id: \.offset) { index, total in
                            if index > 0 { Divider() }
                            IOSXpSourceRow(total: total)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
                    .modifier(IOSAchievementsSurface(highlight: nil))
                    Text(IOSGamification.sessionCapRule())
                        .font(.geist(.footnote))
                        .thrymSecondaryText()
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 4)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .modifier(IOSScrolledFromTop(isScrolled: $contentScrolled))
        // Ember: the grouped background of the workout list, so the mode switch keeps the backdrop.
        .background {
            if appearance == .ember {
                Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
            }
        }
    }

    private func update() async {
        let state = settings.state
        guard let data = store.data else {
            phase = store.errorMessage == nil ? .loading : .failed
            return
        }
        let gamification = await IOSGamification.evaluateInBackground(data: data, settings: state)
        guard !Task.isCancelled else { return }
        phase = .loaded(IOSAchievementsSnapshot(state: gamification))
    }

    private func retry() {
        Task { @MainActor in
            phase = .loading
            await store.reload()
            attempt += 1
        }
    }
}

/// The current stand: ring, chapter, title, scene, the way to the next step and the XP in total.
private struct IOSAchievementsHero: View {
    let progress: LevelProgress

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ThrymCard(tone: .elevated) {
            VStack(spacing: 6) {
                IOSLevelRing(progress: progress, size: 132)
                    .padding(.bottom, 8)
                Text(IOSGamification.chapterOf(progress.chapter).uppercased())
                    .font(.geist(.caption, weight: .semibold))
                    .tracking(0.8)
                    .foregroundStyle(IOSGamification.accent(progress.chapter, dark: colorScheme == .dark))
                Text(IOSGamification.title(progress))
                    .font(.geist(.title, weight: .heavy))
                Text(IOSGamification.chapterScene(progress.chapter))
                    .font(.geist(.subheadline))
                    .thrymSecondaryText()
                Text("\(IOSGamification.hammerDepth(progress)) · \(IOSGamification.nextStep(progress))")
                    .font(.geist(.subheadline, weight: .semibold))
                    .monospacedDigit()
                    .padding(.top, 6)
                Text(IOSGamification.totalXP(progress.totalXp))
                    .font(.geist(.footnote))
                    .monospacedDigit()
                    .thrymSecondaryText()
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct IOSAchievementsSectionHeader: View {
    let title: String
    var intro: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.geist(.title3, weight: .bold))
                .accessibilityAddTraits(.isHeader)
            if let intro {
                Text(intro)
                    .font(.geist(.subheadline))
                    .thrymSecondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 4)
        .padding(.bottom, 2)
    }
}

/// One chapter of the hero's journey. Chapters still ahead are dimmed and keep their scene for later.
private struct IOSJourneyChapterRow: View {
    let chapter: JourneyChapter

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.thrymTheme) private var theme

    var body: some View {
        let palette = theme.palette(for: colorScheme)
        let locked = chapter.status == .locked
        let accent = IOSGamification.accent(chapter.chapter, dark: colorScheme == .dark)
        HStack(spacing: 14) {
            IOSChapterEmblem(chapter: chapter.chapter, tier: chapter.tier)
                .frame(width: 48, height: 48)
                .saturation(locked ? 0 : 1)
                .opacity(locked ? 0.35 : 1)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(IOSGamification.chapterLabel(chapter.chapter).uppercased())
                    .font(.geist(.caption, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(locked ? palette.textSecondary : accent)
                Text(IOSGamification.chapterName(chapter.chapter))
                    .font(.geist(.headline, weight: .semibold))
                Text(IOSGamification.chapterDetail(chapter))
                    .font(.geist(.subheadline))
                    .monospacedDigit()
                    .foregroundStyle(locked ? palette.textSecondary : palette.textPrimary)
                if !locked {
                    Text(IOSGamification.chapterScene(chapter.chapter))
                        .font(.geist(.footnote))
                        .foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .modifier(IOSAchievementsSurface(highlight: chapter.status == .current ? accent : nil))
        .accessibilityElement(children: .combine)
    }
}

/// After the last chapter: the further deeds, one star per step.
private struct IOSJourneyDeedsRow: View {
    let progress: LevelProgress

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let accent = IOSGamification.accent(LevelCurve.shared.CHAPTERS, dark: colorScheme == .dark)
        HStack(spacing: 14) {
            Image(systemName: "star.fill")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(accent)
                .frame(width: 48, height: 48)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Weitere Taten")
                    .font(.geist(.headline, weight: .semibold))
                Text(IOSGamification.nextStep(progress))
                    .font(.geist(.subheadline))
                    .monospacedDigit()
                    .thrymSecondaryText()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(verbatim: "\(progress.stars) ★")
                .font(.geist(.title3, weight: .heavy))
                .monospacedDigit()
                .foregroundStyle(accent)
        }
        .padding(14)
        .modifier(IOSAchievementsSurface(highlight: nil))
        .accessibilityElement(children: .combine)
    }
}

/// One XP source: its name and rule, and what it brought so far.
private struct IOSXpSourceRow: View {
    let total: XpTotal

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(IOSGamification.sourceTitle(total.source))
                    .font(.geist(.headline, weight: .semibold))
                Text(IOSGamification.sourceRule(total.source))
                    .font(.geist(.subheadline))
                    .thrymSecondaryText()
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 2) {
                Text(verbatim: "\(IOSGamification.formatXP(total.xp)) XP")
                    .font(.geist(.headline, weight: .bold))
                    .monospacedDigit()
                if total.count > 0 {
                    Text(verbatim: "\(total.count)×")
                        .font(.geist(.caption, weight: .semibold))
                        .monospacedDigit()
                        .thrymSecondaryText()
                }
            }
        }
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }
}

/// Surface of the achievement rows: glass in Liquid Glass, a filled card in Ember. `highlight`
/// tints the current chapter in its accent and draws its border.
private struct IOSAchievementsSurface: ViewModifier {
    let highlight: Color?

    @Environment(\.thrymAppearance) private var appearance
    @Environment(\.thrymTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let palette = theme.palette(for: colorScheme)
        let shape = RoundedRectangle(cornerRadius: appearance == .liquidGlass ? 24 : 18, style: .continuous)
        let tinted = content.background {
            if let highlight { shape.fill(highlight.opacity(0.12)) }
        }
        switch appearance {
        case .liquidGlass:
            tinted
                .liquidGlass(in: shape)
                .overlay {
                    if let highlight { shape.strokeBorder(highlight, lineWidth: 1) }
                }
        case .ember:
            tinted
                .background(palette.surface, in: shape)
                .overlay { shape.strokeBorder(highlight ?? palette.separator, lineWidth: 1) }
        }
    }
}
