# Plan: Erfolge, Level und Tagesform

Stand: 24. Oktober 2026. Entwurf, mit dem Nutzer abgestimmt; offene Punkte stehen am Ende. Nach dem Umsetzen wandert das Ergebnis in `docs/features/` und dieser Plan wird gelöscht.

Thrym soll Erfolge in den Mittelpunkt stellen und dabei deutlich verspielter werden: Level, Abzeichen und eine Feier am Trainingsende. Vorbilder sind Strava (Bestleistungen, Rückblicke), Apple Fitness (Abzeichen für Meilensteine) und Hevy/Strong (Rekorde je Übung). Gleichzeitig wird der Tagesform-Check-in umgebaut: Er wandert vom Tag an den Start jeder Einheit und lässt sich mit der Leistung vergleichen.

## Leitlinien

1. **Keine Strafen, kein Druck.** Pausen kosten nie XP, Level oder Abzeichen. Wer nach einer Pause zurückkommt, wird belohnt (Comeback). Eine gerissene Serie heißt „Neue Serie“, nie „verloren“, und wird nicht rot gezeigt. Es gibt keine Erinnerungen, die mit Verlust drohen („Deine Serie endet heute“).
2. **Langfristig statt Dauerfeuer.** Die ersten Level kommen in den ersten Wochen, danach im Abstand von Monaten. Abzeichen sind gestaffelt und selten. Ein Abschluss feiert höchstens drei Momente; alles Weitere steht still in der Erfolgsübersicht.
3. **Nur Siege.** Gefeiert werden echte Rekorde, Marken, übernommene Steigerungen, Level, Meilensteine und Comebacks. Keine zweit- oder drittbesten Leistungen, keine Teilnahmeabzeichen.
4. **Nordisch, nie abwertend.** Die Level erzählen die Þrymskviða als Heldenreise, ohne Knechte, Sklaven oder Kriegerpathos. **Keine Runen** als Symbole: Die Markenregeln 2 und 3 in [`brand/README.md`](../brand/README.md) erlauben nur das Zeichen ᚦ und keine Rune im Kreis. Kapitel zeigen stattdessen Hantelscheiben. Begriffe aus dem Walhall-Umfeld (Einherjer, Berserker) bleiben draußen.
5. **Abgeleitet, nicht gespeichert.** XP, Level, Abzeichen und Rekorde werden aus Trainings, Sätzen und Progressionsentscheidungen berechnet. Das Backup-Format ändert sich dafür nicht, alte IronLog-Backups bekommen ihre Erfolge rückwirkend, und Android und iOS rechnen gleich, weil die Regeln in `:shared` liegen. Neu gespeichert werden nur Nutzerangaben (Check-in je Einheit, optionales Profil) und der Stand „schon gefeiert“.

## Level und XP

### Heldenreise: acht Kapitel

Thrym hat Thors Hammer „acht Rasten unter der Erde“ versteckt. Die Level erzählen, wie man ihn zurückholt: acht Kapitel mit je drei Stufen, also 24 Level. Jedes Kapitel holt den Hammer eine Rast höher; mit dem letzten ist er zurück. Thrym ist dabei das Gewicht, gegen das man antritt, das Eisen auf der Hantel. Das Ende ist „Hammer heimgeholt“, nicht Thryms Tod.

| Kapitel | DE | EN | Szene |
|---|---|---|---|
| 1 | Der Ruf | The Call | Thor erwacht, der Hammer ist fort |
| 2 | Das Federkleid | The Feather Cloak | Loki leiht Freyjas Federgewand und sucht |
| 3 | Die Spur | The Trail | Thrym verrät: acht Rasten tief, nur gegen Freyja als Braut |
| 4 | Die List | The Ruse | Thor reist als Braut verkleidet |
| 5 | Der Wagen | The Chariot | Fahrt mit dem Bockgespann, Berge bersten |
| 6 | Jötunheim | Jotunheim | In der Halle des Riesenkönigs |
| 7 | Das Festmahl | The Feast | Ein Ochse, acht Lachse, drei Fässer Met |
| 8 | Der Hammer | The Hammer | Der Hammer liegt im Schoß der Braut, Thor greift zu |

