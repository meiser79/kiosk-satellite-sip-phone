# Kiosk Satellite SIP Phone Plugin

A SIP softphone for Kiosk Satellite. It registers with a PBX over UDP, displays incoming calls full-screen with the caller ID and **Answer** and **Decline** controls, and carries calls over RTP using G.711.

The plugin interface, settings, logs and status are in English. When Android's system language is German, the incoming call screen uses German labels from [`translations/de.properties`](translations/de.properties); caller names and phone numbers are shown as received.

## Features

- SIP REGISTER with Digest authentication (MD5, `qop=auth`) and automatic renewal
- Native full-screen call overlay with caller ID and themed Answer, Decline, and Hang up controls; falls back to a plugin window on older Kiosk Satellite versions
- Incoming call handling with 100 Trying / 180 Ringing, CANCEL, and decline with 603
- Answer calls with SIP 200 OK + SDP; RTP media starts after ACK
- G.711 PCMU (payload 0) and PCMA (payload 8), 8 kHz mono, using the Android microphone and speaker
- Remote BYE and an in-call Hang up button
- Home Assistant entities: Call status, SIP registered, Last call, and Do not disturb
- `answer` and `decline` actions for gestures, the drawer and ESPHome buttons
- German labels for the incoming call screen when Android's system language is German
- Plays Kiosk Satellite's built-in intercom telephone ring every four seconds while an incoming call is ringing

## Setup

1. Build the plugin ZIP with `python3 tools/build.py` (see the build requirements below) and install it from Plugin Manager > Developer Tools > Install from ZIP.
2. Configure the SIP server, username and password, then enable the plugin.
3. Ensure the SIP server and its RTP port range are reachable. Incoming INVITEs must include an SDP offer with PCMU or PCMA.

## Build and tests

`python3 tools/test.py` compiles the plugin with JDK 8 or later and an installed Android SDK platform, then tests SIP registration, call control, overlay requests, SDP negotiation, localization and assets against a loopback mock PBX. Building the Android plugin ZIP also requires Android build-tools. Ringtone, microphone, speaker and native overlay rendering must be tested on the target device.

The build compiles `translations/de.properties` into the plugin's DEX code, keeping `plugin.jar` limited to DEX files as required by Kiosk Satellite. Add further locales using the same pattern and update `PluginText` to select them.

## Limitations

- UDP and IPv4 only; no TLS, SRTP, ICE or video
- G.711 PCMU/PCMA at 8 kHz mono; other codecs and DTMF/RFC 2833 are not supported
- No plugin-level echo or noise cancellation; results depend on the Android device and audio driver
- The media address from SDP must be reachable from the kiosk
- Native overlays require a Kiosk Satellite version that supports the SDK 1 overlay API. Older versions show the plugin's floating call window instead.
