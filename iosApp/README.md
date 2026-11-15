# Thrym iOS

Dieses Verzeichnis enthält die native SwiftUI-iPhone-App von Thrym (Target und Scheme `ThrymIOS`, Tests `ThrymIOSTests`, Bundle-ID `com.thrym.ios`). Sie nutzt den gemeinsamen Kotlin-Kern `:shared` als statisches Framework `Shared`. Architektur und Funktionsstand: [`docs/architektur.md`](../docs/architektur.md) und [`docs/ios-feature-parity.md`](../docs/ios-feature-parity.md).

## Voraussetzungen

- macOS mit vollständigem Xcode 26.x (die CI nutzt Xcode 26.6). Mit ausschließlich CommandLineTools lässt sich das Projekt erzeugen, aber nicht als iOS-App bauen oder ausführen.
- JDK 17, weil eine Build-Phase Gradle aufruft (siehe unten).
- Python 3 aus macOS für den Projektgenerator.
- Zielplattform iOS 17.0+, Swift 5.10 Language Mode.

Kurzer Check:

```sh
xcode-select -p
xcodebuild -version
xcrun simctl list devices available
/usr/libexec/java_home -v 17
```

## Projekt generieren

`ThrymIOS.xcodeproj` wird von `generate_project.py` erzeugt und ist eingecheckt. Die `project.pbxproj` **nie von Hand ändern**:

```sh
python3 iosApp/generate_project.py
open iosApp/ThrymIOS.xcodeproj
```

Der Generator braucht keine weiteren Werkzeuge, führt keinen Build aus und lädt nichts herunter. Er inventarisiert die Swift-Dateien direkt aus `ThrymIOS/` und `ThrymIOSTests/` und nimmt Ressourcen wie `Localizable.xcstrings`, Fonts und Asset-Kataloge auf. Neue Dateien müssen deshalb nicht in einer zweiten Liste gepflegt werden; nach neuen, umbenannten oder gelöschten Dateien genügt ein erneuter Aufruf. Das erzeugte Projekt wird mit committet, die CI prüft, dass ein erneuter Lauf nichts ändert. Den sichtbaren App-Namen liest der Generator aus `../branding.properties` (`appDisplayName`).

`project.yml` ist eine lesbare Projektbeschreibung im XcodeGen-Format. Der Generator liest sie nicht, sondern führt die Projektwerte (Targets, iOS 17, Swift 5.10, Shared-Framework-Pfad) selbst als Konstanten. Die Bundle-ID kommt über `Config/Signing.xcconfig`. Maßgeblich ist `generate_project.py`; wer Projektoptionen ändert, ändert dort und zieht `project.yml` nach.

Das KMP-Framework entsteht in der Build-Phase `Build Shared Framework` über `scripts/build-shared.sh` (`./gradlew :shared:embedAndSignAppleFrameworkForXcode`). Das Skript nimmt ein gesetztes `JAVA_HOME`, das auf JDK 17 zeigen muss; sonst sucht es über `/usr/libexec/java_home -v 17` und vorhandene Homebrew-Pfade. Es installiert nichts. Am einfachsten vor dem Build setzen:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

`Shared.framework` wird nur verlinkt; für das statische Framework gibt es keine zusätzliche Embed-/Copy-Phase.

## Bauen und testen im Simulator

Simulator-Builds brauchen keine Signierung. Tests wie in der CI:

```sh
python3 iosApp/generate_project.py
xcodebuild test \
  -project iosApp/ThrymIOS.xcodeproj \
  -scheme ThrymIOS \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGNING_ALLOWED=NO
```

Nur bauen:

```sh
xcodebuild build \
  -project iosApp/ThrymIOS.xcodeproj \
  -scheme ThrymIOS \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

Für einen gezielten Testlauf eine Testklasse auswählen, zum Beispiel `-only-testing:ThrymIOSTests/BackupBridgeTests`. Ist kein „iPhone 17“-Simulator installiert, einen anderen aus `xcrun simctl list devices available` angeben.

## Auf einem Gerät ausführen

Die eingecheckte `Config/Signing.xcconfig` setzt `THRYM_BUNDLE_ID = com.thrym.ios` und ein leeres `DEVELOPMENT_TEAM`. Sie bindet optional `Local.xcconfig` ein, die git-ignoriert ist. Für ein eigenes Gerät:

```sh
cp iosApp/Local.xcconfig.example iosApp/Local.xcconfig
```

In `iosApp/Local.xcconfig` dann setzen:

- `DEVELOPMENT_TEAM` = die eigene Team-ID (Xcode → Settings → Accounts). Für das eigene Gerät reicht ein kostenloses Apple-Konto (Personal Team).
- `THRYM_BUNDLE_ID` = eine eigene Bundle-ID, z. B. `com.example.thrym`. `com.thrym.ios` ist für die offizielle App reserviert, und Bundle-IDs sind bei Apple über alle Teams eindeutig.

Danach in Xcode das Gerät wählen und starten. `Config/Signing.xcconfig` nicht für lokale Zwecke ändern; die eigene Team-ID gehört nur in `Local.xcconfig`. TestFlight und App Store: [`TESTFLIGHT.md`](TESTFLIGHT.md).

## Lokalisierung

- Die App gibt es auf Englisch und Deutsch. Es gibt zwei String-Kataloge: `ThrymIOS/Localizable.xcstrings` für die sichtbaren UI-Texte und `ThrymIOS/Exercises.xcstrings` für die Namen der eingebauten Übungen.
- Schlüssel ist der deutsche UI-Text (`sourceLanguage` `de`). Jeder Eintrag braucht eine `en`- und eine `de`-Lokalisierung. Die `developmentRegion` des Projekts ist `en`, damit Englisch der Fallback für alle anderen Systemsprachen ist.
- SwiftUI-Literale werden zur Laufzeit im Katalog nachgeschlagen, String-basierte Beschriftungen und Meldungen nutzen `String(localized:)`. Mengen („1 Training“, „3 Trainings“) sind Plural-Varianten im Katalog.
- Das generierte Projekt extrahiert keine Strings (`SWIFT_EMIT_LOC_STRINGS` ist nicht gesetzt), ein Build trägt neue Literale also nicht in den Katalog ein. Jeder neue oder geänderte UI-Text kommt von Hand mit `de` und `en` in `Localizable.xcstrings`; ein nicht mehr benutzter Schlüssel wird entfernt. `check-translations.py` und die XCTests prüfen nur Schlüssel, die schon im Katalog stehen. Fehlt ein Eintrag, bleibt die CI grün, und die englische Oberfläche zeigt den deutschen Text.
- Fehlermeldungen aus dem gemeinsamen Kern (`SharedStateStore`, `IosTrainingFeature`, `IosSettingsFeature`) sind feste deutsche Texte. `ilSharedMessage` (`ThrymIOS/SharedBootstrap.swift`) schlägt sie als Schlüssel in `Localizable.xcstrings` nach (Einträge mit `extractionState` `manual`). Wer eine solche Meldung in Kotlin neu einführt oder umformuliert, legt im selben PR den passenden manuellen Eintrag mit `de` und `en` an und entfernt den alten Schlüssel. Englische technische Meldungen, etwa aus dem Backup-Validator, haben keinen Eintrag und erscheinen unverändert.
- Gespeicherte Werte bleiben sprachneutral. Enum-Konstanten aus dem gemeinsamen Kern (`BRUST`, `LANGHANTEL` …) werden nur für die Anzeige übersetzt. Eingebaute Übungen kommen mit ihrem deutschen kanonischen Namen aus `DefaultExerciseCatalog` (`:shared`) und werden über `ilExerciseDisplayName` (`ThrymIOS/Models/ExerciseDisplayName.swift`) mit der Tabelle `Exercises.xcstrings` übersetzt angezeigt; eigene Übungen bleiben, wie sie eingegeben wurden. Der Übungseditor bearbeitet den gespeicherten Namen: In der englischen Oberfläche steht im Feld einer eingebauten Übung deshalb der deutsche Name, und ein Hinweis darunter nennt den angezeigten Namen.
- Eine neue eingebaute Übung braucht einen Eintrag mit `de` und `en` in `Exercises.xcstrings` und dieselben Änderungen auf Android (siehe [`CONTRIBUTING.md`](../CONTRIBUTING.md#ui-text-and-translations)). `StringCatalogTests` erwartet genau 79 Einträge und wird mitgezogen.
- Zahlen und Datumsangaben folgen dem Gerätelocale.
- Die Sprache folgt der Systemsprache und lässt sich in den iOS-Einstellungen pro App ändern.
- Vor dem Commit `python3 .github/scripts/check-translations.py --platform ios` ausführen.
- Feature- und UI-Tests lesen erwartete Texte aus dem String-Katalog, statt deutsche oder englische Literale zu vergleichen. Nur die Katalog- und Bundle-Tests (`StringCatalogTests`, `LocalizationTests`, `ExerciseDisplayNameTests`) prüfen einige Beispielübersetzungen mit Literalen gegen ein explizit geladenes `en`- bzw. `de`-Bundle.

## CI

`.github/workflows/ios-ci.yml` (Job `simulator-build-test`) läuft auf `macos-26` mit Xcode 26.6:

1. `python3 .github/scripts/check-translations.py --platform ios` prüft, dass jeder Eintrag in beiden Katalogen `de` und `en` hat und die Formatplatzhalter passen. Texte, die im Katalog fehlen, findet die Prüfung nicht (siehe Lokalisierung).
2. Der Generator erzeugt das Projekt neu; ändert sich dabei etwas am eingecheckten Projekt, schlägt der Job fehl.
3. `./gradlew :shared:iosSimulatorArm64Test --tests 'com.thrym.shared.backup.IronLogBackupFixtureImportTest'` lässt die Golden-Fixture-Tests für den Import alter IronLog-Backups unter Kotlin/Native im Simulator laufen. Die iOS-App führt diesen Code nativ aus; `:shared:testAndroidHostTest` in der Android-CI prüft nur die JVM.
4. `xcodebuild test` läuft im iPhone-17-Simulator ohne Signierung, mit JDK 17 für die Gradle-Build-Phase.
5. Ein Zero-Tests-Guard wertet das `xcresult`-Bundle aus und schlägt fehl, wenn kein Test lief. Das Bundle wird als Artefakt `ios-test-results` hochgeladen.

Signierte Archive baut die CI nicht.

## Trainingsbedienung

- Ohne geladene Trainingsdaten zeigt die App einen Ladeindikator oder den konkreten Fehler mit „Erneut versuchen“ und dem Weg zur Backup-Wiederherstellung. Der erneute Versuch liest die Shared-Projektion neu. Nach einem fehlgeschlagenen Datei-Start kann die aktuelle Shared-Schnittstelle die Datei nicht erneut öffnen; dafür bleibt die Backup-Wiederherstellung verfügbar.
- Die Hauptaktion auf der Startseite startet den Rotationsvorschlag direkt, ersatzweise den ersten Plan oder ein freies Training. „Anderer Plan“ öffnet die Trainingsauswahl, „Freies Training“ steht daneben; „Teilplan überspringen“ liegt im Menü „Mehr“.
- Nach dem Löschen eines Satzes im aktiven Training erscheint für acht Sekunden „Rückgängig“. Der zuletzt gelöschte Satz wird mit seiner Trainingsabsicht über `set.add` wiederhergestellt. Beim Sessionwechsel verfällt das Angebot.
- Übungsleiste und Satzmenüs haben mindestens 44 pt große Touchflächen; die Übungsleiste wächst mit der Schriftgröße. Sichtbare App-Texte verwenden „Training“.