- Angezeigt wird „Die Spur II“ mit „Kapitel 3 von 8“ und „Hammer noch 5 Rasten tief“ (Tiefe = 8 − Kapitel). Kapitel sind Stationen, keine Personen, und damit geschlechtsneutral.
- Jedes Kapitel hat ein Emblem aus Hantelscheiben in Seitenansicht. Jede Stufe legt eine Scheibe dazu (I, II, III), jedes Kapitel wechselt Farbe und Größe der Scheiben. Das Emblem ahmt nicht das Logo nach (kein Dorn, keine Hülsenspitze).
- Die Erfolgsübersicht zeigt zusätzlich acht Erdschichten, durch die der Hammer Rast für Rast nach oben steigt.
- Nach Kapitel 8 geht es endlos weiter mit „weiteren Taten“ (EN „Further Deeds“) als Sternen, je weitere 13.000 XP einer („Der Hammer · 2 ★“). Sie werden getrennt gezählt und nie als fortlaufende Levelnummer angezeigt, damit keine „Level 88“ entsteht.
- Pausen sind Rast auf dem Weg: Kapitel, Stufen und Sterne gehen nie verloren.

### Kurve

Schritt von Level n zu n+1: `round(300 · n^1,2 / 50) · 50` XP. Mit etwa 170 XP je Training und drei Trainings pro Woche:

| Level | Kapitel | XP gesamt | ≈ Trainings | ≈ nach |
|---|---|---|---|---|
| 2 | Der Ruf II | 300 | 2 | erste Woche |
| 4 | Das Federkleid I | 2.100 | 12 | 1 Monat |
| 7 | Die Spur I | 8.350 | 49 | 4 Monate |
| 10 | Die List I | 19.300 | 114 | 9 Monate |
| 13 | Der Wagen I | 35.300 | 208 | 16 Monate |
| 16 | Jötunheim I | 56.650 | 333 | 2 Jahre |
| 19 | Das Festmahl I | 83.650 | 492 | 3 Jahre |
| 22 | Der Hammer I | 116.400 | 685 | 4½ Jahre |
| 24 | Der Hammer III | 141.550 | 833 | 5⅓ Jahre |

### XP-Quellen

| Ereignis | XP | Grenze |
|---|---|---|
| Abgeschlossenes Training mit mindestens einem Arbeitssatz | 100 | eins je Kalendertag zählt voll, weitere am selben Tag 25 |
| Rekord (schlägt Bestwert aus früherem Training, wie `SessionRecords`) | 40 | je Übung einmal, höchstens 3 Übungen je Training |
| Neue Marke (`StrengthMarks`) | 60 | je Übung einmal |
| Übernommene Steigerung (Vorschlag `ACCEPTED`, `ProgressionAutoApply.isSafeIncrease`) | 30 | höchstens 3 je Training |
| Wochenziel erreicht | 75 | einmal je Woche |
| Comeback (vorheriges Training ≥ 21 Tage her) | 150 | |

- Höchstens 400 XP je Training. Das verhindert, dass viele Sätze oder ein frisch angelegtes Übungs-Duplikat XP erzeugen.
- Volumen bringt bewusst keine XP: Mehr Sätze sollen sich nicht lohnen, mehr Fortschritt schon.
- Das erste Training einer Übung bringt keinen Rekord (wie heute).
- Wer ein Training löscht, verliert dessen XP. Das ist eine Datenkorrektur, keine Strafe, und wird nicht gefeiert oder angezeigt.

## Abzeichen

Selten und gestaffelt. Jedes Abzeichen hat Stufen, gezeigt wird die höchste erreichte und die nächste mit Fortschritt.

| Abzeichen | Stufen |
|---|---|
| Trainings | 1, 10, 25, 50, 100, 250, 500, 1.000 |
| Wochenserie (Wochenziel in Folge, Puffer zählt) | 4, 12, 26, 52, 104 Wochen |
| Rekorde gesamt | 10, 50, 100, 250, 500 |
| Steigerungen im Plan | 10, 25, 50, 100 |
| Bewegte Last (Volumen gesamt) | 100 t, 500 t, 1.000 t, 5.000 t |
| Comeback | einmal, danach Zähler („3× zurückgekommen“) |

Etwa 30 Stufen insgesamt, verteilt über Jahre. Marken bleiben bei ihrer Übung (Übungsstatistik) und sind keine Abzeichen. Monatsherausforderungen gibt es vorerst nicht; sie können später zentral per Update kommen.

## Wo Erfolge erscheinen

### Im Training: dezent

