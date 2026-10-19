# Plan: Wochenziel und Wochen-Serie

Stand 19.10.2026. Status: Entwurf, noch kein Code.

## Ziel

Nutzer setzen sich ein Ziel „X Trainings pro Woche“. Home zeigt, wie weit die laufende Woche ist („2 von 3“), und wie viele Wochen in Folge das Ziel gehalten wurde („7 Wochen in Folge“).

Gezählt wird bewusst **pro Woche, nicht pro Tag**. Ruhetage gehören zum Krafttraining, eine Tagesserie würde sie bestrafen. Deload-Wochen, Krankheit und Urlaub sollen die Serie nicht sofort brechen.

## Was es schon gibt

- **Wochenstart** als Einstellung: `AppPreferences.weekStart` (`WeekStart.MONDAY`/`SUNDAY`) in `shared/src/commonMain/kotlin/com/thrym/shared/model/PlatformTypes.kt`.
- **Trainings dieser Woche** auf Home, beide Stile und beide Plattformen:
  - Android: `DashboardViewModel` zählt über `getCompletedSessionCountBetween` (abgeschlossene Einheiten, `startTime` in der Woche) und baut daraus `weekDays` für den Wochenstreifen (`GlassWeekStrip` in `DashboardGlass.kt`) und die Ember-Karte in `DashboardScreen.kt`.
  - iOS: `IOSDashboardWeekDay.week(...)` und `IOSDashboardGlassWeekStrip` in `iosApp/ThrymIOS/Dashboard/IOSDashboardGlass.swift`.
- **Deload je Einheit:** `isDeload: Boolean?` an jeder Einheit (Backup-Schema 13, Room und iOS-Store). `true` heißt „unter Deload-Modus trainiert“, `null` heißt „unbekannt“ (alte Daten).
- **Abschlusskarte** am Trainingsende mit Rekord-Board (`WorkoutCompletionRecords.kt` auf Android).

## Regeln

### Was zählt als Training

Dieselbe Regel wie der heutige Wochenzähler: eine **abgeschlossene** Einheit (`endTime` gesetzt), zugeordnet über ihren `startTime` zur lokalen Kalenderwoche nach `weekStart`. Mehrere Einheiten am selben Tag zählen einzeln. So passen Zähler, Wochenstreifen und Ziel immer zusammen.

### Wochenziel

- Einstellung „Wochenziel“: **Aus** oder **1 bis 7** Trainings pro Woche.
- Standard für neue und bestehende Installationen: **3**. Das ist der häufigste Rhythmus in Kraftplänen und entspricht den Standard-Erinnerungstagen (Mo/Mi/Fr).
- „Aus“ blendet Ziel, Serie und alle Hinweise darauf vollständig aus. Der heutige Zähler „N Trainings diese Woche“ bleibt dann wie bisher.

### Wann eine Woche „gehalten“ ist

Eine **abgeschlossene** Woche (liegt vollständig in der Vergangenheit) ist gehalten, wenn eine dieser Bedingungen gilt:

1. **Ziel erreicht:** Anzahl Trainings ≥ Wochenziel.
2. **Deload-Woche:** mindestens ein Training mit `isDeload == true`. Eine Deload-Woche ist geplante Erholung und soll nicht bestraft werden.
3. **Joker eingesetzt:** siehe unten.

Sonst ist die Serie gerissen.

### Joker

- Für je **4 gehaltene Wochen** gibt es **1 Joker**, höchstens **2** auf Vorrat.
- Ein Joker wird **automatisch** für eine verpasste Woche eingesetzt, solange einer da ist. Die Woche zählt dann als gehalten, die Serie läuft weiter.
- Wochen, die per Joker gehalten wurden, verdienen keinen neuen Joker.
- Alles wird aus der Historie berechnet, nicht gespeichert. Damit ist das Ergebnis deterministisch und nach einem Backup-Import identisch.

### Die laufende Woche

- Sie bricht die Serie **nie**, solange sie läuft.
- Ist das Ziel schon erreicht, zählt sie sofort zur Serie mit („Ziel erreicht“).
- Sonst zeigt Home die Serie bis zur letzten abgeschlossenen Woche und den Fortschritt der laufenden („1 von 3“).

### Zieländerungen

Das Ziel ist eine Einstellung, keine Trainingsdaten. Ändert jemand das Ziel, soll das nicht rückwirkend die Serie zerstören (Ziel erhöht) oder aufblähen (Ziel gesenkt).

