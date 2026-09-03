package cool.jacoblin.particeps.core.definition

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import cool.jacoblin.particeps.core.model.EventSourceId
import cool.jacoblin.particeps.core.model.EventTypeKey
import cool.jacoblin.particeps.core.resource.ResourceKey
import cool.jacoblin.particeps.core.resource.ResourceKind
import java.math.BigDecimal
import java.time.Instant

/** Exact Protocol v1 signed-configuration codec. Old collector/trigger shapes are rejected. */
object StudyConfigurationCodec {
    private val ROOT_KEYS = setOf(
        "schema_version", "experiment_id", "configuration_id", "assigned_participant_id",
        "issued_at", "expires_at", "platform", "minimum_client_version", "title",
        "researcher", "purpose", "duration_hours", "consent", "collectors", "surveys",
        "interventions", "automations", "traffic_shaping", "storage", "signer", "export", "upload",
    )
    private val UPLOAD_KEYS = setOf("endpoint", "interval_minutes", "allow_metered")

    fun decode(bytes: ByteArray): StudyConfiguration {
        val decoded = decodeStructure(bytes)
        require(encode(decoded).contentEquals(bytes)) { "Configuration JSON is not canonical" }
        return decoded
    }

    fun canonicalize(bytes: ByteArray): ByteArray = encode(decodeStructure(bytes))

    fun encode(configuration: StudyConfiguration): ByteArray = ProtocolCanonicalJson.encode(
        JsonObject().apply {
            addProperty("schema_version", configuration.schemaVersion)
            addProperty("experiment_id", configuration.experimentId)
            addProperty("configuration_id", configuration.configurationId)
            add("assigned_participant_id", configuration.assignedParticipantId.jsonOrNull())
            addProperty("issued_at", configuration.issuedAt.toString())
            addProperty("expires_at", configuration.expiresAt.toString())
            addProperty("platform", configuration.platform)
            addProperty("minimum_client_version", configuration.minimumClientVersion.toString())
            addProperty("title", configuration.title)
            add("researcher", objectOf(
                "name" to JsonPrimitive(configuration.researcherName),
                "contact" to JsonPrimitive(configuration.researcherContact),
            ))
            addProperty("purpose", configuration.purpose)
            addProperty("duration_hours", configuration.durationHours)
            add("consent", objectOf(
                "document_version" to JsonPrimitive(configuration.consentDocumentVersion),
                "summary" to JsonPrimitive(configuration.consentSummary),
            ))
            add("collectors", arrayOf(configuration.collectors.map(::encodeCollector)))
            add("surveys", arrayOf(configuration.surveys.map(::encodeSurvey)))
            add("interventions", arrayOf(configuration.interventions.map(::encodeIntervention)))
            add("automations", arrayOf(configuration.automations.map(::encodeAutomation)))
            add("traffic_shaping", encodeTrafficShaping(configuration.trafficShaping))
            add("storage", objectOf("maximum_local_bytes" to JsonPrimitive(configuration.maximumLocalBytes)))
            add("signer", objectOf(
                "key_id" to JsonPrimitive(configuration.signer.keyId),
                "public_key" to JsonPrimitive(configuration.signer.publicKey),
            ))
            add("export", objectOf(
                "researcher_key_id" to JsonPrimitive(configuration.export.researcherKeyId),
                "hpke_public_key" to JsonPrimitive(configuration.export.hpkePublicKey),
            ))
            add("upload", configuration.upload?.let(::encodeUpload) ?: JsonObject())
        },
    )

    private fun decodeStructure(bytes: ByteArray): StudyConfiguration {
        require(bytes.size in 2..MAX_CONFIGURATION_BYTES) { "Invalid configuration size" }
        val root = ProtocolCanonicalJson.parse(bytes, MAX_CONFIGURATION_BYTES).requireObject("root")
        root.requireExactKeys(ROOT_KEYS)
        val researcher = root.requireObject("researcher").also {
            it.requireExactKeys(setOf("name", "contact"))
        }
        val consent = root.requireObject("consent").also {
            it.requireExactKeys(setOf("document_version", "summary"))
        }
        val storage = root.requireObject("storage").also {
            it.requireExactKeys(setOf("maximum_local_bytes"))
        }
        val signer = root.requireObject("signer").also {
            it.requireExactKeys(setOf("key_id", "public_key"))
        }
        return StudyConfiguration(
            schemaVersion = root.requireInt("schema_version"),
            experimentId = root.requireString("experiment_id"),
            configurationId = root.requireString("configuration_id"),
            assignedParticipantId = root.requireNullableString("assigned_participant_id"),
            issuedAt = Instant.parse(root.requireString("issued_at")),
            expiresAt = Instant.parse(root.requireString("expires_at")),
            platform = root.requireString("platform"),
            minimumClientVersion = root.requireDecimalLongString("minimum_client_version"),
            title = root.requireString("title"),
            researcherName = researcher.requireString("name"),
            researcherContact = researcher.requireString("contact"),
            purpose = root.requireString("purpose"),
            durationHours = root.requireInt("duration_hours"),
            consentDocumentVersion = consent.requireString("document_version"),
            consentSummary = consent.requireString("summary"),
            collectors = root.requireArray("collectors").mapElements(::decodeCollector),
            surveys = root.requireArray("surveys").mapElements(::decodeSurvey),
            interventions = root.requireArray("interventions").mapElements(::decodeIntervention),
            automations = root.requireArray("automations").mapElements(::decodeAutomation),
            trafficShaping = decodeTrafficShaping(root.requireObject("traffic_shaping")),
            maximumLocalBytes = storage.requireLong("maximum_local_bytes"),
            signer = SignerIdentity(signer.requireString("key_id"), signer.requireString("public_key")),
            export = decodeExport(root.requireObject("export")),
            upload = decodeUpload(root.requireObject("upload")),
        )
    }

