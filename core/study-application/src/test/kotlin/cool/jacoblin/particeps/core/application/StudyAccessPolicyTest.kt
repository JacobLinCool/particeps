package cool.jacoblin.particeps.core.application

import cool.jacoblin.particeps.core.collector.AccessKind
import cool.jacoblin.particeps.core.collector.AccessSnapshot
import cool.jacoblin.particeps.core.collector.AccessStatus
import cool.jacoblin.particeps.core.collector.Collector
import cool.jacoblin.particeps.core.collector.CollectorContext
import cool.jacoblin.particeps.core.collector.CollectorDescriptor
import cool.jacoblin.particeps.core.collector.CollectorPlugin
import cool.jacoblin.particeps.core.collector.ProtocolEventSourceRegistry
import cool.jacoblin.particeps.core.collector.StudyAccessGateway
import cool.jacoblin.particeps.core.definition.AutomationDefinition
import cool.jacoblin.particeps.core.definition.CollectorProfileConfiguration
import cool.jacoblin.particeps.core.definition.CollectorResourceConfiguration
import cool.jacoblin.particeps.core.definition.DurationClock
import cool.jacoblin.particeps.core.definition.ExportConfiguration
import cool.jacoblin.particeps.core.definition.LocationV1PriorityValue
import cool.jacoblin.particeps.core.definition.LocationV1ProfileConfiguration
import cool.jacoblin.particeps.core.definition.NamedCollectorProfile
import cool.jacoblin.particeps.core.definition.ProtocolBase64Url
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.ResourceConditionCase
import cool.jacoblin.particeps.core.definition.SignerIdentity
import cool.jacoblin.particeps.core.definition.StateCondition
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyAccessPolicyTest {
    @Test
    fun inspectsEveryNamedLocationProfileAndAggregatesRequiredness() = runTest {
        val registry = cool.jacoblin.particeps.core.collector.CollectorRegistry(listOf(LocationPlugin))
        val gateway = RecordingGateway()

        val statuses = StudyAccessPolicy().inspect(configuration(), registry, gateway)

        assertEquals(2, gateway.requests.size)
        assertEquals(listOf(1_000L, 60_000L), gateway.requests.map { it.locationProfile?.intervalMillis })
        assertTrue(statuses.single { it.kind == AccessKind.FINE_LOCATION }.required)
        assertTrue(statuses.single { it.kind == AccessKind.LOCATION_SERVICES }.granted)
        assertTrue(statuses.single { it.kind == AccessKind.NOTIFICATIONS }.granted)
    }

    private class RecordingGateway : StudyAccessGateway {
        val requests = mutableListOf<cool.jacoblin.particeps.core.collector.AccessInspectionRequest>()

        override suspend fun inspect(
            request: cool.jacoblin.particeps.core.collector.AccessInspectionRequest,
        ): AccessSnapshot {
            requests += request
            return AccessSnapshot(
                request.requirements.map { requirement ->
                    AccessStatus(
                        requirement,
                        cool.jacoblin.particeps.core.collector.AccessResolution.Satisfied,
                        null,
                    )
                },
            )
        }
    }

    private object LocationPlugin : CollectorPlugin {
        override val descriptor = CollectorDescriptor(
            id = LocationV1ProfileConfiguration.SOURCE_ID,
            displayName = "Location",
            sourceContract = requireNotNull(ProtocolEventSourceRegistry[LocationV1ProfileConfiguration.SOURCE_ID]),
            accessKinds = setOf(AccessKind.FINE_LOCATION, AccessKind.LOCATION_SERVICES),
        )

        override fun create(configuration: CollectorProfileConfiguration, context: CollectorContext): Collector =
            error("Access inspection must not instantiate collectors")
    }

    private fun configuration(): StudyConfiguration {
        val resource = CollectorResourceConfiguration(
            id = LocationV1ProfileConfiguration.SOURCE_ID,
            required = true,
            profiles = listOf(
                NamedCollectorProfile(
                    "fast",
                    LocationV1ProfileConfiguration(
                        intervalMillis = 1_000,
                        maximumBatchDelayMillis = 0,
                        minimumDisplacementMillimeters = 0,
                        minimumIntervalMillis = 500,
                        priority = LocationV1PriorityValue.HIGH_ACCURACY,
                    ),
                ),
                NamedCollectorProfile(
                    "slow",
                    LocationV1ProfileConfiguration(
                        intervalMillis = 60_000,
                        maximumBatchDelayMillis = 60_000,
                        minimumDisplacementMillimeters = 1_000,
                        minimumIntervalMillis = 30_000,
                        priority = LocationV1PriorityValue.BALANCED,
                    ),
                ),
            ),
        )
        val automations: List<AutomationDefinition> = listOf(
            ResourceBindingAutomation(
                id = "bind-location",
                resource = resource.resourceKey,
                cases = listOf(
                    ResourceConditionCase(
                        StateCondition.ElapsedAtLeast(1, DurationClock.ACTIVE_RUNNING_TIME),
                        "fast",
                    ),
                ),
                defaultProfileId = "slow",
            ),
        )
        return StudyConfiguration(
            schemaVersion = 1,
            experimentId = "access-study",
            configurationId = "access-config",
            issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
            platform = StudyConfiguration.ANDROID_PLATFORM,
            minimumClientVersion = 1,
            title = "Access study",
            researcherName = "Researcher",
            researcherContact = "researcher@example.invalid",
            purpose = "Verify all named access profiles.",
            durationHours = 24,
            consentDocumentVersion = "v1",
            consentSummary = "Consent.",
            assignedParticipantId = null,
            collectors = listOf(resource),
            surveys = emptyList(),
            interventions = emptyList(),
            automations = automations,
            trafficShaping = TrafficShapingConfiguration.Disabled,
            maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
            signer = SignerIdentity("signer-key", ProtocolBase64Url.encode(ByteArray(32) { 1 })),
            export = ExportConfiguration("export-key", ProtocolBase64Url.encode(ByteArray(32) { 2 })),
            upload = null,
        )
    }
}
