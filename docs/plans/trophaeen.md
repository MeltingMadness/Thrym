# Plan: Trophäen aus der nordischen Mythologie

Stand 19.10.2026. Status: Entwurf, noch kein Code.

## Idee

Trophäen belohnen echte Leistung über lange Zeit. Jede Trophäe hat fünf Stufen, die immer schwerer werden. Die ersten kommen in den ersten Wochen, die letzte braucht Jahre.

Der Name der App gibt das Thema vor. **Thrym** ist in der Edda der Riese, der Thors Hammer **Mjölnir** stiehlt. Thor muss sich als Braut verkleiden, um ihn zurückzuholen. Daraus folgt die oberste Stufe: Wer sie erreicht, hat sich den Hammer zurückgeholt.

## Regeln gegen „zu nerdig“

- Nur Namen, die man aus Filmen, Serien und Spielen kennt: Thor, Odin, Mjölnir, Walhalla, Asgard, Yggdrasil, Fenrir, Wikinger, Berserker, Riese.
- Nicht: Einherjer, Gungnir, Ginnungagap, Runen als Schrift, altnordische Schreibweisen.
- Jede Trophäe zeigt immer, wofür es sie gibt, in Klartext: „Yggdrasil · 100 Trainings“. Der Mythos ist Würze, nie Rätsel.
- Ein Satz Hintergrund pro Trophäe im Detail, augenzwinkernd, nicht episch.
- Gestaltung schlicht: Symbol plus Metallfarbe, kein Fantasy-Kitsch, passend zu Liquid Glass und Ember.

## Stufen (für alle Trophäen gleich)

| Stufe | Name | Gefühl |
|---|---|---|
| 1 | Eisen | erste Wochen |
| 2 | Bronze | erste Monate |
| 3 | Silber | etwa ein Jahr dranbleiben |
| 4 | Gold | mehrere Jahre oder sehr stark |
| 5 | Mjölnir | selten, das Ziel für Ehrgeizige |

Metallnamen versteht jeder. Nur die oberste Stufe trägt den Mythos.

## Trophäen (Vorschlag)

| Trophäe | Wofür | Eisen | Bronze | Silber | Gold | Mjölnir |
|---|---|---|---|---|---|---|
| **Yggdrasil** (der Weltenbaum wächst) | abgeschlossene Trainings | 10 | 50 | 150 | 400 | 1.000 |
| **Riesenlast** (Thrym war ein Riese) | bewegte Gesamtlast | 10 t | 100 t | 500 t | 2.000 t | 10.000 t |
| **Heimdalls Wacht** (der Wächter, der nie schläft) | längste Wochen-Serie, siehe `wochenziel.md` | 4 | 12 | 26 | 52 | 104 Wochen |
| **Berserker** | neue persönliche Rekorde gesamt | 10 | 50 | 150 | 400 | 1.000 |
| **Fenrir** (der Wolf, der seine Ketten sprengt) | Comebacks: erstes Training nach mindestens 3 Wochen Pause | 1 | 3 | 5 | 10 | 20 |
| **Thors Hammer: Kniebeuge** | e1RM relativ zum Körpergewicht | 0,75× | 1,0× | 1,5× | 2,0× | 2,5× |
| **Thors Hammer: Bankdrücken** | e1RM relativ zum Körpergewicht | 0,5× | 0,75× | 1,0× | 1,25× | 1,5× |
| **Thors Hammer: Kreuzheben** | e1RM relativ zum Körpergewicht | 1,0× | 1,5× | 2,0× | 2,5× | 3,0× |
| **Walhalla** | Sammeltrophäe | alle anderen mind. auf Eisen | … Bronze | … Silber | … Gold | … Mjölnir |

Bei der Riesenlast hilft ein greifbarer Vergleich im Detail, zum Beispiel „so schwer wie ein Wikingerschiff“ (rund 20 t) oder „ein Blauwal“ (rund 150 t). Die Vergleiche kommen in die Texte, nicht in die Logik.

Fenrir belohnt bewusst das Zurückkommen. Die Gym-Megastudie (Milkman et al., Nature 2021) fand genau das wirksam, und es nimmt Schuldgefühle nach einer Pause.

## Was gezählt wird

- **Trainings:** abgeschlossene Einheiten, wie beim Wochenzähler.
- **Gesamtlast:** Summe Gewicht × Wiederholungen der Arbeitssätze. Aufwärmsätze zählen nicht. Bei Körpergewichtsübungen nur die Zusatzlast, damit keine Schätzung eingeht.
- **Rekorde:** dieselben Rekordereignisse, die Live-Erkennung und Rekord-Board schon nutzen. Nicht jede Rekordart einzeln, sonst lassen sich Trophäen mit Mini-Rekorden farmen.
- **Thors Hammer:** bestes e1RM der Übung geteilt durch das Körpergewicht am Tag des Satzes (nächster Messwert davor). Nur Sätze bis 10 Wiederholungen, weil e1RM darüber zu ungenau wird. Ohne Körpergewicht-Eintrag bleibt die Trophäe gesperrt mit Hinweis „Körpergewicht eintragen“.
  - Zugeordnet über die eingebauten Übungen mit ihrem kanonischen Namen: „Kniebeuge (Langhantel)“, „Bankdrücken (Langhantel)“, „Kreuzheben“ (Sumo zählt mit). Eigene Übungen zählen nicht.
