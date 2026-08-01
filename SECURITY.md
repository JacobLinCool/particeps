# Security policy

This project handles research data collected from participants' personal phones. A vulnerability here can expose people who agreed to help with a study. We treat reports accordingly.

## Reporting a vulnerability

**Please do not open a public issue for a security or privacy vulnerability.**

Use GitHub's private vulnerability reporting on this repository (Security → Report a vulnerability), or email <jacoblincool@gmail.com> with `SECURITY` in the subject line.

Include whatever you have: affected version or commit, what you did, what happened, and why you think it matters. A partial report is worth sending — we would rather triage an uncertain one than never hear about a real one.

### What to expect

| Stage | Target |
| --- | --- |
| Acknowledgement that the report arrived | 3 working days |
| Initial assessment, including whether we consider it in scope | 10 working days |
| Status update while a fix is in progress | At least every 14 days |

This is a small project without a paid security team. If a deadline slips, we will tell you rather than go quiet. We do not run a bounty programme.

We will credit you in the release notes and the advisory unless you ask us not to. If you plan to publish, tell us your intended date and we will work toward it; if we disagree on timing, we will say so rather than stall.

## In scope

Anything showing that a security property the current release claims does not actually hold. For this version that means:

- **Data leaving the device other than as the signed configuration specifies and the consent screen disclosed.** A study may declare an upload endpoint, and the app then posts encrypted bundles to exactly that endpoint on the stated schedule. Any transmission to another destination, on another schedule, from a study whose `upload` block is empty, or carrying anything the researcher's private key does not have to open, is in scope.
- **Reading study data at rest** without the device's Keystore key — decrypting event segments or metadata, or extracting the key.
- **Reading an encrypted export** without the researcher's HPKE private key, or making a tampered export decrypt successfully.
- **Accepting a study configuration that should have been rejected** — a configuration running without a valid Ed25519 signature over its exact canonical bytes, a mismatch between what is signed and what is executed, a signer key ID in the envelope that disagrees with the one in the signed bytes, an expiry or app-version check bypass, or a build with pinned signers accepting a configuration signed by anyone else or carrying a key other than the pinned one. That the shipped build accepts a correctly signed configuration from an unpinned signer is the documented design, not a vulnerability; the consent screen discloses it.
- **Collecting outside the participant's consent** — recording before an explicit start, after a pause boundary, after completion or withdrawal, or from a collector the configuration did not enable.
- **A collector obtaining more than its contract allows** — most importantly the keyboard collector reaching text, or any collector reaching data outside its declared surface.
- **Silent data loss or corruption** presented to the participant or researcher as a complete dataset.
- **A protection described in [README.md](README.md) or [docs/threat-model.md](docs/threat-model.md) not behaving as described.** If the code is right and the documentation overstates it, send that too — we will fix the documentation.

Behaviour that changes in a later release is not retroactively a vulnerability; reports are assessed against what the release you tested claims.

## Out of scope

These are documented limitations rather than vulnerabilities. See [docs/threat-model.md](docs/threat-model.md) for the reasoning.

- Attacks requiring a rooted or already-compromised device, or a malicious OS build.
- Attacks requiring physical access to an unlocked device.
- A malicious researcher. A participant who consents to a study is trusting that research team; this software limits what a study can technically do, but a researcher can still design a study that collects more than a participant expected.
- A configuration signed by an unpinned key that names a research team it did not come from. A signature proves the file is unchanged since signing, not who wrote it. The consent screen shows the signer key ID and fingerprint and says so; the mitigation is the fingerprint a research team publishes to its participants.
- What happens to an export file after the participant shares it. Once it leaves the device, its safety depends on the researcher's own key handling and storage.
- Loss of the researcher's HPKE private key making exports permanently undecryptable. The current design has no escrow or recovery path.
- Inference risks inherent to the data itself — that keyboard touch dynamics or location traces can be identifying is a property of the data, disclosed in the consent content, not a bug.
- Findings from automated scanners with no demonstrated impact.
- The example keys in `researcher-tools/examples`. They are public fixtures on purpose and are named to say so.

## Verifying the claims yourself

Independent verification is welcome and does not need permission. The checks in [docs/threat-model.md](docs/threat-model.md) are a reasonable starting point. Testing against your own device or emulator with a study you signed yourself is always fine.

Do not test against a live study or another person's device without their informed agreement.
