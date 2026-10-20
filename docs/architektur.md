# Architektur

Thrym hat zwei gleichberechtigte native Clients: **Android** (Kotlin, Jetpack Compose, dieses Gradle-Projekt, `applicationId` `com.thrym.app`) und **iOS** (SwiftUI in `iosApp/`, Bundle-ID `com.thrym.ios`). Beide nutzen den gemeinsamen Kotlin-Multiplatform-Kern `:shared` für Regeln, Backup-Format und Auswertungen. Die Datenhaltung ist plattformspezifisch: Room auf Android, eine JSON-Datei auf iOS.

Android folgt MVVM mit Clean-Architecture-Schichten, aufgeteilt in Gradle-Module. Die Kotlin-Pakete heißen in allen Android-Modulen `com.thrym.app.*`, auch wenn die Module anders aufgeteilt sind. Die Gradle-Namespaces (und damit die `R`-Klassen) folgen dagegen dem Modul: `com.thrym.app` für `app`, `com.thrym.core.<modul>`, `com.thrym.data` und `com.thrym.feature.<modul>`. Der KMP-Kern liegt in `com.thrym.shared.*`.

## Module

```
app/                    Einstieg: MainActivity, Koin-DI (di/AppModule.kt), Navigation, Bottom-Nav
core/
  model/                Domain-Modelle (domain.model) und Repository-Interfaces (domain.repository)
  common/               Logik ohne UI: Formatierung, AppLogger (einzige Android-API: android.util.Log),
                        Progressions-Engine, Deload-Erkennung, Scheibenrechner, Muskelvolumen
  database/             Room: Entities, DAOs, Migrationen, exportierte Schemas (core/database/schemas/)
  designsystem/         Theme, Design-Tokens, gemeinsame Compose-Komponenten (auch der Begleiter), gemeinsame UI-Texte
                        (values/ Englisch, values-de/ Deutsch), Fonts
data/                   Repository-Implementierungen, DataStore-Einstellungen, Backup,
                        Erinnerungen (WorkManager), Incident-Reports
feature/
  dashboard/            Home: Training starten, Plan-Auswahl, Tagesform/Bereitschaft, Deload,
                        Wochenvolumen je Muskelgruppe, Volumentrend
  workout/              Aktives Workout, Übungsauswahl, Abschlussdialog und Zusammenfassung
  plans/                Trainingspläne, Meta-Pläne, Plan-Editor inkl. Progressions-Konfiguration
  progression/          Review-Bildschirm für Progressionsvorschläge
  history/              Verlauf und Workout-Detail
  exercises/            Übungsbibliothek
  statistics/           Statistik pro Übung
  settings/             Einstellungen, Backup, Incident-Report
  onboarding/           Einführung beim ersten Start (inkl. Einheit)
shared/                 Kotlin Multiplatform (Android + iOS), siehe unten
iosApp/                 Native SwiftUI-App (ThrymIOS), Projekt per generate_project.py, siehe iosApp/README.md
```

**Abhängigkeitsrichtung:** `feature/*` → `core/*`. `data` implementiert die Interfaces aus `core:model`. Nur `app` kennt alle Module und verdrahtet sie über Koin.

**Bottom-Navigation:** Home, Pläne, Verlauf, Übungen. Einstellungen, aktives Workout, Progressions-Review und Detailseiten sind eigene Ziele ohne Tab.

## Datenbank (Room, Version 15)

Die Datenbankklasse ist `ThrymDatabase` (`core/database`), die Datei heißt `thrym.db`. Das exportierte Schema liegt in `core/database/schemas/com.thrym.app.data.local.ThrymDatabase/15.json`. Es gibt durchgehende Migrationen von 1 bis 15. Migration 14→15 ergänzt die Erfolgsbestätigung und stellt aktive Planregeln auf Revision 2 um; historische Snapshots behalten ihre Revision. Tests dazu: `app/src/androidTest/.../ThrymDatabaseMigrationTest.kt`.

