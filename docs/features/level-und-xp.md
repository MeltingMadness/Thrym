# Level und XP

Thrym zeigt den Weg durch die Thrymskvida, die Sage vom gestohlenen Hammer: acht Kapitel mit je drei Stufen, auf Android und iOS gleich. XP, Level und Kapitel werden aus dem Trainingsverlauf berechnet und nie gespeichert. Room-Schema und Backup bleiben unverändert, alte Trainings und importierte IronLog-Backups bekommen ihren Stand rückwirkend. Pausen kosten nichts.

Die Regeln liegen in `:shared` (`shared/src/commonMain/kotlin/com/thrym/shared/gamification/`), getestet in `LevelCurveTest` und `GamificationEngineTest`. Android liest die Daten über `GamificationRepository`, iOS über `IOSGamification`; beide rechnen im Hintergrund.

## XP

| Ereignis | XP | Grenze |
|---|---|---|
| Abgeschlossenes Training mit mindestens einem Arbeitssatz | 100 | eins je Kalendertag voll, weitere am selben Tag 25 |
| Rekord (wie `SessionRecords`) | 40 | je Übung einmal, höchstens 3 Übungen je Training |
| Neue Marke (`StrengthMarks`) | 60 | je Übung einmal |
| Übernommene Steigerung (`ACCEPTED`, `ProgressionAutoApply.isSafeIncrease`) | 30 | höchstens 3 je Training |
| Wochenziel erreicht | 75 | einmal je Woche, auch wenn die Anzeige des Ziels aus ist |
| Comeback (vorheriges Training mindestens 21 Tage her) | 150 | |

- Höchstens 400 XP je Training; was darüber liegt, fällt in der Aufschlüsselung von unten weg.
- Volumen bringt keine XP. Das erste Training einer Übung bringt keinen Rekord.
- Wer ein Training löscht, verliert dessen XP. Das wird nicht gefeiert und nicht angezeigt.

## Level, Kapitel und Sterne

- Der Schritt von Level n zu n+1 kostet `round(300 · n^1,2 / 50) · 50` XP (`LevelCurve`). 24 Level ergeben 8 Kapitel mit den Stufen I bis III. Die Levelnummer erscheint nirgends.
- Kapitel: Der Ruf, Das Federkleid, Die Spur, Die List, Der Wagen, Jötunheim, Das Festmahl, Der Hammer. Der Hammer liegt anfangs acht Rasten tief, jedes Kapitel holt ihn eine Rast höher („Hammer noch 5 Rasten tief“), im letzten ist er „heimgeholt“.
- Nach Kapitel 8 folgen „weitere Taten“ als Sterne, je 13.000 XP einer („Der Hammer · 2 ★“).
- Jede Stufe hat ein Emblem: eine bis drei Scheiben auf der Hülse, im Material des Kapitels. Android `core/designsystem/src/main/res/drawable-nodpi/emblem_chapter_N_tier_M.webp`, iOS `Assets.xcassets/Emblems/emblem-N-M`.

## Anzeige

- **Startseite:** Karte unter dem Hauptblock mit Emblem im Fortschrittsring, „Kapitel 3 von 8“, „Die Spur II“ und „Hammer noch 5 Rasten tief · 1.240 XP bis Stufe III“. Auf Stufe III nennt sie das nächste Kapitel, nach dem letzten Level die nächste Tat. Bereitschaft und Tagesform bleiben bis zur Etappe 4 des Plans.
- **Feier:** Schritt „Erfahrung“ mit „+245 XP“, Balken und Aufschlüsselung. Beim Aufstieg folgt „Neue Stufe“, „Neues Kapitel“ (mit der Szene des Kapitels und der Tiefe des Hammers) oder „Weitere Tat“, mit Konfetti und Erfolgs-Haptik. Details in [`../design-system.md`](../design-system.md).
- **Einstellungen:** Abschnitt „Erfolge“ mit „Spielelemente anzeigen“ (Standard an). Aus verschwinden Level-Karte und Feier, die XP zählen weiter, die Rekord-Marker im Training bleiben. Es gibt keinen Eintrag im Backup; die Einstellungen sind wie immer nicht Teil davon.

## Schon gefeiert

Jedes Gerät merkt sich die zuletzt gefeierte XP-Summe (Android DataStore, iOS UserDefaults). Nur daran entscheidet sich, ob die Feier einen Aufstieg zeigt, also auch einen Aufstieg, der durch rückwirkende XP entstand. Ohne Stand (erste Nutzung, nach Backup-Import oder Wiederherstellung) vergleicht die Feier mit dem Stand vor dem Training, sodass nichts über Jahre nachgeholt wird.

Auf Android wartet die Feier bis zu 3 Sekunden auf den Coach, damit automatisch übernommene Steigerungen schon zählen. Auf iOS gehören sie zum Beenden des Trainings.
