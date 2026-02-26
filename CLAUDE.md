# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android real-time voice translation app: streaming speech recognition (Sherpa-ONNX, local/offline) → neural translation (Yandex API) → text-to-speech (Android TTS). Supports microphone and system audio capture.

## Build Commands

```bash
./gradlew build                # Full build
./gradlew assembleDebug        # Debug APK
./gradlew installDebug         # Build and install on connected device
./gradlew assembleRelease      # Release APK
./gradlew bundleRelease        # Release AAB
```

No test suite is configured yet. Lint runs as part of `./gradlew build`.

## Tech Stack

- **Language**: Kotlin, JVM 17
- **UI**: Jetpack Compose with Material 3 (Compose BOM 2026.02.00)
- **DI**: Hilt 2.56.2 with KSP
- **Async**: Kotlin Coroutines + Flow/StateFlow
- **Storage**: DataStore Preferences
- **HTTP**: OkHttp 5.3.2
- **ASR**: Sherpa-ONNX via local .aar (`app/libs/sherpa-onnx.aar`)
- **Min SDK**: 29 (required for AudioPlaybackCapture)
- **Target SDK**: 36

## Architecture

MVVM with foreground service pipeline:

```
UI (Compose Screens) → ViewModels → TranslationService (foreground)
                                         ↓
                              AudioCaptureManager (mic/system)
                                         ↓
                              SherpaOnnxRecognizer (streaming ASR)
                                         ↓
                              FreeTranslator (Yandex + fallback)
                                         ↓
                              TtsEngine (Android TTS with queue)
```

### Key Architectural Details

- **TranslationUiState** (`data/TranslationUiState.kt`) is a singleton shared across service, viewmodels, and UI via StateFlow fields. It bridges the foreground service and Compose UI.
- **TranslationService** (`service/TranslationService.kt`) is the central orchestrator. It runs as a foreground service, manages the full audio→ASR→translate→TTS pipeline, and handles pause/resume via notification actions.
- **AudioCaptureManager** supports two sources via `Source` enum: `MICROPHONE` (direct AudioRecord) and `SYSTEM_AUDIO` (requires MediaProjection). System audio needs a screen capture intent passed from the activity.
- **SpeechRecognizer** interface (`asr/SpeechRecognizer.kt`) with `SherpaOnnxRecognizer` implementation. Uses streaming Zipformer English model downloaded from HuggingFace (~189MB, 4 files). Model state tracked via `ModelStatus` sealed class.
- **TextTranslator** interface (`translation/TextTranslator.kt`) with `FreeTranslator` dual-backend: primary Yandex API, fallback `translate.toil.cc`. Has LRU cache (200 entries).
- **TtsEngine** uses a Channel-based queue for sequential utterances and exposes `isSpeaking` StateFlow for feedback loop prevention (mute mic while speaking).

### DI Structure

Single Hilt module `di/AppModule.kt` provides all singletons. `@AndroidEntryPoint` on `MainActivity` and `TranslationService`.

### Navigation

Two Compose routes via NavHost: `"main"` (MainScreen) and `"settings"` (SettingsScreen).

## Important Conventions

- Language codes follow ISO 639-1: en, ru, es, de, fr, zh, ja, ko
- Default translation pair: English → Russian
- Audio format: 16kHz mono PCM_16BIT, ~100ms chunks (1600 samples)
- ProGuard rules protect Sherpa-ONNX JNI bindings (`proguard-rules.pro`)
- JNI: `libonnxruntime.so` uses pickFirst for multi-ABI support
- Debug builds use `.debug` suffix and `-debug` version name suffix
