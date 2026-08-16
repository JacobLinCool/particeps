# Data dictionary

Every field that can appear in an exported dataset, per collector. This document is written to be quotable in an ethics submission: it describes what the code in this repository actually emits, including the gaps.

Read the "what you cannot claim" column in the [researcher guide](researcher-guide.md) alongside this. This document says what a field *is*; that one says what it does not prove. The machine-readable source of truth is the [Protocol v1 collector catalog](../protocol/v1/collector-catalog.json); [Protocol v1](../protocol/v1/README.md) defines the enclosing document and validation order.

## Reading an export

A decrypted bundle is a `particeps-research-bundle-v1` JSON document.

```json
{
  "bundle_id": "0a1b2c3d-4e5f-4071-8293-a4b5c6d7e8f9",
  "bundle_kind": "automatic_upload",
  "configuration": {},
  "configuration_sha256": "<64 lowercase hex characters>",
  "configuration_signature": {"signature": "<unpadded-base64url>", "signer_key_id": "lab-signer-2026"},
  "experiment": {
    "assigned_participant_id": "cohortA-0042",
    "configuration_id": "config-2026",
    "durable_through_sequence": "4210",
    "event_count": "10",
    "events": [],
    "experiment_id": "study-2026",
    "first_sequence_number": "4201",
    "last_sequence_number": "4210",
    "next_sequence_number": "4211",
    "participant_instance_id": "1a1b2c3d-4e5f-4071-8293-a4b5c6d7e8f9",
    "retained_from_sequence": "1",
    "state": "RUNNING",
    "transitions": [],
    "uploaded_through_sequence": "4200"
  },
  "exported_at_utc_millis": "1767225600000",
  "format": "particeps-research-bundle-v1",
  "producer": {"client_version": "1", "platform": "android"}
}
```

The example is expanded for readability. The authenticated bytes are RFC 8785 JCS, so member
order and number spelling are canonical.

`configuration` is the canonical study configuration the participant consented to, reproduced verbatim. Every dataset therefore carries its own definition of what was supposed to be collected. That includes its `upload` block, so a dataset states whether the study it came from delivered data to an endpoint.

`configuration_signature` preserves the original signer key ID and raw Ed25519 signature. `configuration_sha256` is the digest of that same configuration. [Protocol v1](../protocol/v1/README.md) defines how both are carried and bound by the encrypted container. Both are verified again during decryption. They identify which key issued the artifact. They do not attest who held that key, or which device submitted the bundle — see the [threat model](threat-model.md) for what a signer does and does not establish. `producer` records the producing platform and client build.

| Field | Meaning |
| --- | --- |
| `participant_instance_id` | A random UUID generated on the device for each import. Importing the same signed configuration again creates a different ID and independent sequence space. It is pseudonymous: no name, account, device identifier, or advertising ID. It is absent from upload URLs and headers, but remains personal data after decryption. |
| `assigned_participant_id` | Optional researcher-assigned opaque code copied from the signed configuration. It exists only for a personalized study. It is stored in encrypted metadata and appears in the encrypted export, but is deliberately absent from clear upload headers. It can link the dataset to a research roster and must be governed as personal data. |
| `next_sequence_number` | The device's counter at the snapshot: one past the last event durably stored. Decimal string. |
| `retained_from_sequence` | Lowest sequence still retained locally after confirmed-prefix reclamation. Decimal string. |
| `durable_through_sequence` | Highest event durably stored at the snapshot. Decimal string. |
| `uploaded_through_sequence` | Highest sequence committed after an exact upload receipt. Decimal string. |
| `event_count` | Number of events in this bundle; must agree with the inclusive range. Decimal string. |
| `first_sequence_number` | First event sequence this bundle contains, inclusive. Decimal string. |
| `last_sequence_number` | Last event sequence this bundle contains, inclusive. Decimal string. |

### Whole exports and uploaded chunks

The two ways data leaves the device produce the same document. What differs is the window.

- A **manual export** runs to whatever was durable when the participant pressed export. It starts at `first_sequence_number: "1"`. If the device has reclaimed a delivered prefix to free space, it starts instead at the lowest sequence still on the phone. Successive exports from one participant therefore overlap, each containing everything the previous one did that has not since been reclaimed.
- An **uploaded chunk**, from a study whose configuration names an endpoint, starts after the last sequence an exact upload receipt confirmed. Its end is a boundary the app picks near a 16 MiB plaintext target, not a boundary stated anywhere in the study's configuration. Consecutive chunks normally abut. An exact replay carries the same bundle UUID and the same bytes as the delivery it repeats. [Protocol v1](../protocol/v1/README.md) defines the receipt and what makes a replay exact.

