# Participant guide

Particeps lets you take part in an Android research study without creating an account. The app
shows the study, the researcher’s contact details, the kinds of data requested, the consent text,
and the Android access the study needs before anything is collected.

You can decline, pause, resume, withdraw, and export your encrypted data. Declining before you
start removes the study from the phone; completing or withdrawing also lets you permanently delete
it. Particeps never asks why you made one of these choices.

## Before you start

A Particeps study can arrive as a QR code, a join link, or a signed configuration file. To use a
QR code, open Particeps and choose **Scan study QR code**. Allow camera access when Android asks, then
point the camera at the research team's code. The app downloads and checks the study, so keep your
phone connected to the internet. The camera is used only while the scanner is open, and camera
images are not saved. You can close the scanner at any time.

If you prefer to use a file or cannot scan the code, choose **Choose a study file** and select the signed
configuration file supplied by the research team. A join link can also open the study in the app.

For every entry method, the app checks
the file’s signature, exact contents, expiry, Android version requirement, and study structure
before showing it. A valid signature proves that the file has not changed since it was signed; it
does not by itself prove the real-world identity of the person who holds the signing key. Compare
the displayed researcher details and signer fingerprint with information you received through a
trusted channel.

Importing a configuration does not start collection. Nothing is collected, and nothing is sent to
the research team, until you finish setup and press **Start study** (for a study imported with an
earlier release, see the note on declining below).

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
5. **Start** — **Start study**. Collection is still off at this point.

Every setup step also offers **Decline and remove this study**. Nothing has been collected or sent
before **Start study**, so after you confirm, declining removes the study and your setup progress
from the phone and the app returns to its starting screen. Android access you granted during setup
stays on until you turn it off in Android Settings or uninstall Particeps. To take part later,
import the study again before its configuration expires.

Particeps 1.0.0-rc.13 and some earlier releases began automatic upload when a study was imported,
so a study you imported with one of them may already have sent records of your setup steps and your
installation code. The current app sends nothing more before **Start study**. If the phone has a
confirmation that such records were delivered, the decline confirmation says so; declining does not
take them back.

Particeps-generated setup text does not describe when a study activity happens or how a study may
change its behaviour. Researcher-authored consent, notification, and survey text is shown exactly
as the researcher wrote it and can contain information the researcher chose to disclose.

If the study, contact details, requested data, access, or consent do not match what the research
team told you, stop and contact them before granting access.

## Data categories

Depending on the signed study, the Data step can include:

| Category | What it can contain | What it does not contain |
| --- | --- | --- |
| Particeps app activity | When Particeps’ own screens open, move to the foreground or background, and close, and which Particeps screen it was | Activity in other apps or what is shown on screen |
| Motion | Raw acceleration on three axes, including gravity, with sensor time and accuracy | Audio, images, location, or inferred activities |
| Phone rotation | Raw gyroscope rotation speed on three axes with sensor time and accuracy | Audio, images, location, or inferred orientation or activities |
| Ambient light | The light level from the phone’s light sensor | Images or anything else about your surroundings |
| Proximity sensor | Near or far state, the raw distance reading, and the sensor’s range | Images or who is nearby; a near reading is not a claim that a person is present |
| Battery context | Whole battery percentage, charging state and source, and power-saving mode | Battery health, temperature, or hardware identity |
| Time context | Time-zone setting, UTC offset, daylight-saving state, and why each was recorded | Location or travel inferred from the time zone |
| Screen power and lock state | Whether the screen is on, off, or in a low-power display mode, whether the phone is interactive, and whether the lock screen is showing | Screen contents; a screen that is on does not show attention or use |
| Connection type | Whether the main connection is available; Wi-Fi, mobile, ethernet, or VPN transport; metered, roaming, and internet-validated state; may include Android’s estimate of link speed | Network names, addresses, destinations, DNS names, URLs, or content |
| VPN connection state | Whether any VPN on the phone is connected, including VPNs from other apps | Which VPN app it is, VPN server addresses, or traffic content |
| Observed network throughput | Device-wide bytes sent and received in each measured interval, with the interval’s start and end | Per-app use, destinations, or content; no speed test is run |
| Data volume | Device-wide bytes and packets sent and received over Wi-Fi or mobile data for each period Android reports | Per-app use, destinations, or packet content |
| App and screen use | Android usage-history events: apps moving to and from the foreground with their package name and an opaque code per app screen, screen on and off, lock screen shown and dismissed, and phone startup and shutdown | Screen names, screen contents, typed text, notification content, or an accessibility-service feed |
| Location | Location fixes with accuracy and time, including while Particeps is not open; may include altitude, speed, and direction with their accuracy, and whether Android marked the fix as simulated | Photos, nearby-device scans, place names, or inferred visits |
| Research keyboard touch | Key category, where on the key and when, and touch pressure, size, and angle on the optional Particeps keyboard | Key identity, typed text, clipboard, password-field touches, or another keyboard’s input |

