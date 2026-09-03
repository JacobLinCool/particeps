# Researcher guide

This guide covers the current, destructive Protocol v1 study model: signed collector profiles,
declarative automations, one-shot interventions, stateful resources, condition epochs, encrypted
commit-boundary export/upload, and fail-closed analysis. It has no instructions for the retired
continuous-runtime configuration or phase scheduler because no reader or migration for those
formats exists.

Normative sources:

- [Protocol v1](../protocol/v1/README.md) — exact signed/encrypted wire formats and verification.
- [Event-source registry](../protocol/v1/event-source-registry.json) — sole source/event/field,
  operator, clock, completeness, privacy, rate, access, and collector-profile contract.
- [Generated registry reference](generated/event-source-registry.md) — readable projection of the
  same machine source.
- [Data dictionary](data-dictionary.md) — interpretation and publication rules.
- [Threat model](threat-model.md) — what the implementation proves and what it does not.

## 1. Study model

A study is one canonical JSON object signed with Ed25519. The participant can accept, decline,
pause, resume, complete, withdraw, export, and later delete it; they cannot edit it. Particeps has no
remote command path and does not download executable study code.

This complete configuration body is valid under the current contract. Canonicalize it and wrap its
exact bytes in the binary Ed25519 signed envelope before distribution:

```json
{
  "schema_version": 1,
  "experiment_id": "app-lifecycle-study",
  "configuration_id": "app-lifecycle-study-2026",
  "assigned_participant_id": null,
  "issued_at": "2026-01-01T00:00:00Z",
  "expires_at": "2035-01-01T00:00:00Z",
  "platform": "android",
  "minimum_client_version": "1",
  "title": "App lifecycle study",
  "researcher": {
    "name": "Example Research Lab",
    "contact": "research@example.invalid"
  },
  "purpose": "Measure when participants open and close Particeps during the study.",
  "duration_hours": 24,
  "consent": {
    "document_version": "consent-1",
    "summary": "Particeps records when this app is opened and closed. You can pause, complete, withdraw, or export at any time."
  },
  "collectors": [
    {
      "id": "app_lifecycle.v1",
      "required": true,
      "profiles": [
        {"id": "continuous", "config": {}}
      ]
    }
  ],
  "surveys": [],
  "interventions": [],
  "automations": [
    {
      "type": "resource_binding",
      "id": "bind-app-lifecycle",
      "resource": {"kind": "collector", "id": "app_lifecycle.v1"},
      "cases": [
        {
          "condition": {"type": "study_session_active"},
          "profile_id": "continuous"
        }
      ],
      "default_profile_id": "continuous"
    }
  ],
  "traffic_shaping": {},
  "storage": {"maximum_local_bytes": 16777216},
  "signer": {
    "key_id": "demo-signer-2026",
    "public_key": "sRSaTpZmTSBL7eN6nS_HBsNmLM8n1hdRmIt1vtLZsC0"
  },
  "export": {
    "researcher_key_id": "demo-hpke-2026",
    "hpke_public_key": "GnJDfDh8XH1eARTHmTLNlog9curtphTpEn7L36sY0QA"
  },
  "upload": {}
}
```

`collectors` declares stateful resources and named settings. `interventions` declares reusable
notification/survey actions but contains no trigger. The required `automations` array binds
resources and schedules one-shot actions. `traffic_shaping` is either exactly `{}` or a complete
target-package/profile declaration.

The Web authoring tool is the supported editor. It is registry-driven, canonicalizes every value,
validates dependency/liveness/state bounds before signing, previews the participant-facing
projection, and simulates researcher-authored synthetic traces. It does not provide remote control.

## 2. Identity, keys, and distribution

Choose stable lowercase IDs matching `[a-z0-9][a-z0-9-]{2,63}`. `experiment_id` groups related
configurations; each platform/version/assignment variant gets a distinct `configuration_id` and
signature. `assigned_participant_id` is optional and must be an opaque roster code rather than a
name or contact detail.

Maintain two independent key pairs:

- Ed25519 signs the exact canonical configuration. Pin its public-key fingerprint through a channel
  independent of the artifact host when publisher identity matters.
- X25519 receives the HPKE-wrapped content key for exports. Keep the private key offline from the
  upload receiver whenever possible.

The receiver stores immutable ciphertext and canonical receipts; it never needs either private
key. A join link points to one immutable signed artifact and binds its SHA-256 plus signer
fingerprint. It is not an update channel. To change a study, sign and distribute a new
configuration; an active participant does not silently switch.

## 3. Collector resources and profiles

Each `collectors[]` element has `id`, `required`, and 1–64 sorted unique named profiles. A profile
contains the exact generated configuration for that source. The same source can therefore use
different signed settings without becoming a different event identity.