| Tabelle | Zweck | Wichtige Beziehungen |
|---|---|---|
| `exercises` | Übungen (Seed-Daten + eigene), archivierbar | – |
| `workout_sessions` | Ein Training. `endTime = NULL` heißt: läuft noch. `planId`/`metaPlanId` für den Kontext, `isDeload` für den Deload-Kontext (`NULL` = unbekannt, bei Altbeständen) | – |
| `workout_sets` | Ein Satz mit Gewicht, Wdh., Satztyp (`NORMAL`, `WARMUP`, `DROP_SET`, `FAILURE`, `BACKOFF`), RPE | → session (CASCADE), → exercise (RESTRICT), → plan-target-snapshot (SET NULL) |
| `personal_records` | Rekorde pro Übung | → exercise (CASCADE) |
| `training_plans` | Trainingspläne | – |
| `plan_exercises` | Übungen im Plan: Zielwerte, optionale individuelle Satzvorgaben (`setTargetsJson`), Supersatz-Gruppe, Progressions-Konfiguration (flach gespeichert) | → plan (CASCADE), → exercise (CASCADE) |
| `meta_training_plans` | Meta-Pläne | – |
| `meta_plan_items` | Reihenfolge der Teilpläne im Meta-Plan | → meta-plan, → plan (CASCADE) |
| `meta_plan_skips` | „Überspringen“-Ereignisse für die Rotation | → meta-plan, → plan (CASCADE) |
| `workout_plan_targets` | Unveränderlicher Snapshot der Planziele und Satzvorgaben beim Workout-Start | → session, → plan (CASCADE), → exercise (RESTRICT) |
| `progression_suggestions` | Ergebnisse des Progressions-Coachs (offen, angenommen, verworfen, veraltet, Info) | → snapshot, → session, → plan (CASCADE), → exercise (RESTRICT) |
| `readiness_data` | Eine Zeile mit einem JSON-Dokument: Tagesform-Check-ins und Satzintentionen (Format in `:shared`, `readinessdata`) | – |

Gewichte werden immer in **kg** gespeichert. Die Umrechnung nach lb passiert nur in der Anzeige.

## Wichtige Invarianten

- **Höchstens ein laufendes Workout.** Der DAO liest immer nur die jüngste offene Session (`endTime IS NULL`). Die Migration 2 → 3 hat Altbestände mit mehreren offenen Sessions bereinigt.
- **Pläne ändern sich nur nach Bestätigung.** Der Progressions-Coach schreibt Vorschläge, nie direkt Zielwerte. Details: [`features/progressions-coach.md`](features/progressions-coach.md).
- **Workouts arbeiten mit einem Snapshot des Plans.** Wird der Plan während oder nach dem Training geändert, bleibt die damalige Vorgabe erhalten.
- **Import ist fail-closed.** Ein ungültiges Backup verändert keine Daten.

## Reihenfolge im laufenden Training

Übungen und Supersätze lassen sich im laufenden Training mit Pfeilen nach oben und unten verschieben, auch Zusatzübungen. Die Anzeigereihenfolge ist vom Snapshot getrennt:

