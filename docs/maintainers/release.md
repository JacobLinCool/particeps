# Release process

For maintainers of this repository. Participants and researchers do not need this document.

## Workflows

Both workflows live in [`.github/workflows`](../../.github/workflows).

**`Android CI`** (`ci.yml`) runs on pushes to `main`, on pull requests, and on manual dispatch. It
runs unit tests, Android lint, Protocol/catalog conformance, Collector capability checks, and debug
and release builds. Successful runs retain the debug APK as an artifact for 14 days.

**`Android Release`** (`release.yml`) accepts only `v<SemVer>` tags that are reachable from `main` — for example `v0.1.0`. A tag with a prerelease suffix produces a GitHub prerelease.

The release workflow reconstructs the same `.signing` configuration used locally, re-runs tests and lint, writes the tag into `versionName` and the workflow run number into `versionCode`, then verifies the APK that Gradle signed with `apksigner verify`. The GitHub release carries both the APK and its SHA-256 checksum. Any test, signing, or verification failure stops the release; nothing is published.

## Signing keys

Two unrelated keys are involved, and they must never be interchanged.

| Key | Purpose | Where the private key lives |
| --- | --- | --- |
| Android release signing key | Signs the APK, so a device accepts an update as the same application | Maintainer's offline backup and GitHub Actions secrets |
| Ed25519 study signing key | Signs study configurations; its public half travels inside the configuration it signs | The research team's own key management, never in this repository |

Losing the Android signing private key means no future build can update a directly installed app under the same identity. Keep an offline, encrypted backup.

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

Before tagging, update `version` and `date-released` in [`CITATION.cff`](../../CITATION.cff).

## Pinned signers

A study configuration carries its own Ed25519 signing public key, so issuing a study needs no release. `CollectorApplication.TRUSTED_SIGNING_KEYS` is empty in the shipped build, and a release should keep it that way: the published app then verifies any correctly signed configuration and tells the participant that the publisher is unverified.

Populating that map is for an institution building its own APK to run only its own studies. It is strictly exclusive — every signer not listed is refused — so it is not a hardening step to apply to a general release. It is also a source change, and therefore a new build and a new release, with no revocation path short of another one.

Check the map before tagging. A release that pins a signer by accident refuses every study but that one.
