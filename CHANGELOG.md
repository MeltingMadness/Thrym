# Änderungen

## Unveröffentlicht

- Android-Workout (Liquid Glass): Laufen mehrere Pausen gleichzeitig (z. B. im Supersatz), zeigt das Dock jeden Countdown als eigene Zeile, kürzeste Restzeit zuerst. Die Vollbild-Pause öffnet sich nur noch zu Beginn einer Pause ohne weitere laufende Timer und schließt sich, wenn ihr Timer endet.
- Open Source: Apache-2.0-Lizenz und NOTICE. Der Code steht unter Apache-2.0; Name „Thrym“, Logo und App-Icon sind nicht für Forks lizenziert.
- Neuer App-Name: **Thrym** (vorher IronLog). Der sichtbare Name steht nur noch in `branding.properties` und gilt für Android und iOS (Startbildschirm, Willkommenstext, Incident-Betreff, iOS-Erinnerungen).
- Neues App-Icon und Logo „Gestufter Dorn“: die Rune ᚦ, gebaut aus Hantelscheiben, auf Amber. Android mit adaptivem Icon und Themed Icon ab Android 13, iOS mit den Varianten Dunkel und Getönt ab iOS 18. Der Android-Startbildschirm zeigt das Zeichen auf einem Amber-Kreis; der Kreis fehlte bisher, weil das Splash-Theme die Hintergrundfarbe des Icons ignorierte. Quellen und Markenregeln in `docs/brand/`.
- Neue IDs: Android `com.thrym.app`, iOS `com.thrym.ios`, Pakete `com.thrym.*`, Datenbank `thrym.db`, iOS-Daten unter `Application Support/Thrym/`. Thrym installiert sich als neue App und übernimmt keine IronLog-Daten automatisch. Umzug per Backup: in IronLog „Backup exportieren“, in Thrym „Backup importieren“. Alte IronLog-Backups lassen sich auf beiden Plattformen importieren. Einstellungen (Einheiten, Design, Erinnerungen, Scheiben …) sind nicht im Backup und müssen neu gesetzt werden.
- Englische Oberfläche: Englisch ist Standard und Fallback, Deutsch bleibt vollständig. Ab Android 13 lässt sich die Sprache pro App wählen. Eingebaute Übungen und Muskelgruppen erscheinen übersetzt; gespeicherte Daten und Backups bleiben sprachunabhängig.
- Zahlen und Datumsangaben folgen der Gerätesprache statt fest dem deutschen Format.
- CI: neuer iOS-Workflow (Projektgenerator-Abgleich und XCTest auf `macos-26` mit Xcode 26.6) und Prüfung auf vollständige Übersetzungen auf beiden Plattformen.
- Community-Dateien für Mitwirkende, README auf Englisch. iOS-Gerätebuilds mit eigenem Team und eigener Bundle-ID über `iosApp/Local.xcconfig`.
- F-Droid-Metadaten unter `fastlane/metadata/android/`.
- Persönliche Skripte, Notizen, Mockups und Screenshots aus dem Repository entfernt.
- Technische Basis aktualisiert (Kotlin 2.4, Compose 1.12, compileSdk 37, Vico 3). Android-Einstellungen: Abschnittstitel werden nach der App-Sprache großgeschrieben statt nach der Gerätesprache.
- iOS-Rekorde: Das Beenden eines Trainings mit mehreren Übungen konnte mit „Invalid workout state: Backup contains duplicate personal record id“ scheitern, weil ein neuer Rekord die ID eines bestehenden bekam. Neue Rekorde bekommen jetzt immer eine freie ID, bestehende behalten ihre. Gespeicherte Daten waren nicht betroffen.

## 1.4.0 (Android versionCode 9 / iOS Build 4) – Polish und Parität · 26.09.2026