Each category’s description is fixed app copy and is the same for every study that uses it; it
does not state how often a category is sampled. The Data step and **Study and my data** show it
for each requested category. **Study and my data** also lists the Android access each category
uses, and the Access step lists which categories use each access.

The exact requested categories are always listed before consent. Particeps does not silently add a
new category after the study starts.

## Android access

The Access step shows one existing card for each ordinary Android capability the study needs. A
required item must be satisfied before **Done**, **Start study**, or **Resume** succeeds. An item
used only by optional categories can remain unavailable; those categories stay off while the rest
of the study continues.

Possible access includes:

- permission to show notifications, so Android can display the ongoing research notification and study prompts;
- Usage access for device network accounting or Android app/screen usage history;
- fine and background location plus location-services readiness, only for a location study;
- selecting the optional Particeps research keyboard, only for a keyboard-touch study;
- sensor or hardware availability required by the selected data categories.

Android owns permission dialogs and Settings screens. Particeps checks the result again after you
return. Denying required access leaves the study stopped.

Permission to show Particeps notifications is used for study reminders and the ongoing research
notification. It does not let Particeps observe other apps' notifications.

### Studies that may adjust App transfer speed

Some studies may use Android’s local VPN feature to adjust how quickly apps transfer data. For
those studies, the existing Access step includes this fixed explanation near **Done**, and
**Study and my data** repeats it:

> This study may use a VPN on this device to adjust how quickly apps on this phone transfer data.
> Traffic stays on your usual network and is not sent through a Particeps server, and Particeps
> does not record its content or destinations. Particeps may check whether particular apps are
> installed, but it does not save or upload a list of your installed apps. On Android 17 or later,
> local-network access is used only to forward local connections that apps start; Particeps does
> not look for devices on your local network. Another VPN can interrupt this function; if that
> happens, the study pauses.

The explanation is the same whichever apps a study affects, so it does not tell you which ones.

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
where those actions are valid: **Withdraw** while the study is collecting or paused, and **Delete
local data** once it has completed or been withdrawn. It lists the approved data categories and
current study state; it does not add a participant-facing research-control dashboard.

### Study and my data

Below those controls, one row labeled **Study and my data** opens a page you can read once the
study has started: while it is collecting or paused, including while Android access needs
repairing, and after it is completed or withdrawn. It is not there during setup, or while
Particeps asks you to recover or reset the study. The row is the same in every study. The page has
no Pause, Resume, Withdraw, Delete, or Export control of its own; **Back** returns to the normal
screen. It has four parts:

- **About this study** — the title, purpose, researcher name and contact, study length, the consent
  text you agreed to and its document version, the signing-key fingerprint, your assigned code if
  the study has one, and your installation code. You can select and copy the contact details, the
  fingerprint, and the codes.
- **What is collected** — each data category, whether it is required or optional, what it records
  and does not record, and the Android access it uses; the upload terms; and, for a study that may
  adjust app transfer speed, the fixed VPN explanation above.
- **Your participation** — the current state; the study day, where day 1 is the first 24 hours
  after **Start study** and the last day ends at the planned end; the planned end time, once
  Particeps can verify the phone’s clock; time spent collecting and time paused; the size of your
  last export, if you exported since Particeps last started (Particeps does not keep it after
  Android closes the app); and how much space the study uses on this phone, measured when you open
  the page. Both sizes are shown in 50 MB steps, such as “Less than 50 MB” or “50–100 MB”; the
  export's event count is exact.
- **Your rights** — what **Pause** and **Resume**, **Withdraw**, and **Delete local data** do,
  including what happens to data that has not been sent yet.

The page restates what you were shown before consent and adds facts about your own participation.
It does not show when study activities happen or how a study might change its behaviour. Showing
more, or explaining hidden design details at a debrief, is the researcher’s decision and would come
through a signed disclosure policy; current studies use the default and show only what is listed
here.

The configured duration ends collection automatically. New data is not accepted after that
boundary even if Android runs the background completion work later, and completion does not wait
for another data category to report something.

Android requires a foreground-service notification while continuous work is active. Particeps uses
one neutral research notification even when the study also uses the local VPN. It does not put the
study title, target apps, treatment state, or internal diagnostics in that notification.