Read `first_sequence_number` and `last_sequence_number` on every bundle rather than assuming a starting point or deriving a boundary from the study's configuration. In an uploading study the complete dataset for a participant is the chunks plus the final export, joined on sequence number.

A manual bundle is bounded by `storage.maximum_local_bytes` rather than by the automatic-upload wire ceiling, which is why `researcher-tools decrypt` streams rather than decrypting in memory.

`state`, `transitions`, and `configuration` describe the study as a whole in both cases, not just the window, so the same transition history repeats in every chunk. `events`, `event_count`, `first_sequence_number`, and `last_sequence_number` are window-scoped.

A reader built for a different bundle format fails to decrypt rather than silently misreading one: the authentication tag fails before any field is parsed. [Protocol v1](../protocol/v1/README.md) lists everything the container authenticates alongside `format`.

### The transition history

`transitions` is the study's lifecycle in order, one object per state change, from import up to the `state` the bundle reports. A study that has only been imported carries an empty array.

```json
{
  "from": "RUNNING",
  "reason": "PARTICIPANT_PAUSED",
  "time": {
    "wall_time_utc_millis": "1767225600000",
    "monotonic_time_nanos": "12345678901234",
    "boot_session_id": "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
  },
  "to": "PAUSED"
}
```

| Field | JSON type | Meaning |
| --- | --- | --- |
| `from` | string | The state before this change |
| `to` | string | The state after it |
| `reason` | string | Why it happened. Each reason has exactly one destination state. |
| `time` | object | The same three clocks, with the same caveats, as an event's `observed_time` |

A state is one of `IMPORTED`, `CONFIG_VERIFIED`, `CONSENT_PENDING`, `ACCESS_SETUP`, `READY`, `RUNNING`, `PAUSED`, `COMPLETED`, or `WITHDRAWN`. The reasons and the state each one produces:

| `reason` | `to` |
| --- | --- |
| `CONFIGURATION_SIGNATURE_VERIFIED` | `CONFIG_VERIFIED` |
| `CONSENT_REVIEW_OPENED` | `CONSENT_PENDING` |
| `CONSENT_ACCEPTED` | `ACCESS_SETUP` |
| `ACCESS_PREFLIGHT_PASSED` | `READY` |
| `PARTICIPANT_STARTED` | `RUNNING` |
| `PARTICIPANT_PAUSED` | `PAUSED` |
| `DEVICE_REBOOT` | `PAUSED` |
| `AUTOMATIC_RECOVERY` | `RUNNING` |
| `PARTICIPANT_RESUMED` | `RUNNING` |
| `STUDY_DURATION_ELAPSED` | `COMPLETED` |
| `PARTICIPANT_WITHDREW` | `WITHDRAWN` |
| `COLLECTION_HOST_FAILURE` | `PAUSED` |
| `COLLECTION_TEARDOWN_FAILURE` | `PAUSED` |
| `REQUIRED_ACCESS_MISSING` | `PAUSED` |
| `STORAGE_FAILURE` | `PAUSED` |
| `WORK_SCHEDULING_FAILURE` | `PAUSED` |

The last five rows are lifecycle transition reasons, not generic error labels. They appear in an
exported history only when an already durable `RUNNING` study is forced to `PAUSED`. Missing access
during setup, first Start, or Resume leaves the existing state unchanged and therefore adds no such
transition. Collector-health codes, upload-failure codes, and participant-facing incident codes are
separate diagnostic fields and must not be interpreted as `transitions[].reason` values.

The history is checked before any plaintext is published: the first `from` is `IMPORTED`, each `from` equals the previous `to`, each `reason` agrees with its destination, the pair is a legal transition, and the last `to` equals `state`. A bundle whose history does not chain fails verification rather than decoding partially.

Reconstruct the running and paused windows from `transitions` rather than from export times. `particeps-analysis` validates the history but does not materialize it. The typed Parquet dataset holds collector events only, so this array is read from the decrypted bundle JSON.

### The event envelope

Every event has the same shape regardless of collector.

```json
{
  "sequence_number": "1",
  "collector_id": "accelerometer.v1",
  "payload_schema_version": 1,
  "observed_time": {
    "wall_time_utc_millis": "1767225600000",
    "monotonic_time_nanos": "12345678901234",
    "boot_session_id": "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
  },
  "payload_type": "ACCELEROMETER_SAMPLE",
  "fields": { }
}
```

| Envelope field | JSON type | Meaning |
| --- | --- | --- |
| `sequence_number` | decimal string | Monotonic, starts at 1, **shared across all collectors in a study**. Not per-collector. |
| `collector_id` | string | Which collector produced this event |
| `payload_schema_version` | number | Version of the `fields` schema for this collector |
| `observed_time` | object | See below |
| `payload_type` | string | Which kind of event this is, within the collector |
| `fields` | object | The payload. Keys are sorted. |

