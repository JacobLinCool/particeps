# Participant guide

Particeps is a research data collection app for Android. It collects and stores everything on your own phone. Every study you import shows you its title, research team, purpose, duration, contact details, data sources, whether it sends data to the research team automatically, and consent text — all before anything is collected.

The name is a Latin word. It means someone who takes part. That is meant concretely here: your data stays on the phone, every source a study uses is shown to you before you are asked to consent, and nothing at all is collected until you press Start study.

It does not mean the study is yours to design. Which sources it may use, how long it runs, and whether it sends data to the research team automatically are fixed in the signed file you import, and nothing you do in the app changes them. You can read all of that before you agree, and you can say no to the whole study. Within a study, the only sources you can hold back are the ones it marks optional: declining the Android access an optional source needs, or not enabling the research keyboard, leaves that source off and the study runs without it. Section 4 covers what each source asks for and section 5 covers the keyboard; a source the study marks required stops the study instead of running without it. Data that has already reached the research team cannot be taken back.

What you can always do is decline, pause, finish early, withdraw, and — once you have finished or withdrawn — permanently delete the study data on your phone. The app never asks why.

This guide describes what the app does on your phone. It does not replace the consent document approved for your study. Where this guide and that document disagree, the consent document and your research team's answers come first. If anything is unclear, do not start. Ask first.

Declining is a complete answer. You do not need a reason, and you do not need to tell anyone why.

## The app's on-screen language

The app ships in **English and Traditional Chinese**, and English is the default. On first run it follows your phone's system language: a phone set to Traditional Chinese shows the app in Traditional Chinese, and anything else gets English.

You can change it at any time, before or after you import a study. At the top right of the header there is a **globe** — a circle with a horizontal line across it and a curved meridian from top to bottom. Tap it and a picker opens, headed Language (語言). It lists **System default** (跟隨系統) first, then each language this build ships, each one written in its own language, so you can find yours without being able to read the language currently on screen. A check mark sits beside the one in use. Tapping a language applies it immediately and closes the picker; Cancel (取消) closes it without changing anything.

That picker is not a private setting inside the app. It writes Android's own per-app language setting, the same one under Android Settings → Apps → Particeps → Language, so changing it in either place changes both. System default (跟隨系統) hands the choice back to your phone.

Because English is the default, this guide quotes the screen in English and gives the Traditional Chinese in parentheses where you might be running it: Start study (開始研究).

**Ordinary study prose is never translated.** The study title, purpose, contact details, and consent text are shown exactly as signed. Survey titles, descriptions, questions, and choices are different: the signed configuration can carry an English and Traditional Chinese version, and the survey uses the best exact language match with the signed default as its fallback.

Two pieces of text stay in English whichever language you pick, because they are written into the code rather than into the translated set:

- The ongoing notification shown while a study is collecting: "Research collection active", followed by the study title.
- The banner on the research keyboard: "Research touch capture active" or "Touch capture disabled for this field".

The interface also says some things without words: a check mark, a coloured dot, a row of five dots, a progress bar, a set of small drawn icons. The app draws all of these itself rather than taking them from an icon set, so this guide describes what each one looks like, and you can match it against the screen in front of you.

Every word of the interface lives in [`app/src/main/res/values/strings.xml`](../app/src/main/res/values/strings.xml) (English) and [`app/src/main/res/values-zh-rTW/strings.xml`](../app/src/main/res/values-zh-rTW/strings.xml) (Traditional Chinese), so anyone can read those two files and check every label in this guide against them.

## What matters most

- Importing a study configuration collects nothing. Collection starts only after you consent, complete the access setup, and press Start study (開始研究).
- **Before you are asked to consent, the app shows you every source the study switched on**, one row each, with a sentence describing what it records — written by the app, with that study's actual settings filled in, not by the research team. Section 3 is that list.
- **The consent screen shows you a fingerprint of the key the study was signed with, and asks you to check it.** For most studies the app cannot tell who published the study — that is ordinary, not a fault — so the fingerprint is what ties a study to a real research team. Section 2 shows the exact screen.
- Data is encrypted and kept on your phone. It stays there unless you export and send it yourself, or the study you imported says it sends data automatically.
- **Whether a study sends data automatically is shown to you before you consent.** If it does, the consent screen carries a block naming where it sends to, how often, and which networks it may use. If there is no such block, the study does not send anything. Section 2 shows you the exact screen.
- A study that sends automatically sends the same encrypted package you would export by hand. Only the research team's own key can open it — not the company that runs the network, not whoever runs the receiving computer.
- Automatic sending is part of the study, not a separate setting you can switch off on its own. Pausing, finishing and withdrawing all stop collection, but data already collected is still sent afterwards. Deleting the local data is what stops that. Section 7 sets out exactly what each one does.
- In a study that sends data automatically, your phone keeps its own copy too, so you can still export it yourself. If the space the study is allowed runs low, the phone may remove events the research team has already received — never anything still waiting to be sent — and the app tells you when that has happened. Section 6 explains it.
- You can pause, resume, finish early, or withdraw. You never have to explain the reason to the app.
- You can export your data while the study is Collecting (收集中), Paused (已暫停), Completed (已完成), or Withdrawn (已退出), and you can export as many times as you like.
- Exporting does not change the study's state and does not mean the research team has received anything. You choose whether and how to send the file.
- After finishing or withdrawing, you can permanently delete the study data on your phone. That does not delete export files you already saved elsewhere or already sent to someone.

