import Foundation
import Shared
import XCTest
@testable import ThrymIOS

/// The level card opens History in the achievements, which exist only with the game elements on.
/// The texts of the achievements come from the catalog and carry the numbers of the shared rules.
final class HistoryAchievementsTests: XCTestCase {
    private func languageBundle(_ language: String) throws -> Bundle {
        let path = try XCTUnwrap(
            Bundle.main.path(forResource: language, ofType: "lproj"),
            "\(language).lproj is missing from the app bundle"
        )
        return try XCTUnwrap(Bundle(path: path))
    }

    /// The level card switches to History, selects the achievements and asks History for its root.
    @MainActor
    func testShowAchievementsOpensHistoryAtItsRoot() {
        let store = IOSTrainingStore()
        store.selectedTab = 0
        store.historyMode = .trainings
        let request = store.historyRootRequest

        store.showAchievements()

        XCTAssertEqual(store.selectedTab, 2)
        XCTAssertEqual(store.historyMode, .achievements)
        XCTAssertEqual(store.historyRootRequest, request + 1)

        store.showAchievements()
        XCTAssertEqual(store.historyRootRequest, request + 2, "Every tap returns to the root again")
    }

    /// Without the game elements History is the workouts, also when the achievements were selected
    /// or requested before.
    @MainActor
    func testHistoryShowsTheWorkoutsWithoutGameElements() {
        XCTAssertEqual(IOSHistoryMode.achievements.effective(gamificationEnabled: false), .trainings)
        XCTAssertEqual(IOSHistoryMode.trainings.effective(gamificationEnabled: false), .trainings)
        XCTAssertEqual(IOSHistoryMode.achievements.effective(gamificationEnabled: true), .achievements)
        XCTAssertEqual(IOSHistoryMode.trainings.effective(gamificationEnabled: true), .trainings)

        let store = IOSTrainingStore()
        store.showAchievements()
        XCTAssertEqual(store.historyMode.effective(gamificationEnabled: false), .trainings)
        XCTAssertEqual(store.historyMode.effective(gamificationEnabled: true), .achievements)
    }

    /// Every source has a title and a rule in German and English, and each rule shows the numbers
    /// of the engine, so the overview cannot drift from the rules.
    func testSourceTextsComeFromTheCatalogWithTheRuleValues() throws {
        let english = try languageBundle("en")
        let german = try languageBundle("de")
        let engine = GamificationEngine.shared
        let expected: [(XpSource, [Int32])] = [
            (.training, [engine.TRAINING_XP, engine.FURTHER_TRAINING_SAME_DAY_XP]),
            (.record, [engine.RECORD_XP, engine.MAX_RECORD_EXERCISES]),
            (.mark, [engine.MARK_XP]),
            (.increase, [engine.INCREASE_XP, engine.MAX_INCREASES]),
            (.weeklyGoal, [engine.WEEKLY_GOAL_XP]),
            (.comeback, [engine.COMEBACK_XP, TrainingMilestones.shared.COMEBACK_MIN_DAYS])
        ]
        XCTAssertEqual(expected.map { $0.0 }, HeroJourney.shared.SOURCE_ORDER)

        for (source, values) in expected {
            // The German title is the catalog key; the English table must know it.
            let germanTitle = IOSGamification.sourceTitle(source, bundle: german)
            XCTAssertEqual(
                english.localizedString(forKey: germanTitle, value: "<missing>", table: nil),
                IOSGamification.sourceTitle(source, bundle: english),
                "\(source): title not in the catalog"
            )

            let germanRule = IOSGamification.sourceRule(source, bundle: german)
            let englishRule = IOSGamification.sourceRule(source, bundle: english)
            XCTAssertNotEqual(germanRule, englishRule, "\(source): rule has no English translation")
            for value in values {
                XCTAssertTrue(germanRule.contains("\(value)"), "\(germanRule) lacks \(value)")
                XCTAssertTrue(englishRule.contains("\(value)"), "\(englishRule) lacks \(value)")
            }
        }

        let germanCap = IOSGamification.sessionCapRule(bundle: german)
        let englishCap = IOSGamification.sessionCapRule(bundle: english)
        XCTAssertNotEqual(germanCap, englishCap)
        XCTAssertTrue(germanCap.contains("\(engine.MAX_XP_PER_SESSION)"))
        XCTAssertTrue(englishCap.contains("\(engine.MAX_XP_PER_SESSION)"))
    }

