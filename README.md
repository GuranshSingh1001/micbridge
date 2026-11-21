# MicBridge

**Voice input for sideloaded apps on Jio set-top boxes.**

MicBridge makes the Jio remote's microphone work in apps that Jio never whitelisted — SmartTube, Nuvio, Kodi, Jellyfin, and anything else you sideload. It needs **no root** and does not modify the system partition.

It was built and tested for the **JHSD200** (Android 11, SDK 30) running production Jio firmware, where the mic key is hard-wired to Jio's voice assistant. Not sure about other models you can try.

![MicBridge demo: the mic key is pressed in SmartTube, the Jio voice overlay appears, and "hello" lands in the search box](docs/demo.gif)

*Recorded on a JHSD200: SmartTube home → search → mic key → “hello” typed into the search box → results.*

---

## The problem

On these boxes the remote's mic button only works in Jio/preset apps:

- The mic key reports `KEYCODE_VOICE_ASSIST` (231, scan code 217) and is defined in the **system** keylayout. The firmware's `IWediaJioPodsHelper` intercepts it in `system_server` and hard-launches `com.android.app.jio.voiceassist`.
- That voice app is the **only** client the Jio speech service accepts. Starting your own `SpeechRecognizer` gets `concurrent startListening received - ignoring this call`, and the Jio app can crash.
- The keylayout and the voice component cannot be changed on a production build (`pm disable-user` returns `SecurityException: Shell cannot change component state`), and an accessibility service can observe keys but cannot consume them.
- The firmware also has **no handler** for `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, which is why in-app mic buttons (SmartTube's search orb) do nothing.

## How it works

MicBridge lets Jio do the recognition, then takes the result away from Jio:

```
remote mic press
      │
      │ firmware (unavoidable)
      ▼
com.android.app.jio.voiceassist
      │  recognised text
      ▼
:id/voiceStatesView
      │
      │  MicA11yService reads the query
      │  waits for it to stabilise / for the result banner
      ▼
GLOBAL_ACTION_BACK  ── closes the Jio overlay
      │
      ▼
foreground app receives the text via ACTION_SET_TEXT
```

Delivery is generic, in this order:

1. the focused editable field,
2. otherwise the first editable field in the window,
3. otherwise a Leanback search box: a node whose class contains `StreamingTextView`/`SearchEditText`, or whose id looks like a search field (`lb_search_text_editor`, `search_text`, `search_box`, …),
4. otherwise the first node whose text or content description mentions "Search" (clicked, then retried — this is what opens the search screen in SmartTube, TizenTube and most Leanback apps),
5. otherwise it gives up quietly — nothing is typed and the app is left untouched.

Leanback apps such as SmartTube and TizenTube have no editable field at all: their search box is a `StreamingTextView`, so it is filled with `ACTION_SET_TEXT` directly. Browsers such as TV Bro get an extra click + focus + retry because their WebView text field often rejects the first `ACTION_SET_TEXT`.

The same APK registers a handler for `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, so apps that ship their own mic button can launch a working recogniser.

## Covered apps

Routing is **global with a blocklist**. These keep their stock Jio behaviour and are never touched:

- the Jio/preset apps (JioTV+, Hotstar, Netflix, Prime Video, `com.jio.yt`, Zee5, SonyLIV, Sun NXT, Hoichoi, EPIC ON, Ultra Jhakaas, ETV Win, and everything under `com.jio.*`, `com.jiostb.*`, `com.jiotv.*`, `com.iwedia.*`),
- Settings, SystemUI, `android`, the Jio voice app, the current launcher and MicBridge itself.

Everything else gets the generic flow — **including apps that the Jio launcher has added to `Settings.Global remote_app_list`** (TVExplorer, Shevery, a sideloaded TizenTube, …). Version 1.0 blocked every package in that list, which silently disabled MicBridge for sideloaded apps pinned to the Jio home screen; 1.1 uses the explicit list above instead.

## Requirements

- A Jio set-top box with the Jio voice assistant (`com.android.app.jio.voiceassist`) — developed and tested on **JHSD200 / Android 11**.
- ADB access over the network (`adb connect <STB_IP>:5555`).
- **No root.**

## Install