    private fun decodeCollector(element: JsonElement): CollectorResourceConfiguration {
        val root = element.requireObject("collector")
        root.requireExactKeys(setOf("id", "required", "profiles"))
        val sourceId = root.requireString("id")
        return CollectorResourceConfiguration(
            id = sourceId,
            required = root.requireBoolean("required"),
            profiles = root.requireArray("profiles").mapElements { item ->
                val profile = item.requireObject("collector profile")
                profile.requireExactKeys(setOf("id", "config"))
                NamedCollectorProfile(
                    id = profile.requireString("id"),
                    configuration = GeneratedCollectorProfileCodec.decode(sourceId, profile.requireObject("config")),
                )
            },
        )
    }

    private fun encodeCollector(collector: CollectorResourceConfiguration): JsonObject = objectOf(
        "id" to JsonPrimitive(collector.id),
        "required" to JsonPrimitive(collector.required),
        "profiles" to arrayOf(collector.profiles.map { profile ->
            objectOf(
                "id" to JsonPrimitive(profile.id),
                "config" to GeneratedCollectorProfileCodec.encode(profile.configuration),
            )
        }),
    )

    private fun decodeIntervention(element: JsonElement): InterventionConfiguration {
        val root = element.requireObject("intervention")
        root.requireExactKeys(setOf("id", "required", "action"))
        return InterventionConfiguration(
            id = root.requireString("id"),
            required = root.requireBoolean("required"),
            action = decodeAction(root.requireObject("action")),
        )
    }

    private fun encodeIntervention(intervention: InterventionConfiguration): JsonObject = objectOf(
        "id" to JsonPrimitive(intervention.id),
        "required" to JsonPrimitive(intervention.required),
        "action" to encodeAction(intervention.action),
    )

    private fun decodeAction(root: JsonObject): InterventionAction = when (root.requireString("type")) {
        "notification" -> {
            root.requireExactKeys(setOf("type", "notification_title", "notification_message"))
            NotificationAction(root.requireString("notification_title"), root.requireString("notification_message"))
        }
        "survey" -> {
            root.requireExactKeys(setOf("type", "notification_title", "notification_message", "survey_id"))
            SurveyAction(
                root.requireString("notification_title"),
                root.requireString("notification_message"),
                root.requireString("survey_id"),
            )
        }
        else -> throw IllegalArgumentException("Unknown intervention action")
    }

    private fun encodeAction(action: InterventionAction): JsonObject = JsonObject().apply {
        addProperty("type", when (action) {
            is NotificationAction -> "notification"
            is SurveyAction -> "survey"
        })
        addProperty("notification_title", action.notificationTitle)
        addProperty("notification_message", action.notificationMessage)
        if (action is SurveyAction) addProperty("survey_id", action.surveyId)
    }

    private fun decodeAutomation(element: JsonElement): AutomationDefinition {
        val root = element.requireObject("automation")
        return when (root.requireString("type")) {
            "occurrence" -> {
                root.requireExactKeys(setOf(
                    "type", "id", "trigger", "guard", "intervention_id", "availability_seconds",
                    "cooldown", "maximum_activations",
                ))
                OccurrenceAutomation(
                    id = root.requireString("id"),
                    trigger = decodeTrigger(root.requireObject("trigger")),
                    guard = root.requireNullableObject("guard")?.let(::decodeCondition),
                    interventionId = root.requireString("intervention_id"),
                    availabilitySeconds = root.requireInt("availability_seconds"),
                    cooldown = root.requireNullableObject("cooldown")?.let(::decodeCooldown),
                    maximumActivations = root.requireInt("maximum_activations"),
                )
            }
            "resource_binding" -> {
                root.requireExactKeys(setOf("type", "id", "resource", "cases", "default_profile_id"))
                ResourceBindingAutomation(
                    id = root.requireString("id"),
                    resource = decodeResourceKey(root.requireObject("resource")),
                    cases = root.requireArray("cases").mapElements { item ->
                        val case = item.requireObject("resource condition case")
                        case.requireExactKeys(setOf("condition", "profile_id"))
                        ResourceConditionCase(
                            condition = decodeCondition(case.requireObject("condition")),
                            profileId = case.requireNullableString("profile_id"),
                        )
                    },
                    defaultProfileId = root.requireNullableString("default_profile_id"),
                )
            }
            else -> throw IllegalArgumentException("Unknown automation type")
        }
    }