Every declared resource has exactly one `resource_binding` automation. Its cases are evaluated in
signed order; the first true condition selects its `profile_id`. `null` means inactive. If no case
matches, `default_profile_id` applies.

For ordinary continuous collection, use the Web macro that emits:

```json
{
  "type": "resource_binding",
  "id": "bind-battery",
  "resource": {"kind": "collector", "id": "battery_state.v1"},
  "cases": [
    {"condition": {"type": "study_session_active"}, "profile_id": "continuous"}
  ],
  "default_profile_id": "continuous"
}
```

This is how the previous “start every collector” behaviour is expressed now; the runtime does not
hard-code it. A required collector must select a non-null profile in every case and in the default;
an optional collector that is permitted to become inactive may instead use `null`.

A trigger source required by another automation must itself be `required` and remain on a
non-inactive profile throughout the active study session. Validation rejects a resource that can
turn off its own only source of reactivation, multiple owners, dependency cycles, and unreachable
resources.

The registry defines exact access and data semantics. A `required` resource blocks activation or
safely pauses the study if its access/health cannot be verified. An optional resource can remain
inactive only when no required automation depends on it.

## 4. One-shot actions

An intervention declares only the action:

```json
{
  "id": "reflection-prompt",
  "required": false,
  "action": {
    "type": "survey",
    "notification_title": "Quick check-in",
    "notification_message": "Please answer when convenient.",
    "survey_id": "reflection"
  }
}
```

An `occurrence` automation supplies the trigger, optional guard, intervention reference,
availability, optional cooldown, and maximum activations. A deterministic invocation ID binds the
configuration digest, automation, and causal event/deadline. The durable outbox retries external
delivery with the same idempotency key; do not describe notification display as exactly-once or as
proof the participant saw it.

Survey drafts are not research data. Opening, final submission, dismissal/expiry, and the generic
action result are ordered runtime events. Submission validates the signed question schema and
commits one canonical answer object atomically.

## 5. Closed-world automation language

### Triggers

Protocol v1 supports only:

- `event_match` with an explicit evaluation clock;
- a bounded ordered `sequence`;
- bounded-window `count` or exact-integer `sum` threshold;
- `condition_rising_edge`;
- `one_time`, `interval`, `daily_local`, or `random_window` schedule.

### State conditions

Protocol v1 supports only:

- active study-session state;
- event latch with explicit set/reset matchers;
- keyed presence with explicit enter/exit matchers and registry-approved key field;
- `held_for` on active-running or calendar time;
- elapsed active-running/calendar time;
- bounded-window threshold;
- `all`, `any`, and `not`.

There is no arbitrary code, regular expression, SQL, generic JSON comparison, push/remote trigger,
or downloaded operator. A field/operator pair is legal only when the registry declares it.

### Deterministic semantics and bounds

- Event identity is `(source_id, schema_version, event_type)`.
- Matcher arrays are OR. When the same event both exits/resets and enters/sets state, exit/reset wins.
- A missing field makes every operator false, including `ne`.
- `in.values` contains 1–64 sorted unique typed-canonical strings.
- Floating comparison accepts finite binary64 only. v1 window sum accepts registry-approved integer
  fields and uses exact integer arithmetic.
- Each rate contract bounds any half-open `P`-second interval to `E` events. A window retains at most
  `E × ceil(W/P)` entries; sequence state also accounts for step count. Compilation rejects a result
  above 4,096.
- At most 128 automations, 16 cases per binding, depth 8/64 condition nodes, 16 sequence steps,
  seven-day windows, and 512 lifetime one-shot activations.

The pure reducer reads no clock, Android state, storage, UUID generator, or randomness. Clock
observations, random-window selections, and epoch UUIDs are coordinator inputs committed before
they are consumed. Kotlin, TypeScript, and Python replay recorded choices; they do not redraw them.

## 6. Time and random windows

Use `ACTIVE_RUNNING_TIME` when a pause should stop elapsed duration and `CALENDAR_TIME` when it
should continue. Same-boot deadlines use elapsed realtime. Calendar deadlines use UTC plus recorded
local-time semantics. WorkManager is only a wakeup adapter carrying timer ID and generation; it is
not a scheduler truth store.

The signed `duration_hours` is enforced by a separate runtime-owned durable deadline, not by an
automation and not by the arrival of another collector event. Its same-boot target is the admission
gate's exclusive upper bound, so observations at or after the deadline are rejected even if Android
runs the wakeup late. The verified wake retires the deadline and automatically completes the study.

`random_window` selects an unbiased deadline locally on the participant device. The selected
deadline and constraints become durable before any Android wakeup is scheduled. The Web simulator
shows the legal bounds but does not draw participant-specific times.