Download `micbridge.apk` from the [latest release](https://github.com/Lucifer7535/micbridge/releases/latest), then:

```bash
adb connect <STB_IP>:5555
adb install -r -g micbridge.apk
```

`-g` grants the microphone permission up front. `-r` replaces an existing copy.

Installing an APK kills the accessibility binding, so **re-enable the service after every install or update** (next section).

### Enable the accessibility service

Keep the two stock services in the list (`com.iwedia.ledmanager/...KeyEventMonitorService` and `com.klevico.monet/...LauncherAccessibilityService`). Replacing the list with only MicBridge will break the remote and launcher keys.

```bash
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then
  NEW="dev.dedsec.micbridge/.MicA11yService"
else
  NEW="$CUR:dev.dedsec.micbridge/.MicA11yService"
fi
adb shell settings put secure enabled_accessibility_services "$NEW"
adb shell settings put secure accessibility_enabled 0
adb shell settings put secure accessibility_enabled 1
```

Confirm it bound:

```bash
adb shell settings get secure enabled_accessibility_services
adb logcat -s MicBridge
# I MicBridge: service connected; flags=114 home=com.klevico.monet
# I MicBridge: blocklist: Jio/preset + launcher + Settings/SystemUI; allow=
```

The service also starts on its own after a reboot, as long as it stays in `enabled_accessibility_services`.

## Use

Press the mic key and speak. The Jio overlay appears for ~2–4 seconds (the firmware always starts it; hiding it would need root), then closes and the text lands in the app.

- **SmartTube:** press mic anywhere, or press its own search orb.
- **Nuvio and other apps:** open the app's search screen first if it does not expose a search entry point on the current screen.

### Injecting a query without speaking

Useful for testing or for scripted setups:

```bash
adb shell am broadcast -a dev.dedsec.micbridge.TEST --es target com.nuvio.tv --es query "hello"
```

Omit `--es target` to use the last foreground app, or omit `--es query` to capture from an already-open Jio overlay.

## Debugging

```bash
adb logcat -s MicBridge
```

A working press looks like this:

```text
mic key kc=231 scan=217
voice overlay over org.smarttube.stable -> capturing
captured query: "hello"
deliverToApp try=0 top=org.smarttube.stable target=org.smarttube.stable
typed into org.smarttube.stable (StreamingTextView#lb_search_text_editor): hello
```

If the second line says `-> blocked`, the app is on the blocklist (use `micbridge_allow`). If the last line says `no text field in <pkg>`, the app needs its search screen opened first.

The same log is appended to `/sdcard/Android/data/dev.dedsec.micbridge/files/micbridge.log`.

## Build from source

`build.sh` builds the APK directly with the SDK build-tools — **no Gradle**. You need:

| Tool | Version |
| --- | --- |
| JDK | 17 |
| Android SDK build-tools | `30.0.3` |
| Android platform | `android-30` (`platforms/android-30/android.jar`) |

```bash
export JAVA_HOME=/path/to/jdk-17
export SDK=/path/to/android-sdk      # must contain build-tools/30.0.3 and platforms/android-30
./build.sh                            # -> micbridge.apk
```

The APK is signed with the bundled `micbridge.keystore` (alias `micbridge`, store/key password `android`).

> [!IMPORTANT]
> That keystore is a public, self-signed development key. It exists only so rebuilds keep the same signature and can update the installed app. Never reuse it for anything else.

### Project layout

```
app/src/main/AndroidManifest.xml
app/src/main/java/dev/dedsec/micbridge/
  MicA11yService.java     accessibility service: capture, scrape, deliver
  RecognizeActivity.java  RecognizerIntent handler for in-app mic buttons
  L.java                  logger (logcat + file)
app/src/main/res/xml/a11y_config.xml
build.sh
micbridge.keystore
micbridge.apk
```

## Changing the blocklist

You do not need to rebuild. `micbridge_allow` is a comma/space separated list of package names that always get the generic flow, even if they are Jio/preset/launcher packages. A trailing `*` matches a prefix:

```bash
# allow one app (e.g. a TizenTube APK that reuses com.jio.yt)
adb shell settings put global micbridge_allow "com.jio.yt"

# allow a whole family, plus one exact package
adb shell settings put global micbridge_allow "com.jio.*,com.example.app"
```

The setting is read live; no restart is needed. Clear it with:

```bash
adb shell settings delete global micbridge_allow
```

To change the built-in list instead, edit `MicA11yService.java`:

```java
private static final String[] BLOCKED_EXACT = { ... };
private static final String[] BLOCKED_PREFIX = { "com.iwedia.", "com.jio.", "com.jiostb.", "com.jiotv." };
```

then rebuild and reinstall.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Nothing is typed, log ends with `no text field in <pkg>; nothing typed` | The app has no text field and no visible "Search" entry point. Open its search screen first. |
| The mic still opens Jio's universal search, log says `voice overlay over <pkg> -> blocked` | The app is on the built-in Jio/preset/launcher blocklist. Add it to `micbridge_allow` (see above). |
| The mic still opens Jio's universal search and the log shows nothing after `service connected` | 1.0 logged no routing decisions. Upgrade to 1.1, which logs `blocked` vs `capturing` for every press. |
| Nothing happens at all | The accessibility service is not enabled/bound. Re-run the enable steps and check `adb logcat -s MicBridge`. |
| Service disappears after a reboot | Ensure it is listed in `enabled_accessibility_services`; toggling `accessibility_enabled` off/on also rebinds it. |
| Wrong app receives the text | `lastApp` was stale. Press mic while the target app is in the foreground. |
| `concurrent startListening` in logcat | Another app is using the Jio speech service; only the Jio voice app is accepted by the firmware. |

## Uninstall / rollback

```bash
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
NEW=$(printf '%s' "$CUR" | sed 's#:dev.dedsec.micbridge/.MicA11yService##; s#dev.dedsec.micbridge/.MicA11yService:##; s#^dev.dedsec.micbridge/.MicA11yService$##')
adb shell settings put secure enabled_accessibility_services "$NEW"
adb uninstall dev.dedsec.micbridge
```

The stock mic path is untouched, so Jio apps keep working exactly as before.

## Permissions and privacy

- `RECORD_AUDIO` — used only by `RecognizeActivity`, which forwards the result of the Jio recogniser to the calling app.
- `QUERY_ALL_PACKAGES` — lets the accessibility service resolve the foreground package name on Android 11.
- Accessibility — observes window changes and the Jio overlay's view tree to read the recognised text.
- **No `INTERNET` permission.** MicBridge cannot upload anything; the text never leaves the box.

## Limitations

- The Jio voice overlay still flashes on every press.
- Requires the stock Jio voice assistant; removing or disabling it breaks the capture step.
- Text delivery only — no cursor positioning, selection, or password fields.
- Specific to the Jio firmware that hard-launches `com.android.app.jio.voiceassist`. Other vendors' boxes do not need it.

## Credits

Extracted from [sideload-jiostb](https://github.com/ilikechocopies/sideload-jiostb), the ADB setup guide for Jio set-top boxes.

## License

[Apache License 2.0](LICENSE)
