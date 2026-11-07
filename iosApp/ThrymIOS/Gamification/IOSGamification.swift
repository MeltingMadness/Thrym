import Foundation
import Shared
import SwiftUI

/// Swift side of the shared XP rules (`GamificationEngine` in `:shared`): Android and iOS derive
/// XP and level from the same history with the same rules; this file only converts the data and
/// formats text.
enum IOSGamification {
    /// The XP this device already celebrated. It is not part of a backup; without it a level-up
    /// compares with the state before the workout.
    private static let celebratedKey = "thrym.gamification.celebratedXp"

    static func evaluate(data: ILTrainingData, settings: SettingsFormState) -> GamificationState {
        let sessions = data.workoutSessions.filter { $0.endTime != nil }.map { session in
            MilestoneSession(
                id: session.id,
                epochDay: IOSRecognition.epochDay(session.startDate),
                orderKey: session.startTime,
                isDeload: session.isDeload.map { KotlinBoolean(bool: $0) }
            )
        }
        let sets = IOSWorkoutRecords
            .recordInputs(exerciseIDs: Set(data.workoutSets.map(\.exerciseId)), data: data)
            .filter(\.sessionFinished)
        let increases = data.progressionSuggestions.filter {
            $0.status.uppercased() == "ACCEPTED"
                && ProgressionAutoApply.shared.isSafeIncrease(outcomeType: $0.outcomeType, reasonCode: $0.reasonCode)
        }
        return GamificationEngine.shared.evaluate(
            input: GamificationInput(
                sessions: sessions,
                sets: sets,
                categoryByExercise: Dictionary(
                    data.exercises.map { (KotlinLong(longLong: $0.id), $0.category) },
                    uniquingKeysWith: { first, _ in first }
                ),
                addedLoadExerciseIds: Set(data.exercises.filter(\.tracksAddedLoad).map { KotlinLong(longLong: $0.id) }),
                acceptedIncreaseSessionIds: increases.map { KotlinLong(longLong: $0.sourceSessionId) },
                weekStart: IOSRecognition.weekStart(settings.weekStart),
                goalHistory: WeeklyGoalRules.shared.decode(text: settings.weeklyGoalHistory),
                unitSystem: IOSRecognition.unitSystem(settings.unitSystem)
            )
        )
    }

    /// The whole history counts, so this runs off the main thread.
    static func evaluateInBackground(data: ILTrainingData, settings: SettingsFormState) async -> GamificationState {
        await Task.detached(priority: .userInitiated) {
            evaluate(data: data, settings: settings)
        }.value
    }

    /// The celebration of the finished workout, nil when it earned no XP. Moves the sum this
    /// device celebrated forward, so a level-up shows once.
    static func celebrate(sessionID: Int64, data: ILTrainingData, settings: SettingsFormState) -> SessionCelebration? {
        let defaults = UserDefaults.standard
        let celebrated = (defaults.object(forKey: celebratedKey) as? NSNumber)?.int64Value
        guard let celebration = GamificationEngine.shared.celebration(
            state: evaluate(data: data, settings: settings),
            sessionId: sessionID,
            celebratedXp: celebrated.map { KotlinLong(longLong: $0) }
        ) else { return nil }
        defaults.set(max(celebrated ?? 0, celebration.after.totalXp), forKey: celebratedKey)
        return celebration
    }

    /// After an import or a restore the old history must not celebrate again.
    static func resetCelebrated() {
        UserDefaults.standard.removeObject(forKey: celebratedKey)
    }

    // MARK: - Text

    static func chapterName(_ chapter: Int32) -> String {
        switch chapter {
        case 1: String(localized: "Der Ruf")
        case 2: String(localized: "Das Federkleid")
        case 3: String(localized: "Die Spur")
        case 4: String(localized: "Die List")
        case 5: String(localized: "Der Wagen")
        case 6: String(localized: "Jötunheim")
        case 7: String(localized: "Das Festmahl")
        default: String(localized: "Der Hammer")
        }
    }

    static func chapterScene(_ chapter: Int32) -> String {
        switch chapter {
        case 1: String(localized: "Thor erwacht, der Hammer ist fort.")
        case 2: String(localized: "Loki leiht Freyjas Federgewand und sucht.")
        case 3: String(localized: "Thrym verrät: acht Rasten tief, nur gegen Freyja als Braut.")
        case 4: String(localized: "Thor reist als Braut verkleidet.")
        case 5: String(localized: "Das Bockgespann zieht den Wagen, Berge bersten.")
        case 6: String(localized: "In der Halle des Riesenkönigs.")
        case 7: String(localized: "Ein Ochse, acht Lachse, drei Fässer Met.")
        default: String(localized: "Der Hammer liegt im Schoß der Braut, Thor greift zu.")
        }
    }

