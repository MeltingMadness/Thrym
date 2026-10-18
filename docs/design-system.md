# Design-System

Stand des Codes in `core/designsystem` (Android) und `iosApp/ThrymIOS/Design` (iOS). Es gibt zwei Darstellungen, wählbar unter Einstellungen → Darstellung: **Liquid Glass** (Standard) und **Ember**. Beide nutzen dieselben Farbschemata, Typografie und Tokens; Liquid Glass legt darüber einen farbigen Hintergrund und Glasflächen. Die Designkonzepte Forge, Raster, Tide und Pulse sind als zusätzliche Farbschemata eingeflossen. Diese Datei beschreibt, was **jetzt** gilt, nicht den Weg dorthin.

## Farbschemata

In den Einstellungen wählbar (`ThemeScheme`). Dazu kommen Hell, Dunkel oder System (`ThemeMode`, Standard: **Dunkel**) und optional Dynamic Color ab Android 12.

| Schema | Primär (hell / dunkel) | Sekundär | Tertiär | Flächen |
|---|---|---|---|---|
| **Amber** (Standard) | `#FF6B00` / `#F58B20` | Teal `#0D9488` | Violett `#7C3AED` | hell: Creme `#FFF8F0`, dunkel: Blauschwarz `#0E131A` |
| **Deep Cyan** | `#007A85` / `#4DD0E1` | Blaugrau | Pink | neutral-kühl |
| **Neon Red** | `#D50000` / `#FF5252` | Grau | Gelb | neutral-kühl |
| **Forge** | Molten Orange `#FF7A1A` | Anthrazit `#2E2E34` | Grün `#3DFF88` | Industrial, dunkel fast Schwarz `#060606` |
| **Raster** | Kobalt `#3B82F6` | Slate `#64748B` | Smaragd `#10B981` | Swiss/Bento, dunkel `#08090A` |
| **Tide** | Mint `#00F5A0` | Cyan `#06B6D4` | Indigo `#818CF8` | Recovery, dunkel Grünschwarz `#040908` |
| **Pulse** | Rose `#FF3366` | Violett `#A855F7` | Cyan `#06B6D4` | dunkel `#0B080C` |

Die Farben liegen in `theme/Color.kt`, die Zuordnung zu Material-3-Schemata in `theme/Theme.kt` (`ThrymTheme`). iOS bildet dieselben Schemata in `Design/ThrymTheme.swift` ab. Die Systemleisten passen sich dem gewählten Design an.

Auf Android liefert `MaterialTheme.accentText` für jedes feste Schema und jeden Modus eine eigene, auf Glas lesbare Akzent-Textfarbe. Ungefüllte Text- und Outline-Buttons sowie unselektierte FilterChips verwenden sie über `ThrymInteractiveColors`; selektierte, gefüllte Chips verwenden die zum Container gehörige Gegenfarbe. `primary` bleibt für Flächen, Ringe, Balken und Icons; Buttontext auf `primary` verwendet `onPrimary`. `onSurfaceVariant` ist in allen Schemata ein neutrales Grau (`#44484F` hell, `#BCC2CC` dunkel), damit Nebentext unabhängig vom Akzent lesbar bleibt. Bei dynamischen Systemfarben fällt `accentText` auf `onSurface` zurück.

Statusbeschriftungen verwenden `MaterialTheme.semanticText` (Erfolg, Gefahr, Warnung, Rose, Himmelblau, Violett, Türkis) mit dunkler Tinte in Hell und heller Tinte in Dunkel. Die bisherigen `semantic`-Farben bleiben für Ringe, Balken, Icons, Rahmen und getönte Flächen. `LiquidGlassContrastTest` prüft die Textrollen gegen alle Glasstufen und Farbschemata mit mindestens 4,5:1.

## Semantische Farben

