package cool.jacoblin.particeps.core.export

import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.crypto.HpkeCrypto
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.StudyConfigurationCodec
import cool.jacoblin.particeps.core.model.ConditionEpoch
import cool.jacoblin.particeps.core.model.EngineCommit
import cool.jacoblin.particeps.core.model.EngineCommitIntegrity
import cool.jacoblin.particeps.core.model.GENESIS_DIGEST
import cool.jacoblin.particeps.core.model.RecordedEvent
import cool.jacoblin.particeps.core.model.ResearchTime
import cool.jacoblin.particeps.core.model.RuntimeDocument
import cool.jacoblin.particeps.core.model.RuntimeMutation
import cool.jacoblin.particeps.core.model.RuntimeProjection
import cool.jacoblin.particeps.core.model.SourceCheckpoint
import cool.jacoblin.particeps.core.model.SourceCoverage
import cool.jacoblin.particeps.core.model.SourceObservation
import cool.jacoblin.particeps.core.model.StudyClockCheckpoint
import cool.jacoblin.particeps.core.model.StudyStore
import cool.jacoblin.particeps.core.protocol.VerifiedConfiguration
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BundleKind(val wireValue: String) {
    MANUAL_EXPORT("manual_export"),
    AUTOMATIC_UPLOAD("automatic_upload"),
}