## 1. Install only a version you trust

Use only the official installation source your research team gives you, and check that the app name and study description match what they told you. Do not install an APK from an unknown source, one that asks you to turn off Android security features, or one that asks for account passwords. This app does not ask for your passwords.

A study configuration file usually ends in `.partcfg`. When you import one, the app checks the signature on the file, its Android platform target, the validity period, the minimum client build, and the full structure of the file. If any check fails, the app stops. It does not fall back to a permissive mode and it does not collect anything. If that happens, ask your research team for a correct file rather than trying to work around it.

That signature check tells you the file has not been altered since it was signed. It does not, on its own, tell you who wrote it — the next section explains what the app shows you instead, and what you can do about it.

**A released app has no demo study.** The only thing it can run is a configuration a research team signed and gave you. If you see a Load demo study (載入展示研究) button under Choose a study file (選擇設定檔), you are running a development build, not a release: that study's signing key and data-encryption key are public test fixtures committed to [`researcher-tools/examples`](../researcher-tools/examples), so anyone with the repository can decrypt what it collects. Do not use it for anything real about yourself.

## 2. Import and read

The setup is five steps, and the screen shows **one** of them at a time. The header holds the study title and, underneath it, a row of five dots telling you which step you are on. Everything else on screen belongs to the step you are reading.

1. Open the app. Before any study is imported the header shows the app's own name, Particeps, and the panel below has one button: Choose a study file (選擇設定檔).
2. Pick the `.partcfg` file your research team gave you, using Android's file picker. The header title changes to the study's own title.
3. **Study.** The panel shows the study's purpose in the research team's own words, then three rows, each an icon and a value with no label: a head and shoulders for the research team's name, an envelope for their contact details, a clock for how long the study runs. Read all four before you go on. Press Continue (繼續).
4. **Data.** Every source this study switched on, one row each, with a sentence saying what it records and a second line saying what it does not. Section 3 goes through them. Press Continue (繼續).
5. **Consent.** The research team's consent text, then two blocks the app writes itself: who signed the study, and whether it sends data automatically. Read all of it: the data, the purpose, the risks, the export, the withdrawal, and the deletion terms. Only if you understand it and want to take part, tick "I have read and agree to the data collection and export described above." (我已閱讀並同意上述資料收集與匯出方式。) and press Agree (同意).
6. **Access.** Grant what the study needs. Section 4 goes through it. Press Done (完成).
7. **Start.** Press Start study (開始研究). This is the press that begins collection; section 6 describes what happens next.

At every one of these steps you can stop. Closing the app after importing leaves the study sitting there, unstarted, with nothing collected. Withdraw (退出研究) sits at the bottom of every panel except the Data one, so you can leave from almost anywhere in the setup without starting.

If the content does not match what the research team told you, if the contact details do not work, or if it asks for more data than you expected, stop and ask. Do not grant access first and sort it out later.

Two of these presses are not the same kind of thing. Continue (繼續) on the **Study** step is what moves the study forward internally, from imported through verified to awaiting consent. Continue (繼續) on the **Data** step only turns the page: the study's state does not change, because reading the list of sources and agreeing to it are one decision as far as the app is concerned, shown to you as two pages. One consequence is worth knowing: if you leave the app while you are on the Consent page and come back, you land on the Data page again rather than on the checkbox.

### Where you are in the setup

Under the study title, during setup, the app draws five dots left to right, joined by a line: Study, Data, Consent, Access, Start. A step you have finished is a filled check mark, the step you are on is a thick ring, and steps still ahead are faint thin rings. The line between them fills in as you go. The names are not printed — the dots give the position and the panel below gives the content — but a screen reader announces the name of the step you are on. Nothing on this row is a button; it tells you how much of the setup is left.

Once setup is over, the dots are gone for good and that same place shows the study's status instead. Section 6 describes it.

### Who signed the study

Above the agreement checkbox, the consent step shows a block headed Configuration signature (設定檔簽章). Beside that heading is a mark: a check mark if this build of the app has built-in trust for the signer, a solid red dot if it does not.

Directly under the heading, on its own, is the key fingerprint: eight groups of four characters, such as `9D0D AE5A 0D20 B29F D642 942A 0E17 4AAE`. It is a short summary of the key the study was signed with, and a different key produces a different one. This is the line you compare.

Underneath, the block says one of two things.

Most studies show an instruction, then the reason for it in smaller grey type:

> Check this against the fingerprint your research team published.
>
> (請與研究團隊公佈的金鑰指紋核對。)

> A signature shows the file has not been altered since it was signed. It does not show who wrote it.
>
> (簽章證明設定檔在簽署後未被竄改，但不能證明是誰寫的。)

**Nothing here is in red, and that is deliberate.** A study whose signer the app does not recognise is the ordinary case, not a fault — so the block reads as something for you to do, not as an alarm. Red in this app is reserved for a source that has actually stopped working. If the fingerprints do not match, or your research team never published one, that is when to stop and ask.

**Seeing this is normal.** It is what the app shows for any study whose signer is not built into the app, which is most of them. It is not a warning that this particular study is fake. What it means is that the app cannot do this check for you, so you do it: your research team should have given you the fingerprint in the study information sheet, the consent document, or wherever they recruited you. Compare the two. If they match, the file came from whoever holds that key. If they do not match, or you were never given a fingerprint, stop and ask your research team before consenting.

The other possibility is a version of the app built by an institution to run only its own studies. It shows:

> This app trusts this signer.
>
> (這個 App 內建信任此簽章者。)

In that case the check has already been done for you, at the time the app was built.

Nothing in the researcher name, contact details, or study description proves who wrote them — they are part of the file, so whoever signed the file chose them. The fingerprint is the part you can check independently.

### Your study codes

The consent step also says whether the study is **Anonymous or pseudonymous** (匿名或假名研究) or **Personalized** (個人化研究). An anonymous configuration contains no code assigned by the research team. A personalized configuration shows the exact opaque code embedded in your signed file; compare it with the code the team gave you. The app never asks you to enter a name, email address, or phone number.

Every import also creates a fresh random installation code. Importing the same configuration twice therefore produces two different installation codes. A personalized export contains both codes inside its encrypted body. Automatic upload headers contain neither code, nor the study or configuration identifier; they contain only bundle-level routing claims. Treat either code as linkable study data after decryption even though neither is required to be a name.

### Whether the study sends data automatically

The consent step always answers this, in a block above the agreement checkbox. A study that sends nothing says so, under a check mark:

| Line | What it tells you |
| --- | --- |
| This study does not send data automatically (這個研究不會自動傳送資料) | Nothing leaves the phone on its own |
| What it collects stays on this phone until you export it yourself. (資料只留在這支手機，直到你自己匯出。) | What it collects stays on this phone until you export it yourself |

A study that does send data shows a block headed This study sends data automatically (這個研究會自動傳送資料) instead, marked with two arrows pointing opposite ways, and stating, in this order:

| Line | What it tells you |
| --- | --- |
| To ⋯ (傳送對象：⋯) | The site the data goes to, shown as a host name such as `study.example.org` |
| ⋯, ⋯ (⋯，⋯) | How often it tries — About every N minutes (約每 N 分鐘), About every N hours (約每 N 小時), or About every N days (約每 N 天) — then a comma, then Wi-Fi only (僅在 Wi-Fi 下) or Wi-Fi or mobile data (Wi-Fi 或行動網路) |
| Only the research team's key can open what is sent. Neither the network it travels over nor the server that receives it can read the contents. | Only the research team's key can open what is sent; neither the network it travels over nor the server that receives it can read the contents |
| A randomly generated code travels with the data so the team can tell participants apart. It contains no name and no account. | A random code is attached so the team can tell participants apart. It contains no name and no account |
| Automatic sending is part of this study and cannot be switched off on its own. Pausing or withdrawing stops new collection, but data already collected and not yet sent still goes to the research team. | Exactly that, and section 7 sets out what each control does |

The app builds this block from the signed configuration itself, not from the research team's written summary, so it describes what the app will actually do even if the summary leaves it out. If it says something the research team did not tell you, that is a reason to stop and ask before consenting.

Sending happens in the background. It does not need you to do anything, and your phone keeps its own copy, so you can still export by hand at any time. If the space the study is allowed runs low, the phone frees some by removing events the research team has already received; section 6 describes exactly what that looks like on screen.

One detail the block states briefly and section 7 explains in full: pausing, finishing and withdrawing all stop *collection*, but data already collected and not yet sent still goes to the research team afterwards. The only thing that stops that is deleting the local study data, which is offered once the study has finished or you have withdrawn.

## 3. What a study can collect

The app starts only the collectors listed in that study's signed configuration. It cannot add others later without a new configuration that you import yourself.

The **Data** step, step 2 of the setup, lists every one of them before you are asked to agree to anything. Each row is an icon, the source's name, sometimes the word Optional (選用), and one sentence about what it records.

That sentence comes from the app, not from the research team. It is a template with that study's own settings filled into it, so a study that samples your location every ten seconds and one that samples it every ten minutes do not read the same. No field in a configuration can change the wording.

The screen also names selected limits the implementation can guarantee, such as omitted battery identity or inference labels. Those short statements are not an exhaustive threat model; use the table below and the [data dictionary](data-dictionary.md) for the complete field definitions and interpretation limits, which are written against the same source the app runs.

| Row | The sentence it shows |
| --- | --- |
| **App activity** (App 使用狀況) — a rounded square with a dot in the middle | When this app itself is opened and closed (這個 App 本身何時被開啟與關閉) |
| **Motion** (動作) — a wave, two crests around a centre line | Movement of the phone, about **N** times per second or more (手機的移動，每秒約 **N** 次或更多) |
| **Battery context** (電池情境) — opposing data arrows | Whole percentage, charging state/source, and power-save mode; not health, temperature, or hardware identity (整數電量、充電狀態與來源、省電模式；不含健康、溫度或硬體識別碼) |
| **Time context** (時間情境) — a clock | Time-zone setting, UTC offset, daylight-saving state, and clock changes; not a location or travel claim (時區、UTC 偏移、日光節約與時鐘變更；不代表位置或旅行) |
| **Phone rotation** (手機旋轉) — the motion wave | Raw three-axis rotation, about **N** times per second or more; no orientation or activity labels (三軸旋轉，每秒約 **N** 次以上；不含方向或活動標記) |
| **Ambient light** (環境光線) — the app-shaped sensor mark | Raw light level within the configured interval and threshold; no environmental content (依設定間隔與門檻記錄原始照度；不含環境內容) |
| **Proximity sensor** (接近感測器) — the connection arcs | Raw near/distance state within the configured interval and threshold; near/far transitions are recorded even below the distance threshold, and many phones report only near/far (依設定間隔與門檻記錄遠近／距離；遠近狀態切換不受距離門檻限制，且許多手機只能回報近或遠) |
| **Connection type** (連線類型) — three rising arcs over a dot | Whether you are on Wi-Fi or mobile data, and whether it is metered (你目前是 Wi-Fi 還是行動網路，以及是否計費) |
| **Data volume** (流量) — two arrows side by side, one up and one down | Total bytes your phone sent and received, every **T** (手機每 **T** 傳送與接收的總位元組數) |
| **App and screen use** (App 與螢幕使用) — a phone outline with a short bar near its foot | Which apps open and close, and when the screen turns on, every **T** (每 **T** 記錄哪些 App 被開啟關閉、螢幕何時亮起) |
| **Location** (位置) — a map pin, a teardrop with a filled dot inside | Where the phone is, about every **T**, after it moves at least **D** (手機所在位置，約每 **T** 一次，且移動超過 **D** 才記錄) |
| **Keyboard touch** (鍵盤觸控) — a wide rounded outline with three dots in a row and a bar beneath them | How you touch the keys — position, timing, pressure — inside the research keyboard only (你按鍵的方式——位置、時間、力道——僅限研究鍵盤內) |

