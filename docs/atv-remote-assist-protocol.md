# Remote mic → assistant handoff (Google TV Remote Service)

How the TV hands the remote's microphone audio to "the assistant" when the
mic button is pressed. This is an **interface spec**: facts about the
protocol, written so the replacement assistant can be built from this doc and
the public Android SDK alone (clean room). The implementation must not copy
code from the analyzed APK. Identifiers below (keys, interface names,
transaction codes) are listed only because both sides have to use them.

## Provenance

| Item | Value |
|------|-------|
| Device | TCL Smart_TV_Pro (G05_4K_US), Android TV 11 / SDK 30, over network adb |
| APK | `com.google.android.tv.remote.service` 7.00.999235495 (versionCode 95855584), updated 2026-10-20 |
| APK SHA-256 | `20c12f4a42d64947b0105c59069e0157f4e2752a732fe029a7b24cf7ea58c16c` (base.apk, no splits) |
| Static method | `dexdump -d` disassembly of classes2.dex (REA's JADX provider was not configured) |
| Runtime method | logcat of real mic presses, 2026-10-20 14:12 and 14:18 |
| REA evidence | pull `ev_bcf675d8…4174`, package `ev_f02c375e…f556`, settings `ev_cf6cc6a7…e1ea` / `ev_6f8e044f…41ee1ea` |

Labels: **[static]** = read in the disassembly, **[runtime]** = seen in logcat
or `dumpsys` on the TV, **[inferred]** = follows from the two but not
observed directly.

## 0. Two modes: read this first

When the remote connects (and again when the `assistant` setting changes),
the service sends the remote a capability request that sets its voice mode.
[static: `sendCapabilityRequest`; runtime: behavior changed right after the
role switch]

| Mode | When | What a mic press does |
|------|------|-----------------------|
| **Non-legacy** | the assistant package is exactly `com.google.android.katniss`, setup is complete, and mic privacy is off | the remote starts streaming on its own (`HTT/PTT session initiated`), and the service pushes a pipe into the assistant's session args (§2–§5) |
| **Legacy** | any other assistant | the remote only reports `ASSISTANT_PRESSED`. The service launches the assistant and waits for **an app to start recording** (§8) |

The service logs `Non-legacy assistant mode is suppressed due to missing
Google Assistant, ongoing setup wizard or enabled microphone privacy` when it
picks legacy. **A replacement assistant always gets legacy mode**, so it
uses §8. §2–§5 describe Katniss's path and are kept for reference. The direct
API for that path (`IAtvAudioProviderService.provideAudioStream`) only accepts
callers in the package allowlist `{com.google.android.katniss}`. [static]

## 1. Who receives the mic button

1. The remote (`TCL_RC902N_M1`, BLE, Google "ATVV" voice profile) opens a mic
   session. The service logs `HTT session initiated` (hold-to-talk) or
   `PTT session initiated` (push-to-talk). [runtime + static]
2. The service keeps the **assistant package** in memory. It is the package
   part of `Settings.Secure "assistant"` (a flattened ComponentName), and a
   ContentObserver on that key keeps it up to date. Below SDK 29 it is hard-coded
   to `com.google.android.katniss`. It then looks up that package's UID. [static]
3. Today `assistant` and `voice_interaction_service` are both
   `com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService`,
   and the `android.app.role.ASSISTANT` holder is `com.google.android.katniss`.
   [runtime]

**Consequence:** whoever holds the Assistant role (which rewrites
`Settings.Secure assistant`) is the package that gets the audio. Nothing in
the launch path checks for Katniss. [static]

**Package visibility (required).** The service targets SDK 36, and its only
`<queries>` entry is the intent action `com.google.android.tv.FIND_MY_REMOTE`.
If it can't see the assistant package, the UID lookup fails: it logs
`AssistantServiceLauncher: Package <pkg> is not found` and the UID stays -1
(§5 then fails). [runtime, 2026-10-20] The fix is to declare an exported
component that matches that action. We use a no-op receiver protected by
`DUMP` (`RemoteVisibilityReceiver`). Side effect: the service also uses
`queryBroadcastReceivers(FIND_MY_REMOTE)` to decide whether to offer "find my
remote" to phone remote apps. That option now shows but does nothing. [static]
The UID is looked up only when the `assistant` setting changes or the service
starts. After the receiver is first installed, switch the role away and back
(the script does this when needed). [runtime: observer logs
`Assistant package name is now …`]

## 2. Launch sequence (per mic press)

`prepareAssistant(interactionModel, timestamp)` in the remote service:

1. Gate: if setup is not complete, it logs `PTT/HTT interaction is not allowed
   during setup` and launches with **no audio**. [static]
2. It builds an `AudioStream` value (§3) with channelCount 1,
   bitsPerSample 16, sampleRate 16000 and the press's interactionModel. It puts
   that value under key **`AUDIO_STREAM_KEY`** in a new Bundle (*session args*). [static]
3. **openMic gate** (§5). If it passes, it creates an OS pipe
   (`ParcelFileDescriptor.createPipe()`), starts the writer thread (§4) and
   launches. [static]
4. If openMic fails: when the assistant package **lacks** `RECORD_AUDIO`, it
   launches **without** session args. When the package **has** the permission
   (but the app-op was refused), it does **not launch at all**. [static]
5. Launch, through `SearchManager.launchAssist(bundle)` (when SearchManager is
   available), where `bundle` = { `android.intent.extra.TIME`: long press
   time, `SHOW_SESSION_ARGS`: *session args* }. Fallback: `startActivity(new
   Intent(ACTION_ASSIST).addFlags(NEW_TASK).putExtra("SHOW_SESSION_ARGS",
   sessionArgs))`. [static]
6. The system turns `launchAssist` into `showSession` on the **active
   VoiceInteractionService**. Logcat shows this order: the session service is
   bound, then an AssistStructure of the foreground app is flattened, then the
   session's `onShow` runs. [runtime]
7. The Bundle that Katniss received in `onShow` was
   `{search_type=1, SHOW_SESSION_ARGS={AUDIO_STREAM_KEY=AudioStream{directAudioStream=null, indirectAudioStream=<binder>, channelCount=1, bitsPerSample=16, sampleRate=16000, interactionModel=2}}, android.intent.extra.TIME=…, android.intent.extra.KEY_EVENT=219}`.
   [runtime] We don't know what adds `search_type` and `KEY_EVENT` (219 =
   `KEYCODE_ASSIST`). The remote service doesn't, so something along the
   system path does. They aren't needed.

