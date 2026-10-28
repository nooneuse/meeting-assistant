# Meeting Assistant

[English](README.md) · [简体中文](README.zh-CN.md)

An open-source Android assistant that combines live transcripts and text from slides or documents to answer questions and offer suggestions during discussions.

**Android 8.0+ · Bring your own API keys · MIT**

## Features

- **Manual and Auto modes:** analyze when you stop listening, or continuously update answers as the discussion develops.
- **Audio input:** use a built-in, USB or supported Bluetooth microphone, or an available capture-card audio input.
- **Image input:** capture from a phone camera or USB video source and recognize text and layout alongside the transcript.
- **Topics and collections:** organize discussions in folders, resume earlier topics, and keep each topic's context separate.
- **Response preferences:** save and switch instructions for the subject, language and style of answers.
- **Keyboard controls:** customize Bluetooth keyboard shortcuts for listening, capture, scrolling and topic navigation.
- **Adaptive interface:** portrait, landscape and foldable layouts, system light/dark mode and dynamic colors, with English and Simplified Chinese interfaces.

## Getting started

1. Open **☰ → Settings → Model connections** and enter the service address, model and API key for speech recognition and text analysis. Configure an image recognition service to use image input.
2. Open the left sidebar to create a topic or resume an existing one. Use collections to organize related topics.
3. Save instructions under **Response preferences** in Settings, then tap the preference name on the main screen to switch.
4. Tap **Start listening**, then **Stop listening** to analyze. Enable **Auto** before listening for automatic segmentation and ongoing answer updates.
5. For slides or documents, open **Input sources** in the top-right corner, enable video and select a source. Preview it in **Image input settings**, then use the camera button to capture a frame for analysis.

Use the bulb to keep the screen awake, the transcript button to show or hide the original text, and the copy icons to copy results. Audio sources can also be selected from **Input sources**.

### Keyboard shortcuts

Pair a keyboard in Android settings, then customize bindings under **Settings → External keys**.

| Action | Default key |
| --- | --- |
| Start / stop listening | Space |
| Scroll answer up / down | ↑ / ↓ |
| Scroll transcript up / down | Page Up / Page Down |
| Use no response preference | 0 |
| Toggle keep-awake | L |

Auto, image capture, new topics, topic/collection navigation and transcript visibility can also be bound to keys.

## How it works

Speech is transcribed in real time. Captured images are processed into text and layout information. The text model combines these inputs with the current topic's context and response preference to generate or revise an answer. In Auto mode, audio capture continues while text analysis runs.

Each topic maintains its own history and context. Context management selects and organizes relevant information so model input stays bounded as a discussion grows. Collections organize topics without sharing their context.

Service adapters separate the app's workflow from provider APIs:

| Task | Supported interface |
| --- | --- |
| Speech recognition | Alibaba Cloud Fun-ASR Realtime |
| Text analysis | OpenAI-compatible Chat Completions, including hosted DeepSeek |
| Image recognition | Qwen3.5-OCR or compatible structured vision output |

Audio and images are sent to the configured recognition services; relevant text is sent to the text service. Topic records and API keys are encrypted on the device. The app saves no audio recordings and has no cloud sync. Listening runs in the foreground. Service charges and data policies are set by your providers; verify important model output against the source material.

## Build and install

Use **JDK 21**, **Android SDK 36** and **Build Tools 36.0.0**. Open the project in Android Studio or set `ANDROID_HOME`, then run:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat`. See [Contributing](CONTRIBUTING.md) for development and test commands.

## Documentation

- [Product design](docs/DESIGN.md) · [Architecture and adapters](docs/ARCHITECTURE.md)
- [Audio input](docs/AUDIO_INPUT.md) · [Image input and OCR](docs/OCR_INPUT.md)
- [Topics and collections](docs/TOPIC_COLLECTIONS.md) · [Context management](docs/CONTEXT_MANAGEMENT.md)
- [Themes](docs/THEMING.md) · [Localization](docs/LOCALIZATION.md) · [About and versioning](docs/ABOUT.md)
- [Testing](docs/TESTING.md) · [Security](SECURITY.md) · [Contributing](CONTRIBUTING.md)

## License

[MIT](LICENSE). Dependencies retain their own licenses; see [Third-party notices](THIRD_PARTY.md).