A running wall-clock discontinuity is a discard barrier: it closes admission, removes every
retrospective cursor without flushing backlog, resets latch/presence/window/sequence state, restarts
active retrospective resource generations, and opens a new epoch only after the replacement vector
verifies. If it crosses the signed duration, the barrier completes the study instead. A paused
reboot similarly records a quality gap and discards retrospective cursors; Resume requires a
trustworthy UTC re-anchor, while Complete and Withdraw remain available without admission.
Particeps does not backfill or guess.

## 7. App-use conditions

`usage_events.v1` is Android `UsageStatsManager.queryEvents()` history, not a real-time foreground
callback. Android does not promise complete or timely delivery. The collector uses
`poll_interval_seconds`; when any automation references it, validation requires exactly 15 seconds.
That is still a worst-case polling delay, not an accuracy guarantee.

Activity lifecycle records include package name, source wall time, and a study-scoped opaque HMAC
component token. The class name is never persisted. A generic sustained-use condition is expressed
as keyed presence plus `held_for`, for example:

```json
{
  "type": "held_for",
  "duration_seconds": 180,
  "clock": "ACTIVE_RUNNING_TIME",
  "condition": {
    "type": "keyed_presence",
    "key_field": "activity_component_token",
    "enter_when": [{
      "event": {"source_id": "usage_events.v1", "schema_version": 1,
                "event_type": "ACTIVITY_RESUMED"},
      "predicates": [{"field": "package_name", "operator": "eq",
                      "value": "com.example.social"}]
    }],
    "exit_when": [{
      "event": {"source_id": "usage_events.v1", "schema_version": 1,
                "event_type": "ACTIVITY_PAUSED"},
      "predicates": [{"field": "package_name", "operator": "eq",
                      "value": "com.example.social"}]
    }]
  }
}
```

The last observed exit, pause, reboot, or quality gap resets presence. If a delayed batch already
contains both entry and exit, reduction can reconstruct the ended interval for audit but does not
apply a resource state that is no longer true. Describe this in preregistration as **best observed**,
not as precise foreground duration.

## 8. Traffic-shaping resource

Enabled configuration is exact and signed:

```json
"traffic_shaping": {
  "target_packages": ["com.example.social"],
  "profiles": [
    {"id": "baseline", "uplink_kbps": null, "downlink_kbps": null},
    {"id": "slower", "uplink_kbps": 256, "downlink_kbps": 1024}
  ]
}
```

Packages are 1–64 sorted unique Android application IDs and cannot include Particeps. Profiles are
1–64 sorted unique IDs. A directional cap is `null` for unlimited or an integer in
1–1,000,000 kbps, where 1 kbps is 1,000 bit/s including the TUN Layer-3 IP header.

Traffic shaping is a required `actuator:traffic-shaping.v1` resource with one binding automation.
The binding chooses the current named profile from conditions; there is no separate phase list or
phase scheduler. Start baseline, elapsed-time changes, and observed app-use changes all use the same
generic reducer/barrier.

The Android implementation is a local allowlisted `VpnService`, not a remote VPN gateway. Only the
signed packages enter the TUN; other apps use the ordinary network. Selected apps share one
aggregate uplink bucket and one aggregate downlink bucket. This controls throughput only and makes
no latency, jitter, or packet-loss guarantee.

Particeps verifies a fresh generation-scoped owned VPN network, open TUN, native forwarder/limiter,
package/UID snapshot, protected outbound sockets, and exact applied-profile digest. Any failure
closes event admission and safely pauses. Another VPN replaces it because Android permits one VPN
per user/profile. Particeps reports only “ours/not ours”; it does not guess another VPN’s identity.

The runtime exports aggregate bytes/packets/throttled-duration snapshots every 60 seconds and at
epoch boundaries. It never records packet payload, destination, DNS name, per-package flow, or an
installed-app inventory. `network_usage.v1` remains a separate device-wide contextual total.

## 9. Activation, epochs, and safety

Start/Resume first records the lifecycle request, evaluates desired resources, applies and verifies
all required profiles, commits the complete applied vector and first condition epoch, then enters
`RUNNING` and opens collector data admission.

Any collector or actuator change uses one global barrier:

1. durably stage a bounded causal batch when one initiated the change;
2. close admission and suspend resources in resource-key order;
3. capture one boundary and flush retrospective sources with durable cursors;
4. keep manifests in causal/pre-drain/flush producer order, but sequence reducer events as
   pre-drain/flush then causal; commit them with audit events, old epoch deactivation, and desired
   state;
5. apply/verify side effects after that commit;
6. commit receipts, new epoch, and applied-vector digest;
7. resume resources and reopen admission.

