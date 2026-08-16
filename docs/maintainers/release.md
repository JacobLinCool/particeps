# Release process

For maintainers of this repository. Participants and researchers do not need this document.

## Workflows

All workflows live in [`.github/workflows`](../../.github/workflows). The two that decide what ships are below, and `Pages` has its own section. `Analysis CI` and `Receiver CI` verify their own directories on the pull requests that touch them.

**`Android CI`** (`ci.yml`) runs on pushes to `main`, on pull requests, and on manual dispatch. It
runs unit tests, Android lint, Protocol/catalog conformance, Collector capability checks, and debug
and release builds. A dependent job then runs the complete connected suite on an API 34 Google APIs
emulator. Successful runs retain the debug APK as an artifact for 14 days.

**`Android Release`** (`release.yml`) accepts only `v<SemVer>` tags that are reachable from `main` — for example `v0.1.0`. A tag with a prerelease suffix produces a GitHub prerelease.

The release workflow first runs the complete connected suite on an API 34 Google APIs emulator. Only
after that gate passes does the dependent release job reconstruct the same `.signing` configuration
used locally and re-run host-side tests plus debug and release lint. It writes the tag into
`versionName` and the workflow run number into `versionCode`, then verifies the APK that Gradle signed
with `apksigner verify`. Verification must report exactly one signer certificate and its SHA-256 must
match the checked-in production identity anchor; printing certificate details is not sufficient. The
GitHub release carries both the APK and its SHA-256 checksum. Any device test, host-side test,
signing, identity, or verification failure stops the release; nothing is published and signing
secrets are not materialized before the device gate succeeds.

Permissions are job-scoped. The instrumented gate, including the third-party emulator action, gets
only `contents: read`; only the dependent release job gets `contents: write` to create or update the
GitHub Release.

Particeps is distributed directly as that signed APK. A Play listing, Play release track, and AAB
are not prerequisites for this release process; do not replace the verified APK artifact with an
unsigned APK or an unrelated bundle build.

## The published site

**`Pages`** (`pages.yml`) deploys the web authoring surface on pushes to `main` that touch it, not on a tag. It builds a project site, and `BASE_PATH` comes from the repository name at build time, so the published path follows whatever the repository is called. No path is pinned in the source, which is why renaming the repository is enough on its own — and why it was not optional. The repository has been renamed from `android-data-collector` to `particeps`, and the site publishes at `https://jacoblincool.github.io/particeps/`; the old path serves nothing.

`BASE_PATH` is read at build time from the event payload, and that payload carries the repository name as it was when the event was created. A run queued before a rename therefore keeps building the old path however many times it is retried. Re-running replays the same event and redeploys the same artifact.

The Particeps rename hit this. The deploy that followed the merge timed out, for an unrelated reason: a GitHub Pages deployment-lag incident that day. Re-running only the failed job then republished a build whose HTML still pointed at `/android-data-collector/_app/…`. The title and the routes were right and the workflow was green; every asset was a 404. That is the failure mode least likely to be noticed, and the retry is what caused it, not the timeout.

So after renaming the repository, trigger Pages fresh rather than re-running anything: `gh workflow run pages.yml --ref main`. A `workflow_dispatch` event is created at dispatch time and carries the current name. Then check the published HTML rather than the workflow's green tick. `curl -s <url> | grep -c '<old-repo-name>'` must be zero, and one asset URL taken from that HTML must return 200. A deploy can also simply be slow or stuck on GitHub's side; check <https://www.githubstatus.com> before assuming the rename broke something.

GitHub will redirect the old repository URL to the new one, but only until some other repository claims the old name. Treat that as a courtesy to stale links rather than an address the project still publishes.

What a redirect cannot fix is anything already in a participant's hands. A join link is a `particeps://join/v1` URI carrying the hosting URL of the signed `.partcfg`, its SHA-256, and the signer fingerprint, and a QR code is that URI rendered. Both are immutable by design, so an issued link or a printed QR cannot be repointed at a new address. If a study's artifact was served from the old Pages path, reissue the join link and the QR at the new URL and redistribute them. The same applies if a poster, an email, or a consent appendix sent participants there. The configuration itself does not need re-signing — the envelope bytes and their digest are unchanged, only the URL that serves them.

## Signing keys

Two unrelated keys are involved, and they must never be interchanged.

| Key | Purpose | Where the private key lives |
| --- | --- | --- |
| Android release signing key | Signs the APK, so a device accepts an update as the same application, provided the `applicationId` is also unchanged | Maintainer's offline backup and GitHub Actions secrets |
| Ed25519 study signing key | Signs study configurations; its public half travels inside the configuration it signs | The research team's own key management, never in this repository |

Losing the Android signing private key means no future build can update a directly installed app under the same identity. Keep an offline, encrypted backup.