- Eine Stufe bleibt erreicht, auch wenn ein Wert später sinkt (Körpergewicht steigt, Daten korrigiert). Ausnahme: Löscht jemand Trainings, wird neu berechnet. Das ist ehrlich und einfach.

## Daten und Kompatibilität

- Alles wird aus der vorhandenen Historie **berechnet**. Keine Room-Migration, keine Backup-Änderung, Golden-Backups unberührt.
- Damit eine Stufe nicht „verschwindet“, wenn ein Wert sinkt, wird nur das **Datum des ersten Erreichens** berechnet (aus der Historie), nicht gespeichert.
- Neu ist höchstens eine Einstellung „Trophäen anzeigen“ (an/aus) und lokal gemerkt, welche Stufen schon gefeiert wurden, damit die Feier nicht doppelt kommt. Beides Einstellungen, nicht im Backup. Nach einem Import erscheinen bereits erreichte Stufen still, ohne Feier.

## Architektur

- `:shared`, neues Paket `com.thrym.shared.trophies`: pure Funktion `TrophyEngine.evaluate(input): List<TrophyStatus>` im Stil von `readiness/`.
- Trophäen und Schwellen als Daten (eine Tabelle im Code), nicht als Logik je Trophäe. So lassen sich Schwellen später ohne Umbau anpassen.
- IDs als stabile englische Konstanten (`YGGDRASIL`, `GIANT_LOAD`, `HEIMDALL`, `BERSERKER`, `FENRIR`, `THOR_SQUAT`, `THOR_BENCH`, `THOR_DEADLIFT`, `VALHALLA`) und Stufen `IRON`, `BRONZE`, `SILVER`, `GOLD`, `MJOLNIR`. Nie umbenennen, nur Anzeige übersetzen.
- Heimdalls Wacht hängt an der Wochenziel-Engine und kommt deshalb nach ihr.

## Oberfläche

- **Trophäenhalle:** eigene Seite, erreichbar von Home und Statistik. Raster mit allen Trophäen, aktuelle Stufe, Fortschritt zur nächsten („noch 23 Trainings bis Silber“).
- **Abschlusskarte:** neue Stufe erreicht → eigene Zeile über dem Rekord-Board, mit kurzer Animation (aus bei reduzierter Bewegung).
- **Home:** keine Dauerwerbung. Höchstens ein Hinweis, wenn eine Stufe kurz bevorsteht („noch 2 Trainings bis Yggdrasil Bronze“).
- Barrierefreiheit: Stufe immer als Text, nie nur über Farbe.

## Texte (Beispiele)

| Deutsch | Englisch |
|---|---|
| Yggdrasil · 100 Trainings | Yggdrasil · 100 workouts |
| Der Weltenbaum wächst mit jedem Training. | The World Tree grows with every workout. |
| Riesenlast · 500 t bewegt | Giant's Load · 500 t lifted |
| Thrym war ein Riese. Du hebst inzwischen wie einer. | Thrym was a giant. You're starting to lift like one. |
| Fenrir · 3 Comebacks | Fenrir · 3 comebacks |
| Keine Kette hält dich auf. | No chain can hold you. |
| Mjölnir: Du hast dir den Hammer zurückgeholt. | Mjölnir: you took the hammer back. |

Eigennamen bleiben in beiden Sprachen gleich (Yggdrasil, Fenrir, Mjölnir). Gewichtseinheit folgt der Einstellung kg/lb.

## Umsetzung in Schritten

1. `:shared`: Engine, Trophäentabelle, Tests (Schwellen, Körpergewicht fehlt, Aufwärmsätze, Löschen, Sumo zählt, eigene Übungen zählen nicht).
2. Android: Trophäenhalle, Abschlusskarte, Texte EN+DE, Symbole.
3. iOS: dasselbe, Katalogeinträge von Hand.
4. Heimdalls Wacht, sobald das Wochenziel gemergt ist.
5. Doku: README, `docs/features/trophaeen.md`, CHANGELOG, Plan löschen.

## Bewusst nicht

- Trophäen für Trivialitäten („erste Übung angelegt“).
- Trophäen fürs reine Erscheinen an vielen Tagen hintereinander.
- Versteckte Trophäen mit Rätseln. Alles ist sichtbar und erklärt.

## Offene Fragen an Martin

1. **Kraft-Trophäen:** Gleiche Schwellen für alle sind einfach, wirken aber für Frauen und ältere Lifter deutlich härter. Vorschlag: so lassen und im Detail sagen, dass die Schwellen sportlich gemeint sind. Alternative: optionale Angabe von Geschlecht und Alter für angepasste Schwellen, was aber neue persönliche Daten bedeutet.
2. Passen die Namen, oder soll etwas raus oder rein (etwa **Odin** für Wissen, z. B. Pläne mit Progression abgeschlossen)?
3. Mjölnir als oberste Stufe für **alle** Trophäen (Vorschlag), oder nur für die Kraft-Trophäen?