### Every payload value is a JSON string

This is the most important thing to know before writing a parser.

`fields` is a string-to-string map. Numbers and booleans are stringified: acceleration appears as `"9.81"`, not `9.81`, and flags appear as `"true"` / `"false"`, not `true` / `false`. `payload_schema_version` is a bounded JSON number; sequence and time values are canonical decimal strings.

Field keys match `[a-z][a-z0-9_]{0,63}` and an event has at most 32 fields. A field value is capped at 60 Ki UTF-16 code units; storage independently caps the complete protocol-encoded event at 64 KiB. Ordinary collectors emit much smaller scalar values. The larger bound exists so one survey submission can be committed as a single immutable value.

### Time

Three clocks are recorded on every event, because no single one is sufficient.

| Field | Source | Unit | Caveat |
| --- | --- | --- | --- |
| `wall_time_utc_millis` | `System.currentTimeMillis()` | ms since Unix epoch, UTC | Can jump forwards or backwards — NTP corrections, manual changes, timezone travel. Do not assume monotonicity. |
| `monotonic_time_nanos` | `SystemClock.elapsedRealtimeNanos()` on Android | ns on a continuous monotonic clock | Includes deep sleep on Android and is only comparable **within the same `boot_session_id`** |
| `boot_session_id` | derived | 32 hex characters | Changes on every reboot. A change means the two elapsed-realtime values either side are incomparable. |

The common event envelope does not carry a time zone or UTC offset. A study that explicitly enables
`temporal_context.v1` receives the bounded snapshots documented below; otherwise local time cannot
be reconstructed from an export.

`observed_time` is stamped inside the collector callback at capture time, with two exceptions. `network_usage.v1` and `usage_events.v1` are polling collectors: they stamp one `observed_time` per poll, shared by every event in that batch. For those two, use the in-payload source time instead.

Several collectors also carry a source-supplied time in their payload. **Do not subtract across clock bases:**

- `monotonic_time_nanos` and `source_elapsed_realtime_nanos` fields (accelerometer, gyroscope,
  ambient light, proximity, and location) use Android's elapsed-realtime base, which **includes**
  deep sleep.
- The keyboard's `event_uptime_millis` and `down_uptime_millis` use Android's uptime base, which **excludes** deep sleep.

### Deduplication

Exports overlap by design — a participant can export repeatedly, and each export contains everything from its retained floor up to its boundary. Partition by `(experiment_id, configuration_id)` and deduplicate on `(participant_instance_id, sequence_number)`, making the complete event identity all four values. Identical repeats are duplicates; different content at one identity is a conflict, never a last-write-wins update. Do not merge solely on an assigned ID. Receiver ingestion uses a different key entirely — the immutable bundle UUID with its exact bytes and metadata, never a participant/range pair — as [Protocol v1](../protocol/v1/README.md) sets out.

### Intervention and survey events (`interventions.v1`)

The runtime, not a data collector, emits these v1 events. All share `intervention_id`, `trigger_id`, `occurrence_id`, and `scheduled_for_utc_millis`. The occurrence ID is a 64-character lowercase SHA-256 identity derived from the logical schedule position; it is the primary join key across lifecycle events.

| `payload_type` | Meaning | Additional fields |
| --- | --- | --- |
| `INTERVENTION_SCHEDULED` | The logical occurrence became durable | none |
| `INTERVENTION_RESCHEDULED` | A pending active-time or daily-local occurrence received a new target after pause, clock, or timezone reconciliation; its occurrence ID is unchanged | none |
| `NOTIFICATION_POSTED` | Android was asked to display its notification; this does **not** prove the participant saw it | none |
| `INTERVENTION_OPENED` | A notification-only occurrence was opened | none |
| `SURVEY_OPENED` | The exact survey occurrence was opened | none |
| `SURVEY_EXPIRED` | Its signed availability window ended without a submission | none |
| `INTERVENTION_EXPIRED` | A notification-only occurrence expired | none |
| `SURVEY_SUBMITTED` | One validated, final survey response was atomically committed | `survey_id`, `scheduled_time`, `opened_time`, `submitted_time`, `answers_json` |

The three `*_time` fields are compact JSON encodings of the same three-clock `ResearchTime` shape documented above. `answers_json` is one compact JSON object keyed only by stable question IDs. Short text is a JSON string, scale is an integer, and choice answers are arrays of stable option IDs. Question wording, translated labels, and option display text are never copied into the answer. Optional unanswered questions are absent. There are no draft, answer-change, or partial-submission events.

