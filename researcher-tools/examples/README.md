# Demonstration keys and study

**The private key material in this directory is committed to a public repository. It is not secret and never was. Anything signed or encrypted with it can be read or forged by anyone.** Both key files carry an `INSECURE-` prefix for that reason.

| File | What it is |
| --- | --- |
| `INSECURE-demo-signing-private.key` | Raw Ed25519 study signing private key, unpadded base64url — public fixture |
| `INSECURE-demo-hpke-private.key` | Raw X25519 HPKE private key, unpadded base64url — public fixture |
| `demo-study.json` | An example study configuration, useful as a schema reference |

These exist so a debug build can exercise signing and export decryption end to end, and so the example configuration in the [researcher guide](../../docs/researcher-guide.md) is runnable. That is their only purpose. There is no separate file for the demonstration signing public key; the [threat model](../../docs/threat-model.md) describes where a configuration's signing key lives.

`demo-study.json` is intentionally formatted for people to read. Run `canonicalize` before `sign`; only the resulting RFC 8785 bytes are valid Protocol v1 signing input.
The signed result mirrored at `app/src/debug/res/raw/demo_study_envelope.txt` is verified by
`DemoStudyAssetTest`. Changing this configuration or its signing key requires regenerating that
asset through the same `canonicalize` and `sign` commands.

The example is an anonymous v1 configuration with one localized short-answer survey and one one-time survey intervention. It deliberately has no researcher-assigned participant code.

**Never use them for a real study.** A study signed with these keys is not authentic: anyone can sign a configuration that presents itself as the demo study. An export encrypted to this HPKE key is readable by anyone who clones this repository.

**A release build ships no demonstration study.** The signed envelope and the code that loads it live in the app's `debug` source set, so neither is compiled or packaged into the release APK, and the dashboard renders no entry point for it. A released app can therefore only run a study that a research team signed and handed to a participant. A debug build still offers the demo, which is what the instrumentation test drives.

The distinction that makes this necessary is not trust. The shipped build pins no signers at all, so `demo-signer-2026` is verified exactly like any other signer and the consent screen reports the publisher as unverified; see the [threat model](../../docs/threat-model.md) for what that verification does and does not establish. What makes the demo wrong to ship is that its export key is public. A study run under it collects real data from a real phone into a file anyone can open.

The build-variant boundary therefore removes a foot-gun rather than an attack. Anyone can still sign their own configuration with the published demo key and hand the file to someone, because pinning no signers is the deployment model. The consent screen is what carries that case.

For a real study, generate your own key pairs with `:researcher-tools` and keep both private keys under your own key management. Put the signing public key in the configuration's `signer` block, and publish its fingerprint where your participants can compare it against the consent screen. See the [researcher guide](../../docs/researcher-guide.md) for the full procedure and the [threat model](../../docs/threat-model.md) for what each key protects.
