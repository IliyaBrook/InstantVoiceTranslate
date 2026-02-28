# NLLB-200-distilled-600M ONNX Research Notes

## The Merged Decoder Crash (Root Cause Analysis)

### Error
```
ORT_RUNTIME_EXCEPTION: Non-zero status code returned while running Reshape node.
Name:'/model/decoder/layers.0/encoder_attn/Reshape_4'
Status Message: The dimension with value zero exceeds the dimension size of the input tensor.
```

### Root Cause
The **merged decoder** (`decoder_model_merged_quantized.onnx`) uses an ONNX `If` node
with a `use_cache_branch` boolean input to switch between two execution paths:
- `use_cache_branch=false` (first step): runs `decoder_model` logic
- `use_cache_branch=true` (subsequent steps): runs `decoder_with_past_model` logic

On Android ONNX Runtime, the `If` node's first branch (no-cache) produces
`present.*.encoder.key/value` tensors with **zero-length sequence dimension**
(shape `[1, 16, 0, 64]`). When these are fed as `past_key_values.*.encoder.*`
to the second step's cache branch, the Reshape node inside encoder cross-attention
fails because it can't handle zero dimensions.

### Why Transformers.js Works
Transformers.js (`getPastKeyValues()` in `modeling_utils.js` lines 1221-1254) has
special handling: after the first step, it takes `present.*.encoder.*` from the
model output. On subsequent steps, it **reuses the encoder KV from step 1** and
**ignores** the encoder KV from current model output. This works because the
WebAssembly/WebGPU ONNX Runtime produces valid (non-zero) encoder KV on step 1.

### Why Android ONNX Runtime Fails
The Android ONNX Runtime's `If` node implementation appears to handle the
non-cache branch differently, producing zero-length encoder KV outputs from
step 1. Even if the encoder KV were valid, the merged decoder is also 3-4x
slower due to If-node overhead (microsoft/onnxruntime#11367).

## Solution: Two Separate Decoder Models

Use `decoder_model_quantized.onnx` for step 1, `decoder_with_past_model_quantized.onnx`
for subsequent steps. This is the original Optimum ONNX export design.

### decoder_model_quantized.onnx (first step, no KV cache)
- **Inputs**: input_ids, encoder_hidden_states, encoder_attention_mask
- **Outputs**: logits + present.{0..11}.decoder.key/value + present.{0..11}.encoder.key/value
- Total present outputs: 48 tensors (12 layers x 4)
- Encoder KV outputs have shape `[1, 16, encoder_seq_len, 64]` (non-zero, valid)

### decoder_with_past_model_quantized.onnx (subsequent steps, with KV cache)
- **Inputs**: input_ids, encoder_hidden_states, encoder_attention_mask,
  past_key_values.{0..11}.decoder.key/value, past_key_values.{0..11}.encoder.key/value
- **Outputs**: logits + present.{0..11}.decoder.key/value (NO encoder KV outputs!)
- Total present outputs: 24 tensors (12 layers x 2, decoder only)
- Encoder KV is passed as input and used directly (no re-computation)

### KV Cache Strategy
1. First step: extract ALL 48 KV tensors from decoder_model output
2. Separate: 24 encoder KV (constant) + 24 decoder KV (changes each step)
3. Subsequent steps: pass encoder KV (from step 1) + decoder KV (from previous step)
4. Extract only decoder KV from decoder_with_past output (24 tensors)
5. Encoder KV is NEVER re-extracted after step 1

## Optimum ONNX Export Configuration

From `optimum/exporters/onnx/base.py` line 742-748:
Encoder cross-attention KV is included in outputs when:
- `is_merged=True` (merged decoder), OR
- `behavior=DECODER and not use_past_in_inputs` (base decoder_model), OR
- `direction="inputs"` (always included as inputs)

This means decoder_model.onnx DOES output encoder KV, but
decoder_with_past_model.onnx does NOT output them (only accepts as input).

## Model File Sizes (Xenova/nllb-200-distilled-600M quantized)
- encoder_model_quantized.onnx: 419 MB
- decoder_model_quantized.onnx: 471 MB
- decoder_with_past_model_quantized.onnx: 445 MB
- decoder_model_merged_quantized.onnx: 476 MB (DEPRECATED, crashes on Android)
- sentencepiece.bpe.model: ~5 MB
- tokenizer_config.json: ~30 KB

## References
- Xenova model: https://huggingface.co/Xenova/nllb-200-distilled-600M
- Transformers.js source: packages/transformers/src/models/modeling_utils.js
- Optimum merge_decoders issue: https://github.com/huggingface/optimum/issues/1913
- ONNX If-node overhead: https://github.com/microsoft/onnxruntime/issues/11367