- Gespeichert wird eine kurze Liste `(gültig ab Wochenstart, Ziel)`. Eine Änderung gilt ab der **laufenden** Woche.
- Wochen davor werden mit dem Ziel bewertet, das damals galt.
- Für Wochen vor dem ersten Eintrag gilt das erste gespeicherte Ziel.
- Einstellungen sind nicht im Backup. Nach einem Import auf einem neuen Gerät gilt deshalb das aktuelle Ziel für die ganze Historie. Das ist akzeptabel und wird in der Feature-Doku erwähnt.

### Wochenstart-Änderung

Wechselt `weekStart`, wird alles mit den neuen Wochengrenzen neu berechnet. Die Serie kann sich dabei um eine Woche verschieben. Kein Sonderfall.

## Architektur

### `:shared` (Logik, beide Plattformen)

Neues Paket `com.thrym.shared.weeklygoal` mit einer **puren Funktion** ohne Uhr, Zeitzone oder Persistenz, im Stil von `readiness/`:

```kotlin
object WeeklyGoalEngine {
    fun evaluate(input: WeeklyGoalInput): WeeklyGoalStatus
}

data class WeeklyGoalInput(
    val today: LocalDate,                 // lokales Datum, vom Adapter berechnet
    val weekStart: WeekStart,
    val sessions: List<WeeklyGoalSession>,   // nur abgeschlossene Einheiten
    val goalHistory: List<WeeklyGoalChange>, // leer = Ziel aus
)

data class WeeklyGoalSession(val localStartDate: LocalDate, val isDeload: Boolean?)
data class WeeklyGoalChange(val effectiveWeekStart: LocalDate, val goal: Int)

data class WeeklyGoalStatus(
    val goal: Int,
    val completedThisWeek: Int,
    val goalMetThisWeek: Boolean,
    val streakWeeks: Int,
    val jokersAvailable: Int,
    val longestStreakWeeks: Int,
    val weeks: List<WeekOutcome>,  // für Tests und eine spätere Verlaufsansicht
)

enum class WeekOutcome { MET, DELOAD, JOKER, MISSED, CURRENT }
```

- Die Zeitzonenumrechnung `startTime` → `LocalDate` bleibt im Adapter, wie bei der Readiness.
- `WeekOutcome` ist nur Laufzeit-Modell, wird nie gespeichert.
- Einstellung in `AppPreferences`: `weeklyGoalHistory: List<WeeklyGoalChange>` mit Standard `[(beliebiges frühes Datum, 3)]`. „Aus“ wird als Eintrag mit `goal = 0` gespeichert, damit ein Wiedereinschalten die alte Historie nicht verliert. Plus `SettingsPreferencesController.updateWeeklyGoal(goal: Int)`.

### Android

- `AppPreferencesRepository` und DataStore-Implementierung um die Zielhistorie erweitern (JSON in einem String-Key, wie andere strukturierte Werte).
- `DashboardViewModel`: abgeschlossene Einheiten ab dem Beginn der Serie laden (für die Berechnung reichen `startTime` und `isDeload`), Engine aufrufen, Ergebnis in den UI-State.
  - Performance: Die Serie braucht nur die Wochen bis zum ersten Bruch. Lädt man rückwärts wochenweise oder einfach alle `startTime`/`isDeload`-Paare (zwei Spalten, auch bei tausenden Einheiten klein), reicht das. Vorschlag: neue DAO-Abfrage, die nur diese zwei Spalten liefert.
- UI: siehe unten. Einstellungen: neuer Eintrag „Wochenziel“ im Bereich Training.

### iOS

- Gleiches Modell über `IosSettingsFeature` und `IOSSettingsViewModel`.
- Dashboard ruft die Engine aus `Shared` mit den Einheiten aus `IOSTrainingStore` auf.
- Neue Strings von Hand in `Localizable.xcstrings` (de + en), Plural-Varianten für „Wochen in Folge“.

## Oberfläche

### Home

- **Liquid Glass:** Der Wochenstreifen bekommt einen Fortschritt „2 / 3“ statt nur „2 Trainings“, darunter eine Zeile „7 Wochen in Folge“ (Flammen- oder Ketten-Symbol, kein Emoji). Erreicht: Ring/Balken in der Akzentfarbe, Text „Wochenziel erreicht“.
- **Ember:** dieselben Informationen in der vorhandenen Wochenkarte.
- Joker als kleine Zahl mit Symbol neben der Serie. Tippen zeigt eine kurze Erklärung (Bottom Sheet bzw. Popover): was gezählt wird, wie Joker und Deload-Wochen wirken.
- Serie 0 und keine Historie: kein „0 Wochen in Folge“, sondern nur das Ziel der Woche. Negative Formulierungen gibt es nicht.

### Abschlusskarte

- Hat dieses Training das Wochenziel erfüllt, erscheint oben „Wochenziel erreicht · 8 Wochen in Folge“.
- Sonst „Noch 1 Training bis zum Wochenziel“.
- Reduzierte Bewegung respektieren: keine Animation, wenn `reducedMotion` an ist.

