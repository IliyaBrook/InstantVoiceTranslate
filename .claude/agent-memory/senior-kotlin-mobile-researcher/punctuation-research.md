# OnlinePunctuation Research (2026-02-26)

## AAR Version Confirmation
- Current AAR: `app/libs/sherpa-onnx.aar` = v1.12.26 (size 39,832,030 bytes)
- OnlinePunctuation classes confirmed present in classes.jar
- Issue #2568 reported missing OnlinePunctuation in v1.12.11 AAR
- PR #2577 fixed it by adding symlink to kotlin-api/OnlinePunctuation.kt
- Our AAR (v1.12.26) is well past that fix

## Kotlin API (decompiled from AAR)

```kotlin
// Config for model files
data class OnlinePunctuationModelConfig(
    var cnnBilstm: String = "",      // path to model.int8.onnx or model.onnx
    var bpeVocab: String = "",       // path to bpe.vocab
    var numThreads: Int = 1,         // default 1, can increase
    var debug: Boolean = false,
    var provider: String = "cpu",    // "cpu" is the only option for Android
)

// Wrapper config
data class OnlinePunctuationConfig(
    var model: OnlinePunctuationModelConfig,
)

// Main class - manages native JNI pointer
class OnlinePunctuation(
    assetManager: AssetManager? = null,  // null = load from file path
    config: OnlinePunctuationConfig,
) {
    // Key method:
    fun addPunctuation(text: String): String  // "how are you" -> "How are you?"
    fun release()  // free native resources
    // Also has finalize() as safety net
}
```

### Constructor behavior:
- `assetManager = null` -> calls `newFromFile(config)` -- loads from filesystem paths
- `assetManager != null` -> calls `newFromAsset(assetManager, config)` -- loads from APK assets
- For downloaded models, use `assetManager = null` with filesystem paths

### Usage pattern:
```kotlin
val config = OnlinePunctuationConfig(
    model = OnlinePunctuationModelConfig(
        cnnBilstm = "/path/to/model.int8.onnx",
        bpeVocab = "/path/to/bpe.vocab",
        numThreads = 2,
    )
)
val punct = OnlinePunctuation(config = config)  // assetManager = null -> file mode
val result = punct.addPunctuation("how are you i am fine thank you")
// result = "How are you? I am fine. Thank you."
punct.release()
```

## Model Files
- Archive: sherpa-onnx-online-punct-en-2024-08-06.tar.bz2 (29.3MB compressed)
- Download URL: https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2
- Individual files we need (int8):
  - model.int8.onnx: 7,490,500 bytes (7.1MB)
  - bpe.vocab: 149,430 bytes (146KB)
- Full precision (not needed):
  - model.onnx: 29,662,269 bytes (28MB)
- Source paper: arxiv.org/abs/2407.13142 (Edge-Punct-Casing)
- English only -- no multi-language support

## Integration Architecture Plan

### Where to put punctuation in pipeline:
```
AudioCapture -> ASR (SherpaOnnxRecognizer) -> [PUNCTUATION HERE] -> Translator -> TTS
```

### Option A: Inside SherpaOnnxRecognizer (RECOMMENDED)
- Apply `addPunctuation()` to text BEFORE emitting via `_recognizedSegments`
- Also apply to `_partialText` for live preview with proper casing
- Pros: Single responsibility -- recognizer outputs properly formatted text
- Pros: Smart segmentation benefits immediately (can split on real punctuation!)
- Cons: Couples punctuation with ASR class

### Option B: Separate PunctuationProcessor class
- Create new class injected between ASR and translation in TranslationService
- Pros: Clean separation of concerns
- Cons: Another layer of indirection, harder to apply to partialText

### Option C: Inside TranslationService pipeline
- Apply punctuation after collecting recognizedSegments, before translate()
- Pros: Service owns the pipeline
- Cons: Cannot apply to partialText, smart segmentation won't benefit

### Recommendation: Option A (inside SherpaOnnxRecognizer)
Reasons:
1. Smart segmentation already looks for ".!?" -- punct model provides exactly that
2. partialText shown to user benefits from proper casing and punctuation
3. Minimal code changes -- just wrap text through addPunctuation() before emit
4. Translation quality improves with properly punctuated input
5. TTS sentence splitting works better with real punctuation

### Lifecycle management:
- Initialize OnlinePunctuation alongside OnlineRecognizer in `initialize()`
- Release in `release()` method
- Model files download alongside ASR model files (extend ModelDownloader)
- English-only: only load when source language is "en"

### Download integration:
- Add punctuation model files to ModelDownloader LANGUAGE_MODELS or separate config
- Individual file download (not tar.bz2):
  - https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-online-punct-en-2024-08-06.tar.bz2
  - Need to either: download tar.bz2 and extract, OR host individual files on HuggingFace
  - Check if files are available individually on HuggingFace

### Potential issues:
1. English only -- other languages won't get punctuation
2. Latency: addPunctuation() should be fast (~13-30ms per doc), but measure on mobile
3. Memory: 7.1MB model in RAM -- acceptable
4. Thread safety: OnlinePunctuation JNI -- unclear if thread-safe, may need synchronization
5. Model download: tar.bz2 format needs extraction, current downloader expects individual files
