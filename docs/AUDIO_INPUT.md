# Audio input selection / 音频输入来源

Status: implemented. Built-in and Enco X3 SCO input have passed local device checks on one Android 17 foldable; USB/UAC, BLE Audio and other hardware remain unverified. See TESTING.md.

## Product behavior

Audio sources are independent of speech providers and image sources. The default is the built-in microphone. Users choose an input in **Settings → Audio input settings** or the right **Input sources** drawer. Choosing a source alone never records. **Start / Stop listening** remains the recording control.

Supported candidates are system-exposed built-in, wired, USB/UAC and Bluetooth voice/LE Audio inputs. A capture card appears as USB audio only if Android exposes its audio function. Video and audio may use different devices; disabling video must not stop audio. Only one audio input is captured at a time. This implementation does not provide a raw USB audio driver or simultaneous microphone mixing.

The drawer shows a native radio group of sources, input status with a transparent refresh icon on its right, actual routed device during capture, a level meter, and an audio settings shortcut. Audio and video use the same styled radio rows. Its entire content scrolls on short landscape screens. The settings page adds a local input test, refresh and system Bluetooth settings. Local tests display level only: no audio file, playback, recognition request or text request. During listening, the meter reuses the existing stream; a second recorder is prohibited. Leaving the page or backgrounding stops a local test.

All owned controls and errors use Simplified Chinese / English according to the existing system-language policy. Physical device names are left intact. The existing keyboard shortcut bindings remain independent of Bluetooth audio.

## Selection and lifecycle

| Situation | Behavior |
| --- | --- |
| Idle selection | Persist preference; open only on Start or explicit Test |
| Source changed while testing | Stop local test; do not automatically record from the new source |
| Connecting / finalizing speech | Defer source changes until that operation finishes |
| Listening source change | Confirm possible interruption; close the old capture/route before opening the new one |
| Successful switch | Keep ASR connection, meeting, preference, answer and transcript; do not invoke manual text analysis |
| Route missing, rejected, lost or mismatched | Stop with a visible error; retain received text; never silently use another microphone |
| Reconnection | Update availability; never automatically start recording |
| Background / lock | Existing session background policy stops recording; local tests also stop |
| Activity recreation | Keep listening source and stream; restore settings page; local tests stop |

A switch records a timestamped **application event** in the transcript, explicitly warning that sound may have been missed. Events are stored separately from recognized segments, survive encrypted meeting persistence and archive, and are supplied as application context rather than utterances. Events alone cannot trigger an answer. Per-round event count is bounded at 128. An existing ASR connection can still fail during a long Bluetooth transition; in that case listening stops with its normal connection error. No claim of seamless device switching is made.

Received stable speech and current partial text remain available after capture failure; incomplete speech is not silently treated as fully finalized or submitted for a final analysis.

## Architecture

- `AudioInputSettings`: global physical-source preference, independent of meeting/provider configuration. Old settings default to built-in; invalid fields repair independently.
- `AndroidAudioDevices`: input enumeration, hotplug notifications, system USB audio, Bluetooth routing, AudioRecord and format negotiation. The UVC video adapter does not claim audio interfaces.
- `AudioInputController`: exclusive capture ownership, actual-route validation, bounded producer/delivery queue, switching, cancellation and local test state. Hardware interfaces are injected in JVM tests.
- `PcmNormalizer`: streaming PCM16 stereo downmix / rate normalization, retaining phase and incomplete interleaved frames across chunks. Current ASR output is mono PCM16LE at 16 kHz.
- `AudioInputUi`: shared drawer controls and setup page; permission flows and explicit live-switch confirmation.
- `AudioSource.Listener.discontinuity`: source-independent application event; does not stop/finalize the speech transport.

The preferred device request is not proof of actual routing. Verify `AudioRecord.getRoutedDevice()` after recording starts and on every frame; discard unverified startup frames and stop if verification cannot complete within three seconds. A mismatched route is never forwarded. Watch client silencing on supported systems. Stopping drains accepted delivery frames before ASR finish; cancel discards queued frames and suppresses late callbacks.

Capture and network delivery use separate workers. A queue of 20 approximately 100 ms PCM frames bounds waiting audio to about two seconds; overflow stops listening with an explicit congestion error. OCR, text generation and UI callbacks do not run on the capture thread. Workers and native resources are released before another source owns capture.

First request 16 kHz mono from Android; negotiate alternate 48/44.1 kHz mono/stereo or 8 kHz mono configurations if initialization fails. Convert to the service format locally. Resampling does not restore bandwidth absent in the original microphone. The level meter indicates signal strength, not speech presence or recognition accuracy; silence can be legitimate. Advanced channel selection, gain controls and acoustic quality processing are outside this version.

## Identity, Bluetooth and permissions

Persist a source category and SHA-256 locator derived from device metadata, rather than the transient AudioDeviceInfo ID. Bluetooth uses type, product name and the retained address suffix so that Android privacy-masked and full addresses resolve to the same source after permission refresh; other sources use type, product and address. Indistinguishable input or communication endpoints are rejected as ambiguous. Raw addresses are not persisted or logged. Hardware/port changes can change a locator; require reselection instead of guessing. Inputs with indistinguishable locators are marked ambiguous and cannot be selected while ambiguous. Built-in mic elements are represented as one logical built-in source.

Android 12+ uses communication-device routing and Android 8–11 uses legacy SCO. Modern enumeration also includes available voice/LE Audio communication endpoints whose input becomes visible only after routing. Legacy enumeration uses connected headset-profile devices; no discovery scan. Wait for route activation, with cancellation and a maximum 30-second Bluetooth setup deadline. Always release the communication selection / SCO and the mode owned by the app on exit. Communication mode may also change headset playback behavior. A2DP playback-only devices and Bluetooth keyboards are excluded.

Permissions: existing `RECORD_AUDIO`, plus normal `MODIFY_AUDIO_SETTINGS`, legacy `BLUETOOTH` (max SDK 30) and runtime `BLUETOOTH_CONNECT` on Android 12+. Bluetooth is optional hardware. Request microphone permission only for explicit recording/testing, and Nearby devices only for Bluetooth features. Pairing occurs in Android settings; no Bluetooth discovery, location, storage, new provider URL or API credential is introduced. Permission denial/revocation is handled as an unavailable input, without falling back.

## Validation

Desktop tests use fake device catalogs, routes, audio frames, permission states and speech/text providers. They cover exclusive ownership, drain/cancel, rejected/missing/ambiguous inputs, actual-route mismatch, no auto-resume, rapid switches, Bluetooth setup cancellation, bounded backpressure, local tests, event persistence/context, format conversion and responsive bilingual UI.

Physical acceptance still requires built-in / USB / Bluetooth devices and capture-card audio on the actual phone, including video + audio concurrently, hot unplug, profile activation/release, silence/mute and reconnection. No real model request is required for initial device and level checks. Keep any temporary screen-awake changes scoped to testing, and restore the user's screen timeout and developer charging-awake option after all phone checks.

References: [AudioRecord routing](https://developer.android.com/reference/android/media/AudioRecord), [Bluetooth voice / LE Audio routing](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager), [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
