# Agent Memory - Senior Kotlin Mobile Researcher

## Project Structure (key files)
- Pipeline orchestrator: `app/src/main/java/.../service/TranslationService.kt`
- ASR: `app/src/main/java/.../asr/SherpaOnnxRecognizer.kt`
- Translation: `app/src/main/java/.../translation/FreeTranslator.kt`
- TTS: `app/src/main/java/.../tts/TtsEngine.kt`
- Shared state: `app/src/main/java/.../data/TranslationUiState.kt`
- DI: `app/src/main/java/.../di/AppModule.kt`
- ViewModel: `app/src/main/java/.../ui/viewmodel/MainViewModel.kt`
- Model downloader: `app/src/main/java/.../data/ModelDownloader.kt`

## Architecture Decisions
- Translation pipeline runs segments in PARALLEL via coroutine-per-segment + Semaphore(3)
- TTS uses Channel.UNLIMITED for ordered delivery; `speak()` auto-splits text into sentences
- ASR has smart segmentation: emits early on sentence boundaries and long text
- ASR endpoint detection tuned for low latency: rule2 trailing silence = 0.6s
- TranslationUiState accumulates text history (last 10 lines) instead of overwriting
- ASR model + TTS pre-loaded eagerly in MainViewModel.init{}, not on Record press
- FreeTranslator HTTP timeouts: connect=3s, read=3s, write=2s
- Translation context: lastTranslatedSegment passed to translator for coherence

## Sherpa-ONNX AAR
- **Current version: v1.12.26** (confirmed by size: 39,832,030 bytes)
- Located: `app/libs/sherpa-onnx.aar`
- JNI for arm64-v8a, armeabi-v7a, x86, x86_64
- **OnlinePunctuation API IS AVAILABLE** (PR #2577 added it)
- See `punctuation-research.md` for full API and integration plan

## Punctuation Model (sherpa-onnx-online-punct-en-2024-08-06)
- CNN-BiLSTM, English only, adds punctuation + truecasing
- Files: model.int8.onnx (7.1MB) + bpe.vocab (146KB) = ~7.3MB total
- Full precision: model.onnx (28MB) -- too heavy for mobile
- Download URL: github releases tag `punctuation-models`
- Smart segmentation relies on punctuation -- punct model makes it much more effective

## Technical Notes
- Sherpa-ONNX models: EN ~189MB, RU ~26MB, DE/FR ~70MB, ES ~155MB
- OkHttp client is singleton in FreeTranslator (connection pooling)
- TTS init is async (callback-based), pre-init saves ~500ms
- Segment ordering: AtomicLong counter + ConcurrentHashMap buffer + flushTtsBuffer()
- TTS sentence splitting: `(?<=[.!?]["')\]]*)\s+` regex -- needs punctuation to work!