- Android speichert sie im DataStore unter `active_workout_exercise_order` (`WorkoutExerciseOrderStore` in `feature/workout`), iOS in `UserDefaults` unter `thrym.workout.exerciseOrder` (`IOSWorkoutExerciseOrderPersistence` in `IOSWorkoutExerciseOrder.swift`). Es gibt höchstens einen Eintrag. Format und Regeln liegen in `:shared` (`SessionExerciseOrder`, `SessionExerciseOrderCodec`).
- Der Eintrag ist durch Session-ID und Startzeit geschützt. Ein Eintrag einer anderen Session oder Startzeit wird ignoriert und entfernt, auf Android nur beim Laden einer laufenden Session. Verwerfen löscht ihn (auf Android ein leeres Training). Nach normalem Beenden bleibt er für den Abschlussbildschirm stehen. iOS sichert dabei die angezeigte Reihenfolge, auch ohne vorheriges Verschieben, und löscht den Eintrag beim Schließen des Abschlussbildschirms.
- Gespeichert werden Zeilenschlüssel: Android `planned-<snapshotId>`, iOS `target-<snapshotId>`, beide `adhoc-<exerciseId>`. Auf Android stehen Zusatzübungen auch ohne Sätze darin und kommen so nach einem Kaltstart zurück; iOS merkt sich die hinzugefügten Zusatzübungen davon getrennt je Training. Android verschiebt und ergänzt erst, wenn die Planziele der Session geladen sind, damit eine gespeicherte Reihenfolge nicht aus einer leeren Liste neu berechnet wird.
- Ein Supersatz bewegt sich als Block; innerhalb wird nicht umsortiert. Die Blöcke entstehen immer aus der Standardreihenfolge (`orderIndex`, dann Zusatzübungen in Einfügereihenfolge; iOS hängt Zusatzübungen, die nur über ihre Sätze bekannt sind, nach ihrem ersten Satz an).
- `workout_plan_targets.orderIndex` und `supersetGroupId` bleiben unberührt. Sie gehören zur Identität der Planposition, die Coach, „Satzwerte übernehmen“ (unter „Planänderungen prüfen“) und Readiness nutzen.
- Die Reihenfolge ist nicht im Backup. Soll sie dort einmal hinein, dann als additives, nullable Feld mit `@EncodeDefault(NEVER)` und Backup-Schema 16.

## Rekorde eines Trainings

`personal_records` (Android Room, iOS Trainingsgraph) hält je Übung und Typ nur den aktuellen Bestwert, ohne Verlauf; Dashboard, Statistik und Verlauf lesen diese Werte. Was ein Training geschafft hat, leiten beide Plattformen dagegen allein aus den Sätzen ab, mit `SessionRecords` in `:shared` (`shared/src/commonMain/kotlin/com/thrym/shared/workout/SessionRecords.kt`). Gespeichert wird dafür nichts, Room-Schema und Backup bleiben unverändert.

- Verglichen werden die Arbeitssätze des Trainings mit denen aller anderen Sessions. Arbeitssatz: kein Aufwärmsatz, Wiederholungen > 0, endliches Gewicht ≥ 0. Gewicht, Wiederholungen und e1RM (Epley, bei einer Wiederholung das Gewicht selbst) gelten je Satz, Volumen als Summe des Trainings; dessen früherer Bestwert zählt nur abgeschlossene Sessions. Werte ≤ 0 sind kein Rekord, eine Übung mit 0 kg hat also nur Wiederholungsrekorde. Bei Eigengewichtsübungen ist das Gewicht eine Zusatzlast; für sie (`addedLoadExerciseIds` bzw. `addedLoad`) zählen nur Gewicht und Wiederholungen, weil e1RM und Volumen der Zusatzlast allein nichts über die Übung sagen.
- Ein Rekord braucht einen Zuwachs von mindestens einem Schritt (0,1 kg, 1 Wdh, 1 kg Volumen). Gleichstand zählt nicht. Eine Übung ohne früheren Arbeitssatz hat keine Rekorde, sondern „erste Bestwerte“.
- `summarize` liefert die Tafel des Abschlusses, `liveImprovements` die Meldung nach einem Log oder einer Änderung. Live braucht eine Bestmarke aus einem früheren Training und meldet nie Volumen; der Zuwachs bezieht sich in beiden Fällen auf den Bestwert vor dem Training.
- Android liest die Sätze über `StatisticsRepository.getSetsForExerciseList` (live vor und nach der Änderung, unter dem Mutation-Mutex), iOS aus dem Trainingsgraphen (`IOSWorkoutRecords.swift`).
- Die gespeicherten e1RM-Bestwerte folgen derselben Regel: Android über `WorkoutCalculations.calculateE1RM`, der iOS-Store (`SharedStateStore`) über `SessionRecords.estimatedOneRepMaxKg`. Ältere iOS-Builds speicherten eine Einzelwiederholung rein nach Epley (100 kg × 1 als 103,3 kg). Solche Werte repariert `repairLegacySingleRepE1rm` in `:shared` (`com.thrym.shared.backup`): Ein gespeicherter e1RM, der genau dem alten Bestwert der Sätze entspricht und über dem heutigen liegt, bekommt Wert und Zeitpunkt des heutigen Bestsatzes (bei Gleichstand der spätere). Dasselbe gilt, wenn ein Satz mit mehreren Wiederholungen den alten Bestwert erreicht, der Rekord aber noch das Datum der Einzelwiederholung trägt, die die alte Regel als spätere bevorzugte (85 kg × 1 und 77,5 kg × 4 ergeben beide 87,8 kg); die ID bleibt, alle anderen Rekorde bleiben, wie sie sind. Der iOS-Store ruft die Reparatur bei jedem Dekodieren auf (Start, Import, Vorschau, Wiederherstellung), Android beim Import und bei der Wiederherstellung, bevor die Rekorde geschrieben werden. Sie ist idempotent, braucht keine Markierung und ändert weder Backup-Format noch Room-Schema.

