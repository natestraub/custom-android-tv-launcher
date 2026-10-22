# Custom Android TV Launcher

> **Disclaimer:** This is a personal hobby project, shared as is with no warranty. It isn't
> affiliated with or endorsed by TCL, Google or Meta. Their names and the logos bundled here
> belong to them. Setting it up changes your TV's settings and apps over adb: you're responsible
> for your own device, and anything you do with it is at your own risk. The Muse integration and
> the remote's mic handoff rely on undocumented behavior of Google TV and Muse, which can change
> or stop working with any update.

A fast, zero-dependency home screen for Android TV, built for (and tuned on) a TCL Google TV running
Android TV 11. It replaced a Flutter launcher that used 356 MB of memory; this one uses 23–27 MB,
draws the whole home screen in its first frame (~365 ms from a cold start), rotates your own photos
as the wallpaper, and can make **Muse** (Meta's assistant) the thing the remote's mic button talks to.

This repo is personalized for one TV. It isn't meant to run unchanged on yours: the idea is that you
point your own Claude at it and have it adapt the TV-specific parts. See
[Having your Claude customize this repo](#having-your-claude-customize-this-repo).

## What it does

- **One row of apps** that scrolls sideways, plus a strip of small pinned logos above it (search,
  YouTube, Spotify, and an HDMI input shown as an Xbox logo). Hold OK on a tile to move, hide or
  inspect an app.
- **Wallpapers from your photos.** A command resizes a folder of photos and pushes them to the TV;
  each one slowly pans and zooms, with a crossfade every 60 seconds.
- **Weather, time and date** in the corner ([Open-Meteo](https://open-meteo.com), no API key; type
  a ZIP code or city once in the gear menu).
- **Continue watching** below the fold, read from the "Watch Next" list most streaming apps keep on
  the TV.
- **The remote's Settings button works from any app**, through a key-filter accessibility service.
- **Muse on the TV** (optional):
  - pair the TV as a Muse gadget, then ask Muse questions from the quick bar;
  - let Muse run TV commands (open apps, switch HDMI input, media keys, volume, show a message, take
    a screenshot).
- **Muse as the remote's assistant** (optional): press the mic, and the Muse "M" draws itself at the
  top of the screen while you talk. Your words stream into a small bubble through Meta's realtime
  speech-to-text, and Muse's answer appears under them.

## How it's fast

- Plain Kotlin on Android's own View system. No AndroidX, AppCompat or Compose; the release APK is
  a few hundred KB.
- Every app banner is drawn once into a single cached image (a tile atlas), so a cold start uploads
  one texture instead of decoding 20 PNGs.
- Wallpapers decode straight into GPU memory at their final size ("hardware bitmaps"), with a
  screen-sized copy cached on disk.
- Scrolling slides one strip of tiles sideways, without re-measuring or redrawing a single tile.
- Animations run on the launcher's own vsync-driven animator, so they stay smooth even with the
  system's animations turned off.

Measure it yourself with `mise run bench` (cold and hot start, scroll jank, idle CPU, memory).

## Requirements

- An Android TV device with **Android TV 11 or newer** and developer options. Only a TCL Smart TV Pro
  (MediaTek MT5889, 32-bit userland, ~2.7 GB RAM) has been tested.
- A Mac. The scripts use the macOS Keychain and `sips`; ImageMagick also works for wallpapers.
- [mise](https://mise.jdx.dev) (it installs the pinned Java 17), and the Android SDK at
  `~/Library/Android/sdk` (install it with Android Studio, or set `ANDROID_HOME` in `mise.local.toml`).
- `adb` on your PATH: `brew install --cask android-platform-tools`.

## Setup

**A note before you start:** this is your TV, and you're on your own if something goes wrong.
Everything below is reversible: apps are removed only for your user profile, and a factory reset puts
everything back. But removing the wrong system app can break setup, your remote, or casting. Go slowly,
keep a list of what you remove, and don't remove anything you can't name.

### 1. Connect to the TV over adb

1. On the TV: **Settings → System → About**, then select **Android TV OS build** seven times until it
   says you're a developer.
2. **Settings → System → Developer options** → turn on **USB debugging** (and **Network debugging** if
   it's listed separately).
3. Find the TV's IP address: **Settings → Network & Internet** → your network.
4. In this repo, create `mise.local.toml` (git-ignored) with your TV's address:

   ```toml
   [env]
   TV_ADB = "192.168.1.50:5555"
   ```

5. Run `mise install`, then `adb connect 192.168.1.50:5555`. On the TV, check **Always allow from
   this computer** and select **Allow**. `adb devices` should now list the TV as `device`.

### 2. (Optional) Debloat the TV

Most of the speed-up on the original TV came from memory, not CPU: two minutes after a reboot it had
already pushed ~414 MB into swap on slow internal storage. A cleanup cut that by about two-thirds:

- **Look before you remove:** `adb shell pm list packages` lists every app.
- **Remove an app for your user:** `adb shell pm uninstall --user 0 <package>`.
- **Bring it back:** `adb shell cmd package install-existing <package>`.
- **Block background running** for apps that don't need it:
  `adb shell cmd appops set <package> RUN_ANY_IN_BACKGROUND ignore`.
- **Turn off system animations:**
  `adb shell settings put global window_animation_scale 0`, and the same for
  `transition_animation_scale` and `animator_duration_scale`.
- **Cap cached background apps:**
  `adb shell settings put global activity_manager_constants max_cached_processes=3`.

The packages worth removing differ by TV maker. Ask Claude to audit yours (see below).

### 3. Install the launcher

```sh
mise run install
```

This builds the release APK, installs it, and fully compiles it ahead of time. It then makes it the
home screen and turns on its accessibility service (the Settings button, and screenshots for Muse).

### 4. Wallpapers

```sh
mise run wallpapers ~/Pictures/TV              # add or update photos
mise run wallpapers ~/Pictures/TV --replace    # replace everything on the TV
mise run wallpapers --list                     # what's on the TV
mise run wallpapers --clear                    # remove them all
```

The script shrinks each photo to just cover 2304×1296 (the 1080p screen plus 20%, so the pan and zoom
never upscales), saves it as JPEG, and pushes it over adb into the launcher's own folder on the TV
(`/sdcard/Android/data/dev.tvlauncher/files/wallpapers`). The launcher picks up changes within a
minute. With no photos, the wallpaper stays black.

### 5. Weather

On the TV, open the gear icon → **Weather location** and type a ZIP code or city.

### 6. (Optional) Muse on the TV

1. Get a Muse Gadget SDK token at <https://gadgets.muse.ai/settings/sdk-tokens>, then run
   `mise run muse-token`. It asks for the token once at a hidden Keychain prompt and sends it to the
   TV over adb's stdin, so it never appears in a command line. The TV stores it encrypted with an
   Android Keystore key.
2. On the TV, select the Muse logo in the quick bar above the app row. It walks you through pairing: the TV
   advertises over Bluetooth as `MuseGadgetXXXXXX` for 10 minutes. In the Muse app, go to **Settings →
   Devices → Developer** and add the gadget.
3. Ask Muse something from the chat. Muse can also run the TV's commands now.

To start over: `mise run muse-token --unpair` (keeps the token), remove the TV in the Muse app, then
pair again.

### 7. (Optional) Make the remote's mic button talk to Muse

```sh
mise run assistant muse     # mise run assistant google   gives the button back to Google
mise run asr-key            # Meta Model API key, for live speech-to-text
```

- `assistant muse` makes the launcher the TV's assistant: it holds the Assistant role and gets the
  mic permission.
- `asr-key` stores a [Meta Model API](https://platform.meta.ai) key on the TV, the same way the Muse
  token is stored. The account needs billing set up: speech-to-text costs $0.18 per hour of audio.

On a mic press:
1. The launcher records the remote's audio.
2. It streams the audio to Meta's realtime speech-to-text (`wss://api.meta.ai/v1/asr/realtime`,
   endpointing mode). Your words appear as you talk, and Meta decides when you've finished.
3. The finished text goes to Muse, about 2 seconds after the press. Press the mic again to stop
   early, or Back to cancel.
4. If Meta can't be reached, the recording is trimmed to just your speech and sent to Muse as a
   voice note instead.

Muse answers in text by default. Set `museVoiceReplies=true` in `gradle.properties` to have spoken
questions answered out loud in Muse's own voice.

How the mic handoff works, in detail: [docs/atv-remote-assist-protocol.md](docs/atv-remote-assist-protocol.md).
The Muse gadget protocol the TV speaks: [docs/muse-protocol.md](docs/muse-protocol.md).

## Commands

| Command | What it does |
| --- | --- |
| `mise run test` | JVM unit tests |
| `mise run build` | Release APK |
| `mise run install` | Build, install, set as home screen, turn on the accessibility service |
| `mise run wallpapers <folder> [--replace]` | Resize photos and push them to the TV (`--list`, `--clear`) |
| `mise run assistant muse\|google` | Choose what the remote's mic button talks to (no argument: show) |
| `mise run muse-token` | Send the Muse SDK token to the TV (`--status`, `--clear`, `--forget`, `--unpair`) |
| `mise run asr-key` | Send the Meta Model API key to the TV (`--status`, `--clear`, `--forget`) |
| `mise run bench` | Benchmark start time, scroll jank, idle cost and memory on the TV |

## Layout

```
app/src/main/java/dev/tvlauncher/
├── LauncherActivity.kt, LauncherMenus.kt   home screen and its dialogs
├── apps/          app row, tile atlas, pinned strip, HDMI inputs, Continue watching
├── ui/            scroll math, pan/zoom, frame animator, status block (clock, weather)
├── wallpaper/     the photo folder, crossfade and decoding
├── weather/       Open-Meteo client
└── muse/          pairing (BLE), the encrypted link, chat, TV commands, assistant + voice
scripts/           install, wallpapers, assistant role, token/key delivery, benchmark
docs/              the remote-mic handoff and the Muse gadget protocol
```

## Having your Claude customize this repo

This launcher was built with Claude Code driving a TCL TV over adb, and adapting it to your TV works
the same way. Connect the TV (Setup step 1), open Claude Code in this repo, and ask something like:

> This launcher was built for a TCL Google TV. Adapt it to my TV at `$TV_ADB`: check each item in the
> README's customization list against my TV, change what's different, then build, install and verify
> it on the TV.

These are the parts written for the original TV or its owner. Each says where it lives and what to
check on yours:

| What | Where | Specific to | What to check on your TV |
| --- | --- | --- | --- |
| TV address | `mise.local.toml` (`TV_ADB`) | your network | Your TV's IP; network adb on port 5555 |
| App ID `dev.tvlauncher` | `app/build.gradle.kts`, `PKG`/`MUSE` in `scripts/*.sh`, `wallpapers.sh` `DEST` | this repo | Rename if you want your own ID; uninstall the old one first |
| Remote's Settings button | `SettingsButton.kt` (`KEYCODE_TCL_SETTINGS = 4077`) | TCL remotes (scan code 233 → `TCL_SET`) | Press your remote's Settings button while watching `adb shell getevent -l` / `adb logcat` for its key code |
| HDMI inputs | `apps/HdmiInputs.kt` | TCL's TV app (handles the standard passthrough URI; input IDs sort by port) | `adb shell dumpsys tv_input` for your inputs' IDs and labels; test that switching works |
| Default pins | `apps/AppOrder.kt` (`DEFAULT_PINNED`: YouTube, Spotify, HDMI 2), `hdmiLogo` (HDMI 2 shown as Xbox), `apps/PinnedIcons.kt` (bundled logos) | the owner's setup | Your apps' package names; which input your console is on |
| Screen size | `wallpapers.sh` (`W`/`H` = 2304×1296) | a 1920×1080 UI | `adb shell wm size`; use the UI size × 1.2 |
| Auto-start permission | `scripts/assistant-role.sh` (`appops set … APP_AUTO_START allow`) | TCL's TGuard app-start blocker | Without it, a TCL TV doesn't re-bind the assistant after a reboot. Other TVs don't have this app-op (the command fails); drop the line |
| Remote mic handoff | `muse/assist/*`, `RemoteVisibilityReceiver.kt`, `docs/atv-remote-assist-protocol.md` | Google TV's remote service (`com.google.android.tv.remote.service`) in "legacy" mode for non-Google assistants | `adb shell pm list packages \| grep remote`; whether the mic opens our overlay and audio arrives (see the doc's §8) |
| Google's speech recognizer | `muse/voice/GoogleSpeech.kt` (`com.google.android.katniss`) | Google TV | On the TCL TV it's refused (error 9) after Google's app restarts its old assistant service; the doc explains why. It may work on other TVs, but Meta's speech-to-text is the default anyway |
| Google assistant package | `scripts/assistant-role.sh` (`GOOGLE=com.google.android.katniss`) | Google TV | `adb shell dumpsys role` for your TV's assistant holder |
| macOS-only tooling | `scripts/muse-token.sh`, `scripts/meta-asr-key.sh` (Keychain), `wallpapers.sh` (`sips`) | a Mac | On Linux, swap the Keychain for `secret-tool`/`pass`; wallpapers already work with ImageMagick |
| Performance tuning | throughout (`apps/`, `wallpaper/`, `ui/`) | a 4-core, 32-bit, ~2.7 GB TV | Usually fine as is; run `mise run bench` before and after changes |
| Benchmark app | `scripts/bench.sh` (uses YouTube for the hot-start test) | the owner's apps | Any installed app works |
| Debloat list | Setup step 2 | every maker ships different apps | Have Claude list the packages with what each one is, and remove only what you can name |

Known limits, on the original TV at least:
- Streaming apps show as black in `tv.screenshot`. Android blanks DRM-protected video in every
  capture.
- Netflix starts at every boot: TCL's auto-start manager exempts it.
- Muse can't press remote keys yet. On Android 11 an app can't send keys to another app; the protocol
  doc lists the options.

## License

[Apache License 2.0](LICENSE). The Muse gadget protocol code in `app/src/main/java/dev/tvlauncher/muse/`
is a Kotlin port of Meta's [Muse Gadget SDK](https://github.com/facebookincubator/muse-gadget-sdk)
(Apache-2.0); see [NOTICE](NOTICE) for what's derived from it. The bundled app logos belong to their
owners and aren't covered by the license.