    private fun encodeAutomation(automation: AutomationDefinition): JsonObject = when (automation) {
        is OccurrenceAutomation -> objectOf(
            "type" to JsonPrimitive("occurrence"),
            "id" to JsonPrimitive(automation.id),
            "trigger" to encodeTrigger(automation.trigger),
            "guard" to (automation.guard?.let(::encodeCondition) ?: JsonNull.INSTANCE),
            "intervention_id" to JsonPrimitive(automation.interventionId),
            "availability_seconds" to JsonPrimitive(automation.availabilitySeconds),
            "cooldown" to (automation.cooldown?.let(::encodeCooldown) ?: JsonNull.INSTANCE),
            "maximum_activations" to JsonPrimitive(automation.maximumActivations),
        )
        is ResourceBindingAutomation -> objectOf(
            "type" to JsonPrimitive("resource_binding"),
            "id" to JsonPrimitive(automation.id),
            "resource" to encodeResourceKey(automation.resource),
            "cases" to arrayOf(automation.cases.map { case ->
                objectOf(
                    "condition" to encodeCondition(case.condition),
                    "profile_id" to case.profileId.jsonOrNull(),
                )
            }),
            "default_profile_id" to automation.defaultProfileId.jsonOrNull(),
        )
    }

    private fun decodeTrigger(root: JsonObject): Trigger = when (root.requireString("type")) {
        "event_match" -> {
            root.requireExactKeys(setOf("type", "selector", "evaluation_clock"))
            Trigger.EventMatch(decodeMatcher(root.requireObject("selector")), root.requireEvaluationClock("evaluation_clock"))
        }
        "sequence" -> {
            root.requireExactKeys(setOf("type", "steps", "within_seconds", "evaluation_clock"))
            Trigger.Sequence(
                root.requireArray("steps").mapElements { decodeMatcher(it.requireObject("sequence step")) },
                root.requireInt("within_seconds"),
                root.requireEvaluationClock("evaluation_clock"),
            )
        }
        "window_threshold" -> {
            root.requireExactKeys(setOf(
                "type", "selector", "window_seconds", "evaluation_clock", "aggregate", "comparison",
            ))
            Trigger.WindowThreshold(
                decodeMatcher(root.requireObject("selector")),
                root.requireInt("window_seconds"),
                root.requireEvaluationClock("evaluation_clock"),
                decodeAggregate(root.requireObject("aggregate")),
                decodeComparison(root.requireObject("comparison")),
            )
        }
        "condition_rising_edge" -> {
            root.requireExactKeys(setOf("type", "condition"))
            Trigger.ConditionRisingEdge(decodeCondition(root.requireObject("condition")))
        }
        "schedule" -> {
            root.requireExactKeys(setOf("type", "schedule"))
            Trigger.Schedule(decodeSchedule(root.requireObject("schedule")))
        }
        else -> throw IllegalArgumentException("Unknown trigger type")
    }

    private fun encodeTrigger(trigger: Trigger): JsonObject = when (trigger) {
        is Trigger.EventMatch -> objectOf(
            "type" to JsonPrimitive("event_match"),
            "selector" to encodeMatcher(trigger.selector),
            "evaluation_clock" to JsonPrimitive(trigger.evaluationClock.wire()),
        )
        is Trigger.Sequence -> objectOf(
            "type" to JsonPrimitive("sequence"),
            "steps" to arrayOf(trigger.steps.map(::encodeMatcher)),
            "within_seconds" to JsonPrimitive(trigger.withinSeconds),
            "evaluation_clock" to JsonPrimitive(trigger.evaluationClock.wire()),
        )
        is Trigger.WindowThreshold -> objectOf(
            "type" to JsonPrimitive("window_threshold"),
            "selector" to encodeMatcher(trigger.selector),
            "window_seconds" to JsonPrimitive(trigger.windowSeconds),
            "evaluation_clock" to JsonPrimitive(trigger.evaluationClock.wire()),
            "aggregate" to encodeAggregate(trigger.aggregate),
            "comparison" to encodeComparison(trigger.comparison),
        )
        is Trigger.ConditionRisingEdge -> objectOf(
            "type" to JsonPrimitive("condition_rising_edge"),
            "condition" to encodeCondition(trigger.condition),
        )
        is Trigger.Schedule -> objectOf(
            "type" to JsonPrimitive("schedule"),
            "schedule" to encodeSchedule(trigger.schedule),
        )
    }

