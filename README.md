<div align="center">

# 🏍️ OpenCfMoto

### Wireless Android Auto on your CFMoto MotoPlay dashboard — no root, no PC.

Put **Google Maps / Waze** on your bike's dash over Wi-Fi, drive it from the touchscreen, and log
every ride — all from an Android phone in your pocket.

<br/>

<img src="docs/screenshots/01_main.png" width="240" alt="Home screen"/>&nbsp;
<img src="docs/screenshots/02_setup.png" width="240" alt="Setup — display"/>&nbsp;
<img src="docs/screenshots/09_trip_map.png" width="240" alt="Logged ride on the map"/>

<br/><br/>

### 🎥 Live demo — Android Auto on a CFMoto dash

<img src="docs/media/hud-demo.gif" width="640" alt="Android Auto running on the CFMoto MotoPlay dashboard"/>

**▶ [Watch the full demo](docs/media/hud-demo.mp4)** — Google Maps navigation + media, driven from the
dash touchscreen.

</div>

---

> ⚠️ **Community project — not affiliated with or endorsed by CFMoto.** Developed and tested against
> CFMoto **800MT** and **1000 MT‑X** dashes; other bikes/phones may need a retry or aren't supported
> yet. Don't rely on it for critical navigation — set your route **before** you ride. Use at your own
> risk.

---

## ✨ Features

| | |
| --- | --- |
| 🗺️ **Android Auto on the dash** | Relays Google Maps / Waze / any AA app to the MotoPlay screen over Wi‑Fi. |
| 👆 **Multi-touch** | Two-finger pinch-to-zoom and full tap/scroll straight from the dash touchscreen. |
| ⚡ **One-tap Connect & Auto-connect** | Remembers your bike; reconnects on launch automatically when it's in range (toggleable). |
| 🛰️ **Trip computer + ride logging** | Live speed/distance/duration from GPS, auto-logs every ride, with a saved-trips list and route maps. |
| 📐 **Smart resolution & orientation** | Auto-fits recognized dashes and learns unknown ones; manual landscape/portrait + SD/HD overrides. |
| 🔋 **Battery & power tuning** | Frame-rate caps (Smooth / Balanced / Saver) to reduce heat and drain during long rides. |
| 🛟 **Auto-recovery watchdog** | Detects a stalled or dropped dash and reconnects automatically — no Stop/Start. |
| 🔄 **Seamless resume** | Stop the bike for a bit? Projection parks to save battery, watches for the bike, and re-projects on its own when it's back — screen off, phone stowed. |
| 📱 **Whole-screen mirroring** | Optional: mirror your entire phone to the dash instead of Android Auto. |
| 🧰 **In-app diagnostics** | Live log panel with one-tap share for troubleshooting. |

---

## 📋 What you need

