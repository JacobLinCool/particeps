package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.AutomationDefinition
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.NamedCollectorProfile
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.ResourceConditionCase
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.TRAFFIC_SHAPING_RESOURCE_ID
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.definition.TrafficShapingProfile
import cool.jacoblin.particeps.core.definition.UsageEventsV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.resourceKey
import cool.jacoblin.particeps.core.definition.signedProfiles
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyConfigurationAutomationAdapterTest {
    @Test
    fun projectsSortedResourcesAndExactSignedProfileDigests() {
        val configuration = configuration()
        val input = configuration.toAutomationCompilerInput(CONFIGURATION_DIGEST)

        assertEquals(86_400, input.studyDurationSeconds)
        assertEquals(
            listOf(ResourceKind.ACTUATOR, ResourceKind.COLLECTOR),
            input.resources.map { it.key.kind },
        )
        val traffic = configuration.trafficShaping as TrafficShapingConfiguration.Enabled
        assertEquals(
            traffic.signedProfiles().associate { it.id to it.expectedSha256.value },
            input.resources.single { it.key == traffic.resourceKey }.profileDigests,
        )
        assertEquals(
            TrafficShapingProfile("baseline", null, null).canonicalBytes().sha256(),
            input.resources.single { it.key == traffic.resourceKey }.profileDigests.getValue("baseline"),
        )
        assertTrue(AutomationCompiler(EventContractRegistry { null }).compile(input) is CompilationResult.Success)
    }

    @Test
    fun rejectsMissingAndDuplicateBindingOwnersBeforeCompilation() {
        val base = configuration()
        val usageBinding = base.automations.filterIsInstance<ResourceBindingAutomation>()
            .single { it.resource.kind == ResourceKind.COLLECTOR }
        assertThrows(IllegalArgumentException::class.java) {
            configuration(automations = base.automations - usageBinding)
                .toAutomationCompilerInput(CONFIGURATION_DIGEST)
        }
        assertThrows(IllegalArgumentException::class.java) {
            configuration(
                automations = (base.automations + usageBinding.copy(id = "bind-usage-copy"))
                    .sortedBy(AutomationDefinition::id),
            ).toAutomationCompilerInput(CONFIGURATION_DIGEST)
        }
    }

    private fun configuration(
        automations: List<AutomationDefinition> = bindings(),
    ): StudyConfiguration = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "adapter-test",
        configurationId = "adapter-config",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = "android",
        minimumClientVersion = 1,
        title = "Adapter test",
        researcherName = "Researcher",
        researcherContact = "researcher@example.invalid",
        purpose = "Verify the signed automation compiler projection.",
        durationHours = 24,
        consentDocumentVersion = "v1",
        consentSummary = "Consent summary.",
        assignedParticipantId = null,
        collectors = listOf(
            CollectorResourceConfiguration(
                UsageEventsV1ProfileConfiguration.SOURCE_ID,
                required = true,
                profiles = listOf(
                    NamedCollectorProfile("continuous", UsageEventsV1ProfileConfiguration(15)),
                ),
            ),
        ),
        surveys = emptyList(),
        interventions = emptyList(),
        automations = automations,
        trafficShaping = shaping(),
        maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
        signer = SignerIdentity("signer-key", ProtocolBase64Url.encode(ByteArray(32) { 1 })),
        export = ExportConfiguration("export-key", ProtocolBase64Url.encode(ByteArray(32) { 2 })),
        upload = null,
    )

    private companion object {
        const val CONFIGURATION_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000"

        fun shaping() = TrafficShapingConfiguration.Enabled(
            targetPackages = listOf("com.example.social"),
            profiles = listOf(
                TrafficShapingProfile("baseline", null, null),
                TrafficShapingProfile("slow-network", 256, 1_024),
            ),
        )

        fun bindings(): List<AutomationDefinition> = listOf(
            ResourceBindingAutomation(
                "bind-traffic",
                ResourceKey(ResourceKind.ACTUATOR, TRAFFIC_SHAPING_RESOURCE_ID),
                listOf(ResourceConditionCase(StateCondition.StudySessionActive, "slow-network")),
                "baseline",
            ),
            ResourceBindingAutomation(
                "bind-usage",
                ResourceKey(ResourceKind.COLLECTOR, UsageEventsV1ProfileConfiguration.SOURCE_ID),
                listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                "continuous",
            ),
        ).sortedBy(AutomationDefinition::id)
    }
}

private fun ByteArray.sha256(): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
