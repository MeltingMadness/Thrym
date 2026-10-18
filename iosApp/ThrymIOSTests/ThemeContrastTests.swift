import SwiftUI
import UIKit
import XCTest
@testable import ThrymIOS

private struct ThrymTestRGB {
    var red: Double
    var green: Double
    var blue: Double
}

final class ThemeContrastTests: XCTestCase {
    private let teal = ThrymTestRGB(red: 46 / 255, green: 196 / 255, blue: 182 / 255)
    private let violet = ThrymTestRGB(red: 109 / 255, green: 91 / 255, blue: 255 / 255)

    func testThemeTextRolesMeetWCAGContrastAcrossGlassLevels() {
        var weakest = (ratio: Double.infinity, description: "")

        for scheme in ThrymTheme.Scheme.allCases {
            let theme = ThrymTheme(scheme: scheme)

            for colorScheme in [ColorScheme.light, .dark] {
                let dark = colorScheme == .dark
                let palette = theme.palette(for: colorScheme)
                let primary = rgb(palette.primary)
                let backdrop = backdropSamples(primary: primary, dark: dark)
                let textRoles = [
                    ("Nebentext", rgb(palette.textSecondary)),
                    ("Akzenttext", rgb(palette.accentText))
                ]

                for level in [ThrymGlassLevel.standard, .tint] {
                    for (role, foreground) in textRoles {
                        var worst = (ratio: Double.infinity, sample: backdrop[0])
                        for sample in backdrop {
                            for sheenAlpha in dark ? [0.20, 0.05, 0.03] : [0.70, 0.30, 0.20] {
                                let glass = glassColor(
                                    over: sample,
                                    primary: primary,
                                    level: level,
                                    dark: dark,
                                    sheenAlpha: sheenAlpha
                                )
                                let value = contrast(foreground, glass)
                                if value < worst.ratio {
                                    worst = (value, sample)
                                }
                            }
                        }
                        let description = "\(scheme.rawValue) \(dark ? "dunkel" : "hell") \(level) \(role)"
                        if worst.ratio < weakest.ratio {
                            weakest = (worst.ratio, description)
                        }
                        XCTAssertGreaterThanOrEqual(
                            worst.ratio,
                            4.5,
                            "\(description): \(String(format: "%.3f", worst.ratio)):1 bei x=\(worst.sample.x), y=\(worst.sample.y)"
                        )
                    }
                }

                let buttonContrast = contrast(rgb(palette.buttonText), primary)
                let buttonDescription = "\(scheme.rawValue) \(dark ? "dunkel" : "hell") Buttontext/Akzent"
                if buttonContrast < weakest.ratio {
                    weakest = (buttonContrast, buttonDescription)
                }
                XCTAssertGreaterThanOrEqual(
                    buttonContrast,
                    4.5,
                    "\(buttonDescription): \(String(format: "%.3f", buttonContrast)):1"
                )
            }
        }

        print("Knappster geprüfter Kontrast: \(String(format: "%.3f", weakest.ratio)):1 (\(weakest.description))")
    }