    private fun decodeCondition(root: JsonObject): StateCondition = when (root.requireString("type")) {
        "study_session_active" -> StateCondition.StudySessionActive.also {
            root.requireExactKeys(setOf("type"))
        }
        "event_latch" -> {
            root.requireExactKeys(setOf("type", "set_when", "reset_when"))
            StateCondition.EventLatch(
                root.requireArray("set_when").mapElements { decodeMatcher(it.requireObject("latch matcher")) },
                root.requireArray("reset_when").mapElements { decodeMatcher(it.requireObject("latch reset matcher")) },
            )
        }
        "keyed_presence" -> {
            root.requireExactKeys(setOf("type", "enter_when", "exit_when", "key_field"))
            StateCondition.KeyedPresence(
                root.requireArray("enter_when").mapElements { decodeMatcher(it.requireObject("presence enter matcher")) },
                root.requireArray("exit_when").mapElements { decodeMatcher(it.requireObject("presence exit matcher")) },
                root.requireString("key_field"),
            )
        }
        "held_for" -> {
            root.requireExactKeys(setOf("type", "condition", "duration_seconds", "clock"))
            StateCondition.HeldFor(
                decodeCondition(root.requireObject("condition")),
                root.requireInt("duration_seconds"),
                root.requireDurationClock("clock"),
            )
        }
        "elapsed_at_least" -> {
            root.requireExactKeys(setOf("type", "duration_seconds", "clock"))
            StateCondition.ElapsedAtLeast(root.requireInt("duration_seconds"), root.requireDurationClock("clock"))
        }
        "window_threshold" -> {
            root.requireExactKeys(setOf(
                "type", "selector", "window_seconds", "evaluation_clock", "aggregate", "comparison",
            ))
            StateCondition.WindowThreshold(
                decodeMatcher(root.requireObject("selector")),
                root.requireInt("window_seconds"),
                root.requireEvaluationClock("evaluation_clock"),
                decodeAggregate(root.requireObject("aggregate")),
                decodeComparison(root.requireObject("comparison")),
            )
        }
        "all" -> {
            root.requireExactKeys(setOf("type", "conditions"))
            StateCondition.All(root.requireArray("conditions").mapElements { decodeCondition(it.requireObject("condition")) })
        }
        "any" -> {
            root.requireExactKeys(setOf("type", "conditions"))
            StateCondition.Any(root.requireArray("conditions").mapElements { decodeCondition(it.requireObject("condition")) })
        }
        "not" -> {
            root.requireExactKeys(setOf("type", "condition"))
            StateCondition.Not(decodeCondition(root.requireObject("condition")))
        }
        else -> throw IllegalArgumentException("Unknown condition type")
    }

    private fun encodeCondition(condition: StateCondition): JsonObject = when (condition) {
        StateCondition.StudySessionActive -> objectOf("type" to JsonPrimitive("study_session_active"))
        is StateCondition.EventLatch -> objectOf(
            "type" to JsonPrimitive("event_latch"),
            "set_when" to arrayOf(condition.setWhen.map(::encodeMatcher)),
            "reset_when" to arrayOf(condition.resetWhen.map(::encodeMatcher)),
        )
        is StateCondition.KeyedPresence -> objectOf(
            "type" to JsonPrimitive("keyed_presence"),
            "enter_when" to arrayOf(condition.enterWhen.map(::encodeMatcher)),
            "exit_when" to arrayOf(condition.exitWhen.map(::encodeMatcher)),
            "key_field" to JsonPrimitive(condition.keyField),
        )
        is StateCondition.HeldFor -> objectOf(
            "type" to JsonPrimitive("held_for"),
            "condition" to encodeCondition(condition.condition),
            "duration_seconds" to JsonPrimitive(condition.durationSeconds),
            "clock" to JsonPrimitive(condition.clock.wire()),
        )
        is StateCondition.ElapsedAtLeast -> objectOf(
            "type" to JsonPrimitive("elapsed_at_least"),
            "duration_seconds" to JsonPrimitive(condition.durationSeconds),
            "clock" to JsonPrimitive(condition.clock.wire()),
        )
        is StateCondition.WindowThreshold -> objectOf(
            "type" to JsonPrimitive("window_threshold"),
            "selector" to encodeMatcher(condition.selector),
            "window_seconds" to JsonPrimitive(condition.windowSeconds),
            "evaluation_clock" to JsonPrimitive(condition.evaluationClock.wire()),
            "aggregate" to encodeAggregate(condition.aggregate),
            "comparison" to encodeComparison(condition.comparison),
        )
        is StateCondition.All -> objectOf(
            "type" to JsonPrimitive("all"),
            "conditions" to arrayOf(condition.conditions.map(::encodeCondition)),
        )
        is StateCondition.Any -> objectOf(
            "type" to JsonPrimitive("any"),
            "conditions" to arrayOf(condition.conditions.map(::encodeCondition)),
        )
        is StateCondition.Not -> objectOf(
            "type" to JsonPrimitive("not"),
            "condition" to encodeCondition(condition.condition),
        )
    }

