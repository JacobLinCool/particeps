package cool.jacoblin.particeps.core.definition

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDrivenConfigurationTest {
    @Test
    fun signedTextBoundsCountUtf16CodeUnits() {
        assertEquals(120, "😀".repeat(60).length)
        configuration(title = "😀".repeat(60))
        assertThrows(IllegalArgumentException::class.java) {
            configuration(title = "😀".repeat(61))
        }
    }

    @Test
    fun surveyArrayIsBoundedAt128() {
        fun survey(index: Int) = SurveyDefinition(
            id = "survey-${index.toString().padStart(3, '0')}",
            title = LocalizedText("Survey"),
            description = LocalizedText("Description"),
            questions = listOf(
                ShortTextQuestion("question-one", LocalizedText("Prompt"), required = false, maximumLength = 100),
            ),
        )
        configuration(surveys = List(128, ::survey))
        assertThrows(IllegalArgumentException::class.java) {
            configuration(surveys = List(129, ::survey))
        }
    }

    @Test
    fun namedProfilesAutomationsAndTrafficShapingRoundTripCanonically() {
        val configuration = configuration()
        val encoded = StudyConfigurationCodec.encode(configuration)

        assertEquals(configuration, StudyConfigurationCodec.decode(encoded))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"automations\""))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"traffic_shaping\""))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"poll_interval_seconds\":15"))
    }

    @Test
    fun oldConfigurationAndCollectorShapesAreRejectedWithoutFallback() {
        val canonical = StudyConfigurationCodec.encode(configuration()).toString(Charsets.UTF_8)
        val missingAutomationRoot = JsonParser.parseString(canonical).asJsonObject.apply {
            remove("automations")
        }
        val oldCollector = JsonParser.parseString(canonical).asJsonObject.apply {
            getAsJsonArray("collectors").single().asJsonObject.apply {
                remove("profiles")
                add("config", JsonObject().apply { addProperty("poll_interval_minutes", 1) })
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.decode(ProtocolCanonicalJson.encode(missingAutomationRoot))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.decode(ProtocolCanonicalJson.encode(oldCollector))
        }
    }

    @Test
    fun shapingDisabledIsExactlyAnEmptyObject() {
        val noResources = configuration(
            collectors = emptyList(),
            interventions = emptyList(),
            automations = emptyList(),
            trafficShaping = TrafficShapingConfiguration.Disabled,
        )
        val encoded = StudyConfigurationCodec.encode(noResources).toString(Charsets.UTF_8)
        assertTrue(encoded.contains("\"traffic_shaping\":{}"))

        val hostile = encoded.replace("\"traffic_shaping\":{}", "\"traffic_shaping\":{\"enabled\":false}")
        assertThrows(IllegalArgumentException::class.java) {
            StudyConfigurationCodec.decode(hostile.toByteArray())
        }
    }

    @Test
    fun trafficProfilesAndTargetsAreClosedAndSorted() {
        assertThrows(IllegalArgumentException::class.java) {
            TrafficShapingConfiguration.Enabled(
                targetPackages = listOf("com.z", "com.a"),
                profiles = listOf(TrafficShapingProfile("baseline", null, null)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrafficShapingConfiguration.Enabled(
                targetPackages = listOf(TrafficShapingConfiguration.PARTICEPS_APPLICATION_ID),
                profiles = listOf(TrafficShapingProfile("baseline", null, null)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrafficShapingProfile("too-fast", 1_000_001, null)
        }
    }

    private fun configuration(
        title: String = "Runtime test",
        surveys: List<SurveyDefinition> = emptyList(),
        collectors: List<CollectorResourceConfiguration> = listOf(
            CollectorResourceConfiguration(
                id = UsageEventsV1ProfileConfiguration.SOURCE_ID,
                required = true,
                profiles = listOf(
                    NamedCollectorProfile("continuous", UsageEventsV1ProfileConfiguration(15)),
                ),
            ),
        ),
        interventions: List<InterventionConfiguration> = listOf(
            InterventionConfiguration(
                id = "check-in",
                required = true,
                action = NotificationAction("Check in", "Please answer now."),
            ),
        ),
        automations: List<AutomationDefinition> = listOf(
            OccurrenceAutomation(
                id = "app-check-in",
                trigger = Trigger.EventMatch(
                    selector = EventMatcher(
                        EventTypeKey(EventSourceId("usage_events.v1"), 1, "ACTIVITY_RESUMED"),
                        listOf(FieldPredicate("package_name", FieldOperator.EQ, value = "com.example.social")),
                    ),
                    evaluationClock = EvaluationClock.PRIMARY_SOURCE_TIME,
                ),
                guard = null,
                interventionId = "check-in",
                availabilitySeconds = 1_200,
                cooldown = Cooldown(3_600, DurationClock.ACTIVE_RUNNING_TIME),
                maximumActivations = 20,
            ),
            ResourceBindingAutomation(
                id = "bind-traffic",
                resource = ResourceKey(ResourceKind.ACTUATOR, TRAFFIC_SHAPING_RESOURCE_ID),
                cases = listOf(
                    ResourceConditionCase(
                        StateCondition.ElapsedAtLeast(180, DurationClock.ACTIVE_RUNNING_TIME),
                        "slow-network",
                    ),
                ),
                defaultProfileId = "baseline",
            ),
            ResourceBindingAutomation(
                id = "bind-usage",
                resource = ResourceKey(ResourceKind.COLLECTOR, "usage_events.v1"),
                cases = listOf(ResourceConditionCase(StateCondition.StudySessionActive, "continuous")),
                defaultProfileId = null,
            ),
        ).sortedBy(AutomationDefinition::id),
        trafficShaping: TrafficShapingConfiguration = TrafficShapingConfiguration.Enabled(
            targetPackages = listOf("com.example.social"),
            profiles = listOf(
                TrafficShapingProfile("baseline", null, null),
                TrafficShapingProfile("slow-network", 256, 1_024),
            ),
        ),
    ) = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "runtime-test",
        configurationId = "runtime-config",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = "android",
        minimumClientVersion = 1,
        title = title,
        researcherName = "Researcher",
        researcherContact = "researcher@example.invalid",
        purpose = "Exercise the event-driven configuration contracts.",
        durationHours = 24,
        consentDocumentVersion = "v1",
        consentSummary = "Consent summary.",
        assignedParticipantId = null,
        collectors = collectors,
        surveys = surveys,
        interventions = interventions,
        automations = automations,
        trafficShaping = trafficShaping,
        maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
        signer = SignerIdentity("signer-key", ProtocolBase64Url.encode(ByteArray(32) { 1 })),
        export = ExportConfiguration("export-key", ProtocolBase64Url.encode(ByteArray(32) { 2 })),
        upload = null,
    )
}
