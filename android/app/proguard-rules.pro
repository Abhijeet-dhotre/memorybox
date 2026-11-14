# Memory Box — ProGuard rules (release builds only).
# ONNX Runtime (flutter_onnxruntime) must not be obfuscated away:
# the Dart side calls into ai.onnxruntime via JNI.
-keep class ai.onnxruntime.** { *; }
# sqlite-vec is loaded through the sqlite3 package's native-assets hook,
# not through ProGuard-visible Java code; nothing to keep here.
