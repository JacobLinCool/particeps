"""Public error types with intentionally non-sensitive messages."""


class AnalysisError(Exception):
    """Base class for expected analysis failures."""


class ValidationError(AnalysisError):
    """An input failed a Protocol v1 invariant."""


class ConflictError(AnalysisError):
    """Authenticated events reuse one identity with different content."""