Über `MaterialTheme.semantic` (`EmberSemanticColors`, definiert in `theme/DesignTokens.kt`, bereitgestellt in `theme/ThemeTokens.kt`). In Hell sind sie in allen Schemata gleich. In Dunkel nutzen Amber, Deep Cyan und Neon Red die Standardwerte; Forge, Raster, Tide und Pulse übernehmen einzelne Rollen aus ihrer eigenen Palette (Zuordnung in `theme/Theme.kt`). Die Rollen:

- `success` Grün, `warning` Amber, `danger` Rot
- Akzente: `rose`, `sky`, `violet`, `teal` (jeweils mit hellerer Variante)

Beispiel **RPE-Badge** im aktiven Workout: bis 7 grün, bis 8 amber, bis 9 rose, 10 rot.

## Typografie

Eine Schriftfamilie auf beiden Plattformen: **Geist** von Vercel (SIL Open Font License, Lizenztext in `docs/licenses/Geist-OFL.txt`) in sieben statischen Schnitten: sechs Gewichte von Regular bis Black (Regular, Medium, SemiBold, Bold, ExtraBold, Black) plus Italic. Android bindet sie in `theme/Type.kt` ein (`res/font/geist_*.ttf`). Die Skala folgt dem Material-Standard: Fließtext 14 bis 16 sp, Labels mindestens 11 sp, alle Stile mit Tabellenziffern (`tnum`). iOS registriert dieselben Dateien über `UIAppFonts` in `Info.plist`; `Design/ThrymFont.swift` liefert `Font.geist(_:weight:)` für die Textstile und `Font.geist(size:weight:relativeTo:)` für feste Größen, beide skalieren mit Dynamic Type. Lesetext ist mindestens 12 pt groß. SF Symbols behalten die Systemschrift.

Für Kennzahlen gibt es zusätzlich `AthleticHero`, `AthleticNumber` (beide mit Tabellenziffern `tnum`) und `AthleticLabel`, genutzt auf dem Dashboard und im aktiven Workout.

## Tokens

In `theme/DesignTokens.kt` und `theme/ThemeTokens.kt`:

- `Radius` (xs 6 dp … xxl 28 dp, `pill`), `IconSize`, `ButtonSize`
- `MaterialTheme.thrymDimens`, `.thrymMotion`, `.thrymSurfaceRoles`
- Wer „reduzierte Animationen“ einschaltet, bekommt kürzere bzw. keine Bewegungen (`thrymMotion`).

Neue Abstände und Größen gehören in die Tokens, nicht als feste `dp`-Werte in die Screens.

## Oberflächen

- `Modifier.glassmorphism()` (`theme/Glassmorphism.kt`): halbtransparente Karte mit Tönung in der Primärfarbe des aktiven Schemas und Glanzkante. Dazu `Modifier.glow()`.
- `ThrymSurfaceCard` mit den Tönen `ELEVATED`, `MUTED`, `ACCENT`, `COLORED`
- `StatCard` mit den Varianten `PRIMARY`, `SECONDARY`, `TERTIARY`. Kacheln einer Gruppe nutzen dieselbe Variante und gleiche Höhe (Übungsstatistik: Standardvariante in `IntrinsicSize.Min`-Zeilen).
- `ThrymScreenScaffold`: gemeinsames Gerüst für alle Screens

## Liquid Glass

Standard-Darstellung (`AppearanceStyle.LIQUID_GLASS`, iOS `ThrymAppearance.liquidGlass`). Wer Ember ausdrücklich gewählt hat, behält es; Ember-Screens bleiben unverändert erhalten. Die Umschaltung liegt je Screen in einer eigenen Glas-Variante (`*Glass.kt` bzw. `*Glass.swift`), Logik und gespeicherte Daten sind in beiden Darstellungen gleich.