### interactionModel

| Value | Meaning | Who ends the utterance |
|------:|---------|------------------------|
| 1 | PTT: push-to-talk (tap) | **the assistant**: it closes its read end when it decides the user stopped speaking [inferred from §4 "closed by the consumer"] |
| 2 | HTT: hold-to-talk | **the remote**: releasing the button ends the stream with EOF [runtime: release → `Pipe has reached the end of stream`] |

[static: the BLE mic-session handler maps 1→"PTT", 2→"HTT"]

## 3. `AudioStream` Parcelable wire format

Written with `Parcel` primitives in this exact order. [static: writeToParcel]

| # | Write | Field |
|---|-------|-------|
| 1 | `writeTypedObject(pfd)`: int 0 = null, or int 1 + ParcelFileDescriptor | **directAudioStream** |
| 2 | `writeInt` | **channelCount** (1) |
| 3 | `writeInt` | **bitsPerSample** (16) |
| 4 | `writeInt` | **sampleRate** (16000) |
| 5 | `writeInt(0)` | reserved, always 0 |
| 6 | *only if directAudioStream was null:* `writeStrongInterface` (a binder) | **indirectAudioStream** (§4) |
| 7 | *only if directAudioStream was null:* `writeInt` | **interactionModel** (§2) |

Wrapper: the value sits in the session-args Bundle as a typed Parcelable
(`VAL_PARCELABLE` = 4, then the writer's class-name string, then the fields
above). The class name is
`com.google.android.tv.remote.service.AutoValue_AudioStream`. Our process
does not have that class. So the inner Bundle **must not be unparcelled
normally**: `get`, `keySet` or `toString` will throw `BadParcelableException`.
Read it as raw parcel data instead (plan §B2).

