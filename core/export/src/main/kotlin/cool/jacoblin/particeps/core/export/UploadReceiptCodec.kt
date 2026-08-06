package cool.jacoblin.particeps.core.export

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Exact canonical JSON receipt used by both new-upload and exact-replay responses. */
object UploadReceiptCodec {
    private val KEYS = setOf(
        "bundle_id",
        "byte_count",
        "configuration_sha256",
        "event_count",
        "first_sequence_number",
        "last_sequence_number",
        "sha256",
    )

    fun encode(receipt: ExportReceipt): ByteArray = (
        "{\"bundle_id\":\"${receipt.bundleId}\"," +
            "\"byte_count\":\"${receipt.byteCount}\"," +
            "\"configuration_sha256\":\"${receipt.configurationSha256}\"," +
            "\"event_count\":\"${receipt.eventCount}\"," +
            "\"first_sequence_number\":\"${receipt.firstSequence}\"," +
            "\"last_sequence_number\":\"${receipt.lastSequence}\"," +
            "\"sha256\":\"${receipt.sha256}\"}"
        ).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): ExportReceipt {
        require(bytes.size in 2..MAX_RECEIPT_BYTES) { "Invalid upload receipt size" }
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse { throw IllegalArgumentException("Upload receipt is not valid UTF-8", it) }
        val root = runCatching { JsonParser.parseString(text) }
            .getOrElse { throw IllegalArgumentException("Invalid upload receipt JSON", it) }
            .requireObject()
        require(root.keySet() == KEYS) { "Unexpected upload receipt keys" }
        val receipt = ExportReceipt(
            bundleId = runCatching { UUID.fromString(root.string("bundle_id")) }
                .getOrElse { throw IllegalArgumentException("Invalid bundle ID", it) },
            configurationSha256 = root.string("configuration_sha256"),
            firstSequence = root.decimalLong("first_sequence_number"),
            lastSequence = root.decimalLong("last_sequence_number"),
            eventCount = root.decimalLong("event_count"),
            sha256 = root.string("sha256"),
            byteCount = root.decimalLong("byte_count"),
        )
        require(encode(receipt).contentEquals(bytes)) { "Upload receipt JSON is not canonical" }
        return receipt
    }

    private fun JsonElement.requireObject(): JsonObject {
        require(isJsonObject) { "Upload receipt must be an object" }
        return asJsonObject
    }

    private fun JsonObject.string(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
        return value.asString
    }

    private fun JsonObject.decimalLong(name: String): Long {
        val raw = string(name)
        require(UNSIGNED_DECIMAL.matches(raw)) { "$name must be a canonical unsigned decimal string" }
        return raw.toLongOrNull() ?: throw IllegalArgumentException("$name is outside Long range")
    }

    private const val MAX_RECEIPT_BYTES = 2_048
    private val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")
}
