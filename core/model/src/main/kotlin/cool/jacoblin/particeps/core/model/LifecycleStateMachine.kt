package cool.jacoblin.particeps.core.model

/** Validates the durable lifecycle projection; lifecycle events carry the ordered history. */
class LifecycleStateMachine {
    fun canTransition(from: ExperimentState, to: ExperimentState): Boolean =
        to in ALLOWED_TRANSITIONS.getValue(from)

    fun transition(from: ExperimentState, to: ExperimentState): ExperimentState {
        require(canTransition(from, to)) { "Illegal experiment transition: $from -> $to" }
        return to
    }

    private companion object {
        val ALLOWED_TRANSITIONS: Map<ExperimentState, Set<ExperimentState>> = mapOf(
            ExperimentState.IMPORTED to setOf(
                ExperimentState.CONFIG_VERIFIED,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.CONFIG_VERIFIED to setOf(
                ExperimentState.CONSENT_PENDING,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.CONSENT_PENDING to setOf(
                ExperimentState.ACCESS_SETUP,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.ACCESS_SETUP to setOf(
                ExperimentState.READY,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.READY to setOf(
                ExperimentState.ACTIVATING,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.ACTIVATING to setOf(
                ExperimentState.RUNNING,
                ExperimentState.PAUSING,
            ),
            ExperimentState.RUNNING to setOf(ExperimentState.PAUSING),
            ExperimentState.PAUSING to setOf(
                ExperimentState.PAUSED,
                ExperimentState.COMPLETED,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.PAUSED to setOf(
                ExperimentState.ACTIVATING,
                ExperimentState.PAUSING,
            ),
            ExperimentState.COMPLETED to setOf(ExperimentState.WITHDRAWN),
            ExperimentState.WITHDRAWN to emptySet(),
        )
    }
}