For compliance metrics, start with the lifecycle event that actually supports the claim: scheduled is not posted, posted is not seen, opened is not submitted, and expired is not declined. Join on `participant_instance_id` and `occurrence_id`; use `assigned_participant_id` only when an approved personalized-study roster requires it.

### Gaps are real and are not errors

- Nothing is recorded while a study is `PAUSED`, including intervention lifecycle or survey-submission events. Prompt work and visible intervention notifications are removed; calendar time and availability continue, and durable occurrences are reconciled on resume. Polling collectors do not back-fill the paused interval; that data is deliberately never collected.
- Across a process restart, `network_usage.v1` and `usage_events.v1` do resume their query window from the last stored event, so a restart is not the same as a pause.
- A collector that loses access reports `BLOCKED_ACCESS` and stops. It never emits a placeholder or interpolated value.

A reclaimed prefix is not one of these gaps. When a bundle's `first_sequence_number` is above 1, the events below it were collected, delivered to the study's endpoint, and then removed from the phone to free space. They are in the chunks that endpoint received, not missing from the study. Events that were never delivered are never removed.

---

## `app_lifecycle.v1`

Lifecycle callbacks of **this app's own** activities. Present mainly as a low-risk integration reference.

**Configuration:** `{}` — no parameters beyond the collector-level `required` flag.
**Access:** none.

**Payload types:** `ACTIVITY_CREATED`, `ACTIVITY_STARTED`, `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`, `ACTIVITY_INSTANCE_STATE_SAVED`, `ACTIVITY_DESTROYED`.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `activity_class` | string | — | Fully-qualified Java class name of the activity |

Not recorded: any other app's lifecycle, saved-instance-state contents, intent extras, view hierarchy.

---

## `accelerometer.v1`

Raw accelerometer samples, **including gravity**. No filtering, orientation estimation, or activity recognition is applied.

**Configuration:**

| Parameter | JSON key | Type | Unit | Range |
| --- | --- | --- | --- | --- |
| Sampling period | `sampling_period_us` | int | microseconds | 5,000–1,000,000 (200 Hz–1 Hz) |
| Maximum report latency | `maximum_report_latency_us` | int | microseconds | 0–60,000,000 (batching window) |

The sampling period is a **hint to Android**, not a guarantee. Actual delivery rate varies by device, by sensor, and with system power state. Measure the achieved rate from `source_elapsed_realtime_nanos`, not the event-envelope callback time: FIFO batching can deliver many earlier hardware samples in one callback burst.

**Access:** accelerometer hardware must be present. This is a capability check, not an Android permission — there is no dialog and nothing for the participant to grant. A device without the sensor cannot run a study that requires this collector.

**Payload type:** `ACCELEROMETER_SAMPLE`.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `source_elapsed_realtime_nanos` | long | ns since boot | Hardware sample time from `SensorEvent.timestamp`. Use this, not `observed_time`, for inter-sample intervals. |
| `x_meters_per_second_squared` | float | m/s² | Device X axis, includes gravity |
| `y_meters_per_second_squared` | float | m/s² | Device Y axis, includes gravity |
| `z_meters_per_second_squared` | float | m/s² | Device Z axis, includes gravity |
| `accuracy` | int | enum ordinal | Raw `SensorEvent.accuracy`, written unmapped |

Not recorded: gyroscope, magnetometer, or any other sensor; derived orientation, step counts, or activity labels. Accuracy-*change* callbacks are discarded — accuracy is observable only as it rides along on samples, so a transition between two samples is not visible.

---

## `battery_state.v1`

Event-driven battery context with exact duplicate suppression and a one-minute emission bound. If
several changes arrive inside the bound, the newest distinct state is retained.

**Configuration:** `{}`. **Access:** none.

**Payload type:** `BATTERY_STATE`.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `percentage` | int | whole percent | `(level × 100) / scale`, bounded to 0–100 |
| `charging_state` | enum | — | `CHARGING`, `DISCHARGING`, `FULL`, `NOT_CHARGING`, or `UNKNOWN` |
| `charging_source` | enum | — | `AC`, `USB`, `WIRELESS`, `DOCK`, `MULTIPLE`, `NONE`, or `UNKNOWN` |
| `power_save_enabled` | boolean-as-string | — | Current Android power-save mode |

Not recorded: battery serial or hardware ID, capacity, health, voltage, current, temperature, or
the cause of a battery change. Percentage is an integer platform reading, not a calibrated energy
measurement.

---

## `temporal_context.v1`

A snapshot at study start/reconciliation and when Android reports a time, time-zone, or UTC-offset
change. Exact duplicates are suppressed and rapid changes retain the newest event under a
one-minute bound.