- Glasstufen: `GlassLevel.STANDARD`, `STRONG`, `TINT` (Android, `theme/LiquidGlass.kt`, `Modifier.liquidGlass`) bzw. `ThrymGlassLevel` (iOS, `Design/ThrymLiquidGlass.swift`, `.liquidGlass(_:in:)`).
- Nur `STRONG` weichzeichnet den Hintergrund: Android über Haze ab Android 12, iOS über `.ultraThinMaterial`. Darunter, bei reduzierten Animationen und bei „Transparenz reduzieren“ (iOS) gibt es eine fast deckende Tönung.
- Der farbige Hintergrund (`LiquidBackground` / `ThrymLiquidBackground`) besteht aus radialen Verläufen in der Akzentfarbe, Türkis und Violett und wird einmal hinter der App gezeichnet (Android: `LiquidGlassHost` in `MainActivity`).
- `ThrymSurfaceCard` und `Modifier.glassmorphism()` (Android) sowie `ThrymCard` (iOS, `Design/ThrymCard.swift`) schalten automatisch um; Ember-Karten bleiben unverändert.
- Navigation: Android zeigt statt der `NavigationBar` eine schwebende Glas-Pille (`BottomNavBar`, Stufe `STRONG`); der aktive Tab ist ein heller Chip mit Icon und Text, die übrigen Tabs zeigen nur das Icon. iOS behält die System-Tableiste, auf iOS 26 ist sie selbst Liquid Glass.
- Startseite: Kopf mit Datum, Begrüßung und rundem Glasknopf (Android Einstellungen, iOS Statistiken) statt der Titelleiste. Die Hauptkarte (`GlassCommandCenterCard` / `IOSDashboardGlassCommandCenter`, Stufe `STRONG`) zeigt den vorgeschlagenen Plan mit den ersten drei Übungen samt Ziel („3 × 8 · 80 kg“) und einen Start-Knopf als helle Pille mit farbigem Play-Kreis. Die Wochenleiste (`GlassWeekStrip` / `IOSDashboardGlassWeekStrip`) ersetzt die Wochen- und Monatskachel: je Tag eine Linse (trainiert, heute, offen), dazu Trainings und Volumen der Woche. Die übrigen Karten werden zu Glas (Android über `DashboardPanel`); Inhalt und Verhalten bleiben gleich.
- Training: Kopf mit Plan und „Übung x von n“, der Trainingszeit als große Zahl, Sätzen und Volumen sowie „Beenden“ als Glaspille (iOS zusätzlich ein Menü für Notiz und Verwerfen). Darunter eine Übungsleiste (erledigt, aktuell, kommend); ein Tipp scrollt zur Übung. Die offene Übung liegt auf Glasstufe `STRONG`. Die Satzeingabe zeigt Gewicht und Wiederholungen als große Zahlen mit runden −/+-Knöpfen (2,5 kg bzw. 5 lb, 1 Wdh), RPE/RIR als Auswahlleiste und „Satz loggen“ als helle Pille (`WorkoutGlass.kt` / `IOSWorkoutGlass.swift`). Eingabelogik und gespeicherte Werte sind dieselben wie in Ember. Android zeigt im Kopf der aufgeklappten Übung Pfeile zum Verschieben nach oben und unten, bei einem Supersatz im Supersatz-Kopf für den ganzen Block; das gilt in Ember wie in Liquid Glass. Am Rand der Liste ist der Pfeil deaktiviert und gedimmt (Inhaltsfarbe des `IconButton`, kein eigenes Tint). Der verschobene Block bleibt an seiner Stelle auf dem Bildschirm, die Nachbarn wechseln um ihn herum; nur an den Enden der Liste kann er wandern, weil davor bzw. dahinter nichts mehr steht. Bei reduzierten Animationen springt die Karte ohne Gleitbewegung an ihren neuen Platz.
- Pause: Android zeigt im schwebenden Glas-Dock jeden laufenden Countdown als eigene Zeile mit Mini-Ring und „Überspringen“, sortiert nach kürzester Restzeit. Von hochzählenden Timern erscheint nur der neueste als letzte Zeile. Das Dock deckt höchstens die halbe Übungsliste ab und zeigt daher höchstens drei Zeilen gleichzeitig, auf niedrigen Ansichten wie im Querformat weniger; weitere lassen sich im Dock scrollen. Die Vollbild-Pause öffnet sich von selbst nur beim Start eines Countdowns, während kein weiterer Timer läuft. Ein Tipp auf eine Dock-Zeile öffnet deren Pause. „Zum Training“ schließt sie, der Timer läuft weiter; endet oder verschwindet dieser Timer, schließt sich seine Pause. Die Vollbild-Linse zeigt einen mit der Restzeit sinkenden Wasserstand, „−15 s“/„+30 s“, „Als Nächstes“ und „Pause überspringen“ (`WorkoutPauseGlass.kt`). iOS zeigt bis zur Portierung weiterhin nur den neuesten Timer (`IOSWorkoutGlass.swift`). −15/+30 verschieben das Ende des Countdowns (`RestTimerUi.adjustedBy`, `IOSWorkoutRestTimer.adjusted(by:)`); verkürzt endet die Pause spätestens sofort. Am Pausenende gibt es wie in Ember eine Haptik, und der Timer verschwindet.
- Abschluss: Datum und Plan, „Geschafft.“ (bzw. „Teiltraining gespeichert“), drei Glas-Linsen für Dauer, Volumen und Übungen, neue Rekorde auf getöntem Glas (`TINT`), die geloggten Sätze je Übung als Glaskarte und „Fertig“ als helle Pille (`WorkoutCompletionGlass.kt` / `IOSWorkoutCompletionScreen.glassBody`). Die Folgeaktionen (Plan anpassen, Progression) sind dieselben wie in Ember.
- Verlauf: großer Titel und Suche als Glaspille statt Titelleiste; Filter bleiben. Je Woche (Montag bis Sonntag) eine Glaskarte mit „Diese Woche“, „Letzte Woche“ oder Kalenderwoche, der Zahl der Trainings, Zeitraum und Gesamtzeit sowie Mini-Balken Mo–So (Höhe = Trainingsminuten des Tages). Die Balken zählen alle Trainings der Woche, auch wenn ein Filter einzelne ausblendet. Darunter die Trainings als Glaszeilen mit Datums-Linse; Wischen zum Löschen bleibt. Android fügt die Wochenkarten als Trennelemente ins Paging ein (`HistoryListEntry`, `historyWeekSeparator`, `summarizeHistoryWeeks` in `HistoryWeeks.kt`), iOS gruppiert über `IOSHistoryWeek.group` (`IOSHistoryGlass.swift`).
- Übungsstatistik: eigener Kopf statt Titelleiste, darunter eine Glaskarte mit dem letzten Wert der gewählten Kennzahl, der Veränderung als getöntem Chip, einer Verlaufskurve und einer Auswahlleiste für Gewicht, e1RM und Volumen. Die Rekorde stehen als 2×2-Kacheln darunter (`ExerciseStatsGlass.kt` / `IOSExerciseStatisticsGlass.swift`); die übrigen Karten bleiben wie in Ember, nur auf Glas.
- Übrige Screens (Pläne, Plan-Editor, Meta-Pläne, Übungen, Einstellungen, Trainingsdetails, Progression): Android nutzt `ThrymTopBar` (`common/ThrymTopBar.kt`); in Liquid Glass hat sie einen größeren Titel, der Zurück-Knopf ist ein runder Glasknopf und die Aktionen liegen gemeinsam in einer Glaspille. Plankarten zeigen „Training starten“ als helle Pille mit farbigem Play-Kreis. In der Übungsbibliothek ist jede Übung eine eigene Glaszeile; die Suche ist dort wie im Verlauf eine Glaspille (`GlassSearchField` in `common/GlassSearchField.kt`). iOS legt die Zeilen aller Listen und Formulare über `.thrymListRows()` auf durchscheinendes Glas; auch aufgerufene Screens und Sheets setzen `.thrymScreenBackground()`.
- Sheets, Dialoge und Menüs (Android) nutzen in Liquid Glass die neutralen Glastöne des Themes (`withLiquidGlassContainers` in `theme/Theme.kt`). Ausgewählte Chips, Segmente und Tonal-Buttons sind dort weiß auf dunklem bzw. fast schwarz auf hellem Glas. Lade-Platzhalter nutzen eine schwache neutrale Tönung statt der Ember-Fläche.
- Hell: Liquid Glass folgt der Einstellung Hell/Dunkel/System. Auf hellem Grund ist das Glas Milchglas, aktive Elemente (Tab, Start-Knopf, Auswahl) sind dunkel.
- iOS-Screens setzen ihren Hintergrund über `.thrymScreenBackground(ember:emberNavigationBar:)` innerhalb des obersten `NavigationStack`. In Liquid Glass blendet der Modifier die System-Hintergründe von Listen und Formularen aus, zeichnet `ThrymLiquidBackground` und macht Navigations- und Tableiste zu Material; in Ember bleiben die bisherigen Farben.