    /// The semantic ink on the cards it is made for: the Ember surface with a faint fill and
    /// tinted glass, each tinted with the ink's own fills (finish card: success, record board:
    /// warning). The palette text roles must stay readable on them too.
    func testSemanticInkMeetsWCAGContrastOnTintedCards() {
        var weakest = (ratio: Double.infinity, description: "")

        for scheme in ThrymTheme.Scheme.allCases {
            let theme = ThrymTheme(scheme: scheme)

            for colorScheme in [ColorScheme.light, .dark] {
                let dark = colorScheme == .dark
                let palette = theme.palette(for: colorScheme)
                let ink = ThrymSemanticInk.forDark(dark)
                let backdrop = backdropSamples(primary: rgb(palette.primary), dark: dark)
                let textRoles = [
                    ("Erfolgstinte", rgb(ink.success)),
                    ("Warntinte", rgb(ink.warning)),
                    ("Nebentext", rgb(palette.textSecondary)),
                    ("Text", rgb(palette.textPrimary))
                ]

                for (card, fill) in [("Erfolgskarte", rgb(ink.successFill)), ("Warnkarte", rgb(ink.warningFill))] {
                    let looks = [
                        ("Ember", [composite(fill, alpha: 0.08, over: rgb(palette.surface))]),
                        ("Glas", tintedGlassSamples(over: backdrop, tint: fill, dark: dark))
                    ]

                    for (look, surfaces) in looks {
                        let luminances = surfaces.map(luminance)
                        for (role, foreground) in textRoles {
                            let ratio = worstContrast(foreground, on: luminances)
                            let description = "\(scheme.rawValue) \(dark ? "dunkel" : "hell") \(look) \(card) \(role)"
                            if ratio < weakest.ratio {
                                weakest = (ratio, description)
                            }
                            XCTAssertGreaterThanOrEqual(ratio, 4.5, "\(description): \(String(format: "%.3f", ratio)):1")
                        }
                    }
                }
            }
        }

        print("Knappster Kontrast der semantischen Tinte: \(String(format: "%.3f", weakest.ratio)):1 (\(weakest.description))")
    }

    /// The record board's chips and medallion on its warning-tinted card: the gain in green on
    /// a success fill, "Erstmals" in gold on a warning fill (text, 4.5:1), and the trophy on the
    /// medallion (a graphic, 3:1).
    func testRecordBoardChipsMeetWCAGContrast() {
        var weakest = (ratio: Double.infinity, description: "")

        for scheme in ThrymTheme.Scheme.allCases {
            let theme = ThrymTheme(scheme: scheme)

            for colorScheme in [ColorScheme.light, .dark] {
                let dark = colorScheme == .dark
                let palette = theme.palette(for: colorScheme)
                let ink = ThrymSemanticInk.forDark(dark)
                let warningFill = rgb(ink.warningFill)
                let cards = [
                    ("Ember", [composite(warningFill, alpha: 0.08, over: rgb(palette.surface))]),
                    ("Glas", tintedGlassSamples(over: backdropSamples(primary: rgb(palette.primary), dark: dark), tint: warningFill, dark: dark))
                ]
                let marks = [
                    ("Zuwachs", rgb(ink.success), rgb(ink.successFill), 0.14, 4.5),
                    ("Erstmals", rgb(ink.warning), warningFill, 0.16, 4.5),
                    ("Medaille", rgb(ink.warning), warningFill, 0.22, 3.0)
                ]

                for (look, card) in cards {
                    for (mark, foreground, fill, alpha, minimum) in marks {
                        let luminances = card.map { luminance(composite(fill, alpha: alpha, over: $0)) }
                        let ratio = worstContrast(foreground, on: luminances)
                        let description = "\(scheme.rawValue) \(dark ? "dunkel" : "hell") \(look) \(mark)"
                        if ratio / minimum < weakest.ratio {
                            weakest = (ratio / minimum, description)
                        }
                        XCTAssertGreaterThanOrEqual(ratio, minimum, "\(description): \(String(format: "%.3f", ratio)):1")
                    }
                }
            }
        }

        print("Knappste Rekordmarke, relativ zur Grenze: \(String(format: "%.3f", weakest.ratio)) (\(weakest.description))")
    }

    /// `.tint` glass with an explicit tint over every backdrop sample and sheen, both with the
    /// translucent fill and with the stronger fill of Reduce Transparency.
    private func tintedGlassSamples(
        over backdrop: [(x: Double, y: Double, color: ThrymTestRGB)],
        tint: ThrymTestRGB,
        dark: Bool
    ) -> [ThrymTestRGB] {
        backdrop.flatMap { sample in
            (dark ? [0.20, 0.05, 0.03] : [0.70, 0.30, 0.20]).flatMap { sheenAlpha in
                [dark ? 0.30 : 0.22, dark ? 0.45 : 0.30].map { tintAlpha in
                    glassColor(over: sample, primary: tint, level: .tint, dark: dark, sheenAlpha: sheenAlpha, tintAlpha: tintAlpha)
                }
            }
        }
    }

