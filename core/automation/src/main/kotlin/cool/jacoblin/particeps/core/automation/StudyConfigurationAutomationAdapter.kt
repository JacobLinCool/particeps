package cool.jacoblin.particeps.core.automation

import cool.jacoblin.particeps.core.definition.AutomationCompilerInput
import cool.jacoblin.particeps.core.definition.DeclaredResource
import cool.jacoblin.particeps.core.definition.InterventionDefinition
import cool.jacoblin.particeps.core.definition.ResourceBindingAutomation
import cool.jacoblin.particeps.core.definition.StudyConfiguration
import cool.jacoblin.particeps.core.definition.TrafficShapingConfiguration
import cool.jacoblin.particeps.core.definition.resourceKey
import cool.jacoblin.particeps.core.definition.signedProfiles
import cool.jacoblin.particeps.core.resource.Sha256Digest
import cool.jacoblin.particeps.core.resource.SignedResourceProfile

/**
 * Projects the already signature-verified study definition into the compiler's exact input.
 * Event contracts remain a separate generated-registry dependency of [AutomationCompiler].
 */
fun StudyConfiguration.toAutomationCompilerInput(configurationSha256: String): AutomationCompilerInput {
    Sha256Digest(configurationSha256)
    val declaredResources = buildList {
        collectors.forEach { collector ->
            add(
                DeclaredResource(
                    key = collector.resourceKey,
                    required = collector.required,
                    profileDigests = collector.signedProfiles().toDigestMap(),
                ),
            )
        }
        (trafficShaping as? TrafficShapingConfiguration.Enabled)?.let { shaping ->
            add(
                DeclaredResource(
                    key = shaping.resourceKey,
                    required = true,
                    profileDigests = shaping.signedProfiles().toDigestMap(),
                ),
            )
        }
    }.sortedBy(DeclaredResource::key)

    val resourceKeys = declaredResources.mapTo(linkedSetOf(), DeclaredResource::key)
    val owners = automations.filterIsInstance<ResourceBindingAutomation>().groupBy(ResourceBindingAutomation::resource)
    require(owners.keys.all(resourceKeys::contains)) { "Resource binding references an undeclared resource" }
    require(declaredResources.all { owners[it.key].orEmpty().size == 1 }) {
        "Every declared resource must have exactly one binding owner"
    }

    return AutomationCompilerInput(
        configurationSha256 = configurationSha256,
        studyDurationSeconds = Math.multiplyExact(durationHours.toLong(), SECONDS_PER_HOUR),
        resources = declaredResources,
        interventions = interventions.map { intervention ->
            InterventionDefinition(intervention.id, intervention.required)
        },
        automations = automations,
    )
}

private fun List<SignedResourceProfile>.toDigestMap(): Map<String, String> =
    associateTo(linkedMapOf()) { profile -> profile.id to profile.expectedSha256.value }

private const val SECONDS_PER_HOUR = 3_600L
