# Participant guide

Particeps lets you take part in an Android research study without creating an account. The app
shows the study, the researcher’s contact details, the kinds of data requested, the consent text,
and the Android access the study needs before anything is collected.

You can decline, pause, resume, withdraw, and export your encrypted data. Completing or withdrawing
also lets you permanently delete the study from the phone. Particeps never asks why you made one of
these choices.

## Before you start

A Particeps study arrives as a signed configuration file or an immutable join link. The app checks
the file’s signature, exact contents, expiry, Android version requirement, and study structure
before showing it. A valid signature proves that the file has not changed since it was signed; it
does not by itself prove the real-world identity of the person who holds the signing key. Compare
the displayed researcher details and signer fingerprint with information you received through a
trusted channel.

Importing a configuration does not start collection. Nothing is collected until you finish setup
and press **Start study**.

Only one study can be present in the app at a time. A new import is refused while another study or
its deletion is still present.

## The five setup steps

Setup keeps the existing five-step flow. The row of five indicators shows your position; it is not
a set of buttons.

1. **Study** — title, researcher, contact, purpose, and duration.
2. **Data** — high-level data categories the study may collect. A category marked
   **Optional** does not by itself block setup if its Android access is unavailable.
3. **Consent** — the researcher-authored consent summary, document version, and signing-key
   fingerprint. Accepting is required to continue.
4. **Access** — the Android permissions, special access, device settings, and hardware required by
   the requested categories. Shared access appears once even when more than one category uses it.
5. **Start** — **Start study** and **Withdraw**. Collection is still off at this point.

Particeps-generated setup text does not describe when a study activity happens or how a study may
change its behaviour. Researcher-authored consent, notification, and survey text is shown exactly
as the researcher wrote it and can contain information the researcher chose to disclose.

If the study, contact details, requested data, access, or consent do not match what the research
team told you, stop and contact them before granting access.

## Data categories

Depending on the signed study, the Data step can include:

| Category | What it can contain | What it does not contain |
| --- | --- | --- |
| Particeps app activity | Lifecycle of Particeps’ own screens | Activity in other apps |
| Motion | Accelerometer or gyroscope samples | Audio, camera, or inferred activities |
| Nearby light or distance | Ambient-light or proximity sensor readings | Images or nearby-device identities |
| Battery | Charge level, charging state, and power-saving state | Battery serial number |
| Time context | Time zone and offset snapshots | Location inferred from the time zone |
| Connection state | Wi-Fi/mobile/ethernet/VPN flags and coarse link properties | SSID, BSSID, addresses, destinations, DNS names, URLs, or content |
| Data volume | Device-wide network accounting over bounded intervals | Per-app destinations or packet content |
| App and screen use | Android usage-history events such as package resume/pause and screen state | Activity class names, screen contents, or an accessibility-service feed |
| Location | Android fused-location fixes when explicitly requested | Photos, nearby-device scans, or inferred visits added by Particeps |
| Research keyboard touch | Timing and geometry of touches on the optional Particeps keyboard | Key identity, typed text, clipboard, password-field touches, or another keyboard’s input |

The exact requested categories are always listed before consent. Particeps does not silently add a
new category after the study starts.

## Android access

The Access step shows one existing card for each ordinary Android capability the study needs. A
required item must be satisfied before **Done**, **Start study**, or **Resume** succeeds. An item
used only by optional categories can remain unavailable; those categories stay off while the rest
of the study continues.

Possible access includes:

- notifications, so Android can show the neutral ongoing research notification and study prompts;
- Usage access for device network accounting or Android app/screen usage history;
- fine and background location plus location-services readiness, only for a location study;
- selecting the optional Particeps research keyboard, only for a keyboard-touch study;
- sensor or hardware availability required by the selected data categories.

Android owns permission dialogs and Settings screens. Particeps checks the result again after you
return. Denying required access leaves the study stopped.

### Studies that may adjust App transfer speed

Some studies may use Android’s local VPN feature to adjust how quickly some apps transfer data.
For those studies, the existing Access step includes this fixed explanation near **Done**:

> This study may use a VPN on this device to adjust how quickly some apps transfer data. Traffic
> stays on your usual network and is not sent through a Particeps server. Particeps only checks
> whether the study’s apps are installed; it does not save or upload your installed-app list. On
> Android 17 or later, local-network access is used only to forward local connections those apps
> initiate; Particeps does not discover local devices. Another VPN can interrupt this function; if
> that happens, the study pauses.

Pressing the existing **Done** or **Resume** control opens Android’s local-network permission when
the operating system requires it, followed by Android’s standard VPN-consent screen when consent
is not already valid. Particeps does not add a VPN setup screen, card, status dashboard, history,
or second ongoing notification. Android’s own VPN icon and consent surface are unavoidable system
UI.