The values written into those sentences:

- **N** is how many readings per second the study asked for. If it asked for one, the English sentence reads "about once a second or more". **"or more" is not hedging.** Android treats a study's sampling rate as a request, not a limit, and a phone is free to deliver faster — several times faster on some devices — so the rate on screen is a floor, not a ceiling.
- **T** is an interval, written in the largest unit that stays exact: 30 s (30 秒), 15 min (15 分鐘), 2 h (2 小時), 1 day (1 天).
- **D** is a distance in whole metres: 25 m (25 公尺).

Optional (選用) beside a name means the study can start without the Android access that source needs. If you leave that access off, that source alone reports `ACCESS_UNAVAILABLE` on the collecting screen and every other source carries on. Leaving one off is a normal choice, not an error. A source without that word needs its access granted before the study can start at all.

The sentences are short because they are the summary. This is what each source actually puts in the file:

| Source | What can be stored | Important limits |
| --- | --- | --- |
| App activity | Lifecycle timings of this app's own screens | Not your use of any other app |
| Motion | Raw x/y/z acceleration from your phone, with timing and an accuracy status | Can be used to study movement or posture, but the app does not label it for you |
| Battery state | Whole percentage, charging state/source, and power-save mode | No health, temperature, capacity, serial, or hardware ID |
| Time context | Time-zone ID, UTC offset, DST state, and clock-change reason | A time-zone setting is not physical location or travel evidence |
| Rotation | Raw x/y/z angular velocity, timing, and accuracy status | No derived orientation, posture, activity, or gesture labels |
| Ambient light | Raw illuminance, timing, and accuracy status | No image or environmental content; values vary by hardware |
| Proximity | Raw distance/range and the phone's near/far interpretation | Many devices are binary; it does not prove presence or comparable distance |
| Connection type | Wi-Fi/mobile/ethernet/VPN, whether the connection is validated/metered/roaming, bandwidth estimates | No Wi-Fi network names, IP addresses, web addresses, packets, or content |
| Data volume | Total bytes and packets your device sent and received over Wi-Fi and mobile data during a time window | Coarse and possibly delayed; not per-app and not per-website usage |
| App and screen use | Package names, app resumed/paused/stopped, screen, keyguard (lock screen), and boot/shutdown events | Android's own record of these can be delayed or incomplete |
| Location | Latitude and longitude, source time, accuracy, speed, altitude, bearing, and a mock-location flag | Can be inaccurate or have gaps, depending on your phone and surroundings |
| Keyboard touch | Position within a key, timing, pressure, touch size, orientation, tool type, and key category | Does not store the actual characters or text, but the touch pattern itself still carries privacy risk |

The same names and the same icons come back on the collecting screen, one row per source. Internal identifiers such as `accelerometer.v1` are not shown to you anywhere in the app; they exist inside the exported file and in what your research team works with.

## 4. Access setup

You cannot start until every required item is granted. Optional items can be left off: the source that needs one reports `ACCESS_UNAVAILABLE` on the collecting screen, and the other sources keep working.

The access list is one row per item, separated by thin lines. Each row has a mark on the left, the name of the access, and sometimes a word on the right:

| What the row shows | What it means |
| --- | --- |
| A check mark | Granted |
| A solid red dot | Required, and not granted yet. You cannot start until it is |
| A hollow ring, with Optional (選用) at the end of the row | Optional, and not granted yet. You can start without it |

Tap anywhere on a row that is not granted yet and the app sends you straight to the Android screen that grants it. Finish there, come back to the app, and the mark on that row changes. When no required row is left, press Done (完成).

### Notifications (通知)

Used for three things: scheduled study activities, the notification that stays visible while collection is running, and a once-a-day reminder of where the study stands. Activities use Android's background work system, which is not an exact alarm: battery saving, Doze, or system scheduling can delay them.

The daily reminder says either that the study is still collecting, or that it is paused and since when. It exists for the second case: a pause changes nothing else on the phone, so a study you meant to resume can sit stopped for weeks without anything saying so. It is a quiet notification — no sound — and it names only the app and the state, never the study, so it discloses nothing to someone glancing at your lock screen. It stops when the study finishes or you withdraw.

### Sensor hardware (感測器硬體) and basic network state

