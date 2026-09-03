"""Public authoritative automation compiler/reducer API used by analysis replay."""

from .automation_checkpoint import (
    action_id,
    automation_checkpoint_digest,
    decode_automation_checkpoint,
    deterministic_digest,
    encode_automation_checkpoint,
    timer_id,
)
from .automation_compiler import compile_automation_program
from .automation_model import (
    ActionRequest,
    AutomationAudit,
    AutomationCheckpoint,
    AutomationEvent,
    CompiledAutomationProgram,
    DesiredProfile,
    DurableTimer,
    ReducerClock,
    ReducerInput,
    ReductionResult,
    ResearchTime,
    ResourceKey,
    TimerIntent,
    TimerProductionRequest,
    TimerTarget,
)
from .automation_reducer import reduce_automation_batch

__all__ = [
    "ActionRequest",
    "AutomationAudit",
    "AutomationCheckpoint",
    "AutomationEvent",
    "CompiledAutomationProgram",
    "DesiredProfile",
    "DurableTimer",
    "ReducerClock",
    "ReducerInput",
    "ReductionResult",
    "ResearchTime",
    "ResourceKey",
    "TimerIntent",
    "TimerProductionRequest",
    "TimerTarget",
    "action_id",
    "automation_checkpoint_digest",
    "compile_automation_program",
    "decode_automation_checkpoint",
    "deterministic_digest",
    "encode_automation_checkpoint",
    "reduce_automation_batch",
    "timer_id",
]