- Startseite: „Training jetzt starten“ startet den vorgeschlagenen Plan direkt (nächster Meta-Teilplan, sonst nie oder am längsten nicht trainierter Plan); „Anderen Plan wählen“ als zweite Aktion.
- Workout: Kopf mit Satzfortschritt, nur die aktuelle Übung aufgeklappt, Löschen im Bearbeitungsmodus mit „Rückgängig“, Verlauf-Symbol für das vorherige Training.
- Zusammenfassung: „Fertig“ als Hauptaktion, neue Rekorde der Einheit hervorgehoben.
- Planliste mit Meta-Plänen, „zuletzt trainiert“ und Einstieg in alle Progressions-Auswertungen; Trainingsdetails nennen den Plan; Volumen-Diagramm erklärt die laufende Woche; einheitliche Statistik-Kacheln.
- Android-Verlauf: Suche, Sätze abgeschlossener Trainings korrigieren oder löschen (Rekorde neu berechnet, offene Vorschläge verfallen wie auf iOS), Trainingsnotiz.
- Übungsliste mit Einheiten und letztem Training je Übung; der Progressions-Review zeigt frühere Auswertungen.
- Deutsches Zahlenformat, Einzahl/Mehrzahl, Umlaute und einheitliche Satzanzeige („82,5 kg × 8 Wdh“, „Körpergewicht“) auf Android und iOS.
- Aufgeräumt: Workout-Screen und ViewModel aufgeteilt, ungenutzte Muskel-Heatmap entfernt.

Validierung: CI (Unit, Lint, Build, Emulator-Smoke) für PRs #20–#27 grün; iOS-Tests grün. Release-Build mit unverändertem Zertifikat auf einem Android-Gerät installiert und gestartet.

## 1.3.1 (Android versionCode 8) – Progressionsauswahl · 15.09.2026

- Eine Progressionsart antippen übernimmt sie direkt in den Planentwurf und schließt das Menü. Die Übung zeigt die neue Auswahl sofort; anschließend wird der gesamte Plan gespeichert.
- Schrittweite und weitere Progressionsparameter bleiben über „Details anpassen“ erreichbar; deren Übernehmen-Knopf bleibt außerhalb des scrollenden Formulars sichtbar.
- Beim Wechsel von doppelter zu linearer Progression bleiben vorhandene Gewichtsschritte und Entlastungseinstellungen erhalten.

## 1.3.0 (Android versionCode 7) – persönlicher Testrelease · 15.09.2026

- Direktes Trainingslogging mit vorbelegten Werten, Satzbestätigung und aufklappbaren Zusatzangaben.
- Mehrfachauswahl von Übungen und individuelle Satzvorgaben mit manueller Progression.
- Teilabschluss mit offenen Sätzen und Zusammenfassung der tatsächlich absolvierten Sätze.
- Ausdrückliche Planübernahme; offene Vorgaben bleiben erhalten und veraltete Änderungen werden abgelehnt.
- Datenbankschema 14 mit Migration und Satzvorgaben in Snapshots und Backups.

Validierung: neun gezielte Tests und Debug-Build bestanden. Vollständiger Android-Bedienablauf wegen Startabbrüchen der Testumgebung weiterhin offen. Dieser lokale Testrelease ist keine CI- oder Store-Freigabe. Details: [Prüfprotokoll](docs/validation/2026-09-15-approved-design/implementation.md).

## 1.1.3 (Android versionCode 5 / iOS Build 2) – lokaler Paritäts-Korrekturkandidat

- iOS erhält beim Bearbeiten von Sätzen ausgeblendete RPE-Werte; RPE/RIR-Progressionspläne behalten eine passende Intensitätseingabe. Ein anderes Training kann eine aktive Sitzung nicht mehr stillschweigend übernehmen.
- Android schützt vor der Übernahme veralteter Progressionsvorschläge und erhält wiederholte Pläne in Rotationen wie A/B/A.
- Pausentimer berücksichtigen normale Arbeitssätze und Deload-Ziele, werden dauerhaft gespeichert und beim Wiederherstellen gegen veraltete Sitzungen abgesichert. Backoff-Vorschläge verwenden den eingestellten Prozentsatz.
- Statistik und Startseite schließen laufende sowie zukünftige Trainingsdaten aus; lokale Tagesgrenzen werden konsistent verwendet. Der Readiness-Kreis zeigt bei unauffälliger Belastung wieder 100 %.
- Import und Wiederherstellung holen fehlende Progressionsauswertungen nach; ein Fehler dabei wird getrennt vom bereits erfolgreichen Import gemeldet.