## Backup

- Format: JSON, `BackupPayloadV1` mit `formatVersion = 1` und `schemaVersion = 15` (`CURRENT_BACKUP_SCHEMA_VERSION`). Es enthält auch die Readiness-Daten, Satzvorgaben, Erfolgsbestätigung und den Satztyp `BACKOFF`. Modelle und Validator liegen in `:shared` (`com.thrym.shared.backup`). `data` nutzt sie über Typaliase. Beim Import werden nur aktive Plan-Konfigurationen auf die aktuelle Regelrevision umgestellt; historische Quellen bleiben erhalten.
- Android und iOS verwenden dasselbe Format aus `:shared`. Ein Backup ist sprachunabhängig, weil gespeicherte Werte keine übersetzten Texte enthalten (siehe Lokalisierung): Ein Export aus der deutschen Oberfläche importiert in die englische und umgekehrt.
- Ältere Backups ohne neuere Felder (Meta-Plan-Skips, Progression, Readiness, Satzvorgaben) lassen sich weiter importieren. Backups aus einer neueren App-Version werden mit einer klaren Meldung abgelehnt. Fehlende Felder bekommen sichere Standardwerte.
- **Umzug von IronLog:** Thrym ist wegen der neuen IDs eine neue App mit leerer Sandbox und sieht die IronLog-Daten nicht. Der Weg ist „Backup exportieren“ in den Einstellungen von IronLog und „Backup importieren“ in den Einstellungen von Thrym. Der Import ersetzt alle Trainingsdaten in Thrym. IronLog-Backups (`formatVersion` 1, jede `schemaVersion` bis 15) importieren auf beiden Plattformen. Diese Kompatibilität ist eine feste Zusage und braucht Tests, wenn sich das Format ändert. Abgesichert wird sie durch eingefrorene Golden-Backups von IronLog (Android 1.4.0 und iOS, Schema 15) in `IronLogBackupFixtures` (`:shared`, commonTest) und `data/src/test/resources/backup-fixtures/`, die beide `IronLogBackupFixtureImportTest` importieren. Die Fixtures sind per SHA-256 festgenagelt und werden nie geändert; schlägt ein solcher Test fehl, wird der Import-Code korrigiert.
- Vor jedem Import und jeder Wiederherstellung legt die App ein **internes Sicherheitsbackup** an und prüft es per SHA-256 (`FileRecoveryBackupStore`).
- Einstellungen (Einheiten, Design, Pausen-Timer, Scheiben …) und Erinnerungen sind **nicht** Teil des Backups. Nach einem Umzug müssen sie neu gesetzt werden.

## Einstellungen

