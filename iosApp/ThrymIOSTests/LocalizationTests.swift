import Foundation
import XCTest
@testable import ThrymIOS

/// English is the development language and German a translation. The plural
/// tests pin the language explicitly because the wording itself is under test;
/// everything else resolves its expectation from the app's own resources.
final class LocalizationTests: XCTestCase {
    private func languageBundle(_ language: String) throws -> Bundle {
        let path = try XCTUnwrap(
            Bundle.main.path(forResource: language, ofType: "lproj"),
            "\(language).lproj is missing from the app bundle"
        )
        return try XCTUnwrap(Bundle(path: path))
    }

    private func text(_ value: String.LocalizationValue, _ language: String) throws -> String {
        String(localized: value, bundle: try languageBundle(language), locale: Locale(identifier: language))
    }

    func testAppShipsEnglishAndGermanWithEnglishFallback() throws {
        XCTAssertEqual(Bundle.main.developmentLocalization, "en")
        XCTAssertTrue(Bundle.main.localizations.contains("en"))
        XCTAssertTrue(Bundle.main.localizations.contains("de"))
        let declared = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleLocalizations") as? [String])
        XCTAssertEqual(Set(declared), ["en", "de"])
    }

    func testCountKeysUsePluralVariations() throws {
        XCTAssertEqual(try text("\(1) Trainings", "en"), "1 workout")
        XCTAssertEqual(try text("\(3) Trainings", "en"), "3 workouts")
        XCTAssertEqual(try text("\(1) Trainings", "de"), "1 Training")
        XCTAssertEqual(try text("\(3) Trainings", "de"), "3 Trainings")

        XCTAssertEqual(try text("\(1) Vorschläge warten auf deine Entscheidung.", "de"), "1 Vorschlag wartet auf deine Entscheidung.")
        XCTAssertEqual(try text("\(2) Vorschläge warten auf deine Entscheidung.", "de"), "2 Vorschläge warten auf deine Entscheidung.")
        XCTAssertEqual(try text("\(1) Entscheidungen ausstehend", "en"), "1 decision pending")
    }

    func testSubstitutionsPluralizeTheRightArgument() throws {
        XCTAssertEqual(try text("\(0) / \(1) Sätze", "en"), "0 / 1 set")
        XCTAssertEqual(try text("\(2) / \(5) Sätze", "en"), "2 / 5 sets")
        XCTAssertEqual(try text("\(0) / \(1) Sätze", "de"), "0 / 1 Satz")

        XCTAssertEqual(try text("Superset #\(2), \(1) Übungen", "en"), "Superset #2, 1 exercise")
        XCTAssertEqual(try text("Superset #\(2), \(3) Übungen", "en"), "Superset #2, 3 exercises")
        XCTAssertEqual(try text("Superset #\(1), \(1) Übungen", "de"), "Superset #1, 1 Übung")

        XCTAssertEqual(try text("\(1) Sätze × \(8) Wdh · \("80 kg")", "en"), "1 set × 8 reps · 80 kg")
        XCTAssertEqual(try text("\(3) Sätze × \(1) Wdh · \("80 kg")", "de"), "3 Sätze × 1 Wdh · 80 kg")

        // Counted and extra sets each pluralize their own argument (1 and 6).
        XCTAssertEqual(
            try text("Gewertet: \(1) Arbeitssätze, davon \(0) bis zum Versagen. Ausgelassen: \(2) Aufwärm-, \(0) Drop-, \(0) Backoff- und \(1) zusätzliche Arbeitssätze.", "en"),
            "Counted: 1 work set, 0 of them to failure. Excluded: 2 warm-up, 0 drop, 0 back-off, and 1 extra work set."
        )
        XCTAssertEqual(
            try text("Gewertet: \(3) Arbeitssätze, davon \(1) bis zum Versagen. Ausgelassen: \(0) Aufwärm-, \(1) Drop-, \(0) Backoff- und \(2) zusätzliche Arbeitssätze.", "en"),
            "Counted: 3 work sets, 1 of them to failure. Excluded: 0 warm-up, 1 drop, 0 back-off, and 2 extra work sets."
        )
        XCTAssertEqual(
            try text("Gewertet: \(3) Arbeitssätze, davon \(0) bis zum Versagen. Ausgelassen: \(0) Aufwärm-, \(0) Drop-, \(0) Backoff- und \(1) zusätzliche Arbeitssätze.", "de"),
            "Gewertet: 3 Arbeitssätze, davon 0 bis zum Versagen. Ausgelassen: 0 Aufwärm-, 0 Drop-, 0 Backoff- und 1 zusätzlicher Arbeitssatz."
        )
    }