Accelerometer, gyroscope, ambient-light, and proximity items are hardware checks, not permission
dialogs, so their rows do nothing when tapped. A required source blocks enrollment when its sensor
is absent; an optional source remains off. Connection type uses only Android's ordinary
network-state permission and has no row of its own.

### Usage access (使用情況存取權)

Data volume and app and screen use need "Usage access" — a special Android setting that lets an app see which apps have been in the foreground and how much data the device has used. You grant it on an Android system screen, not inside this app, and Android controls that screen.

You can turn it back off later. The affected source then stops receiving data and reports a problem. It does not invent replacement values for the gap.

### Precise location (精確位置) and Background location (背景位置)

Android may ask about precise location and background location separately. These are requested only if the study actually lists a location source. Continuous background collection is accompanied by a visible foreground-service notification — a notification Android requires an app to show while it does ongoing work — so you can see when collection is running. You can decline optional location, or stop it later with pause or withdrawal.

### Enable the research keyboard (啟用研究鍵盤) and Select the research keyboard (選用研究鍵盤)

The research keyboard is an Android input method (a keyboard app, the same kind of component as any third-party keyboard you might install). Turning it on takes two deliberate actions from you: enable it in Android's system settings, then select it in the keyboard picker.

Only touches on this keyboard's own surface are visible to it. The app does not use an Accessibility Service — the Android feature that can observe activity across all apps — to watch touches anywhere else. Read section 5 before you enable it.

## 5. Important warning about the research keyboard

Research events from the keyboard do not include the actual key identity, the text you submitted, the surrounding text, the clipboard, or suggestions. When a field is a password field, or an app marks a field as private or as no-personalized-learning, touch collection is switched off for that field and the keyboard shows "Touch capture disabled for this field".

**"No text" does not mean "no risk."** The key category, the relative position of your touch, and the timing can still reveal patterns in what you type. A skilled analyst working with such data can infer more than a list of field names suggests. Third-party apps also sometimes fail to mark sensitive fields correctly, and when that happens, the field is not protected by the rule above — the app has no way to detect the mistake.

If you are not comfortable with this, any of these is a legitimate choice:

- Do not enable the optional research keyboard.
- Switch back to your normal keyboard before typing anything sensitive.
- Pause the study.
- Withdraw from the study and contact the research team.

The research keyboard is a basic English-letter QWERTY layout. It is not a password manager and not a full multilingual everyday keyboard. Do not rely on it as your only keyboard.

## 6. Starting a study

On the last setup step the fifth dot is the current one and the panel holds two things: Start study (開始研究) and Withdraw (退出研究). Nothing is collected in this state, and the app prints no state name for it — during setup the header shows your position, not a status. Collection begins only when you press Start study (開始研究).

The moment you do, the five dots are replaced, permanently, by a status line, and the panel below becomes the collecting screen.

While a study is collecting:

- Continuous sources run under a visible research foreground service, so the ongoing "Research collection active" notification is present the whole time.
- Signed interventions are scheduled as one-time, repeating-interval, or daily local-time activities. A schedule may count calendar time or only time spent actively collecting.
- When the study's duration is reached, the system completes a collecting or paused study for you. Battery-saving scheduling can delay this.

After you restart your phone, only a study that was Collecting (收集中) tries to resume. If you force stop the app, Android may block its work until you open it again. Open the app and check the status line if you are unsure.

### Scheduled activities and surveys

Each scheduled activity has a durable occurrence identity. Restarting the phone, reopening the app, changing time zone, pausing, or recovering WorkManager reschedules that same occurrence instead of creating another one. Android can deliver a notification late, but the app records separately when it was scheduled, posted, opened, submitted, or expired; a notification being posted is not evidence that you saw it.

Some studies use signed random local-time windows. The phone chooses an instant locally and stores
it before scheduling; a retry or reboot does not draw a new time, and the research team cannot send
a remote trigger. The configuration fixes the windows, limits, and minimum spacing.

Tapping a survey notification opens exactly that occurrence. Surveys are native app screens with screen-reader labels, progress, required/optional indicators, and four answer types: short text, numeric scale, one choice, or multiple choices. Closing before submission stores no research answer or draft. Reopening returns to the same unanswered survey. Submission requires confirmation and is atomic: after one successful submission, the answer is read-only and cannot be edited or submitted again, even after restart or competing taps. An expired occurrence cannot be submitted.

### The status line

Under the study title, where the dots used to be, there are three things:

1. **A coloured dot.** Teal while the study is Collecting; grey for every other status.
2. **The status name**, in bold: Collecting (收集中), Paused (已暫停), Completed (已完成), or Withdrawn (已退出). These four are the only status names the app ever prints.
3. **How long it has been**, since collection first started: 5m (5 分) under an hour, 2h 13m (2 小時 13 分) under a day, 3d 4h (3 天 4 小時) beyond that. It refreshes about every half minute.

That third figure is time **since the study first started**, not time spent collecting: it keeps counting while you are paused. Once the study is completed or withdrawn it stops, so what you are left looking at is how long the study ran from first start to end. A study you withdrew from before ever starting has no such figure, so the line is just a grey dot and Withdrawn (已退出).

### The collecting screen, top to bottom

One panel, in this order: the sources, the counter, the controls, export, delete if the study has ended, withdraw, and a chevron.

**The sources.** One row each, in the order the study lists them: a coloured dot, the source's icon, and its name — the same names and icons as the Data step in section 3. Teal means it is collecting, grey means it is stopped or paused, red means it needs your attention.

