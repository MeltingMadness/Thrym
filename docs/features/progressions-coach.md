# Progressions-Coach

Der Coach schlägt nach einem planbasierten Workout neue Zielwerte pro Planübung vor. **Ohne deine Zustimmung ändert er einen Plan nicht.** Erst wenn du einen Vorschlag bestätigst, wird der Plan angepasst.

**Steigerungen automatisch übernehmen** (Einstellungen → Training, Standard aus): Ist die Einstellung an, übernimmt die App direkt nach dem Beenden eines Trainings die sicheren Steigerungen dieses Trainings in den Plan, als hättest du sie unverändert bestätigt. Sicher sind nur Vorschläge (`PROPOSE_CHANGE`) mit den Gründen `REP_TARGET_ADVANCED`, `LOAD_ADVANCED`, `TOTAL_REPS_COMPLETED` und `RPE_WITHIN_TARGET` (`ProgressionAutoApply` in `:shared`). Backoffs, wiederholte Ziele, abweichende Lasten und Hinweise warten weiter unter „Progression prüfen“. Pro Planposition wird höchstens ein Vorschlag übernommen; ein inzwischen geänderter Plan macht ihn wie bei einer Bestätigung `STALE`. Android liest die Einstellung im `ActiveWorkoutViewModel`, iOS gibt sie mit dem Befehl `workout.finish` an `ProgressionLifecycle.autoApplySafeIncreases`.

Fachliche Grundlage: [`../research/2026-08-08-progression-schemes.md`](../research/2026-08-08-progression-schemes.md).

## Ablauf

1. **Plan-Editor:** Pro Planübung ein Schema wählen („Progression: Aus“ oder Schemaname). Neue und bestehende Übungen starten mit **Aus** (`MANUAL`).
2. **Workout-Start:** Die App speichert pro Planübung einen unveränderlichen Snapshot aus Zielwerten, Schema und Konfiguration (`workout_plan_targets`). Jeder Satz verweist auf seine Planposition (`planTargetSnapshotId`). Spätere Planänderungen deuten das Training nicht mehr um. Die Reihenfolge auf dem Trainingsbildschirm ist davon getrennt; Verschieben ändert den Snapshot nicht.
3. **Während des Workouts:** Keine vorläufigen Vorschläge. Angezeigt werden Schema, Ziel und Hinweise:
   - Gewicht: das zuletzt trainierte Gewicht, sonst das Planziel
   - RPE/RIR-Schema: Ziel-RPE als Platzhalter (bei RIR: 10 − Ziel-RPE)
4. **Workout beenden:** Danach erzeugt der Coach die Ergebnisse für reguläre Einheiten. Explizit als Deload gespeicherte Einheiten erhalten keine Progressionsauswertung. Ein Fehler im Coach hält das Workout nicht offen. Das Workout bleibt gespeichert, die Auswertung lässt sich wiederholen und ist idempotent.
5. **Review-Screen** „Progression prüfen“: pro Übung Schema, `alt → neu`, gewertete Sätze und Begründung. Aktionen: **Übernehmen**, **Bearbeiten**, **Verwerfen** sowie **Alle sicheren übernehmen**. Reine Hinweise brauchen keine Entscheidung. Schließen lässt Offenes offen.
6. **Dashboard:** Solange Vorschläge offen sind, erscheint „N Progressionsvorschläge offen · Prüfen“.
7. **Frühere Auswertungen:** Ohne Trainingsbezug zeigt der Review-Screen unter den offenen Vorschlägen die letzten 50 entschiedenen, veralteten und reinen Hinweis-Ergebnisse (nur lesend). Erreichbar ist er auch ohne offene Vorschläge über das Symbol in der Planliste.

## Begriffe