**Configuration:** `{}`. **Access:** none.

**Payload type:** `TEMPORAL_CONTEXT`.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `change_reason` | enum | — | `STUDY_STARTED`, `RECONCILED`, `TIMEZONE_CHANGED`, `TIME_SET`, or `UTC_OFFSET_CHANGED` |
| `timezone_id` | string | IANA/Android zone ID | Current `ZoneId.systemDefault()` setting |
| `utc_offset_seconds` | int | seconds | Zone-rule offset at observation, −64,800 to 64,800 |
| `daylight_saving_time` | boolean-as-string | — | Whether that zone's rules are in DST at observation |

A time zone is a device setting, not proof of physical location or travel. `TIME_SET` proves that
Android announced a wall-clock change; it does not identify who or what changed it.

---

## `gyroscope.v1`

Raw angular velocity in device coordinates. No filtering, orientation estimation, or activity
inference is applied.

**Configuration:**

| Parameter | JSON key | Type | Unit | Range |
| --- | --- | --- | --- | --- |
| Sampling period | `sampling_period_us` | int | microseconds | 5,000–1,000,000 (200 Hz–1 Hz) |
| Maximum report latency | `maximum_report_latency_us` | int | microseconds | 0–60,000,000 |

**Access:** gyroscope hardware. **Payload type:** `GYROSCOPE_SAMPLE`.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `source_elapsed_realtime_nanos` | long | ns since boot | Hardware `SensorEvent.timestamp` |
| `x_radians_per_second` | float | rad/s | Raw angular velocity around device X |
| `y_radians_per_second` | float | rad/s | Raw angular velocity around device Y |
| `z_radians_per_second` | float | rad/s | Raw angular velocity around device Z |
| `accuracy` | int | enum ordinal | Raw `SensorEvent.accuracy` |

The requested period is a hint; use source timestamps to measure achieved rate. Not recorded:
orientation, posture, gesture, or activity labels.

---

## `ambient_light.v1`

Raw ambient illuminance after a monotonic rate gate and change threshold. Because Android light
sensors are commonly on-change sources, the newest threshold-sized change inside the minimum
interval is retained and emitted when the interval opens. Its original observation time and
`source_elapsed_realtime_nanos` are preserved. A later reading equivalent to the last emitted lux
value cancels that pending change. Accuracy is descriptive metadata on emitted lux samples and is
not an independent emission trigger.

**Configuration:**

| Parameter | JSON key | Type | Unit | Range |
| --- | --- | --- | --- | --- |
| Minimum sample period | `sampling_period_us` | int | microseconds | 200,000–10,000,000 |
| Change threshold | `change_threshold_millilux` | int | millilux | 0–100,000,000 |

**Access:** ambient-light hardware. **Payload type:** `AMBIENT_LIGHT_SAMPLE`.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `source_elapsed_realtime_nanos` | long | ns since boot | Hardware `SensorEvent.timestamp` |
| `illuminance_lux` | float | lux | Non-negative raw sensor reading |
| `accuracy` | int | enum ordinal | Raw `SensorEvent.accuracy` |

Not recorded: images, colour, environmental content, or presence. Lux accuracy and calibration vary
by device; the collector does not normalize across hardware.

---

## `proximity.v1`

Raw proximity readings with a monotonic minimum interval. The newest meaningful reading inside the
interval is retained; exact duplicates and same-state changes below the configured threshold are
suppressed.

**Configuration:**

| Parameter | JSON key | Type | Unit | Range |
| --- | --- | --- | --- | --- |
| Minimum event interval | `minimum_event_interval_ms` | int | milliseconds | 100–60,000 |
| Change threshold | `change_threshold_millimeters` | int | millimetres | 0–10,000 |

**Access:** proximity hardware. **Payload type:** `PROXIMITY_SAMPLE`.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `source_elapsed_realtime_nanos` | long | ns since boot | Hardware `SensorEvent.timestamp` |
| `distance_centimeters` | float | centimetres | Non-negative raw Android distance |
| `maximum_range_centimeters` | float | centimetres | This sensor's declared maximum range |
| `near` | boolean-as-string | — | `distance_centimeters < maximum_range_centimeters` |

Many proximity sensors expose only near and maximum range. Values are not assumed precise or
comparable across devices, and neither `near` nor distance proves a person's presence.

---

## `network_state.v1`

Capabilities of the system default network. Connection shape only.

**Configuration:**

| Parameter | JSON key | Type | Meaning |
| --- | --- | --- | --- |
| Include bandwidth estimates | `include_bandwidth_estimates` | boolean | Adds the two `*_kbps` fields |

