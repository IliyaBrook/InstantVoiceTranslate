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

## MediaProjection / System Audio Capture
- **Critical bug found & fixed**: `Activity.RESULT_OK == -1`, same as default for `getIntExtra()`. Use `Int.MIN_VALUE` as sentinel.
- Order of operations for Android 14+: `startForeground()` MUST be called BEFORE `getMediaProjection()`
- The consent intent token can only be passed to `getMediaProjection()` once (single use)
- `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE` added in API 30 (not 29)
- Manifest declares `microphone|mediaProjection` on the service
- Always register `MediaProjection.Callback.onStop()` to handle system revocation
- AudioPlaybackCapture supports: USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN
- Notification icon: `R.drawable.ic_mic_notification` (monochrome vector)
- PendingIntent for pause/resume toggle needs `FLAG_UPDATE_CURRENT` to update action

## NLLB-200 Offline Translation (ONNX)
- Model: Xenova/nllb-200-distilled-600M (m2m_100 architecture)
- Key files: `NllbTranslator.kt`, `NllbModelManager.kt`, `NllbTokenizer.kt`
- Architecture: 12 layers, 16 heads, d_model=1024, head_dim=64, vocab=256206
- **Uses TWO separate decoder models** (not merged), see `nllb-research.md` for details
- Files: encoder_model_quantized.onnx (419MB) + decoder_model_quantized.onnx (471MB)
  + decoder_with_past_model_quantized.onnx (445MB) + tokenizer (~5MB) = ~1.34GB total
- KV cache: 48 tensors total (12 layers x 4: decoder.key/value + encoder.key/value)
- Encoder KV is constant across all steps (computed once in first decoder step)
- Decoder KV grows each step (self-attention accumulates past tokens)
- SentencePieceBpe: pure Kotlin BPE tokenizer, no native deps needed
- **CRITICAL BUG FOUND**: FAIRSEQ_LANGUAGE_CODES in NllbTokenizer.kt was missing
  `kmr_Latn` (Northern Kurdish) at index 98. Must have 202 codes, not 201.
  sentencepiece.bpe.model has vocabSize=256000, NOT 256003 as comments claimed.
  Language token formula: id = 256000 + 1 + index. Correct base = 256001.

## Technical Notes
- Sherpa-ONNX models: EN ~189MB, RU ~26MB, DE/FR ~70MB, ES ~155MB
- OkHttp client is singleton in FreeTranslator (connection pooling)
- TTS init is async (callback-based), pre-init saves ~500ms
- Segment ordering: AtomicLong counter + ConcurrentHashMap buffer + flushTtsBuffer()
- TTS sentence splitting: `(?<=[.!?]["')\]]*)\s+` regex -- needs punctuation to work!
