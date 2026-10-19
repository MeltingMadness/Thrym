# Thrym iOS: Funktionsstand und Abnahme

Android und iOS sind gleichberechtigte Plattformen; die fachlichen Regeln liegen gemeinsam in `:shared`. Diese Liste unterscheidet implementierte Funktionen von tatsächlich geprüften Abläufen; sie ist keine Releasefreigabe. Die Abnahme in der Tabelle stammt vom 9. September 2026 (damals noch unter dem Namen IronLog).

## Aktueller Stand

Die native SwiftUI-App baut mit Xcode 26.6 und läuft im iPhone-17-Simulator mit iOS 26.5. Dashboard, Training, Verlauf, Pläne und Einstellungen sind echte Screens. Statistik, Progressionsreview, Übungsbibliothek und Editoren sind angebunden. Die früheren Swift-Compiler- und Simulator-Installationsblocker sind behoben.

Der gemeinsame Kotlin-Kern besitzt den kanonischen Trainingsgraphen, Progressionsregeln, Bestleistungen und Auswertungen. iOS speichert den validierten Graphen atomar unter `Application Support/Thrym/training-state.json`. Änderungen werden erst nach erfolgreicher Speicherung veröffentlicht. Einstellungen liegen in NSUserDefaults. Die ältere Repository-Abstraktion ist nicht der direkte UI-Zugriff: Swift verwendet `IosTrainingFeature` und `IosSettingsFeature` als Plattformbrücken zum gemeinsamen Kern.

Die Oberfläche gibt es wie auf Android auf Englisch und Deutsch (String-Kataloge `Localizable.xcstrings` für UI-Texte und `Exercises.xcstrings` für eingebaute Übungen, Englisch als Fallback). Eingebaute Übungsnamen und Enum-Werte werden nur für die Anzeige übersetzt; Backups bleiben mit Android und mit alten IronLog-Backups kompatibel. Das App-Icon „Gestufter Dorn“ ist als opakes iOS-AppIcon eingebunden, ab iOS 18 zusätzlich in den Varianten Dunkel und Getönt (siehe `docs/brand/README.md`); native Bedienelemente übernehmen den gewählten Farbakzent. Der unter iOS wirkungslose Android-Schalter „Dynamische Farben“ wird nicht angeboten.

## Bereits nachgewiesen

