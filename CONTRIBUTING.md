# Contributing

Thanks for considering a contribution.

The main contribution path is **adding a collector** — a new data source a study can enable. Start with [docs/system-design.md](docs/system-design.md) for the architecture, then [docs/data-collector-implementation-guide.md](docs/data-collector-implementation-guide.md), which covers the contract and every registration step with a worked example.

For anything larger than a bug fix, open an issue first. A new data source is a design decision about what studies can ask of participants, and it is worth agreeing on the shape before you write the code.

## Adding a collector

A collector is three pieces:

- a `CollectorConfiguration` — typed parameters that appear in the signed study configuration, with validated ranges
- a `CollectorPlugin` — a fixed descriptor, the access requirements it needs, and a factory
- a `Collector` — the runtime instance, with start, pause, resume, and stop

### Design constraints

Collectors observe a source and emit events. They do not write files, change study state, start activities, schedule interventions, render surveys, export, or request permissions. This is not a rule imposed on collector authors so much as a consequence of the module graph. A `collector:*` module depends only on `core:collector-api` and `core:study-definition`, so storage, the runtime, and the protocol layer are not on its classpath.

That boundary is what keeps a new data source cheap to add and cheap to review. It also means the answer to "how do I persist this myself?" is that you do not. Everything goes through the `EventSink` in your `CollectorContext`. That is what makes sequence numbers contiguous and monotone, quota accounting correct, and a bundle able to declare the exact window it carries.

The boundary is checked, not merely reviewed. `tools/collector_assurance.py` reads `assurance/collector-policy.json` and fails CI on a forbidden import, a forbidden Gradle dependency, or a forbidden symbol in a compiled class. What it does not read is the manifest, so a collector module can still declare a permission or a component that nothing stops — that gap is tracked in issue #11.

### What review will look at

- **An honest statement of what the data cannot establish.** Every collector needs one, in the same voice as the table in [docs/researcher-guide.md](docs/researcher-guide.md). Sensor samples are not an activity label; a location fix is not ground truth. This is what keeps researchers from overclaiming, and writing it usually clarifies the collector's design too.
- **A field-level entry in [docs/data-dictionary.md](docs/data-dictionary.md)** — name, type, unit, semantics, and whether the field is always present. Researchers paste that document into ethics submissions.
- **Accurate access requirements.** A missing optional permission should block only your collector, never the study.
- **Visible failure.** A collector that cannot observe reports `BLOCKED_ACCESS` or `FAILED`. It never synthesises a plausible-looking value to cover a gap — a silent placeholder is worse than a documented hole in the data.
- **Pause behaviour.** Events carry their original observation time and are admitted against an epoch token. Do not buffer across a pause and flush afterwards.
- **Event rate against the quota.** Say what your collector does to a study's storage budget at its default configuration.
- **Privacy surface.** If the collector can observe something outside its own surface, or something a participant would not expect from its name, say so in the issue before you build it. This is the part most worth discussing early.

## Changes to consent, collection scope, or data handling

Some changes alter what a study can ask of a participant, or what the app tells them is happening — new access requirements, anything touching the consent flow, anything that changes where collected data can go, and anything that widens what an existing collector observes.

These are not off-limits, but they need discussion in an issue before implementation. Deployed studies have consent documents describing the app's behaviour, and participants agreed to a specific version of it. Changing that behaviour is a coordination problem with real research teams, not only a code review.

## Development

Requirements: JDK 17, Android SDK platform and build tools for API 37.

```bash
./gradlew test testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. A clean checkout has no signing material, so `assembleRelease` produces an unsigned release APK.

With an emulator or device attached:

```bash
./gradlew connectedDebugAndroidTest
```

The app suite separates the Android signed-configuration regression
([`AndroidConfigurationImportTest`](app/src/androidTest/kotlin/cool/jacoblin/particeps/AndroidConfigurationImportTest.kt)),
the full participant UI flow ([`CoreFlowTest`](app/src/androidTest/kotlin/cool/jacoblin/particeps/CoreFlowTest.kt)),
and the five-collector Android integration
([`P2CollectorEmulatorTest`](app/src/androidTest/kotlin/cool/jacoblin/particeps/P2CollectorEmulatorTest.kt)).
The last test skips when gyro, light, or proximity hardware is absent. Its optional exact-value mode
expects a sensor-capable emulator that the host has already configured; the test does not fake
Android's sensor APIs:

```bash
adb -s emulator-5554 emu power ac on
adb -s emulator-5554 emu power status charging
adb -s emulator-5554 emu power capacity 73
adb -s emulator-5554 emu sensor set gyroscope 1.25:-2.5:0.5
adb -s emulator-5554 emu sensor set light 123
adb -s emulator-5554 emu sensor set proximity 1
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cool.jacoblin.particeps.P2CollectorEmulatorTest \
  -Pandroid.testInstrumentationRunnerArguments.p2SyntheticInputs=true
```

CI runs unit tests, Android lint, debug and release builds, then the complete connected suite on an
API 34 Google APIs emulator on every pull request. Please check the host-side and attached-device
commands above locally first. Note that `allWarningsAsErrors` is on, so an unhandled branch in an
exhaustive `when` is a build failure rather than a warning.

### Tests

New behaviour needs tests. For a collector, at minimum: configuration parsing rejects invalid parameters, the collector honours pause and stop, and missing access produces `BLOCKED_ACCESS` rather than silence or fabricated events.

Changes touching the protocol, storage, export, or the state machine need tests for the failure path as well as the success path. Most of the guarantees in this project are about what happens when something goes wrong.

## Documentation

Documentation is part of the change, not a follow-up. If you alter behaviour a participant or researcher can observe, update the relevant guide in the same pull request.

Two conventions worth knowing:

- Describe what the current version does, in present tense. Avoid writing today's behaviour as a permanent commitment — the platform is meant to grow, and documentation written as a promise has to be broken to ship anything.
- Be precise about what data means and does not mean. No marketing adjectives.

## Pull requests

Keep them focused — one collector, or one fix. Explain what changed and why, and say explicitly if the change affects what data can be collected or what a participant sees.

A change is finished when all five of these hold:

1. the targeted tests and the full build checks pass;
2. a review has checked the failure paths, not only the success path;
3. nothing was added that a simpler version would not need — no duplicate implementation, no legacy path, no unused code, no avoidable dependency;
4. the code you touched is clearer than you found it; and
5. a reader who has never seen the change can find its specification, its source, its tests, and its operational documentation starting from the root documentation.

The fifth is the one people skip. It is also the one that decides whether anyone can maintain this after you.

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).

## Security issues

Do not open a public issue. See [SECURITY.md](SECURITY.md).
