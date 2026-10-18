package com.thrym.shared.workout

/**
 * Text form of a stored [SessionExerciseOrder] for app preferences (Android DataStore,
 * iOS UserDefaults): `v1|<sessionId>|<sessionStartEpochMillis>|<key>,<key>,...`.
 *
 * Session id and start time guard against a payload left behind by another session, for
 * example after an import reused the database id.
 */
object SessionExerciseOrderCodec {
    private const val VERSION = "v1"
    private const val FIELD_SEPARATOR = '|'
    private const val KEY_SEPARATOR = ','
    private val keyPattern = Regex("[a-z]+-[0-9]+")

    /** True for keys like `planned-12`, `target-7` or `adhoc-3`. */
    fun isValidKey(key: String): Boolean = keyPattern.matches(key)

    /**
     * Encodes [keys] for the given session. Invalid keys are skipped and only the first of
     * duplicate keys is kept, so the result always decodes.
     */
    fun encode(
        sessionId: Long,
        sessionStartEpochMillis: Long,
        keys: List<String>,
    ): String {
        val validKeys = keys.filter(::isValidKey).distinct()
        return listOf(
            VERSION,
            sessionId.toString(),
            sessionStartEpochMillis.toString(),
            validKeys.joinToString(KEY_SEPARATOR.toString()),
        ).joinToString(FIELD_SEPARATOR.toString())
    }

    /**
     * Returns the stored keys, or null when the version, session id or start time differ or
     * the payload is malformed. Duplicate keys are dropped; the first occurrence wins.
     */
    fun decode(
        raw: String,
        expectedSessionId: Long,
        expectedSessionStartEpochMillis: Long,
    ): List<String>? {
        val fields = raw.split(FIELD_SEPARATOR)
        if (fields.size != 4) return null
        val (version, sessionId, sessionStart, encodedKeys) = fields
        if (version != VERSION) return null
        if (sessionId != expectedSessionId.toString()) return null
        if (sessionStart != expectedSessionStartEpochMillis.toString()) return null
        if (encodedKeys.isEmpty()) return emptyList()

        val keys = encodedKeys.split(KEY_SEPARATOR)
        if (!keys.all(::isValidKey)) return null
        return keys.distinct()
    }
}
