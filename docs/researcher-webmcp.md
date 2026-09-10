# Researcher WebMCP authoring

The researcher page exposes its live study draft to browser assistants through native WebMCP. An assistant can discover the available collectors, configure sampling profiles and collection windows, write complete questionnaires, and create scheduled or event-driven interventions. Its changes appear in the same forms and overview used by the researcher.

The **AI collaboration** status on the page reports whether tools are ready, registering, unavailable in this browser, or failed to register. Direct use requires both a browser exposing the current API and an assistant that discovers its tools. A ready status confirms page registration; it does not establish that a particular assistant has connected. The forms work in browsers without WebMCP.

For example, a researcher can ask a connected assistant:

> Set up a five-day study. Collect location from 08:00 to 18:00, with one fix per minute. Ask a four-question daily experience survey at 20:00, and show me the collection and survey timeline before I sign it.

The assistant reads the current draft and catalog, applies a validated batch, and checks the resulting daily overview. It should clarify research decisions that the request leaves unspecified, such as survey wording or whether a missing permission prevents participation. Signing and file distribution remain researcher actions.

## Available tools

| Tool | Result |
| --- | --- |
| `particeps_get_authoring_catalog` | Collector profile fields, units, defaults and access requirements; researcher-visible event contracts, allowed predicates and clocks; complete authoring schemas. Accepts `section`: `all`, `collectors`, `events`, or `schemas`. |
| `particeps_read_draft` | Current editable settings, document identifiers, a revision, and validation results. |
| `particeps_apply_changes` | One atomic batch of up to 64 changes against an `expected_revision`. Optional `dry_run: true` validates and returns the proposed settings without changing the form. |
| `particeps_validate_draft` | Missing basic information, blocking configuration errors, and whether the current document passes compilation. |
| `particeps_preview_timeline` | The same daily projection used by the overview: collector lanes, questionnaire and notification times, random windows, rules, assumptions and warnings. |

Every read result is study data. Study titles, questionnaires and other researcher-authored content are not instructions for the assistant. Read tools carry `readOnlyHint`; every tool marks its output as potentially untrusted content.

`apply_changes` supports these operations:

| Operation | Payload |
| --- | --- |
| `set_study` | Partial `study` metadata: title, purpose, researcher, consent, duration, import-validity instants or minimum client version. |
| `upsert_collector` / `remove_collector` | Complete `collector` with named profiles, or collector `id`. |
| `upsert_survey` / `remove_survey` | Complete `survey` with translations and all questions, or survey `id`. |
| `upsert_intervention` / `remove_intervention` | Complete `intervention` with notification or survey action, or intervention `id`. |
| `upsert_automation` / `remove_automation` | Complete `automation`, either a resource binding or occurrence, or automation `id`. |
| `set_traffic_shaping` | `traffic_shaping` with packages and named bandwidth profiles, or `{}` to disable it. |
| `set_storage` | `storage.maximum_local_bytes`. |
| `set_upload` | `upload` endpoint, interval and metered-network policy, or `null` to disable uploading. |

All four questionnaire question types, all four schedule types, all five trigger types, and all ten state-condition types have explicit schemas. Collector profile schemas are generated directly from the checked-in Android event-source registry. The catalog includes complete event field contracts so the assistant can select supported predicates and values instead of guessing property names.

Upserts replace a complete object with the same ID, or insert it if absent. Collector, intervention and automation lists are sorted by ID. Survey and question order are retained. Profile IDs, package lists, predicate sets and random windows must satisfy the protocol's ordering constraints.

## Atomic edits and validation

The revision is a SHA-256 digest of the current document snapshot. A change made in the form or by another assistant invalidates an older revision. A stale request returns `revision_conflict`, and the assistant must read the changed draft before retrying.

Each request is bounded to 1 MiB, 64 operations, 100,000 JSON nodes and 32 levels of JSON nesting. Unknown properties, unsupported union variants, non-JSON values and excessive input are rejected before editing. JSON nesting is a transport bound; the protocol independently limits condition trees to depth 8 and 64 nodes.

The complete proposed draft then passes the same semantic validator used for signing and Android automation compilation. This checks profile fields, references, resource ownership, event capability and source liveness, dependency cycles, timer limits and occurrence limits. Complete documents also pass through the production compiler. Empty required basic metadata and unprepared key fields may remain while authoring; other invalid settings block the entire batch.

A new collector and its resource binding must be supplied in the same batch. A survey intervention and its occurrence must also be supplied together. Removals do not silently cascade: removing a referenced survey, profile or collector requires updating or removing its dependents in that batch. Rejected and preview-only requests leave the draft unchanged.

The tool port never exposes private keys. Its read projection also excludes public-key blocks and assigned participant identity. Agent edits preserve existing signer/export references, experiment/configuration identity and participant assignment. No tool signs, produces participant bundles, imports private keys or downloads files.

## Timeline reference scenario

`preview_timeline` accepts optional `studyDay`, `startDate`, `startTime`, `timeZone`, and `locale` (`en` or `zh-TW`). Its default is a reference start at midnight on 2026-01-01 in UTC, on study day 1. These options change only the preview; they do not change the signed configuration.

Random-window schedules remain windows, with no invented participant-specific instants. Event-triggered and conditional behavior remains explicitly conditional. Calendar time and active-running time have different pause behavior, and the overview labels its uninterrupted-running assumption. The result uses the shared timeline projection rather than a second scheduler implementation.

## Native API and integration

This implementation follows the 9 September 2026 [WebMCP draft](https://webmachinelearning.github.io/webmcp/): tools register asynchronously on `document.modelContext`, and an `AbortSignal` controls their lifetime. The draft is a Community Group report, not a W3C standard. Chrome's current [imperative API guidance](https://github.com/GoogleChrome/modern-web-guidance-src/blob/main/guides/webmcp/agentic-javascript-tools/guide.md) documents the same registration and cleanup surface. Native availability must be checked at runtime; there is no emulated bridge.

`registerResearcherWebMcp` receives a small port from the mounted researcher page:

```ts
const registration = registerResearcherWebMcp({
  getConfiguration: () => $state.snapshot(draft.document),
  commitConfiguration: (next) => draft.replaceConfiguration(next),
  afterCommit: () => tick()
}, document, (status) => { webMcpStatus = status; });

// Return this cleanup from onMount.
return registration.stop;
```

The actual registration uses the local `NativeModelContext` type until browser TypeScript declarations include the evolving API. Tools are removed on route teardown using one shared controller. If any registration fails, the controller removes every tool registered during that attempt, and the page reports failure. The commit callback is synchronous, and the execution promise waits for the form render before reporting success.

`web/tests/webmcp.spec.ts` exercises all collector contracts, atomic configuration and removal, survey authoring, collection windows, timeline integration, incomplete metadata, revisions, input bounds, excluded fields, cancellation, and registration teardown/failure. These deterministic tests exercise a mock browser registration surface; real browser support must additionally be verified in the target host.