    static func tierNumeral(_ tier: Int32) -> String {
        switch tier {
        case 1: "I"
        case 2: "II"
        default: "III"
        }
    }

    /// "Die Spur II", or "Der Hammer · 2 ★" with further deeds. The level number never shows.
    static func title(_ progress: LevelProgress) -> String {
        let name = chapterName(progress.chapter)
        return progress.stars > 0 ? "\(name) · \(progress.stars) ★" : "\(name) \(tierNumeral(progress.tier))"
    }

    static func chapterOf(_ chapter: Int32) -> String {
        String(localized: "Kapitel \(Int(chapter)) von \(Int(LevelCurve.shared.CHAPTERS))")
    }

    static func hammerDepth(_ progress: LevelProgress) -> String {
        progress.hammerDepth > 0
            ? String(localized: "Hammer noch \(Int(progress.hammerDepth)) Rasten tief")
            : String(localized: "Hammer heimgeholt")
    }

    static func formatXP(_ xp: Int64) -> String {
        xp.formatted(.number)
    }

    /// "9.120 XP gesamt".
    static func totalXP(_ xp: Int64) -> String {
        String(localized: "\(formatXP(xp)) XP gesamt")
    }

    /// "Kapitel 3" above a chapter of the hero's journey; the overview shows it in capitals.
    static func chapterLabel(_ chapter: Int32) -> String {
        String(localized: "Kapitel \(Int(chapter))")
    }

    /// Where a chapter of the hero's journey stands: done, the tier reached, or the XP it starts at.
    /// The last chapter done means the hammer is home.
    static func chapterDetail(_ chapter: JourneyChapter) -> String {
        switch chapter.status {
        case .done:
            return chapter.chapter == LevelCurve.shared.CHAPTERS
                ? String(localized: "Hammer heimgeholt")
                : String(localized: "Abgeschlossen")
        case .current:
            return String(localized: "Stufe \(tierNumeral(chapter.tier)) von \(tierNumeral(LevelCurve.shared.TIERS_PER_CHAPTER))")
        default:
            return String(localized: "Ab \(formatXP(chapter.startXp)) XP")
        }
    }

    /// The name of an XP source in the overview. `bundle` lets the tests read one language.
    static func sourceTitle(_ source: XpSource, bundle: Bundle? = nil) -> String {
        switch source {
        case .record: return String(localized: "Rekorde", bundle: bundle)
        case .mark: return String(localized: "Marken", bundle: bundle)
        case .increase: return String(localized: "Steigerungen", bundle: bundle)
        case .weeklyGoal: return String(localized: "Wochenziel", bundle: bundle)
        case .comeback: return String(localized: "Comeback", bundle: bundle)
        default: return String(localized: "Trainings", bundle: bundle)
        }
    }

    /// The rule of an XP source. The numbers come from the engine, so the text never drifts from
    /// the rules. `bundle` lets the tests read one language.
    static func sourceRule(_ source: XpSource, bundle: Bundle? = nil) -> String {
        let engine = GamificationEngine.shared
        switch source {
        case .record:
            return String(
                localized: "\(Int(engine.RECORD_XP)) XP je Rekord, höchstens \(Int(engine.MAX_RECORD_EXERCISES)) Übungen je Training",
                bundle: bundle
            )
        case .mark:
            return String(localized: "\(Int(engine.MARK_XP)) XP je neuer Marke", bundle: bundle)
        case .increase:
            return String(
                localized: "\(Int(engine.INCREASE_XP)) XP je übernommener Steigerung, höchstens \(Int(engine.MAX_INCREASES)) je Training",
                bundle: bundle
            )
        case .weeklyGoal:
            return String(localized: "\(Int(engine.WEEKLY_GOAL_XP)) XP, wenn du dein Wochenziel erreichst", bundle: bundle)
        case .comeback:
            return String(
                localized: "\(Int(engine.COMEBACK_XP)) XP für das erste Training nach \(Int(TrainingMilestones.shared.COMEBACK_MIN_DAYS)) Tagen Pause",
                bundle: bundle
            )
        default:
            return String(
                localized: "\(Int(engine.TRAINING_XP)) XP je Training, weitere am selben Tag \(Int(engine.FURTHER_TRAINING_SAME_DAY_XP))",
                bundle: bundle
            )
        }
    }

    /// The cap per workout below the rules.
    static func sessionCapRule(bundle: Bundle? = nil) -> String {
        String(
            localized: "Höchstens \(Int(GamificationEngine.shared.MAX_XP_PER_SESSION)) XP je Training. Mehr Sätze bringen keine XP, mehr Fortschritt schon.",
            bundle: bundle
        )
    }