data class BundleProducer(
    val platform: String,
    val clientVersion: String,
) {
    init {
        require(platform == StudyConfiguration.ANDROID_PLATFORM) { "Unsupported producer platform" }
        require(UNSIGNED_DECIMAL.matches(clientVersion) && clientVersion.toLongOrNull() != null) {
            "Invalid producer client version"
        }
    }

    private companion object { val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*") }
}

/** Outer identities authenticated by both HPKE context and document AEAD. */
data class AuthenticatedBundleHeader(
    val bundleId: UUID,
    val configurationSha256: String,
    val researcherKeyId: String,
) {
    init {
        require(bundleId.version() == 4 && bundleId.variant() == 2) { "Bundle ID must be a random UUID" }
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        require(StudyConfiguration.ID.matches(researcherKeyId)) { "Invalid researcher key ID" }
    }
}

/** A point-in-time export request. Every range is expressed solely in complete commit boundaries. */
data class ExportSnapshot(
    val verifiedConfiguration: VerifiedConfiguration,
    val runtime: RuntimeDocument,
    val producer: BundleProducer,
    val bundleKind: BundleKind,
    val exportedAtUtcMillis: Long,
    val bundleId: UUID = UUID.randomUUID(),
    val fromCommit: Long = runtime.retainedFromCommit,
    val throughCommit: Long? = null,
    /** Soft plaintext budget; selection includes a whole first commit and stops between commits. */
    val maximumPlaintextBytes: Long? = null,
) {
    init {
        val configuration = verifiedConfiguration.configuration
        require(bundleId.version() == 4 && bundleId.variant() == 2) { "Bundle ID must be a random UUID" }
        require(exportedAtUtcMillis >= 0) { "Invalid export time" }
        require(runtime.experimentId == configuration.experimentId) { "Experiment ID mismatch" }
        require(runtime.configurationId == configuration.configurationId) { "Configuration ID mismatch" }
        require(runtime.assignedParticipantId == configuration.assignedParticipantId) {
            "Assigned participant ID mismatch"
        }
        require(runtime.configurationSha256 == verifiedConfiguration.configurationSha256) {
            "Runtime configuration digest mismatch"
        }
        require(producer.platform == configuration.platform) { "Producer platform mismatch" }
        require(producer.clientVersion.toLong() >= configuration.minimumClientVersion) {
            "Producer client version is too old"
        }
        require(verifiedConfiguration.signerKeyId == configuration.signer.keyId) { "Signer provenance mismatch" }
        require(verifiedConfiguration.signature.size == SIGNATURE_BYTES) { "Invalid Ed25519 signature provenance" }
        val canonical = StudyConfigurationCodec.encode(configuration)
        require(canonical.contentEquals(verifiedConfiguration.canonicalConfigurationBytes)) {
            "Configuration provenance is not canonical"
        }
        require(canonical.sha256Hex() == verifiedConfiguration.configurationSha256) {
            "Configuration digest provenance mismatch"
        }
        maximumPlaintextBytes?.let { require(it > 0) { "Plaintext budget must be positive" } }
    }
}

data class ExportReceipt(
    val bundleId: UUID,
    val configurationSha256: String,
    val firstCommitSequence: Long,
    val lastCommitSequence: Long,
    val commitCount: Long,
    val eventCount: Long,
    val sha256: String,
    val byteCount: Long,
) {
    init {
        require(bundleId.version() == 4 && bundleId.variant() == 2) { "Bundle ID must be a random UUID" }
        require(SHA256.matches(configurationSha256)) { "Invalid configuration digest" }
        require(firstCommitSequence > 0) { "Invalid first commit sequence" }
        require(lastCommitSequence >= 0) { "Invalid last commit sequence" }
        require(commitCount >= 0 && eventCount >= 0) { "Invalid bundle counts" }
        val expectedLast = if (commitCount == 0L) {
            firstCommitSequence - 1
        } else {
            require(firstCommitSequence <= Long.MAX_VALUE - commitCount + 1) { "Receipt range overflows" }
            firstCommitSequence + commitCount - 1
        }
        require(lastCommitSequence == expectedLast) { "Receipt commit range is inconsistent" }
        require(SHA256.matches(sha256)) { "Invalid bundle digest" }
        require(byteCount > 0) { "Invalid bundle byte count" }
    }
}

object ResearchExport {
    fun validate(configuration: StudyConfiguration) {
        HpkeCrypto.validatePublicKey(configuration.export.hpkePublicKeyBytes())
    }

    suspend fun encrypt(
        snapshot: ExportSnapshot,
        store: StudyStore,
        destination: OutputStream,
    ): ExportReceipt = withContext(Dispatchers.IO) {
        val configuration = snapshot.verifiedConfiguration.configuration
        validate(configuration)
        val runtime = snapshot.runtime
        val requestedBoundary = snapshot.throughCommit ?: runtime.revision
        require(requestedBoundary in 0..runtime.revision) { "Export boundary exceeds durable commits" }
        require(snapshot.fromCommit in runtime.retainedFromCommit..(requestedBoundary + 1)) {
            "Export starts outside retained complete commits"
        }
        if (snapshot.bundleKind == BundleKind.AUTOMATIC_UPLOAD) {
            require(snapshot.fromCommit <= requestedBoundary) { "Automatic upload cannot be empty" }
            require(snapshot.fromCommit == runtime.uploadedThroughCommit + 1) {
                "Automatic upload does not start after its durable watermark"
            }
        }
        val selection = selectBoundary(snapshot, store, requestedBoundary)
        val context = contextInfo(
            snapshot.bundleId,
            snapshot.verifiedConfiguration.configurationSha256,
            configuration.export.researcherKeyId,
        )
        val contentKey = KeyGenerator.getInstance("AES").apply { init(KEY_BITS) }.generateKey()
        val wrappedKey = HpkeCrypto.encrypt(configuration.export.hpkePublicKeyBytes(), contentKey.encoded, context)
        require(wrappedKey.size == WRAPPED_KEY_BYTES) { "Unexpected wrapped-key size" }
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, contentKey, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(context)
        }
        val keyId = configuration.export.researcherKeyId.toByteArray(Charsets.UTF_8)
        val digesting = DigestingOutputStream(destination)
        digesting.write(
            ByteBuffer.allocate(FIXED_HEADER_BYTES + keyId.size + WRAPPED_KEY_BYTES)
                .put(MAGIC)
                .putUuid(snapshot.bundleId)
                .put(snapshot.verifiedConfiguration.configurationSha256.hexBytes())
                .putShort(keyId.size.toShort())
                .put(nonce)
                .put(keyId)
                .put(wrappedKey)
                .array(),
        )
        CanonicalJsonWriter(CipherOutputStream(digesting, cipher)).use { writer ->
            writeSnapshot(writer, snapshot, store, selection)
        }
        ExportReceipt(
            bundleId = snapshot.bundleId,
            configurationSha256 = snapshot.verifiedConfiguration.configurationSha256,
            firstCommitSequence = snapshot.fromCommit,
            lastCommitSequence = selection.boundary,
            commitCount = selection.commitCount,
            eventCount = selection.eventCount,
            sha256 = digesting.digest().toHex(),
            byteCount = digesting.count,
        )
    }

    fun decrypt(
        encoded: ByteArray,
        privateKey: ByteArray,
        configuration: StudyConfiguration,
    ): ByteArray = java.io.ByteArrayOutputStream().also { output ->
        decrypt(encoded.inputStream(), output, privateKey, configuration)
    }.toByteArray()

    /** [output] remains unauthenticated staging until this method returns successfully. */
    fun decrypt(
        input: InputStream,
        output: OutputStream,
        privateKey: ByteArray,
        configuration: StudyConfiguration,
    ): AuthenticatedBundleHeader {
        require(privateKey.size == HpkeCrypto.RAW_KEY_BYTES) { "X25519 private key must be 32 bytes" }
        val fixed = input.readNBytes(FIXED_HEADER_BYTES)
        require(fixed.size == FIXED_HEADER_BYTES) { "Truncated export" }
        val header = ByteBuffer.wrap(fixed)
        val magic = ByteArray(MAGIC.size).also(header::get)
        require(magic.contentEquals(MAGIC)) { "Unsupported export format" }
        val bundleId = header.getUuid()
        val configurationDigest = ByteArray(SHA256_BYTES).also(header::get).toHex()
        val keyIdLength = header.short.toInt() and 0xffff
        val nonce = ByteArray(NONCE_BYTES).also(header::get)
        require(keyIdLength in 3..64) { "Invalid researcher key ID length" }
        val expectedDigest = StudyConfigurationCodec.encode(configuration).sha256Hex()
        require(configurationDigest == expectedDigest) { "Configuration digest mismatch" }
        val keyIdBytes = input.readNBytes(keyIdLength)
        require(keyIdBytes.size == keyIdLength) { "Truncated export" }
        val keyId = keyIdBytes.strictUtf8("researcher key ID")
        require(keyId == configuration.export.researcherKeyId) { "Researcher key ID mismatch" }
        val authenticatedHeader = AuthenticatedBundleHeader(bundleId, configurationDigest, keyId)
        val wrappedKey = input.readNBytes(WRAPPED_KEY_BYTES)
        require(wrappedKey.size == WRAPPED_KEY_BYTES) { "Truncated export" }
        val context = contextInfo(bundleId, configurationDigest, keyId)
        val contentKey = HpkeCrypto.decrypt(privateKey, wrappedKey, context)
        require(contentKey.size == KEY_BITS / 8) { "Invalid unwrapped content key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(context)
        }
        val chunk = ByteArray(DECRYPT_CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            cipher.update(chunk, 0, read)?.let(output::write)
        }
        require(total > TAG_BYTES) { "Truncated export" }
        cipher.doFinal()?.let(output::write)
        return authenticatedHeader
    }

    private suspend fun selectBoundary(
        snapshot: ExportSnapshot,
        store: StudyStore,
        requestedBoundary: Long,
    ): CommitSelection {
        if (snapshot.fromCommit > requestedBoundary) {
            return CommitSelection(snapshot.fromCommit - 1, 0, 0, null)
        }
        val budget = snapshot.maximumPlaintextBytes
        var expected = snapshot.fromCommit
        var boundary = snapshot.fromCommit - 1
        var eventCount = 0L
        var stopped = false
        var previousCommitSha256: String? = null
        var lastCommitSha256: String? = null
        val counter = CountingOutputStream(DiscardingOutputStream)
        CanonicalJsonWriter(counter).use { writer ->
            writer.beginArray()
            store.readCommits(snapshot.fromCommit, requestedBoundary) { commit ->
                if (!stopped) {
                    require(commit.commitSequence == expected) { "Non-contiguous export commit range" }
                    EngineCommitIntegrity.verify(commit)
                    if (expected == 1L) require(commit.previousCommitSha256 == GENESIS_DIGEST) {
                        "Genesis commit has a predecessor"
                    }
                    previousCommitSha256?.let { previous ->
                        require(commit.previousCommitSha256 == previous) { "Broken export commit chain" }
                    }
                    writer.writeCommit(commit)
                    writer.flush()
                    boundary = commit.commitSequence
                    eventCount = Math.addExact(eventCount, commit.events.size.toLong())
                    previousCommitSha256 = commit.commitSha256
                    lastCommitSha256 = commit.commitSha256
                    expected++
                    stopped = budget != null && counter.count >= budget
                }
            }
            writer.endArray()
        }
        require(boundary >= snapshot.fromCommit) { "Retained commit range is unavailable" }
        if (!stopped) require(boundary == requestedBoundary) { "Retained commit range is incomplete" }
        if (boundary == snapshot.runtime.revision) {
            require(lastCommitSha256 == snapshot.runtime.lastCommitSha256) {
                "Runtime head does not match the exported commit chain"
            }
        }
        return CommitSelection(boundary, boundary - snapshot.fromCommit + 1, eventCount, lastCommitSha256)
    }

    private suspend fun writeSnapshot(
        writer: CanonicalJsonWriter,
        snapshot: ExportSnapshot,
        store: StudyStore,
        selection: CommitSelection,
    ) {
        val verified = snapshot.verifiedConfiguration
        val runtime = snapshot.runtime
        writer.beginObject()
        writer.name("bundle_id").value(snapshot.bundleId.toString())
        writer.name("bundle_kind").value(snapshot.bundleKind.wireValue)
        writer.name("configuration").rawCanonicalJson(verified.canonicalConfigurationBytes)
        writer.name("configuration_sha256").value(verified.configurationSha256)
        writer.name("configuration_signature").beginObject()
        writer.name("signature").value(ProtocolBase64Url.encode(verified.signature))
        writer.name("signer_key_id").value(verified.signerKeyId)
        writer.endObject()
        writer.name("event_source_registry_sha256").value(ProtocolEventSourceRegistry.REGISTRY_SHA256)
        writer.name("experiment").beginObject()
        writer.name("assigned_participant_id").value(runtime.assignedParticipantId)
        writer.name("commit_count").valueDecimal(selection.commitCount)
        writer.name("commits").beginArray()
        var expected = snapshot.fromCommit
        var eventCount = 0L
        var previousCommitSha256: String? = null
        if (selection.commitCount > 0) {
            store.readCommits(snapshot.fromCommit, selection.boundary) { commit ->
                require(commit.commitSequence == expected) { "Non-contiguous export commit range" }
                EngineCommitIntegrity.verify(commit)
                previousCommitSha256?.let { previous ->
                    require(commit.previousCommitSha256 == previous) { "Broken export commit chain" }
                }
                writer.writeCommit(commit)
                eventCount = Math.addExact(eventCount, commit.events.size.toLong())
                previousCommitSha256 = commit.commitSha256
                expected++
            }
        }
        require(expected == selection.boundary + 1) { "Export commit count mismatch" }
        require(eventCount == selection.eventCount) { "Export event count changed during snapshot" }
        require(previousCommitSha256 == selection.lastCommitSha256) { "Export commit chain changed during snapshot" }
        writer.endArray()
        writer.name("configuration_id").value(runtime.configurationId)
        writer.name("durable_through_commit").valueDecimal(runtime.revision)
        writer.name("evaluated_through_commit").valueDecimal(runtime.evaluatedThroughCommit)
        writer.name("event_count").valueDecimal(selection.eventCount)
        writer.name("experiment_id").value(runtime.experimentId)
        writer.name("first_commit_sequence").valueDecimal(snapshot.fromCommit)
        writer.name("last_commit_sequence").valueDecimal(selection.boundary)
        writer.name("lifetime_data_event_count").valueDecimal(runtime.lifetimeDataEventCount)
        writer.name("next_commit_sequence").valueDecimal(runtime.nextCommitSequence)
        writer.name("participant_instance_id").value(runtime.participantInstanceId)
        writer.name("retained_from_commit").valueDecimal(runtime.retainedFromCommit)
        writer.name("state").value(runtime.state.name)
        writer.name("uploaded_through_commit").valueDecimal(runtime.uploadedThroughCommit)
        writer.endObject()
        writer.name("exported_at_utc_millis").valueDecimal(snapshot.exportedAtUtcMillis)
        writer.name("format").value(BUNDLE_FORMAT)
        writer.name("producer").beginObject()
        writer.name("client_version").value(snapshot.producer.clientVersion)
        writer.name("platform").value(snapshot.producer.platform)
        writer.endObject()
        writer.endObject()
    }

    private fun CanonicalJsonWriter.writeCommit(commit: EngineCommit) {
        beginObject()
        name("commit_sequence").valueDecimal(commit.commitSequence)
        name("commit_sha256").value(commit.commitSha256)
        name("committed_at").writeTime(commit.committedAt)
        name("consumed_pending_input_sha256").value(commit.consumedPendingInputSha256)
        name("events").beginArray()
        commit.events.forEach { writeEvent(it) }
        endArray()
        name("input_kind").value(commit.inputKind.name)
        name("mutations").beginArray()
        commit.mutations.forEach { writeMutation(it) }
        endArray()
        name("previous_commit_sha256").value(commit.previousCommitSha256)
        name("resulting_checkpoint_sha256").value(commit.resultingCheckpointSha256)
        name("source_observations").beginArray()
        commit.sourceObservations.forEach { writeObservation(it) }
        endArray()
        name("successor_projection").writeProjection(commit.successorProjection)
        endObject()
    }

    private fun CanonicalJsonWriter.writeObservation(value: SourceObservation) {
        beginObject()
        name("admission_kind").value(value.admissionKind.name)
        name("condition_epoch_id").value(value.conditionEpochId.value)
        name("coverage").writeCoverage(value.coverage)
        name("encoded_sha256").value(value.encodedSha256)
        name("event_count").value(value.eventCount)
        name("first_event_sequence").valueDecimalOrNull(value.firstEventSequence)
        name("last_event_sequence").valueDecimalOrNull(value.lastEventSequence)
        name("observation_sequence").valueDecimal(value.observationSequence)
        name("producer_ordinal").valueDecimal(value.producerOrdinal)
        name("resource_generation").valueDecimal(value.resourceGeneration)
        name("schema_version").value(value.schemaVersion)
        name("source_id").value(value.sourceId.value)
        endObject()
    }

    private fun CanonicalJsonWriter.writeEvent(value: RecordedEvent) {
        beginObject()
        name("condition_epoch_id").value(value.conditionEpochId?.value)
        name("event_type").value(value.type.eventType)
        name("fields").beginObject()
        value.fields.toSortedMap().forEach { (key, fieldValue) -> name(key).value(fieldValue) }
        endObject()
        name("observed_time").writeTime(value.observedTime)
        name("schema_version").value(value.type.schemaVersion)
        name("sequence_number").valueDecimal(value.sequenceNumber)
        name("source_id").value(value.type.sourceId.value)
        endObject()
    }

    private fun CanonicalJsonWriter.writeMutation(value: RuntimeMutation) {
        beginObject()
        name("canonical_value").value(value.canonicalValue)
        name("component_id").value(value.key.id)
        name("component_kind").value(value.key.kind.name)
        name("operation").value(value.operation.name)
        endObject()
    }

    private fun CanonicalJsonWriter.writeProjection(value: RuntimeProjection) {
        beginObject()
        name("active_condition_epoch").writeEpoch(value.activeConditionEpoch)
        name("clock_checkpoint").writeClock(value.clockCheckpoint)
        name("evaluated_through_commit").valueDecimal(value.evaluatedThroughCommit)
        name("lifetime_data_event_count").valueDecimal(value.lifetimeDataEventCount)
        name("next_commit_sequence").valueDecimal(value.nextCommitSequence)
        name("next_event_sequence").valueDecimal(value.nextEventSequence)
        name("next_observation_sequence").valueDecimal(value.nextObservationSequence)
        name("retained_from_commit").valueDecimal(value.retainedFromCommit)
        name("revision").valueDecimal(value.revision)
        name("source_checkpoints").beginObject()
        value.sourceCheckpoints.toSortedMap().forEach { (sourceId, checkpoint) ->
            name(sourceId.value).writeSourceCheckpoint(checkpoint)
        }
        endObject()
        name("state").value(value.state.name)
        name("uploaded_through_commit").valueDecimal(value.uploadedThroughCommit)
        endObject()
    }

    private fun CanonicalJsonWriter.writeSourceCheckpoint(value: SourceCheckpoint) {
        beginObject()
        name("coverage").writeCoverage(value.coverage)
        name("cursor").value(value.cursor)
        name("next_producer_ordinal").valueDecimal(value.nextProducerOrdinal)
        name("resource_generation").valueDecimal(value.resourceGeneration)
        name("source_id").value(value.sourceId.value)
        endObject()
    }

    private fun CanonicalJsonWriter.writeClock(value: StudyClockCheckpoint?) {
        if (value == null) {
            nullValue()
            return
        }
        beginObject()
        name("active_running_elapsed_nanos").valueDecimal(value.activeRunningElapsedNanos)
        name("anchor").writeTime(value.anchor)
        name("calendar_elapsed_nanos").valueDecimal(value.calendarElapsedNanos)
        name("deadline_utc_millis").valueDecimal(value.deadlineUtcMillis)
        name("deadline_utc_trusted").value(value.deadlineUtcTrusted)
        name("zone_id").value(value.zoneId)
        endObject()
    }

    private fun CanonicalJsonWriter.writeEpoch(value: ConditionEpoch?) {
        if (value == null) {
            nullValue()
            return
        }
        beginObject()
        name("activated_at").writeTime(value.activatedAt)
        name("applied_resource_vector_sha256").value(value.appliedResourceVectorSha256)
        name("configuration_sha256").value(value.configurationSha256)
        name("id").value(value.id.value)
        endObject()
    }

    private fun CanonicalJsonWriter.writeCoverage(value: SourceCoverage?) {
        if (value == null) {
            nullValue()
            return
        }
        beginObject()
        name("clock_basis").value(value.clockBasis.name)
        name("end_exclusive").value(value.endExclusive)
        name("start_inclusive").value(value.startInclusive)
        endObject()
    }

    private fun CanonicalJsonWriter.writeTime(value: ResearchTime) {
        beginObject()
        name("boot_session_id").value(value.bootSessionId)
        name("elapsed_realtime_nanos").valueDecimal(value.elapsedRealtimeNanos)
        name("wall_time_utc_millis").valueDecimal(value.wallTimeUtcMillis)
        endObject()
    }

    private fun CanonicalJsonWriter.valueDecimalOrNull(value: Long?) {
        if (value == null) nullValue() else valueDecimal(value)
    }

    private fun contextInfo(bundleId: UUID, configurationSha256: String, researcherKeyId: String): ByteArray =
        ("{\"bundle_format\":\"$BUNDLE_FORMAT\",\"bundle_id\":\"$bundleId\"," +
            "\"configuration_sha256\":\"$configurationSha256\",\"researcher_key_id\":\"$researcherKeyId\"}"
            ).toByteArray(Charsets.UTF_8)

    private fun cool.jacoblin.particeps.core.definition.ExportConfiguration.hpkePublicKeyBytes(): ByteArray =
        ProtocolBase64Url.decodeExact(hpkePublicKey, HpkeCrypto.RAW_KEY_BYTES, "X25519 public key")

    private fun ByteBuffer.putUuid(uuid: UUID): ByteBuffer = putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)

    private fun ByteBuffer.getUuid(): UUID = UUID(getLong(), getLong())

    private fun String.hexBytes(): ByteArray {
        require(length == SHA256_BYTES * 2 && all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256" }
        return ByteArray(SHA256_BYTES) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.strictUtf8(label: String): String = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrElse { throw IllegalArgumentException("$label is not valid UTF-8", it) }
        .also { require(StudyConfiguration.ID.matches(it)) { "Invalid $label" } }

    private data class CommitSelection(
        val boundary: Long,
        val commitCount: Long,
        val eventCount: Long,
        val lastCommitSha256: String?,
    )

    private val MAGIC = "PTCEXP01".toByteArray(Charsets.US_ASCII)
    const val BUNDLE_FORMAT = "particeps-research-bundle-v1"
    private const val KEY_BITS = 256
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val SHA256_BYTES = 32
    private const val WRAPPED_KEY_BYTES = 80
    private const val FIXED_HEADER_BYTES = 8 + 16 + SHA256_BYTES + Short.SIZE_BYTES + NONCE_BYTES
    private const val DECRYPT_CHUNK_BYTES = 64 * 1024
}

internal val SHA256 = Regex("[0-9a-f]{64}")
private const val SIGNATURE_BYTES = 64

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count = 0L
        private set

    override fun write(value: Int) {
        out.write(value)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        count += length
    }
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}

private class DigestingOutputStream(private val destination: OutputStream) : OutputStream() {
    private val messageDigest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun write(value: Int) {
        destination.write(value)
        messageDigest.update(value.toByte())
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        destination.write(bytes, offset, length)
        messageDigest.update(bytes, offset, length)
        count += length
    }

    override fun flush() = destination.flush()
    override fun close() = destination.flush()
    fun digest(): ByteArray = messageDigest.digest()
}
