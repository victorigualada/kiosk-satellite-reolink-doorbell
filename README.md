# Reolink Doorbell for Kiosk Satellite

A [Kiosk Satellite](https://kiosksatellite.com) plugin that shows a wired or PoE Reolink doorbell on the kiosk. The kiosk connects **straight to the doorbell over RTSP** and decodes the video on the tablet, so there is no go2rtc, FFmpeg or other restreamer in between. When someone presses the doorbell, the kiosk wakes up and opens the viewer on its own.

> **Status: early prototype.** Video, listening and auto-open on ring are built but not yet tested on a real doorbell. Talking back to the doorbell is planned, not built.

## What it does

- Live video (H.264 or H.265) and audio (AAC) from the doorbell's own RTSP stream, with Digest authentication.
- Opens full screen when Home Assistant's Reolink integration reports a visitor (the doorbell button), waking the screen and covering the screensaver.
- Fires a **Doorbell rang** gesture trigger, so you can also run anything the Gestures page offers on a ring.
- **Test connection** button that logs in to the doorbell's HTTP API and shows its model and firmware.
- Optional Home Assistant door lock button, reusing the kiosk's existing Home Assistant connection.
- Commands **Open Reolink doorbell**, **Close Reolink doorbell** and **Simulate a doorbell ring**, which you can put in the kiosk drawer or expose to Home Assistant.

## Requirements

- A wired or PoE Reolink doorbell. The battery doorbell has no local RTSP without a Home Hub.
- RTSP enabled on the doorbell (Reolink app: *Settings > Network > Advanced > Port settings*). Enable HTTP too if you want to use **Test connection**.
- Kiosk Satellite with plugin SDK 1 and native overlays, on Android 8.0 or newer.
- For auto-open on ring: Home Assistant's Reolink integration and the kiosk connected to Home Assistant.

## Set up

1. In Kiosk Satellite, open **Settings > Plugin Manager**, choose **Add plugin** and paste `https://github.com/victorigualada/kiosk-satellite-reolink-doorbell`.
2. Fill in the doorbell's address, username and password. Use **Test** to check them.
3. Pick the doorbell's **Visitor sensor** (for example `binary_sensor.front_door_visitor`).
4. Run **Simulate a doorbell ring** to check the viewer, then press the real doorbell.

The **sub** stream is the default because it starts fast and suits small tablets. Switch to **main** for full resolution if the tablet can decode it.

## Security

- RTSP and the HTTP API run over plain HTTP on your LAN. RTSP uses Digest authentication, but the HTTP login used by **Test connection** sends the password in the request body. Use a dedicated Reolink user for the kiosk and keep the doorbell on a trusted network.
- The password uses Kiosk Satellite's secret setting type, so the settings pages mask it.
- The plugin runs inside Kiosk Satellite with its permissions, like every plugin.

## Build and test

Use JDK 17 or newer, Python 3.9+ and an Android SDK with platform 35 and build-tools. Set `ANDROID_HOME`.

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The build writes `dist/reolink-doorbell-<version>.zip`. Releases are built by the GitHub Actions workflow when a release is published.

## Roadmap

1. Test video, audio and auto-open on a real Reolink PoE doorbell.
2. Two-way audio through the doorbell's ONVIF RTSP backchannel.
3. Ring detection without Home Assistant, through ONVIF events.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
