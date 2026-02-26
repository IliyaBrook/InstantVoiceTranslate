# Instant Voice Translate

Android app for real-time voice translation. Captures audio from microphone or system audio, recognizes speech locally on-device via Sherpa-ONNX, translates text through Yandex Translate API, and speaks the result via Android TTS.

## Overview

The app implements a full voice translation pipeline running as an Android foreground service:

```
Audio input (microphone / system audio)
        |
Streaming speech recognition (Sherpa-ONNX, offline)
        |
Neural translation (Yandex API + fallback backend)
        |
Text-to-speech (Android TTS)
```

Speech recognition runs entirely on-device without sending audio to a server. Text translation requires an internet connection.

## Features

- **Two audio sources** -- microphone (direct recording) and system audio (via AudioPlaybackCapture / MediaProjection)
- **Offline speech recognition** -- streaming Zipformer model (Sherpa-ONNX), ~44 MB, auto-downloaded from HuggingFace on first launch
- **Dual translation backend** -- primary Yandex Translate API, fallback `translate.toil.cc`, LRU cache (200 entries)
- **Translation playback** -- Android TTS with Kotlin Channel-based queue, adjustable speed (0.5x--2.0x) and pitch
- **Feedback loop prevention** -- microphone muting during TTS playback to prevent echo loops
- **8 translation languages** -- English, Russian, Spanish, German, French, Chinese, Japanese, Korean (ISO 639-1)
- **Foreground service** -- continues running in background, control via notification (pause/resume/stop)
- **Partial results display** -- intermediate ASR text with real-time animation
- **Display settings** -- toggle visibility of original text and partial ASR results
- **Theming** -- system, light, dark; dynamic Material You colors on Android 12+

## Tech Stack

| Component          | Technology                                            |
|--------------------|-------------------------------------------------------|
| Language           | Kotlin 2.1.20, JVM 17                                 |
| UI                 | Jetpack Compose + Material 3 (BOM 2026.02.00)         |
| DI                 | Hilt 2.56.2 + KSP                                     |
| Async              | Kotlin Coroutines + Flow / StateFlow / Channel        |
| Speech Recognition | Sherpa-ONNX (local AAR, streaming-zipformer-en model) |
| Translation        | Yandex Translate API + fallback (OkHttp 5.3.2)        |
| Text-to-Speech     | Android TextToSpeech                                  |
| Settings Storage   | DataStore Preferences                                 |
| Navigation         | Navigation Compose 2.9.7                              |
| Build              | AGP 8.9.3, Gradle                                     |
| Min SDK            | 29 (Android 10)                                       |
| Target SDK         | 36                                                    |

## Requirements

- **Android Studio** Ladybug or newer
- **JDK 17**
- **Android SDK 36** (compileSdk)
- **Device or emulator** with API 29+ (Android 10 required for AudioPlaybackCapture)

## Build & Run

```bash
# Clone the repository
git clone <repo-url>
cd instant-voice-translate

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Full build with lint checks
./gradlew build

# Release APK / AAB
./gradlew assembleRelease
./gradlew bundleRelease
```

> **Note:** The file `app/libs/sherpa-onnx.aar` must be present in the repository. It is a local dependency for the Sherpa-ONNX speech recognition library.

## Usage

1. On first launch the app automatically downloads the ASR model (~44 MB) from HuggingFace. Download progress is displayed on the main screen.
2. Select the audio source: **Microphone** or **System Audio**.
3. Tap the microphone FAB to start translation.
   - Selecting System Audio requires screen capture permission (MediaProjection).
4. Translated text appears on the main card; the original phrase is shown below in italics.
5. Control via notification: pause, resume, stop.
6. Settings are accessible via the gear icon in the top bar.

### Settings Screen

- **Languages** -- source and target language selection (EN, RU, ES, DE + 4 more)
- **Text-to-Speech** -- speech rate, pitch, auto-playback, microphone muting during TTS
- **Display** -- show/hide original text and partial ASR results
- **Theme** -- system / light / dark