    private fun decodeMatcher(root: JsonObject): EventMatcher {
        root.requireExactKeys(setOf("event", "predicates"))
        return EventMatcher(
            event = decodeEventKey(root.requireObject("event")),
            predicates = root.requireArray("predicates").mapElements(::decodePredicate),
        )
    }

    private fun encodeMatcher(matcher: EventMatcher): JsonObject = objectOf(
        "event" to encodeEventKey(matcher.event),
        "predicates" to arrayOf(matcher.predicates.map(::encodePredicate)),
    )

    private fun decodeEventKey(root: JsonObject): EventTypeKey {
        root.requireExactKeys(setOf("source_id", "schema_version", "event_type"))
        return EventTypeKey(
            EventSourceId(root.requireString("source_id")),
            root.requireInt("schema_version"),
            root.requireString("event_type"),
        )
    }

    private fun encodeEventKey(key: EventTypeKey): JsonObject = objectOf(
        "source_id" to JsonPrimitive(key.sourceId.value),
        "schema_version" to JsonPrimitive(key.schemaVersion),
        "event_type" to JsonPrimitive(key.eventType),
    )

    private fun decodePredicate(element: JsonElement): FieldPredicate {
        val root = element.requireObject("field predicate")
        val operator = root.requireFieldOperator("operator")
        return if (operator == FieldOperator.IN) {
            root.requireExactKeys(setOf("field", "operator", "values"))
            FieldPredicate(
                field = root.requireString("field"),
                operator = operator,
                values = root.requireArray("values").mapElements { it.requireStringValue("predicate value") },
            )
        } else {
            root.requireExactKeys(setOf("field", "operator", "value"))
            FieldPredicate(root.requireString("field"), operator, value = root.requireString("value"))
        }
    }

    private fun encodePredicate(predicate: FieldPredicate): JsonObject = JsonObject().apply {
        addProperty("field", predicate.field)
        addProperty("operator", predicate.operator.wire())
        if (predicate.operator == FieldOperator.IN) {
            add("values", arrayOf(requireNotNull(predicate.values).map(::JsonPrimitive)))
        } else {
            addProperty("value", requireNotNull(predicate.value))
        }
    }

    private fun decodeAggregate(root: JsonObject): Aggregate = when (root.requireString("type")) {
        "count" -> Aggregate.Count.also { root.requireExactKeys(setOf("type")) }
        "sum" -> {
            root.requireExactKeys(setOf("type", "field"))
            Aggregate.Sum(root.requireString("field"))
        }
        else -> throw IllegalArgumentException("Unknown aggregate")
    }

    private fun encodeAggregate(aggregate: Aggregate): JsonObject = when (aggregate) {
        Aggregate.Count -> objectOf("type" to JsonPrimitive("count"))
        is Aggregate.Sum -> objectOf("type" to JsonPrimitive("sum"), "field" to JsonPrimitive(aggregate.field))
    }

    private fun decodeComparison(root: JsonObject): NumericComparison {
        root.requireExactKeys(setOf("operator", "value"))
        return NumericComparison(root.requireFieldOperator("operator"), root.requireString("value"))
    }

    private fun encodeComparison(comparison: NumericComparison): JsonObject = objectOf(
        "operator" to JsonPrimitive(comparison.operator.wire()),
        "value" to JsonPrimitive(comparison.value),
    )

    private fun decodeCooldown(root: JsonObject): Cooldown {
        root.requireExactKeys(setOf("duration_seconds", "clock"))
        return Cooldown(root.requireInt("duration_seconds"), root.requireDurationClock("clock"))
    }

    private fun encodeCooldown(cooldown: Cooldown): JsonObject = objectOf(
        "duration_seconds" to JsonPrimitive(cooldown.durationSeconds),
        "clock" to JsonPrimitive(cooldown.clock.wire()),
    )

    private fun decodeSchedule(root: JsonObject): AutomationSchedule = when (root.requireString("type")) {
        "one_time" -> {
            root.requireExactKeys(setOf("type", "offset_minutes", "clock"))
            AutomationSchedule.OneTime(root.requireInt("offset_minutes"), root.requireDurationClock("clock"))
        }
        "interval" -> {
            root.requireExactKeys(setOf("type", "start_offset_minutes", "interval_minutes", "clock"))
            AutomationSchedule.Interval(
                root.requireInt("start_offset_minutes"),
                root.requireInt("interval_minutes"),
                root.requireDurationClock("clock"),
            )
        }
        "daily_local" -> {
            root.requireExactKeys(setOf("type", "local_time"))
            AutomationSchedule.DailyLocal(root.requireString("local_time"))
        }
        "random_window" -> {
            root.requireExactKeys(setOf(
                "type", "local_windows", "occurrences_per_window", "maximum_occurrences_per_day",
                "maximum_occurrences_total", "minimum_separation_minutes",
            ))
            AutomationSchedule.RandomWindow(
                localWindows = root.requireArray("local_windows").mapElements { item ->
                    val window = item.requireObject("local time window")
                    window.requireExactKeys(setOf("start_local_time", "end_local_time"))
                    LocalTimeWindow(window.requireString("start_local_time"), window.requireString("end_local_time"))
                },
                occurrencesPerWindow = root.requireInt("occurrences_per_window"),
                maximumOccurrencesPerDay = root.requireInt("maximum_occurrences_per_day"),
                maximumOccurrencesTotal = root.requireInt("maximum_occurrences_total"),
                minimumSeparationMinutes = root.requireInt("minimum_separation_minutes"),
            )
        }
        else -> throw IllegalArgumentException("Unknown automation schedule")
    }