**Access:** none requested at runtime. The module declares `ACCESS_NETWORK_STATE`, a normal install-time permission with no participant-facing prompt.

**Payload types:**

| Type | When | Fields |
| --- | --- | --- |
| `NETWORK_SNAPSHOT` | Once, when the collector starts | Capability fields **plus** `connected` |
| `NETWORK_CAPABILITIES` | Default network capabilities changed | Capability fields |
| `NETWORK_AVAILABLE` | A default network became available | **Empty** — `"fields": {}` |
| `NETWORK_LOST` | The default network was lost | **Empty** — `"fields": {}` |

`NETWORK_AVAILABLE` and `NETWORK_LOST` carry no payload at all, so they cannot be correlated to a particular network. They mark transitions in time, nothing more.

| Field | Type | Meaning |
| --- | --- | --- |
| `wifi` | boolean-as-string | Has Wi-Fi transport |
| `mobile` | boolean-as-string | Has cellular transport |
| `ethernet` | boolean-as-string | Has ethernet transport |
| `vpn` | boolean-as-string | Has VPN transport |
| `validated` | boolean-as-string | Android verified actual internet connectivity |
| `metered` | boolean-as-string | Negation of `NET_CAPABILITY_NOT_METERED` |
| `roaming` | boolean-as-string | Negation of `NET_CAPABILITY_NOT_ROAMING` |
| `downstream_kbps` | int | Android's downstream estimate. Only when bandwidth estimates are enabled. |
| `upstream_kbps` | int | Android's upstream estimate. Only when bandwidth estimates are enabled. |
| `connected` | boolean-as-string | `NETWORK_SNAPSHOT` only. When `"false"` it is the **only** field present. |

The bandwidth values are Android's own coarse link estimates, not measurements. They do not reflect achieved throughput.

Not recorded: SSID, BSSID, IP or MAC addresses, DNS, link properties, carrier or operator name, signal strength, hostnames, URLs, packets, or payloads.

---

## `network_usage.v1`

Device-total byte and packet counters over a polling window, from Android's `NetworkStatsManager`.

**Configuration:**

| Parameter | JSON key | Type | Unit | Range |
| --- | --- | --- | --- | --- |
| Transports | `transports` | array of `"wifi"` / `"mobile"` | — | non-empty |
| Poll interval | `poll_interval_minutes` | int | minutes | 1–1,440 |

The one-minute floor is there so a pilot can confirm within a minute that the collector produces events. It buys finer windows, not finer data: Android's accounting is coarse and lags, so a minute-long window is still an aggregate whose contents cannot be placed within it.

**Access:** Usage Access (`PACKAGE_USAGE_STATS`), a special access the participant grants in Android Settings and can revoke at any time.

**Payload type:** `NETWORK_USAGE_AGGREGATE`. One event per transport per poll.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `transport` | string | — | `"WIFI"` or `"MOBILE"` (uppercase, unlike the lowercase form in the configuration) |
| `coverage_start_utc_millis` | long | ms since epoch, UTC | Inclusive start of the counted window |
| `coverage_end_utc_millis` | long | ms since epoch, UTC | End of the counted window |
| `rx_bytes` | long | bytes | Device total received in the window |
| `tx_bytes` | long | bytes | Device total transmitted in the window |
| `rx_packets` | long | packets | Device total received |
| `tx_packets` | long | packets | Device total transmitted |

**Interpretation limits.** The query is `querySummaryForDevice` with a null subscriber ID: device totals only. There is no per-app or per-UID attribution, and none can be recovered. Android's accounting is coarse and can lag, so a window's totals describe the window, not the instant traffic occurred within it. The first event arrives one full poll interval after the study starts. Resuming from a pause restarts the window at the moment of resume, so the paused interval is never counted.

Not recorded: per-app attribution, subscriber ID, hostnames, URLs, destinations, content, instantaneous throughput, ethernet or VPN transports.

---

## `usage_events.v1`

Raw system usage events, from Android's `UsageStatsManager`.

**Configuration:**

| Parameter | JSON key | Type | Unit | Range |
| --- | --- | --- | --- | --- |
| Poll interval | `poll_interval_minutes` | int | minutes | 1–1,440 |

The one-minute floor is a piloting setting. Polling more often does not make the platform deliver events sooner or more completely; it only shortens the wait before you can see whether anything is arriving.

**Access:** Usage Access (`PACKAGE_USAGE_STATS`).

**Payload types** — exactly nine are recorded; every other Android usage event type is discarded:

`ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`, `SCREEN_INTERACTIVE`, `SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`, `KEYGUARD_HIDDEN`, `DEVICE_STARTUP`, `DEVICE_SHUTDOWN`

