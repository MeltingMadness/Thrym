package com.thrym.shared.store

import com.thrym.shared.backup.BackupPayloadV1
import com.thrym.shared.backup.BackupPersonalRecord

/**
 * One-time repair of duplicate personal record ids in an existing state document.
 *
 * Rebuilding records could reuse an id that another record already owned. The payload validator
 * rejects duplicate ids, so a state that once got such an id could never be written again: every
 * mutation recomputed the same invalid candidate and the workout could not be finished.
 *
 * The repair keeps the first occurrence of every positive id, assigns the remaining records new
 * ids above the current maximum in payload order, and copies every other value unchanged. It never
 * removes a record, it is deterministic, and running it twice changes nothing.
 *
 * Only the durable store load path uses this. Backup imports stay strict, because an imported
 * document with duplicate ids is a defect of the export and not a state that has to be rescued.
 */
internal object PersonalRecordIdRepair {

    fun repair(payload: BackupPayloadV1): BackupPayloadV1 {
        val records = payload.personalRecords
        if (hasUniquePositiveIds(records)) return payload

        val seen = mutableSetOf<Long>()
        var nextId = (records.maxOfOrNull { it.id } ?: 0L) + 1L
        val repaired = records.map { record ->
            if (record.id > 0L && seen.add(record.id)) {
                record
            } else {
                while (nextId in seen) nextId += 1L
                val assignedId = nextId
                seen += assignedId
                nextId += 1L
                record.copy(id = assignedId)
            }
        }
        return payload.copy(personalRecords = repaired)
    }

    private fun hasUniquePositiveIds(records: List<BackupPersonalRecord>): Boolean {
        if (records.isEmpty()) return true
        if (records.any { it.id <= 0L }) return false
        return records.map { it.id }.toSet().size == records.size
    }
}
