package cool.jacoblin.particeps.core.resource

import java.security.MessageDigest

enum class ResourceKind { COLLECTOR, ACTUATOR }

data class ResourceKey(val kind: ResourceKind, val id: String) : Comparable<ResourceKey> {
    init { require(ID.matches(id)) { "Invalid resource ID" } }
    override fun compareTo(other: ResourceKey): Int =
        compareValuesBy(this, other, { it.kind.name.lowercase() }, ResourceKey::id)

    private companion object { val ID = Regex("[a-z][a-z0-9_.-]{2,63}") }
}

@JvmInline
value class ResourceGeneration(val value: ULong) : Comparable<ResourceGeneration> {
    init { require(value > 0uL) { "Resource generation must be positive" } }
    fun next(): ResourceGeneration {
        check(value != ULong.MAX_VALUE) { "Resource generation overflow" }
        return ResourceGeneration(value + 1uL)
    }
    override fun compareTo(other: ResourceGeneration): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
}

@JvmInline
value class Sha256Digest(val value: String) {
    init { require(LOWERCASE_SHA256.matches(value)) { "Invalid SHA-256 digest" } }
    override fun toString(): String = value

    companion object {
        fun of(bytes: ByteArray): Sha256Digest = Sha256Digest(
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        )
        private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
    }
}

class SignedResourceProfile(
    val id: String,
    canonicalBytes: ByteArray,
    val expectedSha256: Sha256Digest = Sha256Digest.of(canonicalBytes),
) {
    private val encoded = canonicalBytes.copyOf()
    val canonicalBytes: ByteArray get() = encoded.copyOf()

    init {
        require(PROFILE_ID.matches(id)) { "Invalid profile ID" }
        require(encoded.isNotEmpty()) { "Canonical profile must not be empty" }
        require(Sha256Digest.of(encoded) == expectedSha256) { "Profile digest mismatch" }
    }

    override fun equals(other: Any?): Boolean =
        other is SignedResourceProfile &&
            id == other.id &&
            encoded.contentEquals(other.encoded) &&
            expectedSha256 == other.expectedSha256

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + encoded.contentHashCode()) + expectedSha256.hashCode()

    private companion object { val PROFILE_ID = Regex("[a-z0-9][a-z0-9-]{2,63}") }
}

data class DesiredResourceState(
    val key: ResourceKey,
    val generation: ResourceGeneration,
    val required: Boolean,
    val profile: SignedResourceProfile?,
)

enum class AppliedResourceStatus { APPLIED, INACTIVE, OPTIONAL_FAILED }

data class AppliedResourceState(
    val key: ResourceKey,
    val desiredGeneration: ResourceGeneration,
    val profileId: String?,
    val appliedProfileSha256: Sha256Digest?,
    val status: AppliedResourceStatus,
    val failureReason: String?,
) {
    init { validate() }
    private fun validate() {
        require(profileId == null || PROFILE_ID.matches(profileId)) { "Invalid applied profile ID" }
        require(failureReason == null || FAILURE_REASON.matches(failureReason)) { "Invalid resource failure reason" }
        when (status) {
            AppliedResourceStatus.APPLIED -> {
                require(profileId != null && appliedProfileSha256 != null && failureReason == null) {
                    "Applied resource requires a profile and matching digest only"
                }
            }
            AppliedResourceStatus.INACTIVE -> {
                require(profileId == null && appliedProfileSha256 == null && failureReason == null) {
                    "Inactive resource cannot retain profile evidence"
                }
            }
            AppliedResourceStatus.OPTIONAL_FAILED -> {
                require(profileId != null && appliedProfileSha256 == null && failureReason != null) {
                    "Optional failure requires desired profile and typed failure reason"
                }
            }
        }
    }

    private companion object {
        val PROFILE_ID = Regex("[a-z0-9][a-z0-9-]{2,63}")
        val FAILURE_REASON = Regex("[A-Z][A-Z0-9_]{2,63}")
    }
}

class AppliedResourceVector(resources: List<AppliedResourceState>) {
    val resources: List<AppliedResourceState> = resources.toList()
    val conditionDigest: Sha256Digest by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Sha256Digest.of(canonicalJson().toByteArray(Charsets.UTF_8))
    }
    init { validate() }
    fun canonicalJson(): String = buildString {
        append("{\"resources\":[")
        resources.forEachIndexed { index, resource ->
            if (index > 0) append(',')
            append('{')
            append("\"applied_profile_sha256\":")
            appendJsonStringOrNull(resource.appliedProfileSha256?.value)
            append(",\"desired_generation\":")
            appendJsonString(resource.desiredGeneration.toString())
            append(",\"failure_reason\":")
            appendJsonStringOrNull(resource.failureReason)
            append(",\"id\":")
            appendJsonString(resource.key.id)
            append(",\"kind\":")
            appendJsonString(resource.key.kind.name.lowercase())
            append(",\"profile_id\":")
            appendJsonStringOrNull(resource.profileId)
            append(",\"status\":")
            appendJsonString(resource.status.name)
            append('}')
        }
        append("]}")
    }

    override fun equals(other: Any?): Boolean = other is AppliedResourceVector && resources == other.resources

    override fun hashCode(): Int = resources.hashCode()

    override fun toString(): String = canonicalJson()

    private fun validate() {
        require(resources == resources.sortedBy { it.key }) { "Applied resources must be sorted by key" }
        require(resources.map { it.key }.distinct().size == resources.size) { "Duplicate applied resource" }
    }
}

private fun StringBuilder.appendJsonStringOrNull(value: String?) {
    if (value == null) append("null") else appendJsonString(value)
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
