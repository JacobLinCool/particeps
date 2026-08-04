# Collector capability policy

`collector-policy.json` defines the static capability boundary enforced by
`tools/collector_assurance.py`. The check covers collector source imports, direct Gradle
dependencies, and compiled production class constant pools.

`:collector:sensor-common` is the sole allowed collector-to-collector dependency. It is scanned by
the same policy and exposes only shared Android sensor-listener lifecycle ownership.

Run the check after compiling collector modules:

```bash
python3 tools/collector_assurance.py
```

This policy complements the runtime payload schema and size checks. It is not telemetry and does
not collect data from participant devices.
