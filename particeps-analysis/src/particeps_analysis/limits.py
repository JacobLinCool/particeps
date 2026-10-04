"""Protocol v1 resource limits shared by inventory and bundle verification."""

AUTOMATIC_UPLOAD_MAX_BYTES = 32 * 1024 * 1024
MANUAL_EXPORT_MAX_BYTES = 8 * 1024 * 1024 * 1024
SIGNED_CONFIGURATION_MAX_BYTES = 1024 * 1024
JSON_STRING_TOKEN_MAX_BYTES = SIGNED_CONFIGURATION_MAX_BYTES
JSON_MAX_DEPTH = 64
# Protocol numeric fields fit signed int64; event/time/sequence int64 values are strings.
# Bound digits before the native JSON parser attempts an integer conversion.
JSON_INTEGER_MAX_DIGITS = 19
