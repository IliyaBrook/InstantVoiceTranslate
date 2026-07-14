# Add project specific ProGuard rules here.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime's native JNI code (libonnxruntime4j_jni.so) looks up Java classes/methods
# by name via reflection (GetMethodID). R8 stripping/renaming them causes a JNI abort
# (java_class == null) inside OrtSession.run, crashing the NLLB offline translation path.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
