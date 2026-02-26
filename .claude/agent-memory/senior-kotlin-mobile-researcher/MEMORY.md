# Agent Memory - Senior Kotlin Mobile Researcher

## Project Structure (key files)
- Pipeline orchestrator: `app/src/main/java/.../service/TranslationService.kt`
- ASR: `app/src/main/java/.../asr/SherpaOnnxRecognizer.kt`
- Translation: `app/src/main/java/.../translation/FreeTranslator.kt`
- TTS: `app/src/main/java/.../tts/TtsEngine.kt`
- Shared state: `app/src/main/java/.../data/TranslationUiState.kt`
- DI: `app/src/main/java/.../di/AppModule.kt`
- ViewModel: `app/src/main/java/.../ui/viewmodel/MainViewModel.kt`

## Architecture Decisions
- Translation pipeline runs segments in PARALLEL via coroutine-per-segment + Semaphore(3)
- TTS uses Channel.CONFLATED to drop stale queued utterances; QUEUE_FLUSH interrupts current speech
- ASR endpoint detection tuned for low latency: rule2 trailing silence = 0.6s (was 1.2s)
- TranslationUiState accumulates text history (last 10 lines) instead of overwriting
- ASR model + TTS pre-loaded eagerly in MainViewModel.init{}, not on Record press
- FreeTranslator HTTP timeouts: connect=3s, read=3s, write=2s (reduced for faster fallback)

## Pipeline Latency Optimization (2026-02-26)
Root causes found and fixed:
1. Sequential translation blocking: collect{} waited for each HTTP translate() call. Fix: launch{} per segment
2. TTS queue blocking: UNLIMITED channel + speakAndWait. Fix: CONFLATED channel, skip stale segments
3. ASR silence thresholds too high (2.4s/1.2s). Fix: 1.5s/0.6s
4. Models loaded on Record press, not app start. Fix: eager preload in MainViewModel
5. UI state overwritten per segment. Fix: accumulating history

## Technical Notes
- Sherpa-ONNX models: EN ~189MB, RU ~26MB, DE/FR ~70MB, ES ~155MB
- OkHttp client is singleton in FreeTranslator (connection pooling works automatically)
- TTS init is async (callback-based TextToSpeech), so pre-init saves ~500ms at Record time
- Semaphore from kotlinx.coroutines.sync used to limit concurrent translations
- Segment ordering for TTS maintained via AtomicLong counter + lastSpokenSeqNum
