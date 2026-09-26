package cool.jacoblin.particeps.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-written validators must accept exactly what their full-match patterns accept. */
class CanonicalTextTest {
    @Test
    fun sha256MatcherIsTheLowercaseHexPattern() {
        assertEquivalent(Regex("[0-9a-f]{64}"), "a".repeat(64), 64, String::isLowercaseSha256)
    }

    @Test
    fun eventFieldKeyMatcherIsTheFieldKeyPattern() {
        assertEquivalent(Regex("[a-z][a-z0-9_]{0,63}"), "k", 64, String::isEventFieldKey)
        assertTrue("source_elapsed_realtime_nanos".isEventFieldKey())
        assertFalse("_leading".isEventFieldKey())
        assertFalse("9leading".isEventFieldKey())
    }

    @Test
    fun bootSessionMatcherIsTheBootSessionPattern() {
        assertEquivalent(Regex("[A-Za-z0-9._:-]{1,128}"), "b", 128, String::isBootSessionId)
    }

    @Test
    fun runtimeIdentityMatchersAreTheirPatterns() {
        assertEquivalent(Regex("[a-z0-9][a-z0-9-]{2,63}"), "experiment-one", 64, String::isStudyId)
        assertEquivalent(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"), "P-01.a_b", 64, String::isAssignedParticipantId)
        assertEquivalent(
            Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,191}"),
            "main/0001:a@b.c_d-e",
            192,
            String::isRuntimeComponentId,
        )
        assertEquivalent(Regex("[A-Za-z0-9_-]{43}"), "A".repeat(43), 43, String::isActivityTokenKey)
        val uuid = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        val valid = "123e4567-e89b-42d3-a456-426614174001"
        assertEquivalent(uuid, valid, 36, String::isLowercaseUuid)
        valid.indices.forEach { index ->
            listOf('-', '0', 'f', 'g', 'A').forEach { replacement ->
                val candidate = valid.substring(0, index) + replacement + valid.substring(index + 1)
                assertEquals(candidate, uuid.matches(candidate), candidate.isLowercaseUuid())
            }
        }
    }

    @Test
    fun componentSizeBoundCountsUtf8BytesExactly() {
        val limit = 512 * 1_024
        val cases = listOf(
            "a".repeat(limit),
            "a".repeat(limit + 1),
            "\u00e9".repeat(limit / 2),
            "\u00e9".repeat(limit / 2) + "a",
            "\u4e2d".repeat(limit / 3) + "a",
            "\u4e2d".repeat(limit / 3) + "\u00e9",
            "\ud83d\ude00".repeat(limit / 4),
            "\ud83d\ude00".repeat(limit / 4) + "a",
            "\ud83d".repeat(limit),
            "\ud83d".repeat(limit + 1),
            "\ude00".repeat(limit) + "a",
        )
        cases.forEach { value ->
            val fits = value.toByteArray().size <= limit
            val mutation = runCatching {
                RuntimeMutation(
                    RuntimeComponentKey(RuntimeComponentKind.TIMER, "timer"),
                    RuntimeMutationOperation.UPSERT,
                    value,
                )
            }
            assertEquals("length ${value.length}", fits, mutation.isSuccess)
        }
    }

    private fun assertEquivalent(
        pattern: Regex,
        valid: String,
        maximumLength: Int,
        matcher: (String) -> Boolean,
    ) {
        val candidates = mutableListOf("", valid, valid.take(1).repeat(maximumLength), valid.take(1).repeat(maximumLength + 1))
        (0..0x2ff).map(Int::toChar).plus(listOf('\ud83d', '\ude00', '\uffff')).forEach { character ->
            candidates += character.toString()
            candidates += character + valid.drop(1)
            candidates += valid.dropLast(1) + character
            candidates += valid.take(1) + character + valid.drop(2)
        }
        candidates += valid.dropLast(1) + "\ud83d\ude00"
        candidates.forEach { candidate ->
            assertEquals("'$candidate'", pattern.matches(candidate), matcher(candidate))
        }
    }
}