### Einstellungen

- „Wochenziel“: Aus, 1–7. Kurzer Hilfetext: „Zählt abgeschlossene Trainings pro Woche. Deload-Wochen unterbrechen die Serie nicht.“

### Barrierefreiheit

- TalkBack/VoiceOver lesen „2 von 3 Trainings diese Woche, 7 Wochen in Folge, 1 Joker“ als eine Einheit.
- Fortschritt nie nur über Farbe.

## Texte (Entwurf)

| Schlüssel (Android) | Deutsch | Englisch |
|---|---|---|
| `weekly_goal_progress` | %1$d von %2$d | %1$d of %2$d |
| `weekly_goal_streak` (plural) | %d Woche in Folge / %d Wochen in Folge | %d week in a row / %d weeks in a row |
| `weekly_goal_met` | Wochenziel erreicht | Weekly goal reached |
| `weekly_goal_remaining` (plural) | Noch %d Training bis zum Wochenziel / Noch %d Trainings … | %d more workout to reach your weekly goal / %d more workouts … |
| `weekly_goal_jokers` (plural) | %d Joker / %d Joker | %d streak freeze / %d streak freezes |
| `settings_weekly_goal` | Wochenziel | Weekly goal |
| `settings_weekly_goal_off` | Aus | Off |

Englisch „streak freeze“ statt „joker“, weil der Begriff dort etabliert ist. Alle Texte auf iOS identisch im Katalog.

## Tests

`:shared` (`WeeklyGoalEngineTest`, commonTest):

- Ziel erreicht über mehrere Wochen ergibt die richtige Serie.
- Laufende Woche unter Ziel bricht die Serie nicht; über Ziel zählt sie mit.
- Deload-Woche mit 1 Training hält die Serie; `isDeload == null` zählt nicht als Deload.
- Joker: nach 4 gehaltenen Wochen 1 Joker, Höchstwert 2, Joker-Wochen verdienen keinen Joker, zwei verpasste Wochen ohne Joker brechen.
- Zieländerung gilt ab der laufenden Woche; frühere Wochen behalten ihr Ziel.
- Ziel aus (`0`): kein Status.
- Wochenstart Sonntag vs. Montag, Jahreswechsel, Sommerzeit (über lokale Daten, nicht Millis).
- Mehrere Trainings am selben Tag zählen einzeln.
- Leere Historie.

Android: `DashboardViewModelTest` für den neuen State, DataStore-Test für die Zielhistorie, ein androidTest für die Einstellung (Texte über Ressourcen).
iOS: XCTest für die Dashboard-Darstellung und die Katalogeinträge.

## Daten und Kompatibilität

- **Keine Room-Migration, keine Backup-Änderung.** Alles wird aus `startTime`, `endTime` und `isDeload` berechnet, die es schon gibt.
- Neue Daten sind nur Einstellungen (Zielhistorie). Einstellungen sind nicht im Backup.
- Golden-Backups bleiben unberührt.

## Umsetzung in Schritten

1. **`:shared`:** Engine, Modelle, Einstellung in `AppPreferences` und `SettingsPreferencesController`, Tests.
2. **Android:** Einstellung, Dashboard (beide Stile), Abschlusskarte, Texte EN+DE, Tests.
3. **iOS:** dasselbe in SwiftUI, Katalogeinträge, XCTests, `generate_project.py` bei neuen Dateien.
4. **Doku:** `README.md` (Feature-Tabelle Home und Settings), neue `docs/features/wochenziel.md`, `CHANGELOG.md` „Unveröffentlicht“, diesen Plan löschen.

Schritte 2 und 3 können parallel laufen, sobald 1 gemergt ist.

## Bewusst nicht in diesem Schritt

- Benachrichtigungen „Deine Serie ist in Gefahr“. Wenn überhaupt, später, freundlich formuliert und abschaltbar.
- Verlaufsansicht aller Wochen (Kalender-Heatmap). Das Modell liefert `weeks` schon dafür.
- Trophäen stehen in `trophaeen.md`; Heimdalls Wacht dort baut auf diesem Plan auf. Rückblick und Wiedereinstieg bekommen eigene Pläne.

## Offene Fragen an Martin

1. Standard für bestehende Nutzer: **an mit Ziel 3** (Vorschlag) oder aus, bis man es einschaltet?
2. Joker-Regel **1 pro 4 Wochen, max. 2** (Vorschlag) passt, oder großzügiger/strenger?
3. Soll eine Deload-Woche schon mit **1 Training** zählen (Vorschlag), oder mit dem halben Ziel?