If the local-network permission is denied or revoked, VPN consent is revoked, the VPN is replaced,
or Particeps can no longer verify safe forwarding, the study pauses. The app does not identify or
guess the name of another VPN. Resume repeats the required Android checks.

Particeps does not record packet contents, destinations, DNS names, or an installed-app inventory.
Research traffic continues over the phone’s ordinary underlying network and is not sent through a
Particeps gateway.

## While the study is active

After Start succeeds, the five setup indicators are replaced by the same compact status area used
by current Particeps studies. The participant-facing states are:

- **Collecting** — the study is active and verified resources may admit data;
- **Paused** — no new collector data is admitted until you explicitly resume;
- **Completed** — the configured study duration has ended;
- **Withdrawn** — the study has ended permanently at your request.

The screen continues to provide **Pause**, **Resume**, **Complete**, **Withdraw**, and **Export** only
where those actions are valid. It lists the approved data categories and current study state; it
does not add a participant-facing research-control dashboard.

The configured duration ends collection automatically. New data is not accepted after that
boundary even if Android runs the background completion work later, and completion does not wait
for another data category to report something.

Android requires a foreground-service notification while continuous work is active. Particeps uses
one neutral research notification even when the study also uses the local VPN. It does not put the
study title, target apps, treatment state, or internal diagnostics in that notification.

Study notifications and native surveys can contain researcher-authored wording. A posted
notification does not prove that you saw it. A survey stores no answer draft; a final submission is
validated and recorded once. Closing an unfinished survey does not submit it.

## Pause, resume, completion, and withdrawal

**Pause** first stops new data admission, finishes work already accepted up to the boundary, and
then stops the study resources. Once the UI shows **Paused**, the app does not backfill the paused
interval. **Resume** checks required access again and starts a new verified collection interval.
Resume is always a participant action; Particeps does not automatically continue after a reboot,
process loss, or safety failure.

After a phone restart, Particeps discards the interval it could not verify and does not retrieve
missed App-use or network history. Resume may remain unavailable until the phone can establish a
trustworthy current time. You can still choose **Complete** or **Withdraw** while the study is
paused.

**Complete** ends collection and keeps already collected encrypted data available for export.
**Withdraw** permanently ends the study but likewise preserves already collected encrypted data
until you delete it. Neither action sends an explanation to the researcher.

If Android access, storage, a required collector, or a continuous study function becomes unsafe,
Particeps closes admission and moves to the same generic paused experience. The participant UI
does not expose internal failure names. Check the ordinary Access step, then use **Resume**; if the
problem persists, contact the research team.

## Export and upload

**Export encrypted data** creates a `.partexp` bundle encrypted to the researcher public key in the
signed configuration. Particeps cannot decrypt it. A manual export contains the complete encrypted
research records still retained on the phone through the boundary captured when export began.

If the signed study includes automatic upload, the app sends immutable encrypted chunks to that
configured HTTPS endpoint. A delivered prefix can be removed locally only after an exact receipt
confirms the same encrypted bytes and complete range. Pausing stops collection but does not
erase existing encrypted data or cancel delivery that the signed study already disclosed.

The researcher may be able to link a personalized dataset through an opaque participant code in
the signed study. Particeps itself has no account, advertising ID, contacts integration, or device
identity field.

## Delete local data

After completion or withdrawal, **Delete local data** removes the signed configuration, encrypted
study data, pending upload material, and cached export metadata for that
study. Android system backups are disabled for these files.

Deletion is permanent. Export anything you want to keep before confirming it. The app does not
silently delete an incompatible or unreadable study; the existing generic recovery flow requires
your confirmation before destructive reset.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Setup cannot finish | Open the existing Access step and satisfy every required item, or decline/withdraw if you do not want to grant it. |
| Start or Resume returns to Paused | Recheck required Android access. A study that may adjust App transfer speed may also need local-network permission and Android VPN consent. |
| Another VPN stops working or the study pauses | Android permits only one active VPN for the same phone user. Choose which VPN to use; Particeps will not resume the study automatically. |
| A data category is unavailable | Check its Android access or hardware. Required categories stop the study; optional categories can remain off. |
| A study notification or survey is late | Android background scheduling is best effort. Do not treat delivery time as proof that the participant saw it. |
| Export fails | Keep the app data intact and retry with enough storage and a writable destination. The app never publishes a partial plaintext or partial encrypted export. |
| Recovery asks for reset | Export information if the existing generic recovery flow offers it, then confirm only if you accept permanent removal of the incompatible local study. |

For protocol-level field definitions, data quality, and interpretation limits, researchers should
use the [data dictionary](data-dictionary.md), [Protocol v1](../protocol/v1/README.md), and
[threat model](threat-model.md). Those documents intentionally contain implementation detail that
this participant-facing guide omits.