## Zahlen und Texte

Gilt für Android und iOS gleich:

- Zahlen und Datumsangaben folgen dem Gerätelocale: auf Deutsch „82,5 kg“ und „12.305 kg“, auf Englisch „82.5 kg“ und „12,305 kg“. Nie Rohwerte wie „80.0“ anzeigen und keine festen `Locale.GERMANY`- bzw. `de_DE`-Formatierer verwenden. Android: `WeightFormatting` in `core:common` (Eingabefelder über `formatInputNumber`: ASCII-Ziffern ohne Tausendertrennzeichen, nur das Dezimalzeichen folgt der Sprache; eingelesen wird über `parseInputNumber`). iOS: `iosWorkoutDecimal`, `IOSNumber`, `ilWeightText`. Gespeichert wird weiter in kg; lb ist nur Anzeige.
- Sätze einheitlich als Gewicht × Wiederholungen, auf Deutsch „82,5 kg × 8 Wdh“. 0 kg wird als „Körpergewicht“ angezeigt.
- Mengen mit passender Einzahl/Mehrzahl („1 Training“, „3 Trainings“) in beiden Sprachen. Android: `plurals`-Ressourcen in `values/` und `values-de/`. iOS: Plural-Varianten im String-Katalog.
- Deutsche Texte mit echten Umlauten, keine zusätzlichen Häkchen oder Pfeile in Button-Texten. Unbekannte Werte (z. B. Satzabsicht „Nicht angegeben“) werden nicht als Daten angezeigt.
- Enum-Werte und eingebaute Übungsnamen werden für die Anzeige übersetzt, nie im gespeicherten Wert (siehe [`architektur.md`](architektur.md), Abschnitt Lokalisierung).
- Links unter Kartentext stehen bündig mit dem Text (`TextButton` um die Innenpolsterung versetzt).