A source that is behaving gets no words, only its dot. When one needs attention, **a raw code appears in red at the end of its row** — `ACCESS_UNAVAILABLE` when the access it needs is not granted, or a code such as `STORAGE_WRITE_FAILED`, `USAGE_ACCESS_REVOKED`, or `NETWORK_STATS_QUERY_FAILED` when something else went wrong. The app does not translate these or soften them, in either language. They are for quoting to your research team, and they contain none of your collected data.

**The counter and the sending bar.** Under the sources is the number of events recorded and encrypted so far, across all sources, written as 1,234 events (1,234 筆).

If the study sends data automatically and at least one event exists, the right-hand end of that same line shows 1,200 sent (已送出 1,200) — how many of those events the research team has confirmed receiving — and a bar underneath fills in the same proportion. An empty bar means nothing has been confirmed yet; a full bar means everything collected so far has arrived. In a study that does not send automatically, there is no bar and no sent figure.

When a send attempt fails, a code in red replaces the sent figure:

| Code | What it usually means |
| --- | --- |
| `UPLOAD_TIMEOUT` | The connection was too slow or stopped responding |
| `UPLOAD_HOST_UNRESOLVED` | The phone could not look up the address, often because it has no working internet connection |
| `UPLOAD_CONNECT_REFUSED` | Nothing accepted the connection at that address |
| `UPLOAD_TLS_HANDSHAKE_FAILED` or `UPLOAD_TLS_FAILED` | The encrypted connection could not be established |
| `UPLOAD_INTERRUPTED` | The attempt was cut short before it finished |
| `UPLOAD_IO_FAILED` | The connection broke partway through |
| `UPLOAD_HTTP_<status>` | The receiving server answered with an error, for example `UPLOAD_HTTP_503` |
| `UPLOAD_FAILED` | Anything else |

**A send failure does not stop collection.** The study keeps collecting and your phone keeps the
staged encrypted package. Connection failures and server-busy responses are retried using exactly
the same bytes, so the receiver can recognize a replay even when its success response was lost.
A redirect, an incomplete-success response, most other client-error responses, or a mismatched
receipt is recorded as a terminal delivery error instead of being retried forever. The app does
not mark those events sent or silently discard them; the research team must correct its study or
receiver setup.

You do not need to interpret the code. If one is still on screen after several days, quote it to your research team as it appears; it tells them where the attempt failed and contains none of your collected data.

**The controls.** While the study is collecting, Pause (暫停) and Finish early (提早完成) sit side by side; while it is paused, Resume (繼續收集) takes the place of Pause. Once the study is completed or withdrawn, neither is there. Below them, Export encrypted data (匯出加密資料) is always available. Delete local data (刪除本機資料) appears only after the study is completed or withdrawn. Withdraw (退出研究) is last, and is there for as long as you have not already withdrawn. Section 7 covers all of them.

**The chevron.** At the very bottom, a small arrowhead pointing down, centred, with nothing beside it. Tap it and the technical identifiers unfold above it; the arrowhead flips to point up, and tapping again folds them away.

### The technical identifiers behind the chevron

Four lines, each a label and a value:

- Configuration (設定) — the configuration's identifier.
- Consent document (同意書) — the version of the consent text you agreed to.
- Signature (簽章) — the signer's fingerprint, the same one you compared on the consent step.
- Last export (上次匯出) — only after you have exported at least once, as 1234 events · a2c3f1b90de4 (1234 筆 · a2c3f1b90de4).

All of it is for telling your research team exactly which study and which file you have. Note that these are only reachable **after** setup is over: during setup, the fingerprint on the consent step is the only identifier on screen.

### When space on your phone runs low

Every study is allowed a fixed amount of space on your phone, set in its configuration. In a study that sends data automatically, the phone makes room when that allowance is nearly full, by removing the oldest events the research team has already confirmed receiving. Two things are always true:

- Nothing that has not yet been sent is ever removed. If there is nothing removable, the study stops collecting and reports a storage problem instead, which you can see on the collecting screen.
- Nothing is removed quietly. When it has happened, a line appears under the counter: The first N events have been sent and removed from this phone. A later export will not include them. (前 N 筆已傳送並從手機移除，之後的匯出不會包含這些事件。)

Many studies never reach that point and keep everything until you delete it. When it does happen, those events are not lost from the study — the research team already has them. What changes for you is that an export you make afterwards covers only the events still on the phone, starting from the point that message names. If you want your own copy of everything, export before that happens and keep the file.

## 7. Staying in control

You can use these controls at any time, for any reason. The app does not ask why and does not record a reason.

In a study that sends data automatically, this is what each control does to the sending:

| Control | Collection | Automatic sending |
| --- | --- | --- |
| Pause (暫停) | Stops until you resume | Continues, for data collected before the pause |
| Finish early (提早完成) | Stops permanently | Continues until everything already collected has been sent, then stops on its own |
| Withdraw (退出研究) | Stops permanently | Continues until everything already collected has been sent, then stops on its own |
| Delete local data (刪除本機資料) | Already stopped | Stops immediately; anything not yet sent is destroyed with the rest |

Finishing or withdrawing does not strand data the research team was already owed: the app keeps sending what it collected before you stopped, and gives up scheduling once there is nothing left. If you would rather it not be sent, delete the local study data — that is offered once you have finished or withdrawn, and it removes what has not gone out yet.

### Pause and resume

