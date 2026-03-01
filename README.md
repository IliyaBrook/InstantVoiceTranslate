# Instant Voice Translate

Android app for real-time voice translation. Captures audio from microphone or system audio, recognizes speech locally
on-device via Sherpa-ONNX, translates text online (Yandex API) or offline (NLLB-200), and speaks the result via
Android TTS.

## Overview

The app runs a full voice translation pipeline as an Android foreground service:

```
Audio input (mic / system audio)
        |
Streaming speech recognition (Sherpa-ONNX, on-device)
        |
Translation -- online (Yandex API) or offline (NLLB-200)
        |
Text-to-speech (Android TTS)
```

Speech recognition always runs on-device. Translation works in two modes: **online** (Yandex API, requires internet)
or **offline** (NLLB-200-distilled-600M via ONNX Runtime, no internet needed after model download).

## Features

- **Two audio sources** -- microphone and system audio (AudioPlaybackCapture / MediaProjection)
- **Offline speech recognition** -- streaming Zipformer models (Sherpa-ONNX), 26--188 MB per language, auto-downloaded
  from HuggingFace
- **Dual translation backend**:
	- **Online** -- Yandex Translate API + fallback, LRU cache (200 entries), 13 target languages
	- **Offline** -- NLLB-200-distilled-600M via ONNX Runtime, ~1.3 GB download, 31 target languages, no internet needed
- **TTS playback** -- Android TTS with queue, adjustable speed (0.5x--2.0x) and pitch
- **Feedback loop prevention** -- microphone muting during TTS playback
- **7 source languages** with ASR models, **up to 31 target languages** (offline mode)
- **Foreground service** -- background operation, notification controls (pause/resume/stop)
- **Parallel translation** -- up to 3 concurrent translations with ordered TTS delivery
- **i18n** -- app UI available in English and Russian (Per-App Language Preferences)
- **Theming** -- system / light / dark, Material You dynamic colors on Android 12+

## Tech Stack

| Component           | Technology                                     |
|---------------------|------------------------------------------------|
| Language            | Kotlin 2.1.20, JVM 17                          |
| UI                  | Jetpack Compose + Material 3 (BOM 2026.02.01)  |
| DI                  | Hilt 2.56.2 + KSP                              |
| Async               | Kotlin Coroutines + Flow / StateFlow / Channel |
| Speech Recognition  | Sherpa-ONNX (local AAR, streaming Zipformer)   |
| Online Translation  | Yandex Translate API + fallback (OkHttp 5.3.2) |
| Offline Translation | NLLB-200-distilled-600M (ONNX Runtime 1.17.1)  |
| Text-to-Speech      | Android TextToSpeech                           |
| Settings Storage    | DataStore Preferences                          |
| Navigation          | Navigation Compose 2.9.7                       |
| Build               | AGP 8.9.3, Gradle                              |
| Min / Target SDK    | 29 (Android 10) / 36                           |

## Requirements

- **Android Studio** Ladybug or newer
- **JDK 17**
- **Android SDK 36** (compileSdk)
- **Device or emulator** with API 29+ (Android 10 required for AudioPlaybackCapture)

## Build & Run

```bash
# Debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Full build with lint
./gradlew build

# Release APK / AAB (requires keystore.properties)
./gradlew assembleRelease
./gradlew bundleRelease
```

> **Note:** `app/libs/sherpa-onnx.aar` must be present -- it is a local dependency for Sherpa-ONNX speech recognition.

## Headphones Recommended

> When using microphone mode, **use headphones** to prevent feedback loops. Without them, the ASR model picks up
> TTS playback and re-translates its own output. Alternatively, enable "Mute mic during TTS" in Settings (may cause
> missed speech segments).

## Supported Languages

Source languages require a local ASR model (26--188 MB, auto-downloaded). Target language availability depends on
translation mode.

| Language   | Code | Source (ASR) | Target: Online | Target: Offline |
|------------|------|--------------|----------------|-----------------|
| English    | en   | yes          | yes            | yes             |
| Russian    | ru   | yes          | yes            | yes             |
| Spanish    | es   | yes          | yes            | yes             |
| German     | de   | yes          | yes            | yes             |
| French     | fr   | yes          | yes            | yes             |
| Chinese    | zh   | yes          | yes            | yes             |
| Korean     | ko   | yes          | yes            | yes             |
| Japanese   | ja   | --           | yes            | yes             |
| Portuguese | pt   | --           | yes            | yes             |
| Italian    | it   | --           | yes            | yes             |
| Turkish    | tr   | --           | yes            | yes             |
| Arabic     | ar   | --           | yes            | yes             |
| Hindi      | hi   | --           | yes            | yes             |
| Ukrainian  | uk   | --           | --             | yes             |
| Polish     | pl   | --           | --             | yes             |
| Dutch      | nl   | --           | --             | yes             |
| Swedish    | sv   | --           | --             | yes             |
| Czech      | cs   | --           | --             | yes             |
| Romanian   | ro   | --           | --             | yes             |
| Hungarian  | hu   | --           | --             | yes             |
| Greek      | el   | --           | --             | yes             |
| Danish     | da   | --           | --             | yes             |
| Finnish    | fi   | --           | --             | yes             |
| Norwegian  | no   | --           | --             | yes             |
| Thai       | th   | --           | --             | yes             |
| Vietnamese | vi   | --           | --             | yes             |
| Indonesian | id   | --           | --             | yes             |
| Malay      | ms   | --           | --             | yes             |
| Hebrew     | he   | --           | --             | yes             |
| Bulgarian  | bg   | --           | --             | yes             |
| Georgian   | ka   | --           | --             | yes             |

