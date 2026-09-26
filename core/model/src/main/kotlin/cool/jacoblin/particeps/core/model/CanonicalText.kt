package cool.jacoblin.particeps.core.model

import java.util.SortedMap

// Exact, allocation-free equivalents of the fixed character-class patterns checked for every event
// and commit. Each names the full-match pattern it replaces; CanonicalTextTest pins the equivalence.

/** `[0-9a-f]{64}` */
internal fun String.isLowercaseSha256(): Boolean = length == 64 && all { it.isLowercaseHex() }

/** `[a-z][a-z0-9_]{0,63}`: an event field key, also checked by the automation reducer's inputs. */
fun String.isEventFieldKey(): Boolean =
    length in 1..64 && this[0] in 'a'..'z' && all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

/** `[A-Za-z0-9._:-]{1,128}` */
internal fun String.isBootSessionId(): Boolean = length in 1..128 && all {
    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == ':' || it == '-'
}

/** `[a-z0-9][a-z0-9-]{2,63}` */
internal fun String.isStudyId(): Boolean =
    length in 3..64 && this[0] != '-' && all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

/** `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` */
internal fun String.isAssignedParticipantId(): Boolean = length in 1..64 && this[0].isAsciiAlphanumeric() &&
    all { it.isAsciiAlphanumeric() || it == '.' || it == '_' || it == '-' }

/** `[A-Za-z0-9][A-Za-z0-9._:@/-]{0,191}` */
internal fun String.isRuntimeComponentId(): Boolean = length in 1..192 && this[0].isAsciiAlphanumeric() &&
    all { it.isAsciiAlphanumeric() || it == '.' || it == '_' || it == ':' || it == '@' || it == '/' || it == '-' }

/** `[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}` */
internal fun String.isLowercaseUuid(): Boolean {
    if (length != 36) return false
    for (index in indices) {
        val separator = index == 8 || index == 13 || index == 18 || index == 23
        if (if (separator) this[index] != '-' else !this[index].isLowercaseHex()) return false
    }
    return true
}

/** `[A-Za-z0-9_-]{43}` */
internal fun String.isActivityTokenKey(): Boolean =
    length == 43 && all { it.isAsciiAlphanumeric() || it == '_' || it == '-' }

private fun Char.isAsciiAlphanumeric(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

private fun Char.isLowercaseHex(): Boolean = this in '0'..'9' || this in 'a'..'f'

private val LOWER_HEX = "0123456789abcdef".toCharArray()

/**
 * Lowercase hexadecimal, byte for byte the same text as `"%02x"` per byte, from a digit table:
 * per-byte string formatting would dominate digests computed for every commit and every scan.
 */
fun ByteArray.toLowerHex(): String {
    val characters = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        characters[index * 2] = LOWER_HEX[value ushr 4]
        characters[index * 2 + 1] = LOWER_HEX[value and 0x0f]
    }
    return String(characters)
}

/**
 * The map in ascending natural key order, iterating exactly as `toSortedMap()` would: itself when
 * it is a naturally ordered sorted map or its keys already ascend, otherwise a sorted copy.
 */
fun <K : Comparable<K>, V> Map<K, V>.inKeyOrder(): Map<K, V> {
    if (this is SortedMap<K, V> && comparator() == null) return this
    var previous: K? = null
    for (key in keys) {
        if (previous != null && previous >= key) return toSortedMap()
        previous = key
    }
    return this
}