When you press Pause (暫停), the app writes a pause boundary, stops the sources, and flushes events that were already queued before that boundary. Once the status line shows Paused (已暫停), no new study events are accepted for the period you are paused. The line under it tells you when the pause started and how long it has lasted. Data already collected stays on your phone, encrypted. The ongoing notification and any visible scheduled-activity notification go away, and the daily reminder starts saying you are paused instead. A survey cannot be opened or submitted while paused.

Pressing Resume (繼續收集) starts a new collection interval. Data volume and app and screen use are not backfilled: the app does not go back and collect what happened while you were paused. Calendar time and scheduled-activity availability still pass during a pause, so the app expires missed activities and reconciles any remaining ones when you resume.

### Finish early

Finish early (提早完成) stops collection permanently and moves the study to Completed (已完成). The confirmation dialog is headed Finish this study early? (提早完成研究？) and says: You cannot restart it afterwards, but you can still export as many times as you like. (完成後不能重新開始，但仍可重複匯出。) Its buttons are Confirm (確認) and Cancel (取消). This cannot be reversed. The data you already have can still be exported as many times as you want, or deleted.

### Withdraw

Withdraw (退出研究) is available at every stage after import except the Data step of the setup, including before you have started collecting. It stops collection permanently and moves the study to Withdrawn (已退出). The confirmation dialog is headed Withdraw from this study? (確定退出研究？) and says: All collection stops permanently. You can still export what was already collected, until you delete it. (所有收集會永久停止；刪除前仍可匯出既有資料。)

Withdrawing does not automatically delete your data, so that you keep the choice between exporting first and deleting straight away.

**Withdrawal does not recall copies that already left your phone.** It does not retrieve files you already saved elsewhere, and it does not delete any copy the research team already received — whether you sent it yourself or the study sent it automatically. If you want the research side to delete your data, you have to ask them, using the contact details in the consent document. Whether and how they can do that is governed by that document and by their ethics approval, not by this app.

## 8. Exporting, whenever you want

Whenever the status is Collecting (收集中), Paused (已暫停), Completed (已完成), or Withdrawn (已退出), the screen shows Export encrypted data (匯出加密資料). The file it writes is encrypted with your research team's public key.

1. Press Export encrypted data (匯出加密資料).
2. Choose a location and file name in Android's file picker. The app suggests a name ending in `.partexp`.
3. Wait for the app to report completion. It shows the code `EXPORT_COMPLETE` in a band under the header. The export's own record is behind the chevron at the bottom of the screen, as Last export (上次匯出) followed by the number of events and the first twelve characters of the file's SHA-256 digest — a fingerprint of the file's exact contents, which the research team can compare against the file they receive to confirm nothing was altered or truncated in transit.
4. Share the `.partexp` file only in the way your study's consent document approves.

The export file is encrypted with the researcher public key contained in that study's signed configuration. An ordinary file manager cannot show you its contents, and neither can anyone else who does not hold the matching private key. Each export uses a fresh random encryption key.

If you export while the study is still collecting, the app takes a consistent snapshot at that moment and then carries on collecting. A later export contains newer events, and normally repeats the older ones as well. That is the intended design, not a duplicate-file bug. The exception is a study that sends data automatically and has had to make room: an export covers only the events still on the phone, so it will not repeat ones that were already sent and removed. Section 6 describes the message that tells you this has happened.

Exporting:

- Does not set the status to "exported". There is no such status.
- Does not pause, complete, or withdraw the study.
- Does not delete anything from your phone.
- Does not itself send anything to the research team; you choose whether and how to send the file. In a study that sends automatically, that sending is separate and carries on regardless of whether you ever export by hand.
- Can be repeated as often as you like.

If writing the file is interrupted or the destination runs out of space, the resulting file can be incomplete and undecryptable. Delete the incomplete file and export again.

## 9. Permanently deleting local data

Delete local data (刪除本機資料) is available only when the study is Completed (已完成) or Withdrawn (已退出). The confirmation dialog is headed Delete local data permanently? (永久刪除本機資料？) and says Encrypted events, study state, and the configuration are all removed. This cannot be undone. (加密事件、研究狀態與設定都會移除，且無法復原。) Press Confirm (確認) to go ahead or Cancel (取消) to stop. When it is done, the app shows the code `LOCAL_DATA_DELETED`.

Confirming deletes:

- The encrypted study configuration and state.
- All local event segments.
- The study's non-exportable key in Android Keystore. Some phones may protect it in hardware, but this app does not require or verify hardware backing. Once the key is gone, any leftover encrypted bytes cannot be read back.
- The export receipt information currently held in the app.

**This cannot be undone.** There is no recovery, no undo, and no backup inside the app. After deleting, you cannot export that study's data again, because the data no longer exists on your phone. If you want the research team to have your data, export and send it before you delete.

Deletion does not touch `.partexp` files you already saved through Android's file picker. Those are ordinary files in whatever location you chose, and you have to delete them yourself. It also does not reach copies you already sent to anyone.

Uninstalling the app or clearing its app data also destroys the local keys and data. If your intention is to formally withdraw from the study, use the app's withdrawal flow first, so the withdrawal is recorded, and tell your research team.

## 10. Troubleshooting