    private fun encodeSchedule(schedule: AutomationSchedule): JsonObject = when (schedule) {
        is AutomationSchedule.OneTime -> objectOf(
            "type" to JsonPrimitive("one_time"),
            "offset_minutes" to JsonPrimitive(schedule.offsetMinutes),
            "clock" to JsonPrimitive(schedule.clock.wire()),
        )
        is AutomationSchedule.Interval -> objectOf(
            "type" to JsonPrimitive("interval"),
            "start_offset_minutes" to JsonPrimitive(schedule.startOffsetMinutes),
            "interval_minutes" to JsonPrimitive(schedule.intervalMinutes),
            "clock" to JsonPrimitive(schedule.clock.wire()),
        )
        is AutomationSchedule.DailyLocal -> objectOf(
            "type" to JsonPrimitive("daily_local"),
            "local_time" to JsonPrimitive(schedule.localTime),
        )
        is AutomationSchedule.RandomWindow -> objectOf(
            "type" to JsonPrimitive("random_window"),
            "local_windows" to arrayOf(schedule.localWindows.map { window ->
                objectOf(
                    "start_local_time" to JsonPrimitive(window.startLocalTime),
                    "end_local_time" to JsonPrimitive(window.endLocalTime),
                )
            }),
            "occurrences_per_window" to JsonPrimitive(schedule.occurrencesPerWindow),
            "maximum_occurrences_per_day" to JsonPrimitive(schedule.maximumOccurrencesPerDay),
            "maximum_occurrences_total" to JsonPrimitive(schedule.maximumOccurrencesTotal),
            "minimum_separation_minutes" to JsonPrimitive(schedule.minimumSeparationMinutes),
        )
    }

    private fun decodeResourceKey(root: JsonObject): ResourceKey {
        root.requireExactKeys(setOf("kind", "id"))
        val kind = root.requireString("kind")
        return ResourceKey(
            ResourceKind.entries.singleOrNull { it.name.lowercase() == kind }
                ?: throw IllegalArgumentException("Unknown resource kind"),
            root.requireString("id"),
        )
    }

    private fun encodeResourceKey(key: ResourceKey): JsonObject = objectOf(
        "kind" to JsonPrimitive(key.kind.name.lowercase()),
        "id" to JsonPrimitive(key.id),
    )

    private fun decodeTrafficShaping(root: JsonObject): TrafficShapingConfiguration {
        if (root.keySet().isEmpty()) return TrafficShapingConfiguration.Disabled
        root.requireExactKeys(setOf("target_packages", "profiles"))
        return TrafficShapingConfiguration.Enabled(
            targetPackages = root.requireArray("target_packages").mapElements {
                it.requireStringValue("target package")
            },
            profiles = root.requireArray("profiles").mapElements { item ->
                val profile = item.requireObject("traffic-shaping profile")
                profile.requireExactKeys(setOf("id", "uplink_kbps", "downlink_kbps"))
                TrafficShapingProfile(
                    profile.requireString("id"),
                    profile.requireNullableInt("uplink_kbps"),
                    profile.requireNullableInt("downlink_kbps"),
                )
            },
        )
    }

    private fun encodeTrafficShaping(configuration: TrafficShapingConfiguration): JsonObject = when (configuration) {
        TrafficShapingConfiguration.Disabled -> JsonObject()
        is TrafficShapingConfiguration.Enabled -> objectOf(
            "target_packages" to arrayOf(configuration.targetPackages.map(::JsonPrimitive)),
            "profiles" to arrayOf(configuration.profiles.map { profile ->
                objectOf(
                    "id" to JsonPrimitive(profile.id),
                    "uplink_kbps" to profile.uplinkKbps.jsonOrNull(),
                    "downlink_kbps" to profile.downlinkKbps.jsonOrNull(),
                )
            }),
        )
    }

    private fun decodeSurvey(element: JsonElement): SurveyDefinition {
        val root = element.requireObject("survey")
        root.requireExactKeys(setOf("id", "title", "description", "questions"))
        return SurveyDefinition(
            root.requireString("id"),
            decodeLocalizedText(root.requireObject("title")),
            decodeLocalizedText(root.requireObject("description")),
            root.requireArray("questions").mapElements(::decodeQuestion),
        )
    }

