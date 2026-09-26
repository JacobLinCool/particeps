package cool.jacoblin.particeps

import cool.jacoblin.particeps.core.collector.SetupAction
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantStudyUiModelTest {
    @Test
    fun participantProjectionCannotRepresentTreatmentOrRuntimeDiagnostics() {
        val exposedNames = PARTICIPANT_TYPES
            .flatMap { type ->
                type.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .map { field -> "${type.simpleName}.${field.name}" }
            }
            .joinToString(separator = "\n")
            .lowercase()

        PROHIBITED_IDENTIFIERS.forEach { prohibited ->
            assertFalse("Participant projection exposes $prohibited:\n$exposedNames", prohibited in exposedNames)
        }
        assertTrue("High-level shaping disclosure flag is required", "trafficshapingdisclosurerequired" in exposedNames)
        assertTrue("Participation facts are part of the allowlist", "participantstudyuimodel.participation" in exposedNames)
    }

    /**
     * The exact instance fields of every projection type. The prohibited-name check above is a
     * deny-list and cannot catch an innocuous-sounding field such as `arm` or `targetApp`; this
     * pin makes any new field, whatever its name, a deliberate change to the allowlist.
     */
    @Test
    fun everyProjectionFieldNameIsPinned() {
        val actual = PARTICIPANT_TYPES.associate { type ->
            type.name.substringAfterLast('.') to type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .mapTo(sortedSetOf()) { it.name }
        }
        assertEquals(PINNED_FIELD_NAMES, actual)
    }

    /**
     * A new field whose type the allowlist above does not list would escape the name check, so
     * every class reachable from the projection, through fields, collection element types, and
     * sealed subtypes, must be one of the pinned types or a closed enum.
     */
    @Test
    fun everyTypeTheProjectionCanReachIsPinned() {
        val reachable = mutableSetOf<Class<*>>()
        fun visit(type: Type) {
            when (type) {
                is ParameterizedType -> type.actualTypeArguments.forEach(::visit)
                is WildcardType -> type.upperBounds.forEach(::visit)
                is Class<*> -> {
                    if (!type.name.startsWith("cool.jacoblin.particeps.") || !reachable.add(type)) return
                    type.declaredFields
                        .filterNot { Modifier.isStatic(it.modifiers) }
                        .forEach { visit(it.genericType) }
                    type.permittedSubclasses?.forEach(::visit)
                }
            }
        }
        visit(ParticipantStudyUiModel::class.java)

        assertTrue(
            "Sealed subtypes must be discoverable for this check to mean anything",
            ParticipantElapsedTime.Growing::class.java in reachable,
        )
        val unpinned = reachable
            .filterNot { it.isEnum || it.isInterface }
            .filterNot { it in PARTICIPANT_TYPES || it in CORE_ACCESS_TYPES }
        assertTrue("Participant projection reaches unpinned types: $unpinned", unpinned.isEmpty())
    }

    private companion object {
        val PARTICIPANT_TYPES = listOf(
            ParticipantStudyUiModel::class.java,
            ParticipantDataCategory::class.java,
            ParticipantAccessItem::class.java,
            ParticipantUploadDisclosure::class.java,
            ParticipantExportSummary::class.java,
            ParticipantParticipationSummary::class.java,
            ParticipantElapsedTime.Settled::class.java,
            ParticipantElapsedTime.Growing::class.java,
            ParticipantAccessOwner.DataCategory::class.java,
            ParticipantAccessOwner.StudyNotifications::class.java,
            ParticipantAccessResolution.Satisfied::class.java,
            ParticipantAccessResolution.ActionRequired::class.java,
            ParticipantAccessResolution.BlockedByPrerequisites::class.java,
            ParticipantAccessResolution.Unavailable::class.java,
            ParticipantExportState.Running::class.java,
            ParticipantExportState.Failed::class.java,
            ParticipantExportState.Cancelled::class.java,
        )
        val PINNED_FIELD_NAMES: Map<String, Set<String>> = mapOf(
            "ParticipantStudyUiModel" to sortedSetOf(
                "experimentId", "title", "purpose", "researcherName", "researcherContact",
                "durationHours", "consentSummary", "consentDocumentVersion", "signerFingerprint",
                "signerAnchored", "assignedParticipantId", "participantInstanceId", "dataCategories",
                "access", "upload", "state", "lifetimeDataEventCount", "durableThroughCommit",
                "uploadedThroughCommit", "retainedFromCommit", "pausedAtUtcMillis", "participation",
                "lastExport", "trafficShapingDisclosureRequired",
            ),
            "ParticipantDataCategory" to sortedSetOf("kind", "optional"),
            "ParticipantAccessItem" to sortedSetOf("kind", "required", "owners", "resolution", "guidance"),
            "ParticipantUploadDisclosure" to sortedSetOf("destinationHost", "intervalMinutes", "allowMetered"),
            "ParticipantExportSummary" to sortedSetOf("commitCount", "eventCount", "byteCount"),
            "ParticipantParticipationSummary" to sortedSetOf(
                "studyDayCount", "plannedEndUtcMillis", "studyLength", "activeCollection", "ended",
            ),
            "ParticipantElapsedTime\$Settled" to sortedSetOf("millis"),
            "ParticipantElapsedTime\$Growing" to sortedSetOf("zeroAtUtcMillis", "limitMillis"),
            "ParticipantAccessOwner\$DataCategory" to sortedSetOf("kind", "required"),
            "ParticipantAccessOwner\$StudyNotifications" to sortedSetOf<String>(),
            "ParticipantAccessResolution\$Satisfied" to sortedSetOf<String>(),
            "ParticipantAccessResolution\$ActionRequired" to sortedSetOf("action"),
            "ParticipantAccessResolution\$BlockedByPrerequisites" to sortedSetOf("missing"),
            "ParticipantAccessResolution\$Unavailable" to sortedSetOf<String>(),
            "ParticipantExportState\$Running" to sortedSetOf("phase", "completedBatches", "totalBatches"),
            "ParticipantExportState\$Failed" to sortedSetOf("incompleteFileRemains"),
            "ParticipantExportState\$Cancelled" to sortedSetOf("incompleteFileRemains"),
        )
        /** The one non-enum core type the access projection reuses: an action with no payload. */
        val CORE_ACCESS_TYPES = setOf<Class<*>>(SetupAction.ShowInputMethodPicker::class.java)
        val PROHIBITED_IDENTIFIERS = listOf(
            "targetpackage",
            "package",
            "cap",
            "schedule",
            "condition",
            "assignment",
            "clockanchor",
            "measuredat",
            "digest",
            "configurationid",
            "profileid",
            "uplink",
            "downlink",
            "bandwidth",
            "automation",
            "trigger",
            "timer",
            "epoch",
            "resourcevector",
            "owneruid",
            "failurecode",
            "reasoncode",
            "collectorhealth",
            "applieddigest",
        )
    }
}
