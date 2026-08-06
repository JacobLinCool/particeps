package cool.linc.particeps.core.model

class ExperimentStateMachine {
    fun canTransition(
        from: ExperimentState,
        to: ExperimentState,
    ): Boolean = to in ALLOWED_TRANSITIONS.getValue(from)

    fun transition(
        metadata: StudyMetadata,
        to: ExperimentState,
        reason: TransitionReason,
        time: ResearchTime,
    ): StudyMetadata {
        require(reason.destination == to) {
            "Reason $reason cannot produce state $to"
        }
        require(canTransition(metadata.state, to)) {
            "Illegal experiment transition: ${metadata.state} -> $to"
        }

        return metadata.copy(
            state = to,
            transitions = metadata.transitions + ExperimentTransition(
                from = metadata.state,
                to = to,
                reason = reason,
                time = time,
            ),
        )
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
                ExperimentState.RUNNING,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.RUNNING to setOf(
                ExperimentState.PAUSED,
                ExperimentState.COMPLETED,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.PAUSED to setOf(
                ExperimentState.RUNNING,
                ExperimentState.COMPLETED,
                ExperimentState.WITHDRAWN,
            ),
            ExperimentState.COMPLETED to setOf(ExperimentState.WITHDRAWN),
            ExperimentState.WITHDRAWN to emptySet(),
        )
    }
}