| Ablauf | Evidenz | Grenze |
|---|---|---|
| Native App starten und navigieren | Erfolgreicher Xcode-Build, Installation und Start im Simulator; Dashboard, Training, Pläne, Verlauf, Settings und Review geöffnet | Letzter integrierter Build: 21:42 UTC; kein Test auf physischem iPhone |
| Individuelle Gewichtsschritte | Plan mit Kurzhantel-Bankdrücken, 3 × 10 @ 7 kg und linearer Steigerung um 1,5 kg im Simulator erstellt | Weitere Schemen durch gemeinsame Regeltests, nicht alle einzeln durch UI ausgeführt |
| Training und Progression | Drei Sätze geloggt, Training beendet, Vorschlag 8,5 kg explizit angenommen; persistierter Graph enthält Planziel 8,5 kg und unveränderte Quelle 7 kg | Atomare Sammelannahme durch gezielte Lifecycle-Tests geprüft; nicht jede Review-Variante zusätzlich durch die UI ausgeführt |
| Automatische Pause ohne feste Dauer | Count-up nach Arbeitssatz gestartet; nach letztem Plansatz kein neuer Timer | Fester Countdown inklusive Wiederherstellung nach App-Neustart und Stop nach letztem Deload-Satz ebenfalls im Simulator geprüft; physische Haptik offen |
| Verlauf und Bestleistungen | Einheit, drei Sätze, Volumen 210 kg und e1RM 9,3 kg dargestellt; Notiz gespeichert und wieder angezeigt | Abgeschlossenen Satz 10→11 Wiederholungen geändert und anschließend gelöscht: PRs und Volumen korrekt neu berechnet; akzeptierter Vorschlag und Plangewicht bleiben erhalten |
| Native Unit-Konvertierung | Zwei iOS-XCTest-Fälle bestanden: 1,5 kg → METRIC; 2,5 lb → IMPERIAL mit korrektem kanonischem kg-Wert | Keine vollständige UI-Abnahme beider Einheitensysteme |
| Gemeinsame Regeln | Gezielt grüne Progressions-, Lifecycle-, Store-, Platten- und Analytics-Tests; Android-Progressionstests gegen gemeinsamen Kern grün | Android `:app:assembleDebug` nach Shared-Integration erfolgreich; keine vollständige Regression |
| Deload und historische Wochen | Fokussierte gemeinsame Tests für Satzhalbierung, 15-%-Reduktion, Quellschutz, lokale Wochengrenzen und Zukunftsbegrenzung grün | Im Simulator bestätigt: 3→2 Satzvorgabe, Aktivierung/Beenden, vorige/leere Woche und Volumenaktualisierung nach Satzlöschung |
| Backup-Vorbereitung | Export öffnet nativen iOS-Dateidialog; gemeinsamer Store-Roundtrip, beschädigte Datei, atomarer Schreibfehler und veraltete Importvorschau gezielt getestet | Zwei native BackupBridgeTests und ein RecoveryBridgeTest grün: Export/Preview, korruptes JSON ohne Mutation, bestätigter Import und Rückkehr zum vollständigen Ausgangsgraphen. Abschluss im nativen Dateidialog wegen gesperrtem Mac noch nicht bestätigt |
| Superset und parallele Pausen | Zwei Übungen als zusammenhängender Superset sichtbar; zwei unabhängige Countdown-Timer geloggt, nach App-Neustart wiederhergestellt, einer beendet ohne den anderen zu stoppen | Drei native Persistenztests zusätzlich grün (Migration, mehrere Zeilen, Deadline) |
| RPE-Hilfe im Training | RPE 9 erzeugt Next-Set-/Backoff-Hinweis; Übernahme öffnet den Editor mit 9 kg statt 10 kg | Gemeinsame Berechnung und Android-Adapter mit 15 gezielten Tests grün; kein physischer Haptiktest |
| Meta-Plan-Rotation | Zwei Pläne erstellt, Reihenfolge gespeichert, Teilplan explizit übersprungen, nächster Plan gestartet und verworfen; später abgeschlossen und Rotation weitergeschaltet | Ausschließlich Testdaten im Simulator |
| Übungsstatistiken | 1RM-Entwicklung 9,3→11,3 kg (+21,4 %), letzter/vorheriger Trainingsvergleich und fünf aktuelle Arbeitssätze im Simulator geprüft | Keine künstlichen Nullwerte bei fehlenden Vergleichen |
| Bereitschaft und große Schrift | Bei ausreichenden Daten wieder Prozentkreis (100 %); bei fehlenden Daten weiterhin unbekannt. Dashboard bei größter Accessibility-Schrift gesichtet, Ring/Erklärung untereinander | Kein vollständiger VoiceOver-Audit; Standard-Schriftgröße danach wiederhergestellt |

Die Backup-Bestätigung hält die tatsächlich geprüften Bytes und den lokalen Ausgangsgraphen fest. Der Import liest die Datei nicht erneut und prüft den Ausgangsgraphen unter demselben Mutex wie den atomaren Schreibvorgang. Damit ist die bestätigte Vorschau gegen Dateiaustausch und zwischenzeitliche Trainingsänderungen gebunden; ein separater SHA-256-Anzeigewert ist dafür nicht erforderlich.

## Implementiert, noch gezielt abzunehmen