    private func backdropSamples(primary: ThrymTestRGB, dark: Bool) -> [(x: Double, y: Double, color: ThrymTestRGB)] {
        let base = rgb(hex: dark ? 0x07080C : 0xEEF0F6)
        let strength = dark ? 1.0 : 0.55
        return (0...100).flatMap { yi in
            (0...100).map { xi in
                let x = Double(xi) / 100
                let y = Double(yi) / 100
                var color = base
                color = blob(color, x: x, y: y, centerX: 0.25, centerY: 0.06, radius: 1.0, color: primary, alpha: 0.85 * strength)
                color = blob(color, x: x, y: y, centerX: 0.98, centerY: 0.48, radius: 0.9, color: teal, alpha: 0.5 * strength)
                color = blob(color, x: x, y: y, centerX: 0.12, centerY: 0.96, radius: 0.95, color: violet, alpha: 0.5 * strength)
                if y > 0.55 {
                    color = composite(base, alpha: 0.6 * ((y - 0.55) / 0.45), over: color)
                }
                return (x, y, color)
            }
        }
    }

    private func blob(
        _ background: ThrymTestRGB,
        x: Double,
        y: Double,
        centerX: Double,
        centerY: Double,
        radius: Double,
        color: ThrymTestRGB,
        alpha: Double
    ) -> ThrymTestRGB {
        let distance = hypot(x - centerX, 2 * (y - centerY)) / radius
        guard distance < 1 else { return background }
        return composite(color, alpha: alpha * (1 - distance), over: background)
    }

    private func glassColor(
        over backdrop: (x: Double, y: Double, color: ThrymTestRGB),
        primary: ThrymTestRGB,
        level: ThrymGlassLevel,
        dark: Bool,
        sheenAlpha: Double,
        tintAlpha: Double? = nil
    ) -> ThrymTestRGB {
        var result: ThrymTestRGB
        switch level {
        case .standard:
            result = composite(
                dark ? rgb(hex: 0x10121A) : rgb(hex: 0xFFFFFF),
                alpha: dark ? 0x47 / 255 : 0x59 / 255,
                over: backdrop.color
            )
        case .tint:
            result = composite(primary, alpha: tintAlpha ?? (dark ? 0.30 : 0.22), over: backdrop.color)
        case .strong:
            return backdrop.color
        }

        if dark {
            result = composite(rgb(hex: 0x000000), alpha: 0.84, over: result)
        } else if level == .tint {
            result = composite(rgb(hex: 0xFFFFFF), alpha: 0.50, over: result)
        }

        return composite(rgb(hex: 0xFFFFFF), alpha: sheenAlpha, over: result)
    }

    private func composite(_ foreground: ThrymTestRGB, alpha: Double, over background: ThrymTestRGB) -> ThrymTestRGB {
        ThrymTestRGB(
            red: foreground.red * alpha + background.red * (1 - alpha),
            green: foreground.green * alpha + background.green * (1 - alpha),
            blue: foreground.blue * alpha + background.blue * (1 - alpha)
        )
    }

    private func rgb(_ color: Color) -> ThrymTestRGB {
        let uiColor = UIColor(color)
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        XCTAssertTrue(uiColor.getRed(&red, green: &green, blue: &blue, alpha: &alpha))
        return ThrymTestRGB(red: Double(red), green: Double(green), blue: Double(blue))
    }

    private func rgb(hex: UInt32) -> ThrymTestRGB {
        ThrymTestRGB(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }

    private func contrast(_ foreground: ThrymTestRGB, _ background: ThrymTestRGB) -> Double {
        let first = luminance(foreground)
        let second = luminance(background)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    /// Lowest contrast of `foreground` on backgrounds given by their relative luminance.
    private func worstContrast(_ foreground: ThrymTestRGB, on backgroundLuminances: [Double]) -> Double {
        let first = luminance(foreground)
        return backgroundLuminances.map { second in
            (max(first, second) + 0.05) / (min(first, second) + 0.05)
        }.min() ?? 0
    }

    private func luminance(_ color: ThrymTestRGB) -> Double {
        func linear(_ component: Double) -> Double {
            component <= 0.04045 ? component / 12.92 : pow((component + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
