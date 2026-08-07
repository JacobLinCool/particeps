# Changelog

What changed between releases, and what each change asks of someone who already installed one.

This project is pre-1.0. Every release so far is a release candidate, and each one below changed
something that a device treats as identity — the application ID, the file formats, or the signing
certificate. None of them can update an earlier install in place. That is stated once here rather
than in each document that touches it.

## Unreleased

- The application ID moved from `cool.linc.particeps` to `cool.jacoblin.particeps`, and the release
  signing key was rotated so that the certificate names Particeps rather than the pre-rename
  product. Either change alone stops a device accepting the build as an update; both apply.
- The status line reports when a pause started and how long it has lasted.
- One low-importance notification a day states whether the study is still collecting, or is paused
  and since when. It names the application, never the study, so it discloses nothing to someone
  reading a lock screen. Starting or stopping collection retracts a standing one.

**Coming from `v1.0.0-rc.4`:** uninstall it. Its data cannot be migrated, and its exports are in the
current format, so export anything worth keeping before you remove it and current tooling will read
it. See [the participant guide](docs/participant-guide.md) for the participant-facing version.

## v1.0.0-rc.4 — 2026-08-06

The project was renamed from Android Data Collector to Particeps. Application ID
`cool.linc.particeps`.

Protocol v1 keeps `schema_version: 1` and gains no second dialect; every identity string was
replaced at once:

| | Was | Now |
| --- | --- | --- |
| Signed configuration | `.adccfg`, `ADCCFG01` | `.partcfg`, `PTCCFG01` |
| Encrypted export | `.adcexp`, `ADCEXP01` | `.partexp`, `PTCEXP01` |
| Join URI | `adc://join/v1` | `particeps://join/v1` |
| Bundle format | `research-bundle-v1` | `particeps-research-bundle-v1` |
| Upload media type | `application/vnd.adc.research-bundle` | `application/vnd.particeps.research-bundle` |
| Upload headers | `X-ADC-*` | `X-Particeps-*` |
| Offline analysis | `adc-analysis` | `particeps-analysis` |

The retired spellings are rejected inputs rather than an older dialect. Every implementation fails
closed on them, and the shared conformance corpus carries a vector for each.

**Coming from `v1.0.0-rc.3` or earlier:** uninstall it first — it is a different application ID and
runs alongside. Its exports are `.adcexp` files that current tooling refuses, so anything worth
keeping has to be exported and analysed with the pre-rename tooling before you remove it.

## v1.0.0-rc.3 — 2026-08-05

Application ID `cool.linc.androiddatacollector`. The R2 ciphertext receiver, the offline
verification and Parquet pipeline, immutable signed join links, and the battery, temporal-context,
gyroscope, ambient-light and proximity collectors.

## v1.0.0-rc.2 — 2026-08-03

Application ID `cool.linc.androiddatacollector`.

## v1.0.0-rc.1 — 2026-08-02

First release candidate. Application ID `cool.linc.androiddatacollector`.