- Bringt ein Satz einen Rekord, bekommt die Satzzeile einen kleinen Pokal mit „PR“ in Warnfarbe, der Übungskopf denselben Marker. Dazu eine kurze Erfolgs-Haptik.
- Die heutige Rekordmeldung oben (Snackbar bzw. Banner) entfällt.
- Eine Marke zeigt den Marker „100 kg“ statt „PR“.
- Screenreader: „Satz 3, 102,5 kg × 5, neuer Rekord“.

### Abschluss: die Feier

Nach „Beenden“ läuft eine kurze Sequenz in Schritten. Jeder Schritt lässt sich antippen, „Überspringen“ springt direkt zur bekannten Zusammenfassung.

1. **Geschafft.** Kennzahlen wie heute.
2. **XP.** Der Balken füllt sich mit dem Zuwachs („+240 XP“) und der Aufschlüsselung (Training, 2 Rekorde, Wochenziel).
3. **Level-Aufstieg**, nur wenn einer fällig ist. Das Emblem bekommt seine neue Scheibe, Haptik, Konfetti in Akzentfarben. Ein neues Kapitel bekommt einen eigenen, größeren Moment mit seiner Szene („Thor reist als Braut verkleidet“) und der neuen Tiefe des Hammers.
4. **Höhepunkte.** Höchstens drei aus Rekorden, Marken, neuen Abzeichen und Comeback, nach Gewicht sortiert. Der Rest steht in der Zusammenfassung.

Bei reduzierter Bewegung gibt es keine Animation und kein Konfetti, die Schritte stehen sofort da. Ein Teilen-Knopf erzeugt ein Bild der Höhepunkte (später, Etappe 3).

### Startseite

- Der Level-Ring ersetzt den berechneten Bereitschafts-Ring: Emblem in der Mitte, daneben „Kapitel 3 von 8“ und „Die Spur II“, der Ring zeigt den Fortschritt zur nächsten Stufe („Hammer noch 5 Rasten tief · 1.240 XP bis Stufe III“).
- Darunter das nächste erreichbare Abzeichen mit Balken („Noch 4 Trainings bis 50“).
- Ein Streifen „Letzte Erfolge“ mit den letzten drei bis fünf Siegen der letzten 30 Tage (Rekord, Marke, Steigerung, Level), ein Tipp öffnet die Erfolge.
- Der Deload-Hinweis und „Leichter einsteigen“ bleiben.

### Verlauf: „Trainings | Erfolge“

Kein neuer Tab. Der Verlauf bekommt oben einen Umschalter wie die Pläne (`PlansModeSwitcher` / `IOSPlansModeSwitcher`):

- **Trainings:** der heutige Verlauf.
- **Erfolge:** Kapitel mit Emblem, XP und dem Bild der acht Rasten, die Abzeichen als Raster (gesperrte gedimmt mit Fortschritt), Rekorde je Übung (bester Wert je Kennzahl mit Datum), später Kraftstufen und Monats-/Jahresrückblick.

## Tagesform je Einheit

Der Tages-Check-in (Schlaf, Energie, Stress auf 1 bis 5, an das Datum gebunden) und der Bereitschafts-Ring entfallen. Stattdessen:

- **Beim Trainingsstart** erscheint ein kurzes Blatt mit drei Fragen auf einer Skala von 1 bis 10: Energie („Wie viel Energie hast du heute?“), Schlaf („Wie gut hast du geschlafen?“), Stress („Wie entspannt bist du?“). Bei allen gilt 10 als gut. Die drei Fragen stehen als Drehräder nebeneinander (iOS Picker im Stil `.wheel`, Android eine einrastende Liste mit Haptik je Raste); ganz oben steht „–“ für keine Angabe. „Überspringen“ und das Weglassen einzelner Fragen sind erlaubt. Eine Einstellung schaltet das Blatt ab.
- **Gespeichert** wird die Antwort an der Einheit: additiv ein neues Feld `sessionCheckIns` im Readiness-Dokument (`sessionId`, `energy`, `sleep`, `stress`, je 1..10 oder `null`, `recordedAtEpochMillis`), in Room, im iOS-Store und im Backup. Die Backup-Schemaversion steigt; alte Tages-Check-ins bleiben im Backup lesbar und werden nicht umgerechnet, weil sich 1..5 nicht sauber auf 1..10 abbilden lässt.
- **Leistung dagegen:** Je Einheit ein Leistungswert aus dem Trainingstrend (`TrainingTrendEngine`: Leistung je Übung gegenüber dem Median vergleichbarer Einheiten, gemittelt über die Übungen). Die Statistik zeigt ab zehn Einheiten mit Check-in, wie sich Energie, Schlaf und Stress auf die Leistung auswirken: als Punktwolke und als Satz („Nach Schlaf ab 8 schaffst du im Schnitt 4 % mehr“). Unter zehn Einheiten steht „Noch 6 Einheiten, dann siehst du, wie deine Tagesform deine Leistung beeinflusst“.
- **Deload-Hinweis:** bleibt, weil er aus der Trainingslast kommt, nicht aus dem Check-in.