| Field | Type | Unit | Meaning | Presence |
| --- | --- | --- | --- | --- |
| `source_time_utc_millis` | long | ms since epoch, UTC | Android's own event time. Use this, not `observed_time`, which is the poll time. | Always |
| `package_name` | string | — | Package of the app the event concerns | **Omitted entirely** when Android reports it as null or blank |

**Interpretation limits.** These are raw events, not a session stream. Android's retention and delivery are not guaranteed to be complete or timely. This collector does not reconstruct sessions, durations, or foreground time. If you need those, you derive them, and the gaps are yours to handle. `SCREEN_INTERACTIVE` means the screen was on and interactive; it does not mean the participant was looking at it.

Not recorded: activity or class names (package only), window titles, notification content, app usage durations, or any unmapped event type.

---

## `location.v1`

Fused Location fixes via Google Play Services.

**Configuration:**

| Parameter | JSON key | Type | Unit | Range |
| --- | --- | --- | --- | --- |
| Interval | `interval_millis` | long | ms | 1,000–3,600,000 |
| Minimum interval | `minimum_interval_millis` | long | ms | 500 up to the configured interval |
| Maximum batch delay | `maximum_batch_delay_millis` | long | ms | 0–86,400,000 |
| Minimum displacement | `minimum_displacement_millimeters` | int | millimetres | 0–10,000,000 |
| Priority | `priority` | `"BALANCED"` or `"HIGH_ACCURACY"` | — | — |

This collector always requires precise location. There is no coarse-only mode, and `priority` selects a power/accuracy trade-off within fine location rather than reducing the permission it needs. Say so in your consent text.

**Access:** fine location, request-specific Android location-service readiness, and background
location. Before registration the app asks Play services whether the exact configured priority,
intervals, batching, and minimum distance can be satisfied. Continuous collection runs under a
visible foreground service notification.

**Payload type:** `LOCATION_FIX`.

| Field | Type | Unit | Meaning | Presence |
| --- | --- | --- | --- | --- |
| `source_elapsed_realtime_nanos` | long | ns since boot | Monotonic fix time | Always |
| `source_time_utc_millis` | long | ms since epoch, UTC | Provider's wall-clock fix time | Always |
| `latitude_degrees` | double | degrees, WGS84 | | Always |
| `longitude_degrees` | double | degrees, WGS84 | | Always |
| `horizontal_accuracy_meters` | float | metres | 68% confidence radius | Always |
| `mock` | boolean-as-string | — | Android flagged this as a mock location | Always |
| `altitude_meters` | double | metres, WGS84 ellipsoid | | Only when the platform supplies it |
| `vertical_accuracy_meters` | float | metres | | Only when supplied |
| `speed_meters_per_second` | float | m/s | | Only when supplied |
| `speed_accuracy_meters_per_second` | float | m/s | | Only when supplied |
| `bearing_degrees` | float | degrees from true north | | Only when supplied |
| `bearing_accuracy_degrees` | float | degrees | | Only when supplied |

Optional fields are **omitted, never null**. Their absence means the platform reported no value, which is itself information about fix quality.

**Interpretation limits.** A fix is an estimate. Indoor and urban environments degrade accuracy substantially, and the fix rate is a request that Android is free to service more slowly under power management. Gaps are expected. The `mock` flag is worth checking before analysis. This collector uses Play Services only, with no platform `LocationManager` fallback, so a device without Play Services produces no location data at all.

Not recorded: provider name, extras bundle, raw GNSS or satellite data, geocoded addresses, cell or Wi-Fi scan lists.

---

## `keyboard_touch.v1`

Touch dynamics on the study's own keyboard surface. This is the only collector classed `RESTRICTED`, and it needs the most careful disclosure in consent content.

**Configuration:**

| Parameter | JSON key | Type | Unit | Range |
| --- | --- | --- | --- | --- |
| Trajectory sampling rate | `trajectory_sampling_hz` | int | Hz | 1–120 |

**Access:** the research keyboard must be both enabled in Android Settings and selected as the active input method. These are two separate, explicit participant actions, and neither is an Android runtime permission.

**Payload type:** `KEYBOARD_TOUCH`.