## Project Structure

```
app/src/main/java/com/example/instantvoicetranslate/
|
|-- MainActivity.kt                    # Activity, navigation, permission requests
|-- InstantVoiceTranslateApp.kt        # Application, notification channel
|
|-- asr/
|   |-- SpeechRecognizer.kt            # Speech recognition interface
|   |-- SherpaOnnxRecognizer.kt        # Sherpa-ONNX Zipformer implementation
|
|-- audio/
|   |-- AudioCaptureManager.kt         # Audio capture: microphone and system audio
|
|-- data/
|   |-- TranslationUiState.kt          # Singleton UI state (StateFlow)
|   |-- SettingsRepository.kt          # Settings via DataStore
|   |-- ModelDownloader.kt             # ASR model download from HuggingFace
|
|-- di/
|   |-- AppModule.kt                   # Hilt module with interface bindings
|
|-- service/
|   |-- TranslationService.kt          # Foreground service, pipeline orchestration
|
|-- translation/
|   |-- TextTranslator.kt              # Text translation interface
|   |-- FreeTranslator.kt              # Yandex API + fallback, LRU cache
|
|-- tts/
|   |-- TtsEngine.kt                   # Android TTS with queue and state tracking
|
|-- ui/
    |-- screens/
    |   |-- MainScreen.kt              # Main screen: translation, model status
    |   |-- SettingsScreen.kt          # Settings screen
    |-- theme/
    |   |-- Theme.kt                   # Material 3, dynamic colors, ThemeMode
    |-- viewmodel/
        |-- MainViewModel.kt           # Main screen ViewModel
        |-- SettingsViewModel.kt       # Settings ViewModel
```

## Architecture

The app follows the **MVVM** pattern with a foreground service as the central orchestrator:

```
UI (Compose)  <-->  ViewModel  <-->  TranslationUiState (singleton)
                                            ^
                                            |
                                    TranslationService (foreground)
                                            |
                        +-------------------+-------------------+
                        |                   |                   |
                AudioCaptureManager  SherpaOnnxRecognizer  FreeTranslator
                (mic / system audio)  (streaming ASR)      (Yandex + fallback)
                                                                |
                                                            TtsEngine
                                                        (playback queue)
```

**Key decisions:**

- `TranslationUiState` -- a singleton with `StateFlow` fields shared across the service, ViewModel, and UI. Bridges the foreground service and Compose UI without binding to the Activity lifecycle.
- `TranslationService` manages the full pipeline: starts audio capture, feeds the stream to the recognizer, sends segments for translation, and plays back results.
- Audio is delivered in ~100 ms chunks (1600 samples, 16 kHz, mono, PCM 16-bit).
- TTS uses a `Channel`-based queue for sequential playback and `isSpeaking: StateFlow` to prevent feedback loops.
- DI via a single Hilt module `AppModule` with bindings from `SpeechRecognizer` and `TextTranslator` interfaces to concrete implementations.

## Permissions

| Permission                            | Purpose                                          |
|---------------------------------------|--------------------------------------------------|
| `RECORD_AUDIO`                        | Microphone recording                             |
| `FOREGROUND_SERVICE`                  | Running the service in background                |
| `FOREGROUND_SERVICE_MICROPHONE`       | Foreground service type for microphone           |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Foreground service type for system audio capture |
| `POST_NOTIFICATIONS`                  | Notifications (Android 13+)                      |
| `INTERNET`                            | Translation API and model download               |
| `ACCESS_NETWORK_STATE`                | Network state check                              |
| `WAKE_LOCK`                           | Prevent sleep while service is running           |

## Default Configuration

| Parameter              | Value        |
|------------------------|--------------|
| Source language        | English (en) |
| Target language        | Russian (ru) |
| Audio source           | Microphone   |
| TTS speed              | 1.0x         |
| TTS pitch              | 1.0x         |
| Auto-playback          | Enabled      |
| Mute microphone on TTS | Disabled     |
| Theme                  | System       |
