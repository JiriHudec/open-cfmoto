# Current App — OpenCfMoto Architecture & State

Package `dev.coletz.opencfmoto`. Kotlin, `minSdk 29`, `targetSdk 36`. Gradle version catalog at
`gradle/libs.versions.toml`. Deps: AppCompat/Material, CameraX + ML Kit barcode (QR), JmDNS (mDNS),
constraintlayout. (No AA/HUR deps yet — you'll add them.)

## What works TODAY on real hardware ✅

1. Scan the bike QR → parse creds.
2. Join the bike Wi-Fi (`WifiNetworkSpecifier`).
3. Full **PXC control handshake** with the bike (probe → CLIENT_INFO → channel selects → SN check →
   heartbeats). Phone acts as server on 10920/10921/10922.
4. **Media negotiation** (config capture → 800×384 H.264, version, heartbeat, configcaptureextend).
5. **H.264 streaming to the dash** in two modes, both confirmed showing on the bike:
   - **UI mode** — a `Presentation` ("Hello World") on a private `VirtualDisplay` → encoder → bike.
   - **Mirror mode** — full phone screen via `MediaProjection` (**"Entire screen"** capture) → encoder → bike.
6. Confirmed: running **Android Auto in non-VPN self mode** (via headunit-revived) alongside the app and
   using **whole-screen mirror** shows Android Auto navigation on the dash. (This is the smoke test that
   green-lit the full plan.)

Known limitations of the current mirror path (why we're doing the full plan): whole-screen mirror
occupies the phone; single-app capture doesn't work (Android 14 partial-projection needs extra handling
we didn't add); mirror dies when the screen locks; aspect is letterboxed.

## Source files (all under `app/src/main/java/dev/coletz/opencfmoto/`)

### Bike / PXC pipeline (the working core — DO NOT rewrite; you'll only swap the video source)
- **`EasyConnProber.kt`** — the heart. Resolves IPs, binds servers on 10920/10921/10922, sends the
  `0x70000010` probe to `bike:10930`, accepts the bike's call-backs, routes by port to either the
  CmdBaseHead control loop (`PxcHandshake`) or the ReqBase `mediaLoop`. `handleMediaReq` answers
  config-capture(16→17), version(48→49), heartbeat(64→65), extend(96→97), data-start(112→113), and on
  data-next(114) pulls a frame from `VideoPipeline.pollFrame()` and `sendFrameRaw()`s it
  (`[size LE][AnnexB AU]`). Owns the `VideoPipeline` instance (created on cmd 112).
- **`PxcFrame.kt`** — CmdBaseHead 16-byte codec (`read`/`write`) + all cmd-ID constants.
- **`PxcHandshake.kt`** — control-plane JSON dispatcher (CLIENT_INFO reply, SN result, channel acks,
  heartbeats). Builds the phone CLIENT_INFO with `RsaKeys`.
- **`RsaKeys.kt`** — in-memory RSA keypair; `publicKeyBase64` + `signHuid()` for CLIENT_INFO.
- **`VideoPipeline.kt`** — MediaCodec H.264 encoder (800×384, see `01` §5). Two sources:
  `setupDisplayAndPresentation()` (UI mode, DisplayManager VirtualDisplay + Presentation) and
  `setupProjectionDisplay()` (mirror mode, MediaProjection). Drain loop → bounded frame queue →
  `pollFrame(timeoutMs)`. **This is where the Android Auto video will plug in as a third source.**

### Wi-Fi / QR / UI
- **`MainActivity.kt`** — buttons (Scan / Scan Mirror / Stop / Share / Clear), the QR scan launcher, the
  MediaProjection consent flow (+ `ProjectionService` foreground-service polling), the on-screen log +
  Share export. Note: a stashed batch had Reconnect/auto-connect + a two-row layout; current tree is the
  simpler version.
- **`QrScanActivity.kt`** / **`QrData.kt`** — CameraX + ML Kit QR scan and URL parse.
- **`BikeWifi.kt`** — `WifiNetworkSpecifier` join. NOTE: this always shows a system "connect?" dialog
  (no silent join possible for a non-system app). `bindProcessToNetwork` binds our process to the bike
  Wi-Fi so our sockets use it while other apps (e.g. Android Auto) use cellular for internet.

### MediaProjection plumbing
- **`ProjectionService.kt`** — `mediaProjection` foreground service (required on Android 14+ before
  `getMediaProjection`). Exposes `isForeground` flag that MainActivity polls.
- **`ProjectionHolder.kt`** — holds the active `MediaProjection`; null = UI mode, non-null = mirror.

### BLE (dormant — not used for projection)
- **`BleWakeUp.kt`**, **`BleProtocol.kt`**, **`BleSecrets.kt`** — the BLE wake-up (see `01` §6). Leave.

## Build / run / debug

- Standard Gradle Android build. Owner installs to a Samsung SM-G991B (Android 15) for real use.
- **On-screen log + Share button** is the primary debugging channel — the owner pastes the exported log.
  Anything you add MUST log its key steps with a stage tag.
- The `MediaProjection` "Entire screen" vs "Single app" choice matters: single-app (partial) capture is
  NOT handled and yields no frames; always test with "Entire screen" until/unless partial-capture is
  implemented (not needed for the AA plan).
- Bike media timeout is ~9s — a source that doesn't produce a frame quickly will make the bike drop.

## The integration point for Android Auto

`VideoPipeline` currently gets pixels from either a Presentation or MediaProjection. For the AA plan you
will add a **third source**: Android Auto's decoded video rendered into the *same encoder input
surface* (`VideoPipeline.inputSurface`). Everything downstream (encoder → frame queue → PXC data
socket → bike) stays exactly as-is. See `03-PLAN-ANDROID-AUTO.md`.
