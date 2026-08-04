package cool.linc.androiddatacollector.core.definition

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class P2ConfigurationTest {
    @Test
    fun newCollectorsAndRandomWindowsRoundTripThroughTheClosedWorldCodec() {
        val configuration = baseConfiguration(
            collectors = listOf(
                BatteryStateConfiguration(required = false),
                TemporalContextConfiguration(required = false),
                GyroscopeConfiguration(false, 20_000, 1_000_000),
                AmbientLightConfiguration(false, 500_000, 2_000),
                ProximityConfiguration(false, 250, 10),
            ),
            interventions = listOf(
                InterventionConfiguration(
                    id = "random-ema",
                    action = NotificationAction("Check in", "Please answer now."),
                    triggers = listOf(
                        InterventionTrigger(
                            id = "random-window",
                            schedule = RandomWindowSchedule(
                                localWindows = listOf(
                                    RandomLocalWindow("09:00", "12:00"),
                                    RandomLocalWindow("14:00", "18:00"),
                                ),
                                occurrencesPerWindow = 2,
                                maximumOccurrencesPerDay = 3,
                                maximumOccurrencesTotal = 20,
                                minimumSeparationMinutes = 30,
                            ),
                            availabilityMinutes = 20,
                        ),
                    ),
                ),
            ),
        )

        val encoded = StudyConfigurationCodec.encode(configuration)

        assertEquals(configuration, StudyConfigurationCodec.decode(encoded))
    }

    @Test
    fun p2IntegerFieldsEnforceBothBoundsAndStrictCodecShapes() {
        data class IntegerFieldCase(
            val field: String,
            val minimum: Int,
            val maximum: Int,
            val collector: (Int) -> CollectorConfiguration,
        )
        val fields = listOf(
            IntegerFieldCase("sampling_period_us", 5_000, 1_000_000) {
                GyroscopeConfiguration(false, it, 1_000_000)
            },
            IntegerFieldCase("maximum_report_latency_us", 0, 60_000_000) {
                GyroscopeConfiguration(false, 20_000, it)
            },
            IntegerFieldCase("sampling_period_us", 200_000, 10_000_000) {
                AmbientLightConfiguration(false, it, 2_000)
            },
            IntegerFieldCase("change_threshold_millilux", 0, 100_000_000) {
                AmbientLightConfiguration(false, 500_000, it)
            },
            IntegerFieldCase("minimum_event_interval_ms", 100, 60_000) {
                ProximityConfiguration(false, it, 10)
            },
            IntegerFieldCase("change_threshold_millimeters", 0, 10_000) {
                ProximityConfiguration(false, 250, it)
            },
        )

        fields.forEach { case ->
            listOf(case.minimum, case.maximum).forEach { boundary ->
                val configuration = baseConfiguration(listOf(case.collector(boundary)), emptyList())
                assertEquals(configuration, StudyConfigurationCodec.decode(StudyConfigurationCodec.encode(configuration)))
            }
            assertThrows(IllegalArgumentException::class.java) { case.collector(case.minimum - 1) }
            assertThrows(IllegalArgumentException::class.java) { case.collector(case.maximum + 1) }

            val canonical = StudyConfigurationCodec.encode(
                baseConfiguration(listOf(case.collector(case.minimum)), emptyList()),
            ).toString(Charsets.UTF_8)
            val member = "\"${case.field}\":${case.minimum}"
            val withoutMember = canonical
                .replace("$member,", "")
                .replace(",$member", "")
            val withUnknownMember = canonical.replace("\"config\":{", "\"config\":{\"unknown\":0,")
            val withWrongType = canonical.replace(member, "\"${case.field}\":\"${case.minimum}\"")
            listOf(withoutMember, withUnknownMember, withWrongType).forEach { hostile ->
                assertThrows(IllegalArgumentException::class.java) {
                    StudyConfigurationCodec.decode(hostile.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    @Test
    fun randomWindowsRejectAmbiguousOrImpossibleBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            RandomWindowSchedule(
                listOf(RandomLocalWindow("12:00", "13:00"), RandomLocalWindow("09:00", "10:00")),
                1,
                1,
                1,
                10,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RandomWindowSchedule(
                listOf(RandomLocalWindow("09:00", "09:30")),
                occurrencesPerWindow = 2,
                maximumOccurrencesPerDay = 2,
                maximumOccurrencesTotal = 10,
                minimumSeparationMinutes = 30,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RandomWindowSchedule(
                listOf(RandomLocalWindow("09:00", "10:00")),
                occurrencesPerWindow = 1,
                maximumOccurrencesPerDay = 2,
                maximumOccurrencesTotal = 10,
                minimumSeparationMinutes = 5,
            )
        }
    }

    @Test
    fun randomOccurrenceBoundUsesTheSignedTotalUnderArbitraryWallClockEdits() {
        assertEquals(3L, maximumReachableLocalDates(studyMinutes = 60))
        val triggers = (1..2).map { index ->
            InterventionTrigger(
                id = "random-trigger-$index",
                schedule = RandomWindowSchedule(
                    localWindows = listOf(RandomLocalWindow("08:00", "09:00")),
                    occurrencesPerWindow = 8,
                    maximumOccurrencesPerDay = 8,
                    maximumOccurrencesTotal = 512,
                    minimumSeparationMinutes = 1,
                ),
                availabilityMinutes = 20,
            )
        }
        fun configuration(triggerCount: Int) = baseConfiguration(
            collectors = listOf(BatteryStateConfiguration(required = false)),
            interventions = listOf(
                InterventionConfiguration(
                    id = "random-ema",
                    action = NotificationAction("Check in", "Please answer now."),
                    triggers = triggers.take(triggerCount),
                ),
            ),
            durationHours = 1,
        )

        assertEquals(1, configuration(1).interventions.single().triggers.size)
        assertThrows(IllegalArgumentException::class.java) { configuration(2) }
    }

    private fun baseConfiguration(
        collectors: List<CollectorConfiguration>,
        interventions: List<InterventionConfiguration>,
        durationHours: Int = 24,
    ) = StudyConfiguration(
        schemaVersion = 1,
        experimentId = "p2-test",
        configurationId = "p2-config",
        issuedAt = Instant.parse("2026-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
        platform = "android",
        minimumClientVersion = 1,
        title = "P2 test",
        researcherName = "Researcher",
        researcherContact = "researcher@example.invalid",
        purpose = "Exercise P2 configuration contracts.",
        durationHours = durationHours,
        consentDocumentVersion = "v1",
        consentSummary = "Consent summary.",
        assignedParticipantId = null,
        collectors = collectors,
        surveys = emptyList(),
        interventions = interventions,
        maximumLocalBytes = StudyConfiguration.MINIMUM_LOCAL_BYTES,
        signer = SignerIdentity("signer-key", ProtocolBase64Url.encode(ByteArray(32) { 1 })),
        export = ExportConfiguration("export-key", ProtocolBase64Url.encode(ByteArray(32) { 2 })),
        upload = null,
    )
}