Participant pause, complete, withdraw, or safety failure always overrides automation. A study
recovered from process death/reboot while `ACTIVATING`, `RUNNING`, or `PAUSING` becomes `PAUSED`; it
does not auto-resume or backfill the unverified interval.

A study that was already `PAUSED` across reboot remains closed until a trustworthy new-boot clock
anchor is committed. Researchers should treat the explicit quality gap as the boundary and must not
infer behavior during the discarded interval. Participants can still Complete or Withdraw when a
Resume cannot be safely anchored.

## 10. Participant disclosure and blinding

The Android participant app intentionally keeps the existing five setup steps and compact running
surface. Particeps-generated UI shows high-level data categories, ordinary access, participant
controls, and generic safety pause. It does not derive or display signed conditions, treatment
state, resource settings, causal history, or internal diagnostics.

A shaping study adds one fixed high-level paragraph inline in the existing Access step. Done/Resume
then invokes Android’s required local-network permission (Android 17+) and system VPN consent. There
is no new VPN card, screen, dashboard, or second ongoing notification. Android’s VPN icon/consent is
system UI and cannot be removed.

Researcher-authored study title, purpose, researcher name/contact, consent, notification, and survey
strings are shown verbatim. These signed free-text fields are the explicit exception to the
generated-UI blinding boundary. Before signing, the Web tool requires the researcher to acknowledge
that every one of them follows the study’s blinding and ethics plan. Runtime cannot semantically
guarantee that arbitrary free text omits treatment information.

Use the participant preview and accessibility sentinel before signing. It intentionally receives a
whitelist projection rather than the full configuration.

## 11. Simulation and testing a study

The Web synthetic-trace simulator accepts researcher-authored collector/system/lifecycle/timer
facts and shows logical desired resources and action requests. It does not execute Android effects,
claim data completeness, or select random-window instants. Lifecycle state is supplied as a typed
simulator input; the emitted `STUDY_STARTED`, `STUDY_RESUMED`, and `STUDY_RUNNING` audit records are
not selectable event triggers. Use `study_session_active` for active-session bindings.

Before deployment:

1. validate and canonicalize the study in Web and CLI;
2. run the shared Kotlin/TypeScript/Python conformance corpus;
3. exercise expected and hostile synthetic traces, including simultaneous matches and reset/exit;
4. pilot actual access/setup/pause/resume on supported API levels;
5. for shaping, test selected/control apps, TCP/UDP/DNS/IPv4/IPv6, competing VPN, permission revoke,
   package replace/remove, process kill, reboot, and network handover;
6. decrypt a pilot export and require analysis replay/materialization to complete without gaps or
   digest divergence.

Do not weaken a required source to optional merely to pass a pilot; either redesign the dependency
or document the platform limitation.

## 12. Export, receiver, and analysis

Exports are HPKE/AES-GCM encrypted to the signed researcher key and contain complete authenticated
`EngineCommit` frames. Automatic upload sends immutable ciphertext over HTTPS. Clear routing
metadata identifies bundle/configuration/key and complete commit range, not participant ID.

The receiver validates framing, bounds, clear metadata, content digest, exact replay, and receipt
shape without decrypting. Store the receiver’s ciphertext objects and canonical receipts; run
offline decryption only in the controlled analysis environment.

`particeps-analysis` verifies every bundle before publishing anything, reassembles one complete
commit chain, independently replays the signed configuration, validates observation coverage,
timers/actions/resources/epochs, and compares the canonical checkpoint digest after every input.
It writes Parquet partitioned by event identity and carries condition-epoch provenance.

Any partial batch, conflict, missing cause, orphan epoch, resource/profile mismatch, unattributable
coverage, or digest divergence rejects the dataset. Do not average across it, allocate it
proportionally, or publish a “best effort” table.

## 13. Privacy and distribution gates

- `QUERY_ALL_PACKAGES` exists only to verify signed target packages and shared-UID peers. The app
  does not enumerate for research, save, or upload the installed-app list. Google Play distribution
  requires a policy declaration.
- `VpnService` use likewise requires the Google Play VpnService declaration. This repository’s
  implementation and release verification do not themselves submit a Play listing.
- Android 17 local-network access is used only to forward local connections initiated by selected
  apps; Particeps does not discover local devices. Refusal/revocation safely pauses a shaping study.
- Release verification builds the Go binding from pinned source, verifies proxy/sumdb hashes, four
  ABIs, 16 KiB alignment, manifest permissions/service flags, SBOM/licenses, registry digest, and
  absence of tracked native binaries or sensitive packet logging.

Keep the consent and ethics materials aligned with the exact signed study, registry privacy fields,
[participant guide](participant-guide.md), and [threat model](threat-model.md). If the intended claim
is stronger than the platform completeness or verification evidence described there, change the
claim or the design before recruitment.
