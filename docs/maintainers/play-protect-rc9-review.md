# RC9 Play Protect review evidence

Prepared 2026-09-09. Status: local investigation and submission draft only. No appeal or malware
analysis upload has been submitted as part of this investigation; installation remains blocked on
the reported device. Device model, Android version, and country/region have been requested but
are not yet known.

## Current disposition

On 2026-09-09 the study owner chose to remove direct cross-app notification collection while
continuing direct APK distribution. The RC10 source excludes that collector and its listener
service, and both five-day examples omit its required resource and binding. Particeps' own
questionnaire and ongoing research notifications remain supported. These changes do not provide
an acceptance verdict for the RC9 artifact described below.
RC10 remains a directly distributed signed APK. Browser installation on the affected physical
phone with Play Protect enabled has not yet been verified. Preserve the exact RC9 artifact and
this evidence when validating RC10; the submission text below remains an unsubmitted historical
RC9 draft.

## Observed result and interpretation

The participant's screenshot shows a browser download named `particeps-v1.0.0-rc.9.apk`, followed
by a Google Play Protect block warning about sensitive data, identity theft, or financial fraud.
It offers an acknowledgement button and does not show an installation override.

The wording matches Google's documented protection for apps downloaded through browsers,
messaging apps, or file managers that declare certain sensitive capabilities, including a
notification listener. Google states that this protection applies in selected markets and provides
a review route. The RC9 APK declares such a listener. This is a strong explanation for the report,
not access to Google's device-specific classification. See [Google's warning guidance](https://developers.google.com/android/play-protect/warning-dev-guidance).

## Exact artifact

| Field | Verified value |
| --- | --- |
| App / package | Particeps / `cool.jacoblin.particeps` |
| Version name / code | `1.0.0-rc.9` / `32` |
| Min / target SDK | `34` / `37` |
| Release commit | `2471938fa1c77a5a7fd8b933312e575d8dd89a99` |
| APK SHA-256 | `6f6957517a3fd9a774105c88952e0790a59a599c81216b8c93b9a723000443f1` |
| Signing certificate SHA-256 | `c2a2cde113ba53cafa2d111dc79698a4d2295256b1ad6e83e9acf3e8eb179384` |

[Published APK](https://github.com/JacobLinCool/particeps/releases/download/v1.0.0-rc.9/particeps-v1.0.0-rc.9.apk)
and [release source](https://github.com/JacobLinCool/particeps/tree/2471938fa1c77a5a7fd8b933312e575d8dd89a99)
identify the reviewed build. The downloaded APK matches its published checksum and the repository's
production signing certificate. RC8 uses that same certificate. These checks establish artifact
identity and signing continuity, not approval by Google.

## Relevant implementation

- The APK's `uses-permission` set is unchanged from RC8. RC9 adds
  `ResearchNotificationListenerService`, protected by the service's
  `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` attribute. This distinction matters:
  examining only the APK's `uses-permission` list misses the new capability.
- Android can deliver notification objects to the approved listener. The current callback accesses
  only `packageName`, `key`, and `postTime`; it does not access notification title, body, or extras.
  Emitted events contain the package, study-scoped HMAC identifier, posting time, and event clocks.
  The raw notification key is not written into the event.
- A selected active study collector must own the callback bridge before it admits observations.
  Pause and stop remove that owner; no notification backlog is requested. This runtime restriction
  does not remove the service declaration from the installed APK.
- The study's data categories and consent precede collection, and Android notification-listener
  access is a separate setup step. The notification collector's description says it records the
  source and time without notification text. Researcher-authored studies still need appropriate
  consent and data handling; this investigation is not a blanket policy certification of every study.

Relevant release-pinned evidence:

- [Service declaration](https://github.com/JacobLinCool/particeps/blob/2471938fa1c77a5a7fd8b933312e575d8dd89a99/collector/notification-events/src/main/AndroidManifest.xml)
- [Notification callback and emitted fields](https://github.com/JacobLinCool/particeps/blob/2471938fa1c77a5a7fd8b933312e575d8dd89a99/collector/notification-events/src/main/kotlin/cool/jacoblin/particeps/collector/notificationevents/NotificationEventsCollector.kt)
- [Active collector ownership](https://github.com/JacobLinCool/particeps/blob/2471938fa1c77a5a7fd8b933312e575d8dd89a99/collector/notification-events/src/main/kotlin/cool/jacoblin/particeps/collector/notificationevents/NotificationObservationBridge.kt)
- [Android metadata and pause tests](https://github.com/JacobLinCool/particeps/blob/2471938fa1c77a5a7fd8b933312e575d8dd89a99/app/src/androidTest/kotlin/cool/jacoblin/particeps/StudySourcesAndroidTest.kt)

## Submission preparation

The official [Play Protect appeal form](https://support.google.com/googleplay/android-developer/contact/protectappeals)
requests a contact email, package name, APK SHA-256 referring to a VirusTotal-uploaded APK, and
supporting information. Confirm the contact email and whether this exact APK already has a
VirusTotal report before submitting. Do not substitute the certificate digest for the APK digest.
No VirusTotal report or detection verdict has been verified here. The form currently states that
appeal decisions are final and a response will not be provided; do not promise an email reply or
a review completion date.

The maintainer must review the app against Google's [Mobile Unwanted Software principles](https://developers.google.com/android/play-protect/mobile-unwanted-software)
and warning guidance before submission. Complete the evidence with the affected device details
and reproduce the browser installation path. Preserve the production signing identity. Resolve the
remaining distribution issues through review if they reproduce in the newly built artifact.
The study owner has authorized removing notification collection; preserve the RC9 evidence when
validating the replacement artifact. Participant instructions should direct people to the research
team when blocked, rather than ask them to disable device protection.

## Supporting-information draft

Please review the Play Protect installation block reported for Particeps,
package `cool.jacoblin.particeps`, version `1.0.0-rc.9` (version code 32), APK SHA-256
`6f6957517a3fd9a774105c88952e0790a59a599c81216b8c93b9a723000443f1`.
The warning shown during browser-based installation refers to sensitive data and the risk of
identity theft or financial fraud. The published APK and release-pinned implementation links are
provided above for examination.

Particeps is an open-source Android research data-collection application. RC9 adds a
NotificationListenerService for recording notification receipt events in participant-authorized
studies. The notification collector records the posting package, posting time, event receipt time,
and a study-scoped HMAC identifier. Its callback does not access notification title, body, or extras.
It does not request a notification backlog or suppress notifications. The implementation and its
Android tests are linked above, including a test that posts private title/body text and verifies
that the event contains only the declared metadata.

Collection is selected by a signed study, shown in the study data categories, and subject to
participant consent and Android's separate notification-access grant. The active collector owns
the observation callback, and pausing or stopping removes that callback. The wider notification
capability remains declared in the APK even when a particular study does not collect notifications.

The APK uses the same production signing certificate as RC8. We request review of the blocked
artifact and its declared notification-listener use for research notification metadata. We do not
claim that successful APK signing or our tests constitute Play Protect approval.
