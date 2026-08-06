# Release process

For maintainers of this repository. Participants and researchers do not need this document.

## Workflows

All workflows live in [`.github/workflows`](../../.github/workflows). The two that decide what ships are below; `Pages` has its own section, and `Analysis CI` and `Receiver CI` verify their own directories on the pull requests that touch them.

**`Android CI`** (`ci.yml`) runs on pushes to `main`, on pull requests, and on manual dispatch. It
runs unit tests, Android lint, Protocol/catalog conformance, Collector capability checks, and debug
and release builds. Successful runs retain the debug APK as an artifact for 14 days.

**`Android Release`** (`release.yml`) accepts only `v<SemVer>` tags that are reachable from `main` — for example `v0.1.0`. A tag with a prerelease suffix produces a GitHub prerelease.

The release workflow reconstructs the same `.signing` configuration used locally, re-runs tests and lint, writes the tag into `versionName` and the workflow run number into `versionCode`, then verifies the APK that Gradle signed with `apksigner verify`. The GitHub release carries both the APK and its SHA-256 checksum. Any test, signing, or verification failure stops the release; nothing is published.

## The published site

**`Pages`** (`pages.yml`) deploys the web authoring surface on pushes to `main` that touch it, not on a tag. It builds a project site, and `BASE_PATH` comes from the repository name at build time, so the published path follows whatever the repository is called. No path is pinned in the source, which is why renaming the repository is enough on its own — and why it is not optional. Until the repository is renamed from `android-data-collector` to `particeps`, the tree says Particeps everywhere while the site still publishes at `https://jacoblincool.github.io/android-data-collector/`. Renaming it moves the site to `https://jacoblincool.github.io/particeps/` on the next deploy to `main`.

GitHub will redirect the old repository URL to the new one, but only until some other repository claims the old name. Treat that as a courtesy to stale links rather than an address the project still publishes.

What a redirect cannot fix is anything already in a participant's hands. A join link is a `particeps://join/v1` URI carrying the hosting URL of the signed `.partcfg`, its SHA-256, and the signer fingerprint, and a QR code is that URI rendered; both are immutable by design. An issued link or a printed QR cannot be repointed at a new address. So if a study's artifact was served from the old Pages path, or if a poster, an email, or a consent appendix sent participants there, reissue the join link and the QR at the new URL and redistribute them. The configuration itself does not need re-signing — the envelope bytes and their digest are unchanged, only the URL that serves them.

## Signing keys

Two unrelated keys are involved, and they must never be interchanged.

| Key | Purpose | Where the private key lives |
| --- | --- | --- |
| Android release signing key | Signs the APK, so a device accepts an update as the same application, provided the `applicationId` is also unchanged | Maintainer's offline backup and GitHub Actions secrets |
| Ed25519 study signing key | Signs study configurations; its public half travels inside the configuration it signs | The research team's own key management, never in this repository |

Losing the Android signing private key means no future build can update a directly installed app under the same identity. Keep an offline, encrypted backup.

### The rename is not an upgrade path

The rename deliberately left the release signing key alone; rotating it would strand every build already installed under the old certificate. That does not make the first post-rename APK an update to an installed pre-rename one. A device identifies an installed application by its `applicationId`, and that moved from `cool.linc.androiddatacollector` to `cool.linc.particeps`, so Android treats the two as unrelated applications: they install side by side, share nothing, and neither can update the other. The shared signing key means only that both builds came from the same maintainer.

Every tester installs the new APK fresh and uninstalls the old one themselves. Uninstalling takes the old app's encrypted storage with it, and there is no migration: the storage key is non-exportable, so data written by the pre-rename build can leave only through that build's own export, in the pre-rename formats, which current tooling does not read. A tester holding data worth keeping should export it before uninstalling and analyse it with the pre-rename tooling. State this in the release notes for the first post-rename tag; a tester expecting an in-place update will otherwise read a correct install as a failed one.

## Android Developer Verification

Google's Developer Verification binds a verified developer identity to the package names that developer distributes and the certificates those packages are signed with. Registration is per package name. `cool.linc.particeps` has never been registered, so it needs its own entry, and an entry for `cool.linc.androiddatacollector` does not cover it. The certificate is the part that carries over unchanged: register the new `applicationId` with the same SHA-256 fingerprint the release keystore has always produced.

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

The programme's requirements and deadlines are Google's and change; check the current rules when a release actually depends on them rather than trusting this paragraph.

## Local signing material

`assembleRelease` produces a signed APK when `.signing/release-signing.properties` exists locally, and an unsigned release APK otherwise. The whole `.signing/` directory is git-ignored and must stay that way.

## GitHub secrets

Create four repository secrets under Settings → Secrets and variables → Actions:

- `ANDROID_KEYSTORE_BASE64` — the release keystore as a single-line Base64 string
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

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

Before tagging, update `version` and `date-released` in [`CITATION.cff`](../../CITATION.cff). Both fields are absent right now: every existing tag predates the rename and carries the old identity, so the file deliberately names no version rather than attributing one of those releases to Particeps. The first post-rename tag adds them back.

### One-off: finishing the Particeps cutover

The rename is not a recurring step, but it is not finished when the code lands either. In order:

1. Rename the GitHub repository from `android-data-collector` to `particeps`, and update its description, topics, and homepage. Do this only once `main` carries the renamed tree, because the badges, documentation links, and `BASE_PATH` in it all assume the new name. Repository secrets survive a rename and need nothing.
2. Confirm the next deploy to `main` publishes the site at the new path, and reissue any join link or QR that pointed at the old one.
3. Register `cool.linc.particeps` under Developer Verification with the existing certificate fingerprint, as above.
4. Cut the first post-rename tag, restore `version` and `date-released` in `CITATION.cff` as part of it, and say plainly in its release notes that it is a fresh install rather than an update.

## Pinned signers

A study configuration carries its own Ed25519 signing public key, so issuing a study needs no release. `CollectorApplication.TRUSTED_SIGNING_KEYS` is empty in the shipped build, and a release should keep it that way: the published app then verifies any correctly signed configuration and tells the participant that the publisher is unverified.

Populating that map is for an institution building its own APK to run only its own studies. It is strictly exclusive — every signer not listed is refused — so it is not a hardening step to apply to a general release. It is also a source change, and therefore a new build and a new release, with no revocation path short of another one.

Check the map before tagging. A release that pins a signer by accident refuses every study but that one.