`AppPreferences` (in `core:model`) wird per DataStore gespeichert. Standardwerte: metrisch, Wochenstart Montag, dunkles Design in der Darstellung Liquid Glass, Farbschema Amber, RPE als Intensitätssystem, getrennte Gewichtshistorie zwischen Einzel- und Meta-Plänen, automatischer Pausen-Timer aus (Standarddauer 120 s), Scheibenrechner an (Scheiben 25 bis 1,25 kg, Stange 20 kg), kein Deload-Modus, Backup-Erinnerung aus.

Die Wahl des Begleiters (Odin, Walküre, aus) liegt nicht in `AppPreferences`, sondern wie der Onboarding-Status in einem eigenen DataStore `companion` (`CompanionRepository`), auf iOS in `UserDefaults` unter `thrym.companion.*`. Details: [`features/begleiter.md`](features/begleiter.md).

## Kotlin Multiplatform (`:shared`)

Enthält den plattformneutralen Kern (`com.thrym.shared.*`): Transportmodelle, Backup-Format und -Validator, Progressionsregeln, Satzvorgaben (`plans`), Scheibenrechner (`plates`), Deload- und Auswertungslogik (`deload`, `analytics`), Trainingsbereitschaft (`readiness`), Tagesform/Satzintention (`readinessdata`), einen kanonischen Trainingsgraphen samt Store (`store`), Next-Set-Hinweise, Reihenfolge und Rekorde im laufenden Training (`workout`), Pixel, Animationen und Regeln des Begleiters (`companion`) sowie Controller für Dashboard, Workout, Verlauf, Pläne, Statistik und Einstellungen.

Android nutzt daraus vor allem Backup-Format und Validator, Readiness, Progression (über `PortableProgressionAdapter`), Satzvorgaben, Scheibenrechner und die Einstellungs-Controller. Die Android-Datenhaltung bleibt Room. Die iOS-App bindet `:shared` als statisches Framework `Shared` ein, speichert den Trainingsgraphen aus `store` als JSON-Datei unter `Application Support/Thrym/training-state.json` und greift über `IosTrainingFeature` und `IosSettingsFeature` (`shared/src/iosMain`) auf den Kern zu, siehe [`ios-feature-parity.md`](ios-feature-parity.md). Einstellungen liegen auf iOS in `UserDefaults`.

`:shared` liefert überwiegend Werte und Codes (z. B. Reason-Codes der Progression und Bereitschaft), die die Plattformen in ihre lokalisierten Texte übersetzen. Ausnahme: Einige Fehlermeldungen aus `SharedStateStore` und den iOS-Brücken (`IosTrainingFeature`, `IosSettingsFeature`) sind feste deutsche Texte. iOS übersetzt sie über `ilSharedMessage` (`iosApp/ThrymIOS/SharedBootstrap.swift`), das den deutschen Text als Schlüssel im String-Katalog nachschlägt (Einträge mit `extractionState` `manual`). Wer eine solche Meldung ändert, zieht den Katalogeintrag im selben PR mit, sonst erscheint sie in der englischen Oberfläche auf Deutsch. Technische Meldungen des Backup-Validators und der Progressions-Lifecycle-Prüfung sind englisch und erscheinen, wenn sie die Oberfläche erreichen, unverändert.

Die Tests von `:shared` laufen über `./gradlew :shared:testAndroidHostTest`, **nicht** über `./gradlew test`. Die iOS-CI führt zusätzlich die Golden-Fixture-Tests (`IronLogBackupFixtureImportTest`) unter Kotlin/Native aus (`./gradlew :shared:iosSimulatorArm64Test`), weil die iOS-App den Kern nativ ausführt.

## Lokalisierung

Die App gibt es auf **Englisch** (Standard und Fallback) und **Deutsch**. Sie folgt der Systemsprache; ab Android 13 lässt sich die Sprache auch pro App wählen (`localeConfig` mit `en` und `de`), auf iOS in den Systemeinstellungen der App.