The sole repository source of truth for the production Android certificate identity is
[`.github/android-release-signing-certificate.sha256`](../../.github/android-release-signing-certificate.sha256).
It is the lowercase SHA-256 of the rc.5 signing **certificate**, not the APK checksum, keystore
checksum, or public-key digest. `tools/verify_release_apk.py` requires exactly one APK signer and
compares its certificate digest with that anchor. Do not change the anchor to make a mismatched build
pass: a deliberate certificate rotation breaks direct update continuity and requires an explicit new
application-distribution plan.

### Update compatibility

`v1.0.0-rc.5` established the current `cool.jacoblin.particeps` application ID and the certificate in
the repository identity anchor. `v1.0.0-rc.6` and `v1.0.0-rc.7` keep both, so rc.7 updates rc.5 or
rc.6 in place. Earlier candidates used another application ID, another certificate, or incompatible
file identities and cannot update directly to the current build. [CHANGELOG.md](../../CHANGELOG.md)
records the action required from each release.

The key was rotated to correct the certificate's subject, which named the pre-rename product. A
certificate is signed over its own subject, so changing it means issuing a new one. That was
affordable only before the current identity and key were handed to testers. It stops being
affordable once a participant is running rc.5 or later, so it does not happen again.

State update compatibility in every release note. In particular, rc.5 and rc.6 update in place to
rc.7; rc.4 and earlier do not.

## Android Developer Verification

The maintainer has confirmed that the developer identity in Android Developer Console is verified.
That is distinct from proving that a particular package name and signing certificate are registered:
this repository contains no authoritative evidence of the package-registration status. Before
relying on Developer Verification for distribution, check the console entry for
`cool.jacoblin.particeps` and confirm that it carries the fingerprint of the **current** keystore.
Register the package/certificate pair there if it is absent; do not infer this step from identity
verification alone.

```bash
python3 tools/verify_release_apk.py \
  "$ANDROID_SDK_ROOT/build-tools/37.0.0/apksigner" \
  app/build/outputs/apk/release/app-release.apk
```

Developer Verification does not make Google Play the distribution channel. This project continues
to publish its signed APK directly. The programme's requirements and deadlines are Google's and
change; check the current rules when a release actually depends on them rather than trusting this
paragraph.

## Local signing material

`assembleRelease` produces the direct-distribution signed APK when
`.signing/release-signing.properties` exists locally, and an unsigned release APK otherwise. The
whole `.signing/` directory is git-ignored and must stay that way. This process does not require
`bundleRelease` or an AAB.

## GitHub secrets

Create four repository secrets under Settings → Secrets and variables → Actions:

- `ANDROID_KEYSTORE_BASE64` — the release keystore as a single-line Base64 string
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The keystore and alias selected by these secrets must produce the anchored certificate. The workflow
rejects an otherwise valid APK when it is signed by any other certificate or by multiple signers.

On macOS you can pipe the keystore straight to the GitHub CLI without leaving a copy in the working directory:

```bash
base64 -i /secure/android-release.jks | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64
```

Enter the passwords and alias through the GitHub web interface so they do not end up in shell history.

## Cutting a release

Once the secrets exist, publish with an annotated SemVer tag:

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

Before tagging, update `version` and `date-released` in
[`CITATION.cff`](../../CITATION.cff). Keep both fields in step with the tag at every release.
Add exactly one non-empty `## <tag> — YYYY-MM-DD` section to
[`CHANGELOG.md`](../../CHANGELOG.md), including explicit update or fresh-install instructions. The
workflow extracts that exact section as the GitHub Release notes and fails before publishing if the
section is missing, duplicated, malformed, or empty; it does not substitute generated notes.

### One-off: the Particeps cutover

The rename is not a recurring step, and it is not finished when the code lands either. What has been done, in order:

1. The GitHub repository was renamed from `android-data-collector` to `particeps`, and its description, topics, and homepage were updated with it. The rename waited on `main` carrying the renamed tree, because the badges, documentation links, and `BASE_PATH` in it all assume the new name. Repository secrets survived it and needed nothing.
2. Pages redeployed under the new name. The published HTML carries no old-name asset paths, and an asset URL taken from it returns 200.
3. `v1.0.0-rc.4` was tagged as the first post-rename release, and `version` and `date-released` returned to `CITATION.cff` in it.

What remains:

- Confirm the `cool.jacoblin.particeps` package/certificate entry in Android Developer Console as
  described above. Developer identity verification is complete; package registration is not marked
  complete without console evidence.
- Reissue any join link or QR that pointed at the old Pages path, as the section above describes. This is per study rather than a single step: it is finished only when no issued link and no printed QR still points there.

## Pinned signers

A study configuration carries its own signing key, so issuing a study needs no release; the [threat model](../../docs/threat-model.md) covers how that trust works. `CollectorApplication.TRUSTED_SIGNING_KEYS` is empty in the shipped build, and a release should keep it that way: the published app then verifies any correctly signed configuration and tells the participant that the publisher is unverified.

Populating that map is for an institution building its own APK to run only its own studies. It is strictly exclusive — every signer not listed is refused — so it is not a hardening step to apply to a general release. It is also a source change, and therefore a new build and a new release, with no revocation path short of another one.

Check the map before tagging. A release that pins a signer by accident refuses every study but that one.