- **A CFMoto motorcycle with a MotoPlay / EasyConnect touchscreen dash.**
  Confirmed working: **800MT** (CFDL26) and **1000 MT‑X**. Other models may work partially — the app
  learns unrecognized dashes after the first connect (see [Resolution & orientation](#-resolution--orientation)).
- **An Android phone**, Android **10 or newer**.
- **Google Android Auto** installed and set up once (see [step 3](#3-one-time-android-auto-setup)). AA
  is what runs Maps/Waze — OpenCfMoto relays its screen to the bike.
- **The OpenCfMoto app** (`app-debug.apk`) — sideloaded (see [step 2](#2-install-the-app)).
- A **mobile-data plan** is recommended: the phone joins the bike's Wi‑Fi for the dash link, so live
  maps/traffic come over cellular.

No root, no VPN, no PC required to ride.

---

## 🚀 Getting started

### 1. Prepare the bike

While parked, open the **MotoPlay / phone-connection (EasyConnect) screen** on the dash so it shows
its **pairing QR code** — the same QR the official CFMoto app uses.

### 2. Install the app

The app isn't on the Play Store — you sideload the APK.

1. Download the latest **`OpenCfMoto.apk`** from the
   **[Releases page](https://github.com/zanderp/open-cfmoto/releases/latest)**
   (direct link: <https://github.com/zanderp/open-cfmoto/releases/latest/download/OpenCfMoto.apk>).
2. Tap it in a file manager / your browser downloads to install; allow installation from your
   browser/file manager when Android prompts about "unknown sources".
3. Open **OpenCfMoto** once and grant the permissions it requests:
   - **Location** — required by Android to join the bike's Wi‑Fi (the app doesn't track you) and to
     log trips.
   - **Camera** — to scan the bike's pairing QR code.
   - **Notifications** — so it can keep running in the background while you ride.

> 💡 The **Setup** screen has an *All granted* button that checks every permission at once and
> deep-links to system settings for anything missing.

### 3. One-time Android Auto setup

Android Auto must be installed and allowed to start in "self / head-unit" mode.

1. Install **Android Auto** from the Play Store (often pre-installed) and open it once to accept its
   terms.
2. In Setup, tap **Open Android Auto settings**, scroll to the bottom and tap **Version** about **10
   times** to unlock Developer settings.
3. In the **⋮ Developer settings** menu, turn **on** *"Add new cars to Android Auto"* / *"Unknown
   sources"* (wording varies by version).

You only do this once.

### 4. Connect and ride

1. In OpenCfMoto tap **Scan bike** and point the camera at the dash's QR code. The app reads your bike
   model + Wi‑Fi and remembers it.
2. Android Auto starts in the background and the phone pops a **Wi‑Fi dialog** to join the bike's
   hotspot (e.g. *CFMOTO4288*) — tap **Connect / Allow**.
3. The dash connects and **Android Auto appears on the dashboard**. 🎉

From then on, drive Android Auto **from the dash touchscreen** — tap, scroll, and pinch-to-zoom.
Your phone can be locked or in your pocket; a persistent notification keeps the link alive.

**Next time**, just tap **Connect to `<your bike>`** — or leave **Auto-connect** on and it links up on
launch whenever the bike's Wi‑Fi is in range.

**To stop:** tap **Stop** in the app. Closing or killing the app also ends projection cleanly.

---

## 🧭 Feature guide

### 🛰️ Trip computer & ride logging

The **Trip** screen is a GPS-driven ride computer showing live speed, distance, duration, and
max/avg speed. Speed/distance come from the phone's GPS (the bike doesn't share telemetry over the
mirroring link).

- **Automatic logging** — with *Log trips automatically* enabled, every projection session records a
  ride in the background, auto-segmenting when you stop for a while.
- **Saved trips** — tap **Saved trips** to browse past rides with their stats; tap one to see its
  **route on a map** (OpenStreetMap), or long-press to delete.
- Manual **Start / Pause / Reset** controls are there too.

<p>
<img src="docs/screenshots/04_trip.png" width="220" alt="Trip computer"/>&nbsp;
<img src="docs/screenshots/08_trips.png" width="220" alt="Saved trips"/>&nbsp;
<img src="docs/screenshots/09_trip_map.png" width="220" alt="Ride route on the map"/>
</p>

<br clear="all"/>

### 📱 Multiple bikes

<img src="docs/screenshots/07_devices.png" width="240" align="right" alt="Paired bikes"/>

**Devices** lists every bike you've paired. Pick one and tap **Use this bike**, **Scan new** to add
another, or **Remove** to forget one. The most recent bike is what one-tap **Connect** and
auto-connect target.

<br clear="all"/>

### 📐 Resolution & orientation

Android Auto only supports a fixed set of resolutions, and dashes come in different shapes. In
**Setup ▸ Resolution & orientation**:

- **Auto** fits recognized dashes automatically. For an **unrecognized** dash, the app reads the
  screen geometry the dash reports on connect and **remembers it** — so on the next connect it picks
  the right **orientation (landscape/portrait)** by itself. (You may see a one-time letterboxed
  frame the very first time; reconnect once and it self-corrects.)
- Manual overrides: **Landscape 800×480 / 1280×720 (HD)** and **Portrait 720×1280 / 1080×1920 (HD)**.
- **Screen fit** — *Fill* (crop), *Fit* (letterbox), or *Stretch* (fill with slight distortion).

> HD is sharper but heavier and can black-screen on some dashes — drop to a smaller size or Auto if
> that happens.

### 🔋 Battery & power, startup & recovery

<img src="docs/screenshots/03_setup2.png" width="240" align="right" alt="Setup — power & recovery"/>

This app runs a live video transcoder, so it always draws power and warms the phone. To manage that:

- **Battery & power** — cap the frame rate: *Smooth* / *Balanced* / *Saver* (coolest). Keep the phone
  on the bike's USB charger and turn its screen **off** while riding (projection keeps running).
- **Auto-connect on launch** — start projecting automatically when a paired bike's Wi‑Fi is in range.
- **Auto-recovery watchdog** — if the dash drops or the stream stalls, reconnect automatically.
- **Seamless resume** — see below.
- **Log trips automatically** — record every ride's route + stats while projecting.

<br clear="all"/>

### 🔄 Seamless resume (stop-and-go rides)

Stopped for fuel, a coffee, or a quick photo? When the bike's Wi‑Fi drops, the app keeps Android Auto
alive for a ~1 minute grace window (so brief blips resume instantly). If the bike stays off longer, it
**parks** the projection — tearing down the video transcode so the phone stops heating and draining —
and quietly watches for the bike's Wi‑Fi to come back. When it does, it **re-projects Android Auto and
maps automatically**. It keeps watching until you tap **Stop**, and it's gated behind the *Auto-recovery*
setting.

**For fully hands-free resume with the phone in a bag or the screen off**, enable one extra permission:

> **Setup ▸ Startup & recovery ▸ Seamless auto-resume ▸ “Enable seamless resume”** → turn on
> **Display over other apps** for OpenCfMoto.

Android blocks apps from relaunching Android Auto from the background unless they hold this permission.
- **Granted:** the app resumes projection entirely on its own — no touch needed, even locked/stowed.
- **Not granted:** everything still works, but resume ends with a **“Bike reconnected — tap to resume”**
  notification; one tap re-projects.

The permission is optional and off by default. Recommended if you keep your phone pocketed or in a
backpack while riding.

<br clear="all"/>

### 🎧 Handlebar buttons & calls (Bluetooth)

The dash sends the handlebar **media / track / call** buttons over **Bluetooth** — *not* the
mirroring link. **Pair your phone to the bike as a phone/audio device** and those buttons control
Android Auto media and calls directly. Setup shows your current Bluetooth status and a shortcut to
system Bluetooth settings.

### 🧰 Diagnostics

<img src="docs/screenshots/06_logs.png" width="240" align="right" alt="Diagnostics log"/>

Tap **Logs** to expand a live diagnostics panel. It narrates every step of the connection and is the
best way to understand what's happening. Use **Share** to export the log if you need help, or
**Clear** to reset it.

<br clear="all"/>

---

## 🔘 Button reference

| Button | What it does |
| --- | --- |
| **Connect to `<bike>`** | One-tap reconnect to your last paired bike and project Android Auto. |
| **Scan bike** | Scan a bike's pairing QR to pair/connect (adds it to Devices). |
| **Mirror** | Mirror your **whole phone screen** to the dash instead of Android Auto. |
| **Stop** | Stop everything and disconnect from the bike Wi‑Fi. |
| **Setup** | Permissions, Android Auto setup, display/battery, startup & recovery, Bluetooth. |
| **Devices** | Manage paired bikes (select / add / remove). |
| **Trip** | GPS trip computer + saved rides and route maps. |
| **Logs** | Show/hide the live diagnostics panel (with Share / Clear). |

---

## 🩺 Troubleshooting

**Normal behavior**
- A brief black/blank moment on the dash while it connects, then the map appears.
- Opening **All Apps** on the dash or **taking a call** may freeze the dash for a couple of seconds,
  then resume.
- Navigation **voice prompts** come out of your phone / paired helmet headset, not the bike speakers.

**If something's wrong**

| Symptom | Try this |
| --- | --- |
| App **won't connect** / keeps retrying, or the dash shows **"device is not on the network"** | This is usually the **dash's Wi‑Fi hotspot** stuck in a bad state. On the dash's phone-connection screen, **toggle between the Android and iOS (CarPlay) QR codes** — this restarts the HUD's Wi‑Fi network — then tap **Connect** again. |
| Dash stays **black** after connecting | Tap **Stop**, then **Connect** / **Scan bike** again. Make sure the dash is on its phone-connection screen. |
| **No Wi‑Fi dialog** appears | Confirm the Location permission is granted; move the phone next to the bike; tap **Stop** and retry. Some phones show the dialog behind Android Auto — swipe back to OpenCfMoto. |
| **Android Auto never starts** | Re-check [step 3](#3-one-time-android-auto-setup) (developer mode + unknown sources). |
| **Auto-connect doesn't fire** | Ensure *Auto-connect* is On, the bike is paired, and its Wi‑Fi is in range; open the app or return to it to retry. |
| Picture is **stretched / letterboxed** on an unknown bike | Reconnect once so it learns the dash shape, or set the orientation/size manually in Setup. |
| Dash **froze** and didn't recover | With *Auto-recovery* on it should reconnect itself; otherwise tap **Stop** then **Connect**. |
| **Didn't resume on its own** after a long stop | Enable **Seamless resume** (Setup ▸ Startup & recovery → *Display over other apps*) so the app can re-project with the screen off; otherwise tap the **“Bike reconnected”** notification. |

**Getting help:** reproduce the issue, then tap **Logs ▸ Share** and send the log file — it describes
each step and makes problems diagnosable.

---

## 📝 Good to know / limitations

- **Set your destination before riding.** Enter navigation while parked.
- Works over the bike's **Wi‑Fi** — keep the phone reasonably close to the dash.
- The live transcode **warms the phone and uses power** — charge it and turn the screen off while
  riding.
- On unsupported bikes or in poor Wi‑Fi you may hit the occasional hiccup and need a **Stop → Connect**.

---

## 🛠️ Building from source

Most riders can just install the release APK. To build it yourself:

1. Install **Android Studio** (bundles the JDK + Android SDK).
2. Create `local.properties` in the repo root pointing at your SDK, e.g.
   `sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk` (Windows) or
   `sdk.dir=/Users/<you>/Library/Android/sdk` (macOS). This file is git-ignored.
3. Build the debug APK:
   - Android Studio: open the project and **Run**, or
   - CLI: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`). The APK lands in
     `app/build/outputs/apk/debug/`.

Requires JDK 11+ (Android Studio's bundled JBR works). The build uses the Gradle configuration cache
for fast incremental rebuilds.

---

## 🙏 Acknowledgements

This project stands on the shoulders of the people who reverse-engineered the CFMoto MotoPlay link
and built the Android Auto plumbing before us. Huge thanks to:

- **[dcoletto/open-cfmoto](https://github.com/dcoletto/open-cfmoto)** — the original CFMoto EasyConnect
  mirroring app this is based on.
- **[richardbizik/open-cfmoto](https://github.com/richardbizik/open-cfmoto)** — for the 1000 MT‑X
  support and profile work.
- **[BojanJ/open-cfmoto](https://github.com/BojanJ/open-cfmoto)** — for the more complete Android Auto
  integration we built on top of.
- **[headunit-revived](https://github.com/andreknieriem/headunit-revived)** by *andreknieriem* — the
  Android Auto (AAP) receiver foundation.

Thank you to everyone in the CFMoto/EasyConnect community who shared logs, captures, and findings that
made this possible. See the [`docs/`](docs/) folder for the technical/architecture write-ups.

<div align="center">

<sub>Built with ❤️ for the CFMoto community.</sub>

</div>