- **Gezählte Arbeitssätze (Revision 2):** die ersten `targetSets` Sätze vom Typ `NORMAL` oder `FAILURE` einer Planposition. Die Markierung „Versagen“ beschreibt die Ausführung; ob das Ziel geschafft wurde, entscheidet die erreichte Leistung. Aufwärm-, Drop- und Backoff-Sätze sowie zusätzliche Arbeitssätze werden nicht gewertet. Ein Zusatzsatz kann einen verfehlten Pflichtsatz nicht ersetzen.
- **Trainiertes Gewicht:** Bei gleicher Last (Toleranz 0,1 kg) bauen Vorschläge auf dem tatsächlich trainierten Gewicht auf. Bei gemischten Lasten gilt das niedrigste Gewicht aller gewerteten Sätze als gemeinsam bestätigte Last. Eine Steigerung ist nur möglich, wenn jeder gewertete Satz mindestens die Planlast erreicht und die jeweilige Erfolgsbedingung erfüllt. Eine niedrigere Last oder ein verfehltes Ziel bei gemischten Lasten ergibt `MIXED_LOADS`: Planziel beibehalten, keine Fehlversuchszählung.
- **Vergleichbare Einheiten:** gleiche Planposition, Zielwerte, Konfiguration und Regelrevision sowie tatsächlich gewertete Last innerhalb der Toleranz. Eine andere Last oder unvollständige Daten beenden die Vergleichsserie. Explizite Deloads werden übersprungen.
- **Erfolgsserie (linear):** aufeinanderfolgende vergleichbare Erfolge. Ein nicht erfolgreicher oder unzureichend belegter Versuch beendet sie. Die einstellbare Anzahl wird als „Erfolge bis Steigerung“ angezeigt.
- **Fehlversuch:** Die Regelbedingung wurde verfehlt. Erfolg, neue Zielwerte oder geänderte Konfiguration beenden die Fehlversuchsserie. Hinweise ohne belastbare Erfolgs-/Misserfolgswertung zählen nicht als Fehlversuch.

## Deload und Aktualität

- Eine Einheit mit `isDeload == true` erzeugt weder einen regulären Änderungsvorschlag noch einen Fehlversuch. Sie wird auch beim Nachholen fehlender Auswertungen und beim Aufbau der Fehlversuchsserie übersprungen. Eine Entlastungseinheit allein setzt die Serie nicht zurück. Alte Einheiten mit unbekanntem Kontext (`null`) behalten ihre bisherige Behandlung; der Kontext wird nicht aus einem niedrigeren Gewicht geraten.
- Ein neueres abgeschlossenes Training mit mindestens einem Nicht-Aufwärmsatz derselben Planposition macht einen älteren offenen Vorschlag `STALE`. Das gilt auch bei „Ziel wiederholen“, einem verworfenen Ergebnis oder noch fehlender Auswertung. Maßgeblich sind Abschlusszeit und bei Gleichstand die Session-ID, nicht die Erstellungszeit oder der Status des Vorschlags.
- Laufende Einheiten, reine Aufwärmeinheiten, unbenutzte Planpositionen und explizite Deloads verdrängen einen regulären Vorschlag nicht. Andere Übungen oder Planpositionen bleiben unabhängig.
- Bereits vorhandene offene Deload-Vorschläge werden beim Abgleich veraltet und können nicht übernommen werden. Schon angenommene oder verworfene Entscheidungen und gespeicherte Planziele werden nicht nachträglich umgeschrieben.
- Der Abgleich erfolgt bei Auswertung, Nachholen und Review. Vor der Übernahme prüft die Transaktion die Aktualität erneut, auch wenn die Ansicht noch einen älteren Stand zeigt.

Fallkatalog und Prüfumfang: [`../validation/2026-09-28-progression-reliability.md`](../validation/2026-09-28-progression-reliability.md).

## Schemata

| Schema | Erfolg, wenn … | Vorschlag bei Erfolg |
|---|---|---|
| **Linear** | alle gezählten Sätze ≥ `targetReps`, über die eingestellte Zahl vergleichbarer erfolgreicher Einheiten (1–6, Standard 1) | Gewicht + Schrittweite; bis dahin Ziel beibehalten mit Erfolgszähler |
| **Doppelte Progression** (`minReps ≤ targetReps ≤ maxReps`) | alle gezählten Sätze ≥ `targetReps` | unterhalb der Obergrenze: niedrigste tatsächlich erreichte Wiederholungszahl + 1, gleiche Last. Sobald alle Sätze `maxReps` erreichen: Gewicht + Schrittweite, Wiederholungsziel zurück auf `minReps` |
| **Gesamtwiederholungen** | Summe der gezählten Wdh. ≥ `targetTotalReps` (Verteilung egal) | Gewicht + Schrittweite |
| **RPE/RIR** | alle gezählten Sätze ≥ `targetReps` **und** höchstes RPE ≤ Ziel-RPE + Toleranz | Gewicht + Schrittweite |