    /// Every text of the switcher and of the achievements is in the catalog, in German as its key
    /// and with its English translation. Read from the language bundles, so the result does not
    /// depend on the language the tests run in.
    func testAchievementTextsAreTranslated() throws {
        let english = try languageBundle("en")
        let german = try languageBundle("de")
        let expected: [String: String] = [
            "Trainings": "Workouts",
            "Erfolge": "Achievements",
            "Verlaufsbereich": "History section",
            "Öffnet die Erfolge": "Opens your achievements",
            "Erfolge konnten nicht berechnet werden.": "Your achievements couldn't be calculated.",
            "Erneut versuchen": "Try again",
            "%@ XP gesamt": "%@ XP in total",
            "Die Heldenreise": "The hero's journey",
            "Thrym hat Thors Hammer acht Rasten unter der Erde versteckt. Jedes Kapitel holt ihn eine Rast höher.":
                "Thrym hid Thor's hammer eight leagues below the earth. Each chapter brings it one league higher.",
            "Kapitel %lld": "Chapter %lld",
            "Abgeschlossen": "Completed",
            "Stufe %@ von %@": "Tier %@ of %@",
            "Ab %@ XP": "From %@ XP",
            "Weitere Taten": "Further deeds",
            "%@ XP bis zur nächsten Tat": "%@ XP to the next deed",
            "So sammelst du XP": "How you earn XP"
        ]
        for (key, value) in expected {
            XCTAssertEqual(english.localizedString(forKey: key, value: "<missing>", table: nil), value, key)
            XCTAssertEqual(german.localizedString(forKey: key, value: "<missing>", table: nil), key, key)
        }
    }

    /// The count of an XP source and the stars of the further deeds are grouped in the device
    /// locale like the XP, never raw integers.
    func testCountsAndStarsAreFormattedLikeTheXP() {
        XCTAssertEqual(IOSGamification.formatCount(1234), IOSGamification.formatXP(1234))
        XCTAssertEqual(IOSGamification.formatCount(7), "7")
        XCTAssertEqual(IOSGamification.stars(1234), "\(IOSGamification.formatXP(1234)) ★")

        let curve = LevelCurve.shared
        let deeds = curve.progress(totalXp: curve.totalXpFor(level: curve.MAX_LEVEL) + 1234 * curve.XP_PER_STAR)
        XCTAssertEqual(deeds.stars, 1234)
        XCTAssertTrue(IOSGamification.title(deeds).hasSuffix("· \(IOSGamification.formatXP(1234)) ★"))
    }

    /// A chapter row reads its status: done, the tier reached in the current one, or the XP a
    /// chapter still ahead starts at.
    func testJourneyRowsDescribeTheirStatus() {
        let curve = LevelCurve.shared
        // Level 8 is chapter 3, tier II.
        let chapters = HeroJourney.shared.chapters(progress: curve.progress(totalXp: curve.totalXpFor(level: 8)))
        XCTAssertEqual(
            chapters.map(\.status),
            [ChapterStatus.done, .done, .current, .locked, .locked, .locked, .locked, .locked]
        )

        for chapter in chapters {
            let detail = IOSGamification.chapterDetail(chapter)
            switch chapter.status {
            case .done:
                XCTAssertEqual(detail, String(localized: "Abgeschlossen"))
            case .current:
                let tier = IOSGamification.tierNumeral(2)
                let tiers = IOSGamification.tierNumeral(curve.TIERS_PER_CHAPTER)
                XCTAssertEqual(detail, String(localized: "Stufe \(tier) von \(tiers)"))
            default:
                let start = IOSGamification.formatXP(curve.chapterStartXp(chapter: chapter.chapter))
                XCTAssertEqual(detail, String(localized: "Ab \(start) XP"))
            }
        }
    }

    /// With the whole journey done, the last chapter says the hammer is home.
    func testTheLastChapterDoneBringsTheHammerHome() {
        let curve = LevelCurve.shared
        let chapters = HeroJourney.shared.chapters(progress: curve.progress(totalXp: curve.totalXpFor(level: curve.MAX_LEVEL)))
        XCTAssertTrue(chapters.allSatisfy { $0.status == .done })
        XCTAssertEqual(IOSGamification.chapterDetail(chapters[6]), String(localized: "Abgeschlossen"))
        XCTAssertEqual(IOSGamification.chapterDetail(chapters[7]), String(localized: "Hammer heimgeholt"))
    }
}