Study notifications and native surveys can contain researcher-authored wording. Particeps marks
study notifications as private, so a locked phone that hides sensitive notification content shows
only “A research activity is available. Unlock your phone to see it.” in place of the researcher’s
wording. If your lock screen shows all notification content, which is Android’s default, the
researcher’s wording appears there; you can change this in Android’s notification settings. A
posted notification does not prove that you saw it. A survey stores no answer draft; a
final submission is validated and recorded once. Closing an unfinished survey does not submit it.

## Pause, resume, completion, and withdrawal

**Pause** first stops new data admission, finishes work already accepted up to the boundary, and
then stops the study resources. Once the UI shows **Paused**, the app does not backfill the paused
interval. No data category collects while the study is paused, but Particeps still records when the
phone’s clock or time zone changes, including the new time zone, and when the phone restarts, so
that the study’s timeline stays correct. **Resume** checks required access again and starts a new verified collection interval.
Resume is always a participant action; Particeps does not automatically continue after a reboot,
process loss, or safety failure.

After a phone restart, Particeps discards the interval it could not verify and does not retrieve
missed App-use or network history. Resume may remain unavailable until the phone can establish a
trustworthy current time. You can still choose **Complete** or **Withdraw** while the study is
paused.

**Complete** ends collection and keeps already collected encrypted data available for export.
**Withdraw** permanently ends the study but likewise preserves already collected encrypted data
until you delete it. Neither action sends an explanation to the researcher. In a study with
automatic upload, neither action cancels sending: data collected but not yet sent may still be sent.
Export if the research team should receive everything collected.

If Android access, storage, a required collector, or a continuous study function becomes unsafe,
Particeps closes admission and moves to the same generic paused experience. The participant UI
does not expose internal failure names. Check the ordinary Access step, then use **Resume**; if the
problem persists, contact the research team.

## Export and upload

**Export encrypted data** creates a `.partexp` bundle encrypted to the researcher public key in the
signed configuration. Particeps cannot decrypt it. A manual export contains the complete encrypted
research records still retained on the phone through the boundary captured when export began.

The app shows preparation, export progress, and final saving. You can still pause, complete, or
withdraw while an export runs. **Cancel export** stops that export without deleting the study's
local data. A slow storage provider may take time to finish cancelling. Wait for the success message
before sharing the file. To delete local study data, first finish or cancel the export.

If the signed study includes automatic upload, the app sends immutable encrypted chunks to that
configured HTTPS endpoint, starting after you press **Start study**. A delivered prefix can be removed locally only after an exact receipt
confirms the same encrypted bytes and complete range. Pausing stops collection but does not
erase existing encrypted data or cancel delivery that the signed study already disclosed.

The researcher may be able to link a personalized dataset through an opaque participant code in
the signed study. Particeps itself has no account, advertising ID, contacts integration, or device
identity field.

## Delete local data

After completion or withdrawal, **Delete local data** removes the signed configuration, encrypted
study data, pending upload material, and cached export metadata for that
study, and cancels any upload that has not happened yet. Android system backups are disabled for
these files. Data that was already sent cannot be deleted from the phone; to ask the research team
about it, contact them and give your installation code. Deleting also removes the installation code
and the researcher’s contact details from the phone, so note both from **Study and my data** before
you confirm.

Deletion is permanent. Export anything you want to keep before confirming it. The app does not
silently delete an incompatible or unreadable study; the existing generic recovery flow requires
your confirmation before destructive reset.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Google Play Protect blocks installation | Keep the warning and contact the research team with the app version and a screenshot. Wait for the team to resolve distribution or review with Google before installing. |
| Setup cannot finish | Open the existing Access step and satisfy every required item, or choose **Decline and remove this study** if you do not want to grant it. |
| Start or Resume returns to Paused | Recheck required Android access. A study that may adjust App transfer speed may also need local-network permission and Android VPN consent. |
| Another VPN stops working or the study pauses | Android permits only one active VPN for the same phone user. Choose which VPN to use; Particeps will not resume the study automatically. |
| A data category is unavailable | Check its Android access or hardware. Required categories stop the study; optional categories can remain off. |
| A study notification or survey is late | Android background scheduling is best effort. Do not treat delivery time as proof that the participant saw it. |
| Export takes a long time | Check the displayed phase and progress. You can still pause or withdraw, or cancel the export and retry with a local storage destination. |
| Export fails or is cancelled | The app keeps your study data and tries to remove the incomplete export. If it reports that the file could not be removed, delete that file before retrying. Only share an export after the success message. |
| Recovery asks for reset | Export information if the existing generic recovery flow offers it, then confirm only if you accept permanent removal of the incompatible local study. |

For protocol-level field definitions, data quality, and interpretation limits, researchers should
use the [data dictionary](data-dictionary.md), [Protocol v1](../protocol/v1/README.md), and
[threat model](threat-model.md). Those documents intentionally contain implementation detail that
this participant-facing guide omits.
