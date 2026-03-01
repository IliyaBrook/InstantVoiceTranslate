# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android real-time voice translation app: streaming speech recognition (Sherpa-ONNX, local/offline) → neural translation (online Yandex API or offline NLLB-200) → text-to-speech (Android TTS). Supports microphone and system audio capture.

## Build Commands

```bash
./gradlew build                # Full build (includes lint)
./gradlew assembleDebug        # Debug APK
./gradlew installDebug         # Build and install on connected device
./gradlew assembleRelease      # Release APK (requires keystore.properties)
./gradlew bundleRelease        # Release AAB
./gradlew test                 # Run unit tests
./gradlew testDebugUnitTest    # Run debug unit tests only
```

Release builds require `keystore.properties` in the project root (not committed).

## Tech Stack

- **Language**: Kotlin 2.1.20, JVM 17
- **UI**: Jetpack Compose with Material 3 (Compose BOM 2026.02.01)
- **DI**: Hilt 2.56.2 with KSP (pinned — Hilt 2.59.2 requires AGP 9.0+)
- **Async**: Kotlin Coroutines + Flow/StateFlow/Channel
- **Storage**: DataStore Preferences
- **HTTP**: OkHttp 5.3.2
- **ASR**: Sherpa-ONNX via local .aar (`app/libs/sherpa-onnx.aar`)
- **Offline Translation**: NLLB-200-distilled-600M via ONNX Runtime 1.17.1
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
                         FreeTranslator (online) OR NllbTranslator (offline)
                                         ↓
                              TtsEngine (Android TTS with queue)
```

### Key Architectural Details

- **TranslationUiState** (`data/TranslationUiState.kt`) — singleton shared across service, ViewModels, and UI via StateFlow. Bridges foreground service and Compose UI without Activity lifecycle binding. Keeps a rolling history of last 10 segments.
- **TranslationService** (`service/TranslationService.kt`) — central orchestrator. Runs as a foreground service, manages audio→ASR→translate→TTS pipeline, handles pause/resume. Translates segments in parallel (up to 3 concurrent) with ordered delivery to TTS via sequence-numbered buffer.
- **AudioCaptureManager** — two sources via `Source` enum: `MICROPHONE` (AudioRecord) and `SYSTEM_AUDIO` (MediaProjection). System audio requires screen capture intent from the activity.
- **SpeechRecognizer** interface (`asr/SpeechRecognizer.kt`) with `SherpaOnnxRecognizer` implementation. Supports 7 source languages with per-language streaming Zipformer models (~26–188 MB each, auto-downloaded from HuggingFace). English has optional punctuation model.
- **Dual translation backends**:
  - `FreeTranslator` (online) — primary Yandex API + fallback `translate.toil.cc`, LRU cache (200 entries). Bound as default `TextTranslator` via Hilt.
  - `NllbTranslator` (offline) — NLLB-200-distilled-600M via ONNX Runtime. Uses two separate decoder models (no-cache + with-past) to avoid Android ONNX Runtime If-node crash. ~900 MB RAM when loaded. Selected when `offlineMode` setting is enabled.
- **TtsEngine** — Channel-based queue for sequential playback, `isSpeaking` StateFlow for feedback loop prevention (mute mic while speaking).

### DI Structure

Single abstract Hilt module `di/AppModule.kt` with `@Binds` for interface→impl mappings (`SpeechRecognizer`→`SherpaOnnxRecognizer`, `TextTranslator`→`FreeTranslator`). Most classes use `@Inject constructor`. `NllbTranslator` is injected directly (not via `TextTranslator` interface) and used only when offline mode is enabled.

`@AndroidEntryPoint` on `MainActivity` and `TranslationService`.

### Navigation

Two Compose routes via NavHost: `"main"` (MainScreen) and `"settings"` (SettingsScreen).

## Important Conventions

- Language codes follow ISO 639-1: en, ru, es, de, fr, zh, ja, ko, pt, it, tr, ar, hi
- 7 source languages (with ASR models), 13 target languages (translation only)
- Default translation pair: English → Russian
- Audio format: 16kHz mono PCM_16BIT, ~100ms chunks (1600 samples)
- ONNX Runtime version MUST match sherpa-onnx.aar bundled version (1.17.1) to avoid symbol conflicts
- ProGuard rules protect Sherpa-ONNX JNI bindings (`proguard-rules.pro`)
- JNI: `libonnxruntime.so` uses pickFirst for multi-ABI support
- Debug builds use `.debug` applicationId suffix and `-debug` versionName suffix
- Adding a new source language requires adding its `LanguageModelConfig` to `ModelDownloader.LANGUAGE_MODELS`
- Adding a new target language requires updating `LanguageUtils.kt` lists
