package cool.jacoblin.particeps

import java.lang.reflect.Modifier
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
    }

    private companion object {
        val PARTICIPANT_TYPES = listOf(
            ParticipantStudyUiModel::class.java,
            ParticipantDataCategory::class.java,
            ParticipantAccessItem::class.java,
            ParticipantUploadDisclosure::class.java,
            ParticipantExportSummary::class.java,
        )
        val PROHIBITED_IDENTIFIERS = listOf(
            "targetpackage",
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