- Verlauf mit Plan-/Datumsfilter; Editieren/Löschen und daraus folgende Bestleistungen sind bereits im Simulator bestätigt.
- Sammelannahme sicherer Progressionsvorschläge, bearbeitete Annahme, Ablehnung und veraltete Vorschläge.
- Eigene Übungen erstellen, ändern und archivieren; Editor unterstützt die zehn kanonischen Muskelgruppen und bis zu drei sekundäre Muskelgruppen. Plan-/Meta-Plan-Reihenfolge, Supersets, Rotation und Überspringen sind bereits im Simulator bestätigt.
- Drop-/Failure-Sätze und weitere Hintergrundabläufe; Warmup, laufendes Training nach Neustart und fester Countdown sind bestätigt.
- Übungen und Supersätze im laufenden Training mit Pfeilen verschieben, auch Zusatzübungen. Die Reihenfolge liegt pro Training in UserDefaults (`thrym.workout.exerciseOrder`, an Session-ID und Startzeit gebunden), nicht im Trainingsgraphen und nicht im Backup; die Regeln stehen in `:shared` (`SessionExerciseOrder`). Beim Beenden wird die angezeigte Reihenfolge für den Abschlussbildschirm gesichert, auch ohne vorheriges Verschieben. Startet ein neues Training, ist der Abschlussbildschirm des vorigen erledigt und kommt auch nach dem Verwerfen des neuen nicht zurück. Bei reduzierter Bewegung wechseln die Karten ohne Animation. Am 18. Oktober 2026 im Simulator bestätigt: hoch und runter, Superset als Block, Zusatzübung über geplanten Übungen, Neustart der App, Pausen-Dock und Pause, Abschlussdialog, Liquid Glass und Ember, Löschen beim Schließen des Abschlussbildschirms und beim Verwerfen. Der Accessibility-Baum (eigene Pfeil-Buttons mit Label und deaktiviertem Zustand, Superset-Zusammenfassung als ein Element) ist geprüft. Offen: Durchlauf mit echtem VoiceOver und auf einem physischen Gerät.
- Trainingsende und Rekorde wie auf Android: Karte „Alle n geplanten Sätze geschafft“ mit „Training beenden“ am Listenende, sobald alle geplanten Sätze geloggt sind, mit einmaligem Scrollen nach dem Satz, der den Plan vollmacht (bei reduzierter Bewegung ohne Animation); Rekordtafel mit Wert und Zuwachs im Abschluss, jetzt auch in Ember; Rekordbanner im Training (auch über der Vollbild-Pause) mit Haptik und VoiceOver-Ansage. Die Regeln stehen in `:shared` (`SessionRecords`), die Farben in `ThrymSemanticInk`. XCTests und Kontrasttests grün, Darstellung in Ember und Liquid Glass, hell und dunkel, als gerenderte Bilder geprüft. Offen: Scrollen, Banner über der Pause und Haptik im laufenden Simulator bzw. auf einem Gerät, VoiceOver und Dynamic Type.
- Onboarding beim ersten Start (`IOSOnboardingView`, Details in [`features/onboarding.md`](features/onboarding.md)): Schritte, Einheitenwahl und Überspringen; Backup-Import aus dem letzten Schritt öffnet den Einstellungen-Tab mit Dateidialog. `OnboardingTests` decken die Entscheidung ab, wann es erscheint. Offen: Durchlauf im Simulator, VoiceOver und Dynamic Type.
- Nativen Backup-Dateidialog bis zum Speichern/Importieren abschließen und Reset über die UI. Vorschau, bestätigter Import, Wiederherstellung und Ablehnung beschädigter Daten sind über native Brückentests geprüft.
- Einstellungen nach Neustart; Reminder-Berechtigung und tatsächliche Zustellung; Diagnoseexport.
- Vollständiger VoiceOver-/Kontrast-/Reduced-Motion-Audit und weitere Displaygrößen. Dashboard mit normaler und größter Schrift gezielt geprüft.

## Reproduzierbare Validierung

Das Projekt wird mit `python3 iosApp/generate_project.py` erzeugt; der Xcode-Build integriert das KMP-Framework über `iosApp/scripts/build-shared.sh`. Die iOS-CI (`.github/workflows/ios-ci.yml`, `macos-26` mit Xcode 26.6) prüft bei jedem Pull Request, dass der Generator das eingecheckte Projekt nicht verändert, und führt die XCTest-Suite im Simulator aus:

```sh
python3 iosApp/generate_project.py
xcodebuild test -project iosApp/ThrymIOS.xcodeproj -scheme ThrymIOS \
  -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO
```

Gezielte Kotlin-Tests des gemeinsamen Kerns laufen mit JDK 17, zum Beispiel:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon :shared:testAndroidHostTest --tests '*DeloadTargetAdjustmentTest' --tests '*SharedTrainingAnalyticsTest'
```

Die beim Android-/iOS-Abgleich gefundenen Funktionslücken sind implementiert. Die kritischen Trainings-, Progressions- und Datenübernahmeabläufe sind gezielt geprüft; das ist keine vollständige UI- oder Release-Abnahme. Signierung, TestFlight/App-Store-Verteilung und ein Test auf einem physischen iPhone sind separat zu bewerten (siehe [`iosApp/TESTFLIGHT.md`](../iosApp/TESTFLIGHT.md)).

Historische Nachweise der Abnahme vom September 2026 (lokale Testläufe und Simulator-Sitzungen) siehe [`docs/validation/`](validation/), insbesondere [`2026-09-11-android-ios-parity.md`](validation/2026-09-11-android-ios-parity.md) und [`2026-09-11-parity-fixes.md`](validation/2026-09-11-parity-fixes.md).