## Kraftstufen (letzte Etappe)

- Optional ein Profil mit Geschlecht, Alter und Körpergewicht. Nur wenn alle drei vorliegen, gibt es Kraftstufen; sonst fallen sie ganz weg (kein Schätzen).
- Je Übung mit bekanntem Standard (zunächst Kniebeuge, Bankdrücken, Kreuzheben, Schulterdrücken, Klimmzüge) eine Stufe nach e1RM im Verhältnis zum Körpergewicht: Einsteiger, Fortgeschritten, Erfahren, Stark, Elite. Ein Aufstieg ist ein Sieg und wird gefeiert.
- Die Tabellen stammen aus einer frei nutzbaren Quelle, die Lizenz wird vor dem Umsetzen geprüft.

## Etappen

Jede Etappe ist ein eigener PR auf beiden Plattformen, mit Tests in `:shared` und Doku.

1. **Rekord-Marker und Feier.** PR-Marker am Satz statt der Rekordmeldung; die Abschluss-Sequenz mit den Schritten 1 und 4 (Höhepunkte aus den bestehenden Rekorden, Marken und Fakten).
2. **XP und Level.** `GamificationEngine` in `:shared` (XP je Training, Level, Kapitel, Sterne), XP- und Level-Schritt in der Sequenz, Level-Ring auf der Startseite, Stand „schon gefeiert“ je Gerät.
3. **Abzeichen und Erfolge.** Abzeichenregeln, Umschalter „Trainings | Erfolge“ im Verlauf, „Letzte Erfolge“ auf der Startseite, Teilen-Bild.
4. **Tagesform je Einheit.** Check-in-Blatt beim Start, `sessionCheckIns` mit Migration und Backup-Schemasprung, Vergleich mit der Leistung, Abbau von Tages-Check-in und Bereitschafts-Ring.
5. **Kraftstufen und Rückblicke.** Profil, Kraftstufen, Monats- und Jahresrückblick.

Etappe 4 hängt nicht von 1 bis 3 ab und kann vorgezogen werden. Der Bereitschafts-Ring verschwindet aber erst, wenn der Level-Ring da ist (Etappe 2).

## Technik

- **Shared:** neues Paket `com.thrym.shared.gamification` mit `XpRules`, `LevelCurve`, `Chapters`, `Badges` und `GamificationEngine.evaluate(history) → GamificationState` (XP gesamt, Level, Kapitel, Fortschritt, Abzeichenstufen, Ereignisse je Training). Eingabe sind dieselben schlanken Daten wie für Wochenziel und Marken: Startzeiten, Arbeitssätze, Rekorde, Marken und Progressionsentscheidungen. Reine Funktionen, getestet in `commonTest`.
- **Gefeiert-Stand:** je Gerät die zuletzt gefeierte XP-Summe und Abzeichenstufen (Android DataStore, iOS UserDefaults). Nur daran entscheidet sich, ob die Sequenz einen Aufstieg zeigt. Nach einem Backup-Import wird der Stand auf den importierten Wert gesetzt, damit kein Feuerwerk über Jahre nachgeholt wird.
- **Leistung:** Die Berechnung über den ganzen Verlauf muss auch bei 1.000 Trainings schnell bleiben; sie läuft im Hintergrund und wird je Training inkrementell fortgeschrieben.
- **Texte:** alle Kapitel- und Abzeichennamen in Englisch und Deutsch (Tabelle oben).
- **Abschalten:** Einstellung „Spielelemente anzeigen“ (Standard an, siehe offene Punkte).

## Offene Punkte

1. **XP-Werte:** die Tabelle oben ist ein Vorschlag; Feinschliff, sobald die Engine echte Verläufe durchrechnen kann.
2. **Abschalten:** Soll „Spielelemente anzeigen“ alles ausblenden (Level, Abzeichen, Feier) oder nur die Feier?
3. **Profil im Backup:** Körpergewicht, Alter und Geschlecht gehören eher ins Backup (additives Feld) als in die Einstellungen, die bei einem Gerätewechsel verloren gehen.