    /// "1.240 XP bis Stufe III", and for the last step of a chapter or the journey what comes next.
    static func nextStep(_ progress: LevelProgress) -> String {
        let xp = formatXP(progress.xpToNext)
        if progress.isLastLevel {
            return String(localized: "\(xp) XP bis zur nächsten Tat")
        }
        if progress.tier == LevelCurve.shared.TIERS_PER_CHAPTER {
            return String(localized: "\(xp) XP bis Kapitel \(Int(progress.chapter + 1))")
        }
        return String(localized: "\(xp) XP bis Stufe \(tierNumeral(progress.tier + 1))")
    }

    /// The title of the step after `progress`; the deeds past the last tier have no name.
    static func nextStepTitle(_ progress: LevelProgress) -> String? {
        if progress.isLastLevel { return nil }
        if progress.tier == LevelCurve.shared.TIERS_PER_CHAPTER {
            return "\(chapterName(progress.chapter + 1)) \(tierNumeral(1))"
        }
        return "\(chapterName(progress.chapter)) \(tierNumeral(progress.tier + 1))"
    }

    /// The accent of a chapter, darker on a light surface so that the ring keeps its contrast.
    static func accent(_ chapter: Int32, dark: Bool) -> Color {
        let (onDark, onLight): (UInt32, UInt32) = switch chapter {
        case 1: (0xC08A5B, 0x8A5A33)
        case 2: (0xAEB7C2, 0x5E6975)
        case 3: (0xDCE3EA, 0x55616E)
        case 4: (0xE8C468, 0x8F6B0C)
        case 5: (0x2DD4BF, 0x0F7F71)
        case 6: (0xA78BFA, 0x6D4FD6)
        case 7: (0x7DD3FC, 0x1B74A3)
        default: (0xF58B20, 0xB85A06)
        }
        let hex = dark ? onDark : onLight
        return Color(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// The emblem of a tier: one plate per tier on a sleeve, in the material of the chapter.
struct IOSChapterEmblem: View {
    let chapter: Int32
    let tier: Int32

    var body: some View {
        Image("emblem-\(chapter)-\(tier)")
            .resizable()
            .scaledToFit()
    }
}

/// The emblem inside a ring that shows the way to the next tier.
struct IOSLevelRing: View {
    let progress: LevelProgress
    let size: CGFloat
    var stroke: CGFloat = 7

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            Circle().stroke(Color.primary.opacity(0.12), lineWidth: stroke)
            Circle()
                .trim(from: 0, to: progress.fraction)
                .stroke(
                    IOSGamification.accent(progress.chapter, dark: colorScheme == .dark),
                    style: StrokeStyle(lineWidth: stroke, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
            IOSChapterEmblem(chapter: progress.chapter, tier: progress.tier)
                .frame(width: size * 0.72, height: size * 0.72)
        }
        .padding(stroke / 2)
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// What the derived XP state depends on; a change computes it again. The dashboard and History
/// use the same input, so both show the same state. Without the game elements the data is left
/// out and a new workout computes nothing.
struct IOSGamificationInput: Equatable {
    let data: ILTrainingData?
    let enabled: Bool
    let weekStart: String
    let goalHistory: String
    let unitSystem: String

    init(data: ILTrainingData?, settings: SettingsFormState) {
        self.data = settings.gamificationEnabled ? data : nil
        enabled = settings.gamificationEnabled
        weekStart = settings.weekStart
        goalHistory = settings.weeklyGoalHistory
        unitSystem = settings.unitSystem
    }
}

/// The place on the hero's journey: emblem in a ring, chapter, tier and the way to the next step.
/// A tap opens the achievements in History.
struct IOSLevelCard: View {
    let progress: LevelProgress
    let action: () -> Void

    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: action) {
            ThrymCard(tone: .elevated) {
                HStack(spacing: 16) {
                    IOSLevelRing(progress: progress, size: 96)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(IOSGamification.chapterOf(progress.chapter).uppercased())
                            .font(.geist(.caption, weight: .semibold))
                            .foregroundStyle(IOSGamification.accent(progress.chapter, dark: colorScheme == .dark))
                        Text(IOSGamification.title(progress))
                            .font(.geist(.title3, weight: .heavy))
                        Text("\(IOSGamification.hammerDepth(progress)) · \(IOSGamification.nextStep(progress))")
                            .font(.geist(.subheadline))
                            .monospacedDigit()
                            .thrymSecondaryText()
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right")
                        .thrymSecondaryText()
                        .accessibilityHidden(true)
                }
            }
            .accessibilityElement(children: .combine)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Öffnet die Erfolge")
    }
}