    private fun encodeSurvey(survey: SurveyDefinition): JsonObject = objectOf(
        "id" to JsonPrimitive(survey.id),
        "title" to encodeLocalizedText(survey.title),
        "description" to encodeLocalizedText(survey.description),
        "questions" to arrayOf(survey.questions.map(::encodeQuestion)),
    )

    private fun decodeLocalizedText(root: JsonObject): LocalizedText {
        root.requireExactKeys(setOf("default", "translations"))
        val translations = root.requireObject("translations")
        return LocalizedText(
            root.requireString("default"),
            translations.keySet().associateWith { language -> translations.requireString(language) },
        )
    }

    private fun encodeLocalizedText(text: LocalizedText): JsonObject = objectOf(
        "default" to JsonPrimitive(text.default),
        "translations" to JsonObject().apply {
            text.translations.forEach { (language, value) -> addProperty(language, value) }
        },
    )

    private fun decodeQuestion(element: JsonElement): SurveyQuestion {
        val root = element.requireObject("question")
        val type = root.requireString("type")
        val id = root.requireString("id")
        val prompt = decodeLocalizedText(root.requireObject("prompt"))
        val required = root.requireBoolean("required")
        return when (type) {
            "short_text" -> {
                root.requireExactKeys(setOf("type", "id", "prompt", "required", "maximum_length"))
                ShortTextQuestion(id, prompt, required, root.requireInt("maximum_length"))
            }
            "scale" -> {
                root.requireExactKeys(setOf(
                    "type", "id", "prompt", "required", "minimum", "maximum",
                    "minimum_label", "maximum_label",
                ))
                ScaleQuestion(
                    id, prompt, required, root.requireInt("minimum"), root.requireInt("maximum"),
                    decodeLocalizedText(root.requireObject("minimum_label")),
                    decodeLocalizedText(root.requireObject("maximum_label")),
                )
            }
            "single_choice" -> {
                root.requireExactKeys(setOf("type", "id", "prompt", "required", "options"))
                SingleChoiceQuestion(id, prompt, required, root.requireArray("options").mapElements(::decodeChoice))
            }
            "multiple_choice" -> {
                root.requireExactKeys(setOf(
                    "type", "id", "prompt", "required", "options",
                    "minimum_selections", "maximum_selections",
                ))
                MultipleChoiceQuestion(
                    id, prompt, required, root.requireArray("options").mapElements(::decodeChoice),
                    root.requireInt("minimum_selections"), root.requireInt("maximum_selections"),
                )
            }
            else -> throw IllegalArgumentException("Unknown survey question type")
        }
    }

    private fun encodeQuestion(question: SurveyQuestion): JsonObject = JsonObject().apply {
        addProperty("type", when (question) {
            is ShortTextQuestion -> "short_text"
            is ScaleQuestion -> "scale"
            is SingleChoiceQuestion -> "single_choice"
            is MultipleChoiceQuestion -> "multiple_choice"
        })
        addProperty("id", question.id)
        add("prompt", encodeLocalizedText(question.prompt))
        addProperty("required", question.required)
        when (question) {
            is ShortTextQuestion -> addProperty("maximum_length", question.maximumLength)
            is ScaleQuestion -> {
                addProperty("minimum", question.minimum)
                addProperty("maximum", question.maximum)
                add("minimum_label", encodeLocalizedText(question.minimumLabel))
                add("maximum_label", encodeLocalizedText(question.maximumLabel))
            }
            is SingleChoiceQuestion -> add("options", arrayOf(question.options.map(::encodeChoice)))
            is MultipleChoiceQuestion -> {
                add("options", arrayOf(question.options.map(::encodeChoice)))
                addProperty("minimum_selections", question.minimumSelections)
                addProperty("maximum_selections", question.maximumSelections)
            }
        }
    }

    private fun decodeChoice(element: JsonElement): ChoiceOption {
        val root = element.requireObject("choice")
        root.requireExactKeys(setOf("id", "label"))
        return ChoiceOption(root.requireString("id"), decodeLocalizedText(root.requireObject("label")))
    }

    private fun encodeChoice(choice: ChoiceOption): JsonObject = objectOf(
        "id" to JsonPrimitive(choice.id),
        "label" to encodeLocalizedText(choice.label),
    )

    private fun decodeExport(root: JsonObject): ExportConfiguration {
        root.requireExactKeys(setOf("researcher_key_id", "hpke_public_key"))
        return ExportConfiguration(root.requireString("researcher_key_id"), root.requireString("hpke_public_key"))
    }

    private fun decodeUpload(root: JsonObject): UploadConfiguration? {
        if (root.keySet().isEmpty()) return null
        root.requireExactKeys(UPLOAD_KEYS)
        return UploadConfiguration(
            root.requireString("endpoint"),
            root.requireInt("interval_minutes"),
            root.requireBoolean("allow_metered"),
        )
    }