| Field | Type | Unit | Meaning |
| --- | --- | --- | --- |
| `action` | string | — | `"DOWN"`, `"MOVE"`, `"UP"`, or `"CANCEL"` |
| `event_uptime_millis` | long | ms, **uptime base** | Touch event time. Excludes deep sleep — a different base from `observed_time`. |
| `down_uptime_millis` | long | ms, uptime base | When the gesture began. `event_uptime_millis − down_uptime_millis` is dwell time. |
| `pointer_id` | int | — | Pointer identity within the gesture |
| `relative_x` | float | fraction 0.0–1.0 | X position **within the touched key's bounds**, clamped |
| `relative_y` | float | fraction 0.0–1.0 | Y position within the touched key's bounds, clamped |
| `pressure` | float | device-relative | Raw `MotionEvent` pressure. **Uncalibrated and not comparable across devices.** Not a force in newtons. |
| `size` | float | device-relative | Raw contact size, same caveat |
| `orientation_radians` | float | radians | Touch major-axis orientation |
| `tool_type` | int | enum ordinal | Raw `MotionEvent` tool type, unmapped |
| `key_category` | string | — | One of `"LETTER"`, `"SPACE"`, `"BACKSPACE"`, `"ENTER"` — **the category only, never which key** |
| `geometry_version` | string | — | Constant `"qwerty-v1"`, identifying the fixed layout that makes relative coordinates interpretable |

**What is structurally impossible here.** Key identity and text never reach the event path. The keyboard's typing path and its observation path are separate, and only the key's *category* is passed to the observer. An event can therefore tell you a letter key was pressed, never which letter. There is no way to reconstruct typed text from this data.

**Capture is disabled entirely** when the input field is a password field of any variation, when the editor sets `IME_FLAG_NO_PERSONALIZED_LEARNING`, or when no field information is available at all. The keyboard keeps working; only observation stops. This is fail-closed: an unknown field is treated as sensitive.

Only `MOVE` events are rate-limited, at the configured sampling rate. `DOWN`, `UP`, and `CANCEL` are never dropped. Touches that land outside a key, and secondary pointers in a multi-touch gesture, produce no events.

**This is still identifying.** No text does not mean no risk. Typing rhythm, dwell times, and within-key touch position are behaviourally distinctive. They can support inference about the person, and in aggregate about what kind of input they were producing. It must be disclosed explicitly in consent content, and participants should be told they can switch back to their normal keyboard before sensitive input.

Not recorded: characters, committed text, surrounding text, clipboard, suggestions or autocorrect data, absolute screen coordinates, keyboard pixel dimensions, the target app's package, `EditorInfo` contents, or calibrated force.

---

## Volume and quota

A study declares a local storage quota between 8 MiB and 8 GiB (8,388,608 to 8,589,934,592 bytes). Events are written into 4 MiB segments, at most 2,048 of them resident at once. That is what lets a study reach the top of that range while still reclaiming space 4 MiB at a time. A single encoded event may not exceed 64 KiB. A segment is appended to and never rewritten.

The ceiling is high because high-rate collectors fill space quickly: an accelerometer at 100 Hz produces tens of megabytes per hour. But a quota is space claimed on someone's personal phone. Ask for what the study needs rather than for the maximum.

When the quota is exhausted, the write fails and the study **fail-closes to `PAUSED`** with a storage-failure reason. It does not drop events silently and it does not overwrite the oldest data. Size your quota against your collectors' event rate before deployment, and check the accelerometer in particular: at 200 Hz it will exhaust a small quota quickly.

In a study that uploads, a confirmed delivery lets the device reclaim space. Above 80% of the quota, whole leading segments are released, down to 60%. A segment is released only when every event in it was confirmed by the endpoint and it is not the segment still being written. Nothing undelivered is ever released. An endpoint that stops answering therefore brings a study back to the fail-closed case above, rather than to a device that discards data. `StudyMetadata.retainedFromSequence` records the lowest sequence still present, sequence numbers are never reissued, and the participant's dashboard states how many earlier events were delivered and removed.

Study metadata is held separately from the events. It is capped at 1 MiB and kept outside the event budget by a 2 MiB reserve. The record of what a study is, and how far it has been delivered, therefore cannot be crowded out by the events it describes. Its container header is `PTCMET01`.

Normal study opening does not decrypt its event log. Framing and sequence contiguity are checked from plaintext frame headers, and each collector's most recent event is persisted in metadata rather than recovered by scanning. Start-up cost is therefore linear in frames rather than in bytes decrypted. The sole exception is a fail-closed append journal whose proposed event is the durable tail: recovery authenticates exactly that tail, retains it inside PAUSED metadata, and keeps the journal until the app-owned winning safety reason is resolved. If the event is absent or truncated, recovery instead retains the prior event boundary PAUSED. All other event payloads are authenticated when read, so damage outside the recovery tail surfaces at export or upload rather than at launch.

Each collector descriptor declares `maximumEncodedEventBytes`. The runtime encodes every admitted
event with the worst-case sequence width and rejects it before append when it exceeds that
collector-specific ceiling. The store's 64 KiB global cap remains a second boundary. CI also
checks collector source, compiled constants, and module dependencies as documented in the
[Collector capability policy](../assurance/README.md).