| Screen or situation | What to do |
| --- | --- |
| The interface is in a language you cannot read | Tap the globe at the top right of the header and pick a language; it is the same setting as Android Settings → Apps → Particeps → Language |
| The configuration file will not import | Check the file, the client build/platform, and the study's validity period; do not modify the `.partcfg`, and contact the research team |
| Check this against the fingerprint your research team published (請與研究團隊公佈的金鑰指紋核對) | Ordinary for most studies; compare the fingerprint on the Configuration signature (設定檔簽章) block with the one your research team published, and if you do not have one, ask before consenting |
| The fingerprint does not match the one you were given | Do not consent. Contact your research team through details you already had, not details taken from the study screen |
| A required item in the access list is not granted | Tap that row, finish on the Android screen it opens, and return to the app; if you do not want to grant it, do not start |
| A source shows `ACCESS_UNAVAILABLE` | The access it needs is not granted. The other sources keep working; grant it only if you want to |
| Motion sensor unavailable | The device has no compatible accelerometer; the app does not fabricate substitute data, so a study that requires it cannot start |
| Gyroscope, ambient-light, or proximity sensor unavailable | The phone lacks that hardware; required collection cannot start and optional collection stays off. There is no substitute or inferred fallback. |
| A source shows a red dot and any other code | Pause first, check that permission or special access, then try to resume; if it still fails, contact the research team and quote the code |
| Data volume does not change in real time | Android's accounting is coarse and delayed, and the study sets how often the app polls; this is a normal limit |
| There are gaps in location | Check location access, your phone's location services, and the research foreground service; indoors it can still be inaccurate |
| A scheduled activity does not arrive on time | Check the notification permission and battery-saving settings; interventions use inexact background scheduling |
| A survey says it expired or is unavailable | It cannot be submitted after its signed response window; contact the research team if the timing was unexpected |
| Export fails | Check that the destination is writable and has enough space, and retry in another location; the study status does not change because of a failed export |
| An `UPLOAD_…` code appears where the sent figure usually is | Collection carries on. Network, timeout, busy-server, and temporary-server errors retry automatically; other protocol/receipt errors can be terminal. Connect to Wi-Fi and charge the phone, then contact the research team and quote a persistent code |
| Storage failure / paused | The app fails closed and stops accepting events; do not clear the app's data, contact the research team first, or export if you need to |

When the app has something to tell you, it shows a short code in capital letters in a red band directly under the header, beside a solid dot — `STORAGE_WRITE_FAILED`, `CONFIGURATION_IMPORT_FAILED`, `EXPORT_FAILED` and the like. Passing the code to your research team helps them diagnose the problem, and it contains none of your collected data. Two codes in that band are confirmations rather than problems: `EXPORT_COMPLETE` after an export finishes, and `LOCAL_DATA_DELETED` after a deletion.

## 11. State reference

A study moves through nine states internally, but the screen names only four of them. During setup you get your position in the five dots instead of a state name; once setup is over, the status line names the state.

| Internal name | What you see | What it means for you |
| --- | --- | --- |
| `IMPORTED` | Dot 1 of 5, the Study panel | The configuration file has been read in; nothing is being collected |
| `CONFIG_VERIFIED` | Dot 1 of 5, the Study panel | Signature, validity period, platform, client build, source list, and export key all checked, which confirms the file is unaltered rather than who wrote it; nothing is being collected |
| `CONSENT_PENDING` | Dot 2 then dot 3 of 5, the Data panel then the Consent panel | You are reading what would be collected and deciding whether to take part |
| `ACCESS_SETUP` | Dot 4 of 5, the Access panel | You are completing the required Android access |
| `READY` | Dot 5 of 5, the Start panel | Waiting for you to press Start study (開始研究); nothing is being collected |
| `RUNNING` | Collecting (收集中), teal dot | Sources can receive events; you can pause, finish, withdraw, or export |
| `PAUSED` | Paused (已暫停), grey dot | No new events accepted; you can resume, finish, withdraw, or export |
| `COMPLETED` | Completed (已完成), grey dot | Permanently stopped; you can export, withdraw, or delete |
| `WITHDRAWN` | Withdrawn (已退出), grey dot | Permanently stopped; you can export or delete |

Dots 2 and 3 are one state, not two: the app shows the sources and the consent text as separate pages of the same decision, and pressing Continue (繼續) on the Data page changes nothing in the study itself.

While the app is still loading, that same place shows Starting up (準備中). It is not a study state. Once loading finishes with no study imported, the line is simply absent: the header is the app's own name and nothing else.

There is no "exported" state. Your export history and the study's lifecycle are two separate things, and nothing you export changes where the study stands.

If you need to describe your situation to your research team, the internal names in the left-hand column above are exactly the ones recorded inside an export file.

## 12. If you tested an earlier version of this app

This app used to be called Android Data Collector. As far as your phone is concerned, that app and Particeps are two completely separate apps. They sit side by side, and installing Particeps brings nothing across: no study, no consent, no collected events, and no export record moves from one to the other. The older app keeps running on your phone, with everything it already holds, until you remove it. What it can no longer do is deliver: it writes files in the older format, and if its study sends data automatically, the research team's server no longer accepts what it sends.

**Ask your research team before you uninstall the older app.** Uninstalling it destroys the key it keeps on your phone, and after that the encrypted data it collected cannot be read by anyone — not by you, not by them. There is no recovery and no backup. If they want what the older app collected, export it from that app while it is still installed and send them the file; tell them it came from the older version, because they need the older tools to open it. Only then uninstall.

If your study uses the research keyboard, you have to enable it and select it again for Particeps. Android treats it as a different keyboard, so the setting you made for the older app does not carry over. Section 4 describes the two steps.

---

Related documents: [researcher guide](researcher-guide.md), [system design](system-design.md).