Bei **RPE/RIR** gilt die Prüfreihenfolge:

1. Wiederholungen verfehlt → Fehlversuch. Ein fehlendes RPE kann einen echten Fehlversuch nicht verdecken.
2. RPE fehlt oder ist ungültig (nicht zwischen 1 und 10) → Hinweis `RPE_MISSING` bzw. `RPE_INVALID`. Das ist kein Fehlversuch und setzt die Zählung auch nicht zurück.
3. RPE zu hoch → „Ziel wiederholen“, **zählt als Fehlversuch**. So kann auch hier ein Backoff greifen.

**Vorbelegungen im Editor:** Schrittweite 2,5 kg bzw. 5 lb. Doppelte Progression: `min = targetReps`, `max = targetReps + 2`. Gesamtwiederholungen: `targetSets × targetReps`. RPE: Ziel 8, Toleranz 0,5.

**Beispiel linear:** 3 × 8 bei 100 kg, Schritt 2,5 kg, zwei Erfolge eingestellt. Nach der ersten vollständig geschafften Einheit zeigt der Coach „1 von 2“, nach der zweiten schlägt er 102,5 kg vor. Ein Deload dazwischen zählt nicht mit. Die neue Last landet erst nach Bestätigung im Plan.

**Nachvollziehbarkeit:** Android und iOS zeigen die gewerteten Arbeitssätze, enthaltene Versagen-Sätze und die Zahl ausgelassener Aufwärm-, Drop-, Backoff- und Zusatzsätze. Bei gemischten Lasten wird die gemeinsame Mindestlast erklärt; bei Erfolgsbestätigung oder Wiederholung erscheint der passende Erfolgs- bzw. Fehlversuchszähler.

**Eigengewichtsübungen** (Kategorie `EIGENGEWICHT`): Das gespeicherte Gewicht ist die Zusatzlast, 0 kg heißt reines Körpergewicht. Der Coach rechnet damit wie mit jeder anderen Last. Die doppelte Progression steigert zuerst die Wiederholungen bis `maxReps` und schlägt erst dann + Schrittweite als Zusatzlast vor (etwa Klimmzüge mit 8–12 Wdh.: 3 × 8 → … → 3 × 12, danach 3 × 8 mit + 2,5 kg). Linear schlägt nach der eingestellten Zahl von Erfolgen (Standard 1) + Schrittweite vor, Gesamtwiederholungen und RPE/RIR nach einem Erfolg, jeweils auch von 0 kg aus. Der Standard bleibt das manuelle Schema, das nichts vorschlägt.

## Fehlversuche und Backoff

- Ein einzelner Fehlversuch ergibt „Ziel wiederholen“ (`REPEAT_TARGET`).
- Erreicht die Folge die **Fehlversuchsschwelle** (1–6, Standard 2), schlägt der Coach einen **Backoff** vor (`STALL_BACKOFF`). Die Basis ist das trainierte Gewicht × (1 − Backoff %), Backoff 1–30 %, Standard 10 %.

## Rundung

Gerechnet wird in der Einheit, in der die Schrittweite konfiguriert wurde (kg oder lb). Gespeichert wird immer in kg.

- **Steigerung:** trainiertes Gewicht + genau eine konfigurierte Schrittweite. Die Gewichtsreihe bleibt relativ zur trainierten Last, beispielsweise 4 → 5,5 → 7 kg bei 1,5 kg Schrittweite.
- **Backoff:** Die gewünschte prozentuale Reduktion wird in ganze Schritte umgerechnet und auf die nächste Schrittzahl gerundet (bei Gleichstand aufwärts). Mindestens ein Schritt wird vom trainierten Gewicht abgezogen, aber nie unter 0 (`BACKOFF_FLOOR_REACHED`). Es wird nicht auf ein absolutes Raster ab 0 gerundet.

## Übernehmen ist atomar

„Übernehmen“ und „Alle sicheren übernehmen“ laufen in einer Room-Transaktion. Die Transaktion prüft:

- Der Vorschlag ist noch offen.
- Der Plan hat dieselbe Übung an derselben Position.
- Ziel, Schema, Konfiguration und Regelrevision entsprechen dem Snapshot.
- Die Quelle ist eine abgeschlossene reguläre Einheit und es gibt kein neueres relevantes Training derselben Planposition.