    private fun encodeUpload(upload: UploadConfiguration): JsonObject = objectOf(
        "endpoint" to JsonPrimitive(upload.endpoint),
        "interval_minutes" to JsonPrimitive(upload.intervalMinutes),
        "allow_metered" to JsonPrimitive(upload.allowMetered),
    )

    private fun objectOf(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject().apply {
        entries.forEach { (key, value) -> add(key, value) }
    }

    private fun arrayOf(elements: List<JsonElement>): JsonArray = JsonArray().apply { elements.forEach(::add) }
    private fun String?.jsonOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull.INSTANCE
    private fun Int?.jsonOrNull(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull.INSTANCE

    private fun JsonObject.requireExactKeys(expected: Set<String>) {
        require(keySet() == expected) { "Unexpected JSON keys: expected=$expected actual=${keySet()}" }
    }

    private fun JsonObject.requireString(name: String): String =
        requireNotNull(get(name)).requireStringValue(name)

    private fun JsonObject.requireNullableString(name: String): String? = requireNotNull(get(name)).let {
        if (it.isJsonNull) null else it.requireStringValue(name)
    }

    private fun JsonElement.requireStringValue(name: String): String {
        require(isJsonPrimitive && asJsonPrimitive.isString) { "$name must be a string" }
        return asString
    }

    private fun JsonObject.requireBoolean(name: String): Boolean {
        val value = requireNotNull(get(name))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be a boolean" }
        return value.asBoolean
    }

    private fun JsonObject.requireInt(name: String): Int {
        val raw = requireIntegerLiteral(name)
        return raw.toIntOrNull() ?: throw IllegalArgumentException("$name is outside Int range")
    }

    private fun JsonObject.requireNullableInt(name: String): Int? = requireNotNull(get(name)).let {
        if (it.isJsonNull) null else requireInt(name)
    }

    private fun JsonObject.requireLong(name: String): Long {
        val raw = requireIntegerLiteral(name)
        return raw.toLongOrNull() ?: throw IllegalArgumentException("$name is outside Long range")
    }

    private fun JsonObject.requireIntegerLiteral(name: String): String {
        val value = requireNotNull(get(name))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
        val raw = value.asString
        require(raw.length <= MAXIMUM_NUMBER_CHARACTERS) { "$name is outside the supported numeric range" }
        raw.substringAfterAny('e', 'E')?.let { exponent ->
            require(exponent.toLongOrNull()?.let { kotlin.math.abs(it) <= MAXIMUM_DECIMAL_EXPONENT } == true) {
                "$name is outside the supported numeric range"
            }
        }
        val integer = runCatching { BigDecimal(raw).toBigIntegerExact() }.getOrNull()
        require(integer != null) { "$name must be an integer" }
        return integer.toString()
    }

    private fun JsonObject.requireDecimalLongString(name: String): Long {
        val raw = requireString(name)
        require(UNSIGNED_DECIMAL.matches(raw)) { "$name must be a canonical unsigned decimal string" }
        return raw.toLongOrNull() ?: throw IllegalArgumentException("$name is outside Long range")
    }

    private fun JsonObject.requireObject(name: String): JsonObject = requireNotNull(get(name)).requireObject(name)
    private fun JsonObject.requireNullableObject(name: String): JsonObject? = requireNotNull(get(name)).let {
        if (it.isJsonNull) null else it.requireObject(name)
    }
    private fun JsonObject.requireArray(name: String): JsonArray {
        val value = requireNotNull(get(name))
        require(value.isJsonArray) { "$name must be an array" }
        return value.asJsonArray
    }
    private fun JsonElement.requireObject(name: String): JsonObject {
        require(isJsonObject) { "$name must be an object" }
        return asJsonObject
    }
    private fun <T> JsonArray.mapElements(transform: (JsonElement) -> T): List<T> = map(transform)

    private fun JsonObject.requireFieldOperator(name: String): FieldOperator {
        val wire = requireString(name)
        return FieldOperator.entries.singleOrNull { it.name.lowercase() == wire }
            ?: throw IllegalArgumentException("Unknown field operator")
    }
    private fun JsonObject.requireEvaluationClock(name: String): EvaluationClock =
        enumValueOf(requireString(name))
    private fun JsonObject.requireDurationClock(name: String): DurationClock =
        enumValueOf(requireString(name))
    private fun FieldOperator.wire(): String = name.lowercase()
    private fun EvaluationClock.wire(): String = name
    private fun DurationClock.wire(): String = name

    private fun String.substringAfterAny(first: Char, second: Char): String? {
        val index = indexOfAny(charArrayOf(first, second))
        return if (index < 0) null else substring(index + 1)
    }

    private const val MAX_CONFIGURATION_BYTES = 1_048_576
    private const val MAXIMUM_NUMBER_CHARACTERS = 64
    private const val MAXIMUM_DECIMAL_EXPONENT = 64L
    private val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")
}