    func testRecordKeysCountRecordsAndSets() throws {
        XCTAssertEqual(try text("\(1) neue Rekorde", "en"), "1 new PR")
        XCTAssertEqual(try text("\(4) neue Rekorde", "en"), "4 new PRs")
        XCTAssertEqual(try text("\(1) neue Rekorde", "de"), "1 neuer Rekord")
        XCTAssertEqual(try text("in \(1) Übungen", "de"), "in 1 Übung")
        XCTAssertEqual(try text("in \(3) Übungen", "en"), "in 3 exercises")

        // A reps record: the live notice names the unit, VoiceOver reads full words without
        // a label, and the gain is a bare number in the sentence.
        XCTAssertEqual(try text("\(12) Wdh.", "de"), "12 Wdh.")
        XCTAssertEqual(try text("\(12) Wdh.", "en"), "12 reps")
        XCTAssertEqual(try text("\(1) Wiederholungen", "de"), "1 Wiederholung")
        XCTAssertEqual(try text("\(12) Wiederholungen", "en"), "12 reps")
        XCTAssertEqual(try text("bei \("60 kg")", "en"), "at 60 kg")
        XCTAssertEqual(
            try text("\("12 Wiederholungen bei 60 kg"), \(2) mehr als bisher", "de"),
            "12 Wiederholungen bei 60 kg, 2 mehr als bisher"
        )
        XCTAssertEqual(try text("\("12 reps at 60 kg"), \(2) mehr als bisher", "en"), "12 reps at 60 kg, 2 more than before")
        XCTAssertEqual(try text("\("1 Wiederholung"), \(1) mehr als bisher", "de"), "1 Wiederholung, 1 mehr als bisher")
        XCTAssertEqual(try text("\("12 Wiederholungen"), zum ersten Mal", "de"), "12 Wiederholungen, zum ersten Mal")
        XCTAssertEqual(try text("\("12 reps"), zum ersten Mal", "en"), "12 reps, first time")

        // The live title pluralizes the count and keeps the exercise name.
        XCTAssertEqual(try text("\(1) neue Rekorde · \("Bench press")", "en"), "1 new PR · Bench press")
        XCTAssertEqual(try text("\(2) neue Rekorde · \("Bankdrücken")", "de"), "2 neue Rekorde · Bankdrücken")

        XCTAssertEqual(try text("Alle \(9) geplanten Sätze geschafft", "de"), "Alle 9 geplanten Sätze geschafft")
        XCTAssertEqual(try text("Alle \(1) geplanten Sätze geschafft", "de"), "1 geplanter Satz geschafft")
        XCTAssertEqual(try text("Alle \(9) geplanten Sätze geschafft", "en"), "All 9 planned sets done")
    }

    func testPercentChangesFollowTheLocale() {
        XCTAssertEqual(ilPercentChangeText(5, locale: Locale(identifier: "en_US")), "+5.0%")
        XCTAssertEqual(ilPercentChangeText(-12.34, locale: Locale(identifier: "en_US")), "-12.3%")
        XCTAssertEqual(ilPercentChangeText(0, locale: Locale(identifier: "en_US")), "0.0%")
        XCTAssertEqual(ilPercentChangeText(7, fractionDigits: 0...1, locale: Locale(identifier: "en_US")), "+7%")
        // German keeps a (no-break) space before the percent sign; the exact space is ICU's.
        let german = ilPercentChangeText(5, locale: Locale(identifier: "de_DE"))
        XCTAssertTrue(german.hasPrefix("+5,0") && german.hasSuffix("%"), german)
        XCTAssertTrue(german.dropLast().last?.isWhitespace == true, german)
    }