Only the indirect form has been seen on this TV. The direct form exists in
the format but has never been observed.

## 4. `IndirectParcelFileDescriptor` binder

| Item | Value |
|------|-------|
| Interface descriptor | `com.google.android.tv.remote.service.IndirectParcelFileDescriptor` |
| Transaction `1` (FIRST_CALL_TRANSACTION) | `getParcelFileDescriptor()`, with no arguments |
| Request | standard AIDL: `writeInterfaceToken(descriptor)` |
| Reply | `writeNoException`, then a typed PFD (int 0 = null, or int 1 + PFD) |

Rules [static]:

- It returns the pipe's **read end** only if `Binder.getCallingUid()` equals the
  assistant UID from §1. Any other caller gets null, **and the pipe is closed**.
- It works **once**. After a successful hand-off it returns null.
- **15-second window.** If nobody fetches the PFD within 15 s, the service
  closes the pipe and ends the mic session.
- Stream contents: **raw PCM, signed 16-bit little-endian, mono, 16 kHz.**
  No header and no framing. The remote's decoded frames are written in
  arrival order. If the remote's native rate divides 16 kHz evenly, the
  service upsamples by repeating each sample. Uneven rates are rejected.
- **End of stream:**
  - normal end (HTT release, or the service finishing) → the writer closes →
    reader sees **EOF** (`read` returns -1);
  - cancel → `closeWithError("Recording interrupted.")` → reader gets an
    **IOException** carrying that message.
- **Consumer close:** if the assistant closes its read end, the writer's next
  write fails. The service logs `Pipe was closed by the consumer`, stops the
  mic and releases the mic app-op.

## 5. openMic gate (app-op)

Run inside the remote service for the assistant's (UID, package). [static]

- SDK < 33 (this TV): `AppOpsManager.startOpNoThrow("android:record_audio",
  uid, pkg)` must return `MODE_ALLOWED`. SDK ≥ 33: the package must hold
  `RECORD_AUDIO`, and the op becomes
  `android:receive_explicit_user_interaction_audio`.
- If the package can't be resolved to a UID, the gate fails (`Unable to resolve
  target package or uid`).
- There is one Katniss-only exception, on SDK 30: if the op returns
  `MODE_IGNORED` and the package is Katniss, it proceeds anyway. **A
  replacement gets no such pass.** It needs `RECORD_AUDIO` granted and the
  `RECORD_AUDIO` app-op set to `allow`.
- On failure it logs `<pkg> doesn't have access to the on-remote mic`.
- Katniss today: `RECORD_AUDIO` granted (GRANTED_BY_DEFAULT), app-op
  `RECORD_AUDIO (allow)`. [runtime]

## 6. Becoming the assistant (Android 11)

- The Assistant role can be set from adb on this build:
  `cmd role add-role-holder android.app.role.ASSISTANT <pkg>`. `get-role-holders`
  is **not** available here; read the holder with `dumpsys role`. [runtime]
- The TV accepted `dev.tvlauncher` (a VoiceInteractionService
  with a session service, a stub recognition service, and `supportsAssist`).
  Granting the role set `assistant` and `voice_interaction_service` to our
  service and `voice_recognition_service` to our recognizer, and the system
  bound our service. [runtime, 2026-10-20]
- `KEYCODE_ASSIST` (e.g. `input keyevent KEYCODE_ASSIST`) also shows our
  session, with show flags 7, but it carries no remote audio. [runtime]
- To roll back: `cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss`.

## 8. Legacy mode: the remote mic as an ordinary recording (what Muse uses)