Validierung und noch ausstehende Geräte-/TestFlight-Schritte: [Paritätskorrekturen](docs/validation/2026-09-11-parity-fixes.md).

## 1.1.2 (versionCode 4) – lokaler Korrekturkandidat

- Progressionsauswertungen unterscheiden gespeichertes Planziel, trainierte Last und vorgeschlagenes Gewicht. Abweichende Trainingsgewichte können auch beim Wiederholen ausdrücklich in den Plan übernommen werden.
- Alte, unbearbeitete informative Auswertungen mit Nullgewicht als Planbasis werden bei eindeutiger Satzevidenz neu bewertet. Bestätigte und bearbeitete Entscheidungen sowie Trainingsbelege bleiben erhalten.
- Individuelle Gewichtsschritte rechnen relativ zur trainierten Last: etwa 4 → 5,5 → 7 kg bei 1,5 kg Schrittweite. Entlastungen bleiben auf derselben Gewichtsreihe.
- Fehlerserien setzen vergleichbare tatsächlich trainierte Gewichte voraus; wiederholt zu hohe RPE berücksichtigt die konfigurierte Entlastungsregel.
- Doppelte Progression schlägt die Laststeigerung vor, sobald alle gewerteten Sätze die Wiederholungsobergrenze erreichen. Die Auswahl erklärt „Gewicht steigern“ und „Wiederholungen, dann Gewicht“.
- Ohne feste Pausendauer zählt der Pausentimer wieder hoch; nach dem letzten geplanten Arbeitssatz endet er.

## 1.1.1 (versionCode 3) – lokaler UI-Korrekturkandidat

- Die MEV/MAV-Karte auf der Startseite ist zunächst eingeklappt; Wochenwahl und Zusammenfassung bleiben sichtbar. Muskelgruppen lassen sich über eine beschriftete Schaltfläche ein- und ausblenden.
- Die Belastungsanalyse verwendet die volle Breite und ordnet ihre Texte untereinander an, damit sie sich nicht überlagern.
- Wochen- und Monatszahlen erhalten eine kompakte gemeinsame Zeile; MEV/MAV-Markierungen stehen an ihrer berechneten Position.
- Die widersprüchliche zusätzliche Muskelgruppenübersicht entfällt. Der Volumentrend zeigt acht vollständige Wochen mit Datumsbeschriftung und Einheit, einschließlich Wochen ohne Training.
- Rekordkarten können für größere Schrift in der Höhe mitwachsen.
- Der grafische Statuskreis bleibt erhalten: Häkchen bei unauffälliger Belastung, Fragezeichen ohne ausreichende Daten, numerischer Score bei vorhandenen Belastungssignalen.

## 1.1.0 (versionCode 2) – Entwicklungskandidat, nicht veröffentlicht

- Satztypen werden über Sicherung und Wiederherstellung vollständig erhalten; alte Sicherungen bleiben lesbar.
- Readiness-/Deload-Hinweise unterscheiden fehlende Daten von unauffälligen Trainingsdaten. Änderungen am Deload-Modus werden erst nach erfolgreicher Speicherung bestätigt.
- Planfelder unterstützen vollständiges Löschen und Bearbeiten. Speichern und Verlassen der Editoren berücksichtigen laufende Vorgänge und ungesicherte Änderungen.
- Scheibenberechnung berücksichtigt passende Kombinationen auch bei individuell gewählten Scheibengrößen.
- Coach-Vorschläge erhalten mehr Kontext und eine klare Übernahmehandlung. Offene Bearbeitungen werden nicht durch Sammelübernahme verworfen.
- Die Startseite zeigt das wöchentliche Muskelvolumen für alle Muskelgruppen, mit Wochenwahl und Orientierung an den vorhandenen Volumenbereichen. Sätze über einen Wochenwechsel werden nach ihrem Abschlusszeitpunkt zugeordnet.
- Historie und Statistiken erhalten Datums-/Planbezug sowie eine vollständige Darstellung der Satzarten.
- Einstellungen zeigen den letzten erfolgreichen Export und die App-Version; eine lokale Sicherungserinnerung ist optional.

Die genaue Validierung und verbleibende Geräte-/Release-Schritte stehen in `docs/validation/2026-09-05-reliability.md`.