## Gemeinsame Komponenten (`presentation/common`)

`SetInputRow`, `CompactTextField`, `ThrymTextField`, `RestTimer`, `WorkoutTimer`, `PlateVisualizer`, `WeeklyMuscleVolumeCard`, `EmptyStateScreen`, `LoadingScreen`, `ShimmerSkeleton`, `SharedTransition`, `HapticFeedback`, `ErrorUiMapper`.

**Scheibenfarben:** `PlateColors` in `theme/Color.kt` folgt der üblichen Kodierung (25 kg rot, 20 kg blau, 15 kg gelb, 10 kg grün, 5 kg weiß, kleinere Scheiben grau/silber).

## App-Hülle

- XML-Themes in `app/src/main/res/values/themes.xml`: `Theme.Thrym` für die App, `Theme.Thrym.Splash` für den Start.
- Splash-Screen (`Theme.Thrym.Splash`, Parent `Theme.SplashScreen.IconBackground`): das Zeichen aus `ic_launcher_foreground` auf einem Amber-Kreis `#FF6B00` vor `#0C0806`. Er bleibt sichtbar, bis die Design-Einstellungen geladen sind. So gibt es keinen weißen Blitz.
- App-Icon „Gestufter Dorn“ (englisch „Stepped Thorn“): die Rune Thurisaz ᚦ („thurs“ heißt Riese, Þ ist der erste Buchstabe von Þrymr), gebaut aus dem Ende einer beladenen Langhantel in Seitenansicht. Eine hohe Bumper-Scheibe bildet den Stab, zwei kleinere Scheiben stufen sich als Dorn auf der Stahlhülse ab, die Hülsenspitze ist die Spitze. Die abgestuften Scheiben stehen für Progression.
  - Farben: Amber-Verlauf `#FF8A2A` → `#E85500` mit weicher Schmiedeglut (`#FFB15C`), Scheiben in Eisen `#2B1A10` → `#140B06`, Hülse in Stahl mit hellem Frostglanz. Bei 48 px liegt der Kontrast Eisen zu Amber bei 5,6–7,1 : 1.
  - Android: adaptives Icon aus drei VectorDrawables, `app/src/main/res/drawable/ic_launcher_background.xml` (Amber und Glut), `ic_launcher_foreground.xml` (Zeichen im sicheren Kreis von 66 dp) und `ic_launcher_monochrome.xml` (Themed Icon ab Android 13), eingebunden über `mipmap-anydpi-v26/ic_launcher.xml` und `ic_launcher_round.xml`. Der Splash zeigt die Vordergrundebene (siehe oben).
  - iOS: `iosApp/ThrymIOS/Assets.xcassets/AppIcon.appiconset` mit drei 1024-px-PNGs: `AppIcon.png` (Standard, opak), ab iOS 18 außerdem `AppIcon-Dark.png` (Dunkel: Amber-Scheiben und heller Stahl auf transparentem Grund, iOS legt den dunklen Hintergrund darunter) und `AppIcon-Tinted.png` (Getönt: Graustufen auf Schwarz).
  - Quellen: die SVGs in [`docs/brand/`](brand/) (Icon, Hintergrund, Zeichen, Monochrom, iOS-Varianten, Schriftzug-Lockups, Ebenen für Icon Composer). Die dortige [`README.md`](brand/README.md) beschreibt, wo welche Datei verwendet wird und wie die PNGs neu erzeugt werden. Wer das Icon ändert, ändert beide Plattformen, das Store-Icon `fastlane/metadata/android/en-US/images/icon.png` und `docs/brand` im selben PR.
  - Markenregeln: Der Schriftzug steht in lateinischen Buchstaben, der Name wird nie in Runen geschrieben, das Zeichen steht nie in einem Kreis- oder Ringrahmen („Rune im Kreis“), keine Schwarz-Weiß-Rot-Paletten, Amber bleibt die Markenfarbe. Details und Mindestgrößen in `docs/brand/README.md`.
  - Der Code steht unter Apache-2.0; Name, Logo und App-Icon sind dagegen nicht für Forks lizenziert (siehe `README.md` und `NOTICE`). Ein Fork braucht einen eigenen Namen und ein eigenes Icon.

## Texte

UI-Texte stehen nie als Literal in Composables oder Views:

- **Android:** `core/designsystem/src/main/res/values/` (Englisch) und `values-de/` (Deutsch) für gemeinsame Texte, sonst `res/values/*_strings.xml` und `res/values-de/*_strings.xml` des Feature-Moduls. Jeder String steht in beiden Sprachen; Lint (`MissingTranslation`) und `.github/scripts/check-translations.py` prüfen das. Sprachneutrale Strings nur in `values/` mit `translatable="false"`.
- **iOS:** String-Kataloge (`*.xcstrings`) mit dem deutschen Text als Schlüssel und Lokalisierungen für `en` und `de`: `iosApp/ThrymIOS/Localizable.xcstrings` für UI-Texte, `iosApp/ThrymIOS/Exercises.xcstrings` für die Namen der eingebauten Übungen; ebenfalls von `check-translations.py` geprüft. Neue Texte werden von Hand eingetragen, weil das generierte Projekt keine Strings extrahiert.