[static: `AudioBridgeX`, `ActiveAudioDeviceManager`, `BleRemoteDevice`; runtime 2026-10-20 17:40]

1. Mic press: the remote sends `ASSISTANT_PRESSED`. The service logs
   `Assistant has been requested. Requested N times since last audio stream.`
   It marks the remote as the **active audio device for 15 s** and launches
   the assistant as in §2 step 5, but the args carry **no** audio. [static +
   runtime]
2. The service registers an `AudioPolicy` with one **loopback** `AudioMix`.
   Its rule is `RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET` = **`VOICE_RECOGNITION`**
   (6), with format 48 kHz, PCM 16-bit, mono. [static] It logs
   `Ad hoc audio policy is being registered`. [runtime]
3. When any app starts an `AudioRecord` with source `VOICE_RECOGNITION` while
   a remote is active, the service sends `OPEN_MIC` to the remote. It runs
   the §5 app-op check for **the recording app's UID**, then plays the decoded
   remote audio into the mix (`AudioTrackWrapper: Playing Audio Track`). The
   recording receives it at whatever rate it asked for (16 kHz mono worked).
   [static + runtime]
4. When the recording stops, the service pauses the track, and later logs
   `AudioPolicy is being unregistered`. [runtime] If no recording starts within
   15 s, the remote stops being active.
5. Requirements for the recording app: `RECORD_AUDIO` granted, the
   `RECORD_AUDIO` app-op set to allow, and being visible to the service (§1,
   for the UID lookup). Being the assistant is what makes the mic button
   launch it. Nothing else ties the audio to the Assistant role. [inferred
   from §5 + the rule above]
6. Seen on the TV: a mic press opened our overlay, and an `AudioRecord(VOICE_RECOGNITION,
   16000, mono, 16-bit)` started about 200 ms later. It read about 5 s of
   speech-level audio (RMS 1,000–5,700), then silence (RMS ≈ 20), while the
   service logged that it was playing the track. [runtime]

7. **The button release can't be seen.** [runtime, 2026-10-20, a 3 s hold]
   - The mic key reaches Android as a key event, but TCL's window manager
     consumes it before input filters (`startAssistLongPressFunction` on the
     press). A key-filtering accessibility service sees neither down nor up.
   - In legacy mode the remote sends no audio stop on release: the recording
     keeps receiving room noise (RMS ≈ 10–40) until the app stops it.
   - So the assistant must decide when the user has finished (silence
     detection). A **second mic press** does reach it: the session is shown
     again, and the app can use that as "stop now".

**TCL quirk:** TCL's boot guard refuses to let the system start an app for a
bind unless the TCL app-op `APP_AUTO_START` is `allow`. After a reinstall,
`VoiceInteractionServiceManager` then logs `Failed binding to voice
interaction service`, and the mic button does nothing. Katniss is allowed by
default; we set `appops set <pkg> APP_AUTO_START allow`. [runtime]

## 7. Open questions

| # | Question | How to settle |
|---|----------|---------------|
| U1 | Does recording work when the mic is pressed over another app (Android 11 while-in-use mic rules)? Our activity is in the foreground while recording, so it should. | Press the mic over YouTube. |
| U2 | ~~Does the role controller accept our package?~~ Yes (§6). Still open: do Katniss or Play updates take the role back later? | Re-check `dumpsys role` after reboots and Play updates. |
| U3 | ~~Raw parse of the session args~~: not needed. Non-Katniss assistants get legacy mode (§0), so no audio arrives in the args. | — |
| U4 | What adds `search_type` / `KEY_EVENT` to the args? | Not needed; ignore. |
| U5 | When is `directAudioStream` used? | Never seen. Handle it anyway (it is cheap). |
| U6 | The remote also has a separate `IAtvSearchAssistantService` / `SearchAssistantSupportedKeyCodesProvider` path. Does it matter? | Not on the mic path seen here. Out of scope. |
