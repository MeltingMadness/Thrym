package com.thrym.shared.workout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionExerciseOrderCodecTest {

    private val sessionId = 42L
    private val start = 1_760_781_600_000L

    @Test
    fun roundTripKeepsKeysInOrder() {
        val keys = listOf("planned-3", "adhoc-7", "target-12")

        val encoded = SessionExerciseOrderCodec.encode(sessionId, start, keys)

        assertEquals("v1|42|1760781600000|planned-3,adhoc-7,target-12", encoded)
        assertEquals(keys, SessionExerciseOrderCodec.decode(encoded, sessionId, start))
    }

    @Test
    fun emptyOrderRoundTrips() {
        val encoded = SessionExerciseOrderCodec.encode(sessionId, start, emptyList())

        assertEquals("v1|42|1760781600000|", encoded)
        assertEquals(emptyList(), SessionExerciseOrderCodec.decode(encoded, sessionId, start))
    }

    @Test
    fun otherSessionStartOrVersionDecodesToNull() {
        val encoded = SessionExerciseOrderCodec.encode(sessionId, start, listOf("planned-1"))

        assertNull(SessionExerciseOrderCodec.decode(encoded, sessionId + 1, start))
        assertNull(SessionExerciseOrderCodec.decode(encoded, sessionId, start + 1))
        assertNull(SessionExerciseOrderCodec.decode(encoded.replaceFirst("v1", "v2"), sessionId, start))
    }

    @Test
    fun malformedPayloadDecodesToNull() {
        val malformed = listOf(
            "",
            "v1",
            "v1|42|1760781600000",
            "v1|42|1760781600000|planned-1|adhoc-2",
            "v1|042|1760781600000|planned-1",
            "v1|42|abc|planned-1",
            "v1|42|1760781600000|planned-1,",
            "v1|42|1760781600000|,planned-1",
            "v1|42|1760781600000|planned-1,,adhoc-2",
            "v1|42|1760781600000|Planned-1",
            "v1|42|1760781600000|planned-x",
            "v1|42|1760781600000|planned-1 ",
            "v1|42|1760781600000|planned-1;adhoc-2",
            "v1|42|1760781600000|single-planned-1",
            "V1|42|1760781600000|planned-1",
        )

        for (raw in malformed) {
            assertNull(SessionExerciseOrderCodec.decode(raw, sessionId, start), raw)
        }
    }

    @Test
    fun duplicateKeysDecodeToFirstOccurrence() {
        assertEquals(
            listOf("planned-1", "adhoc-2"),
            SessionExerciseOrderCodec.decode(
                "v1|42|1760781600000|planned-1,adhoc-2,planned-1",
                sessionId,
                start,
            ),
        )
    }

    @Test
    fun encodeSkipsInvalidAndDuplicateKeys() {
        val encoded = SessionExerciseOrderCodec.encode(
            sessionId,
            start,
            listOf("planned-1", "adhoc-2|v1", "planned-3,adhoc-4", "planned-1", "", "target-5"),
        )

        assertEquals("v1|42|1760781600000|planned-1,target-5", encoded)
        assertEquals(
            listOf("planned-1", "target-5"),
            SessionExerciseOrderCodec.decode(encoded, sessionId, start),
        )
    }

    @Test
    fun keyValidationMatchesBothAppsKeys() {
        listOf("planned-1", "adhoc-25", "target-1234567890123").forEach {
            assertTrue(SessionExerciseOrderCodec.isValidKey(it), it)
        }
        listOf("", "planned", "planned-", "-1", "planned-1-2", "plan ned-1", "planned_1", "single-planned-1")
            .forEach { assertFalse(SessionExerciseOrderCodec.isValidKey(it), it) }
    }
}
