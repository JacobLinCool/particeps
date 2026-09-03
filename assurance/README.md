# Collector capability policy

`collector-policy.json` defines the static capability boundary enforced by
`tools/collector_assurance.py`. The check covers collector source imports, direct Gradle
dependencies, and compiled production class constant pools.

Exactly two narrow collector-to-collector dependencies are allowed and scanned by the same policy:

- `:collector:sensor-common` exposes only shared Android hardware-sensor listener lifecycle
  ownership;
- `:collector:usage-common` exposes only the single Usage Access AppOps probe shared by
  UsageStats-backed collectors and `:core:access`.

The allowlist is not a general permission for collector modules to depend on one another.

Run the check after compiling collector modules:

```bash
python3 tools/collector_assurance.py
```

This policy complements the runtime payload schema and size checks. It is not telemetry and does
not collect data from participant devices.