Passt etwas nicht, wird nichts übernommen und der Vorschlag wird als **nicht mehr aktuell** (`STALE`) markiert.

Wird ein Satz eines abgeschlossenen Trainings im Verlauf korrigiert oder gelöscht, werden die offenen Vorschläge dieses Trainings ebenfalls `STALE`. Sie beruhen auf den alten Werten. Bereits entschiedene Vorschläge bleiben unverändert. Android und iOS verhalten sich hier gleich.

## Status eines Ergebnisses

`PENDING` (offen) · `INFORMATIONAL` (Hinweis) · `ACCEPTED` · `REJECTED` · `STALE`

Nur `PENDING` zählt für den Dashboard-Hinweis.

## Code

| Teil | Ort |
|---|---|
| Modelle, Grundcodes (`ProgressionReasonCode`) | `core/model/.../domain/model/Progression.kt` |
| Engine und Regeln (Revisionen 1 und 2) | `shared/.../progression/ProgressionEngine.kt`; Android-Adapter in `core/common/.../domain/progression/` |
| Persistenz | `ProgressionDao`, Tabellen `workout_plan_targets`, `progression_suggestions` |
| Repository | `data/.../repository/ProgressionRepositoryImpl` |
| UI | `feature/progression` (Review), `feature/plans` (Konfiguration), `feature/workout` (Hinweise) |
| Lifecycle-Test (Emulator) | `app/src/androidTest/.../ProgressionCoachLifecycleTest.kt` |

## Bestandsdaten und Backup

Aktuell gilt **Regelrevision 2**, Datenbank- und Backup-Schema **15**. Neue aktive Konfigurationen verwenden Revision 2. Die Room-Migration 14→15 und der Import beziehungsweise das Laden des iOS-Stores aktualisieren bekannte aktive Plan-Konfigurationen von Revision 1 auf 2; die neue Erfolgsbestätigung beginnt mit dem Standardwert 1. Manuelle Pläne bleiben manuell.

Historische Trainingssnapshots, Ergebnisse und Entscheidungen behalten ihre Revision und Werte. Revision 1 bleibt auswertbar: Sie zählt nur `NORMAL`, verlangt gleiche Lasten und behält die bisherige Wiederholungssteigerung. Ein offener alter Vorschlag passt nach der Planumstellung nicht mehr zur aktuellen Konfiguration und wird beim Abgleich `STALE`. Ältere Backups ohne Erfolgsbestätigung bleiben lesbar; neue Backups speichern die Bestätigung und den Satztyp `BACKOFF` auf beiden Plattformen.

Jede weitere fachliche Regeländerung braucht eine neue **Regelrevision**.

## Individuelle Satzvorgaben (manuelle Progression)

Eine Planübung kann statt `targetSets × targetReps @ Gewicht` eine Liste einzelner Satzvorgaben haben (`setTargetsJson`, Modell `PlannedSet` in `:shared`). Diese Vorgaben landen im Snapshot beim Workout-Start.

Solche Vorgaben bleiben bewusst beim manuellen Schema: Top-Satz und leichtere Backoff-Sätze bilden unterschiedliche Ziele, die ein gemeinsamer Gewichtsschritt nicht passend beschreibt. Ein mit `FAILURE` protokollierter Satz erfüllt dabei eine normale Satzposition; ein expliziter `BACKOFF` seine Backoff-Position. Ältere als `NORMAL` gespeicherte Backoff-Sätze bleiben zuordenbar.

Nach dem Workout bietet die Zusammenfassung „Planänderungen prüfen“ an. Dort lassen sich die **tatsächlich absolvierten** Satzwerte ausdrücklich als neue Vorgaben übernehmen („Satzwerte übernehmen“) oder der Plan bleibt unverändert. Offene, nicht absolvierte Vorgaben bleiben erhalten. Hat sich der Plan seit dem Workout-Start geändert, wird die Übernahme abgelehnt.

## Plattformen

Die Regeln liegen im gemeinsamen Kern (`:shared`, `progression`). Android ruft sie über `PortableProgressionAdapter` auf, die iOS-App direkt. Der Ablauf mit Review und Bestätigung ist auf beiden Plattformen gleich.

## Nicht enthalten

Prozentwellen, Training-Max-Blöcke, Periodisierung, automatische Planänderungen ohne Bestätigung, KI- oder Cloud-Empfehlungen, medizinische Bewertung.