- **Android:** Jedes Modul mit UI-Texten hat `res/values/` (Englisch) und `res/values-de/` (Deutsch) mit denselben Schlüsseln. Gemeinsame Texte und Plurale liegen in `core:designsystem`, featurespezifische im Feature-Modul. Sprachneutrale Strings stehen nur in `values/` mit `translatable="false"`. ViewModels arbeiten mit Ressourcen-IDs oder lösen sie über `UiStrings` auf. Lint (`MissingTranslation`) und `.github/scripts/check-translations.py` sichern ab, dass beide Sprachen vollständig sind und die Platzhalter übereinstimmen.
- **iOS:** Zwei String-Kataloge: `iosApp/ThrymIOS/Localizable.xcstrings` für UI-Texte und `iosApp/ThrymIOS/Exercises.xcstrings` für die Namen der eingebauten Übungen. Schlüssel ist der deutsche Text (`sourceLanguage` `de`), jeder Eintrag hat eine `en`- und eine `de`-Lokalisierung, Mengen als Plural-Varianten. Die `developmentRegion` des Projekts ist `en`, damit Englisch der Fallback für andere Systemsprachen ist. Auch hier prüft `check-translations.py` Vollständigkeit und Platzhalter, allerdings nur für Schlüssel, die schon im Katalog stehen. Das generierte Projekt extrahiert keine Strings; neue Texte werden von Hand in den Katalog eingetragen (siehe [`iosApp/README.md`](../iosApp/README.md#lokalisierung)).
- **Gespeicherte Werte sind sprachneutral.** Enum-Konstanten wie `BRUST`, `RUECKEN`, `LANGHANTEL`, `WARMUP` oder `PLANNED_FAILURE` sind Speicherschlüssel in Room, im iOS-Store und im Backup. Übersetzt wird nur ihre Anzeige.
- **Eingebaute Übungen** werden unter ihrem deutschen kanonischen Namen gespeichert (Android-Seed `ExerciseSeedData.kt` in `core/database`, iOS-Katalog `DefaultExerciseCatalog` in `:shared`). Beide Plattformen übersetzen diese Namen nur für die Anzeige: Android über `BuiltInExerciseNames` (`core:designsystem`) mit `values/` und `values-de/exercise_names.xml`, iOS über `ilExerciseDisplayName` mit `Exercises.xcstrings`. Eigene Übungen erscheinen so, wie sie eingegeben wurden. Android lehnt eigene Übungen ab, deren Name dem einer eingebauten Übung in irgendeiner App-Sprache entspricht. So wird ein eigenes „Deadlift“ auch in der deutschen Oberfläche abgelehnt und steht nach einem Wechsel auf Englisch nicht doppelt in der Liste.
- **Formatierung:** Zahlen und die Reihenfolge in Datumsangaben folgen der Geräteregion („82,5 kg“ auf Deutsch, „82.5 kg“ auf Englisch), Monats- und Wochentagsnamen der Sprache, in der die App tatsächlich angezeigt wird. Auf einem Gerät mit einer nicht unterstützten Sprache (etwa fr-FR) erscheinen Texte und Datumsnamen also einheitlich auf Englisch. Android bildet dafür aus der aufgelösten Ressourcensprache (`ui_language_tag`) und der Geräteregion ein eigenes Locale (`currentLocale()`, `Resources.uiLocale()` in `core:designsystem`). Vorbelegte Eingabefelder nutzen immer ASCII-Ziffern ohne Tausendertrennzeichen; nur das Dezimalzeichen (Komma oder Punkt) folgt der Sprache, wie auf iOS. Die Parser akzeptieren beide Dezimalzeichen und auch Ziffern anderer Schriften. Gewichte bleiben in kg gespeichert; lb ist nur Anzeige.
- **Sprachwechsel zur Laufzeit:** Android-ViewModels überleben einen Sprachwechsel. Sie halten deshalb sprachneutrale Daten (Übungen, Datumswerte) im UI-State, und erst die Composables lösen Namen und Datumstexte auf.