    func testAnalyticsDatesKeepTheCalendarDayInEveryTimeZone() throws {
        // The shared core sends local calendar days; west of UTC they must not slip back a day.
        let english = Locale(identifier: "en_US")
        for identifier in ["America/New_York", "America/Los_Angeles", "UTC", "Europe/Berlin", "Asia/Tokyo", "Pacific/Kiritimati"] {
            let zone = try XCTUnwrap(TimeZone(identifier: identifier))
            let text = try XCTUnwrap(ilAnalyticsDateText("2026-10-11", timeZone: zone, locale: english))
            XCTAssertEqual(text, "Oct 11, 2026", identifier)
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = zone
            let date = try XCTUnwrap(ilAnalyticsDate("2026-10-11", timeZone: zone))
            let day = calendar.dateComponents([.year, .month, .day, .hour], from: date)
            XCTAssertEqual([day.year, day.month, day.day, day.hour], [2026, 10, 11, 0], identifier)
        }
        XCTAssertNil(ilAnalyticsDate("11.10.2026"))
        XCTAssertEqual(ilAnalyticsShiftedWeekStart("2026-10-05", weeks: 1), "2026-10-12")
        XCTAssertEqual(ilAnalyticsShiftedWeekStart("2026-10-26", weeks: 1), "2026-11-02")
        XCTAssertEqual(ilAnalyticsShiftedWeekStart("2026-10-05", weeks: -1), "2026-09-28")
    }

    func testSharedMessagesAreTranslatedAtTheBridge() throws {
        // A fixed message from the Kotlin core is a catalog key with a real translation.
        let key = "Es ist bereits ein anderes Training aktiv. Bitte setze es fort oder beende es zuerst."
        let german = try languageBundle("de").localizedString(forKey: key, value: nil, table: nil)
        let english = try languageBundle("en").localizedString(forKey: key, value: nil, table: nil)
        XCTAssertEqual(german, key)
        XCTAssertNotEqual(english, key)
        XCTAssertFalse(english.isEmpty)
        XCTAssertEqual(ilSharedMessage(key), Bundle.main.localizedString(forKey: key, value: nil, table: nil))

        // English diagnostics have no catalog entry and pass through unchanged.
        let diagnostic = "Unknown training command: plan.archive"
        XCTAssertEqual(ilSharedMessage(diagnostic), diagnostic)
    }

    func testRestTimerValueUsesDurationFormatting() throws {
        // The VoiceOver value wraps a localized Duration ("1 minute, 30 seconds").
        let english = Duration.seconds(90).formatted(
            Duration.UnitsFormatStyle(allowedUnits: [.minutes, .seconds], width: .wide).locale(Locale(identifier: "en"))
        )
        XCTAssertEqual(try text("Noch \(english)", "en"), "\(english) left")
        XCTAssertTrue(english.contains("1 minute") && english.contains("30 seconds"), english)
        let german = Duration.seconds(61).formatted(
            Duration.UnitsFormatStyle(allowedUnits: [.minutes, .seconds], width: .wide).locale(Locale(identifier: "de"))
        )
        XCTAssertEqual(try text("Noch \(german)", "de"), "Noch \(german)")
        XCTAssertTrue(german.contains("1 Minute") && german.contains("1 Sekunde"), german)
    }

    func testHistoryWeekRangeUsesTheLocalePattern() throws {
        var calendar = IOSHistoryWeek.calendar
        calendar.locale = .current
        // 5.10.2026 is a Monday.
        let monday = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 10, day: 5, hour: 12)))
        let start = IOSHistoryWeek.weekStart(of: monday)
        // Day first in German, month first in English; the exact spacing is ICU's.
        let german = IOSHistoryGlassWeekHeader.rangeText(weekStart: start, locale: Locale(identifier: "de_DE"))
        XCTAssertTrue(german.hasPrefix("5."), german)
        XCTAssertTrue(german.contains("11. Okt"), german)
        let english = IOSHistoryGlassWeekHeader.rangeText(weekStart: start, locale: Locale(identifier: "en_US"))
        XCTAssertTrue(english.hasPrefix("Oct"), english)
        XCTAssertTrue(english.contains("11"), english)
    }

    func testHistoryDayLettersFollowTheDate() throws {
        var calendar = IOSHistoryWeek.calendar
        calendar.locale = .current
        let symbols = calendar.veryShortStandaloneWeekdaySymbols
        // 12.10.2026 is a Monday, 18.10.2026 a Sunday.
        let monday = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 10, day: 12, hour: 12)))
        let letters = (0..<7).map { offset in
            IOSHistoryGlassMiniBars.dayLetter(for: calendar.date(byAdding: .day, value: offset, to: monday)!)
        }
        // Calendar weekday symbols start on Sunday; the history week starts on Monday.
        XCTAssertEqual(letters, Array(symbols[1...]) + [symbols[0]])
        XCTAssertTrue(letters.allSatisfy { !$0.isEmpty })
    }
}