## Usage

1. On first launch, the app downloads the ASR model for the selected source language from HuggingFace.
2. Select audio source: **Microphone** or **System Audio**.
3. Tap the microphone button to start translation. System Audio requires screen capture permission.
4. Translated text appears on the main card; original phrase shown below.
5. Control via notification: pause, resume, stop.

### Offline Translation Setup

1. Open Settings and scroll to **Offline Translation**.
2. Tap **Download** to get the NLLB-200 model (~1.3 GB, 5 files from HuggingFace).
3. Once downloaded, toggle **Offline mode** on. The target language list expands to 31 languages.
4. The model uses ~900 MB RAM when loaded.

### Settings

- **App Language** -- English, Russian, or system default
- **Languages** -- source (7 with ASR) and target (13 online / 31 offline)
- **Offline Translation** -- download/delete NLLB model, toggle offline mode
- **TTS** -- speech rate, pitch, auto-playback, mic muting during TTS
- **Display** -- show/hide original text and partial ASR results
- **Theme** -- system / light / dark

## Project Structure

```
app/src/main/java/com/example/instantvoicetranslate/
|
|-- MainActivity.kt                    # Activity, navigation, permissions
|-- InstantVoiceTranslateApp.kt        # Application, notification channel
|
|-- asr/
|   |-- SpeechRecognizer.kt            # ASR interface
|   |-- SherpaOnnxRecognizer.kt        # Sherpa-ONNX Zipformer implementation
|
|-- audio/
|   |-- AudioCaptureManager.kt         # Mic and system audio capture
|   |-- AudioDiagnostics.kt            # Raw audio WAV recording (debug)
|
|-- data/
|   |-- TranslationUiState.kt          # Singleton UI state (StateFlow)
|   |-- SettingsRepository.kt          # Settings via DataStore
|   |-- ModelDownloader.kt             # ASR model download from HuggingFace
|
|-- di/
|   |-- AppModule.kt                   # Hilt bindings
|
|-- service/
|   |-- TranslationService.kt          # Foreground service, pipeline orchestrator
|
|-- translation/
|   |-- TextTranslator.kt              # Translation interface
|   |-- FreeTranslator.kt              # Online: Yandex API + fallback, LRU cache
|   |-- NllbTranslator.kt              # Offline: NLLB-200 via ONNX Runtime
|   |-- NllbModelManager.kt            # NLLB model download and lifecycle
|   |-- NllbTokenizer.kt               # SentencePiece tokenizer for NLLB
|   |-- SentencePieceBpe.kt            # Pure Kotlin BPE tokenizer (no native deps)
|   |-- NllbLanguageCodes.kt           # ISO 639-1 to NLLB code mapping
|
|-- tts/
|   |-- TtsEngine.kt                   # Android TTS with queue
|
|-- ui/
    |-- screens/
    |   |-- MainScreen.kt              # Main screen: translation display
    |   |-- SettingsScreen.kt          # Settings screen
    |-- theme/
    |   |-- Theme.kt                   # Material 3, dynamic colors
    |-- utils/
    |   |-- LanguageUtils.kt           # Language lists and display names
    |-- viewmodel/
        |-- MainViewModel.kt           # Main screen ViewModel
        |-- SettingsViewModel.kt       # Settings ViewModel
```

## Architecture

MVVM with a foreground service as the central orchestrator:

```
UI (Compose)  <-->  ViewModel  <-->  TranslationUiState (singleton StateFlow)
                                            ^
                                            |
                                    TranslationService (foreground)
                                            |
                        +-------------------+-------------------+
                        |                   |                   |
                AudioCaptureManager  SherpaOnnxRecognizer  TextTranslator
                (mic / system audio)  (streaming ASR)      |             |
                                                     FreeTranslator  NllbTranslator
                                                     (online)       (offline/ONNX)
                                                           |
                                                       TtsEngine
                                                   (playback queue)
```

- **TranslationUiState** -- singleton StateFlow bridging the foreground service and Compose UI without Activity
  lifecycle binding. Keeps a rolling history of last 10 segments.
- **TranslationService** -- manages audio capture, ASR, translation (parallel, up to 3 concurrent), and TTS.
  Selects `FreeTranslator` or `NllbTranslator` based on the offline mode setting.
- **NllbTranslator** -- uses two separate decoder ONNX models (no-cache + with-past) to avoid an Android ONNX Runtime
  crash with the merged decoder's If-node. Pure Kotlin SentencePiece BPE tokenizer (no native dependencies).
- **TtsEngine** -- Channel-based queue for sequential playback; `isSpeaking` StateFlow prevents feedback loops.

## Permissions

| Permission                            | Purpose                                |
|---------------------------------------|----------------------------------------|
| `RECORD_AUDIO`                        | Microphone recording                   |
| `FOREGROUND_SERVICE`                  | Background service operation           |
| `FOREGROUND_SERVICE_MICROPHONE`       | Foreground service type: microphone    |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Foreground service type: system audio  |
| `POST_NOTIFICATIONS`                  | Notifications (Android 13+)            |
| `INTERNET`                            | Translation API and model downloads    |
| `ACCESS_NETWORK_STATE`                | Network availability check             |
| `WAKE_LOCK`                           | Prevent sleep during service operation |

## Default Configuration

| Parameter              | Value        |
|------------------------|--------------|
| Source language        | English (en) |
| Target language        | Russian (ru) |
| Audio source           | Microphone   |
| Translation mode       | Online       |
| TTS speed              | 1.0x         |
| TTS pitch              | 1.0x         |
| Auto-playback          | Enabled      |
| Mute microphone on TTS | Disabled     |
| Theme                  | System       |
