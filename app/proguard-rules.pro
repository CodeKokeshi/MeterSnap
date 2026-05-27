# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.**

# Gson (used for JSON serialization of history/settings)
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.kokeshi.metersnap.data.** { *; }
-keep class com.kokeshi.metersnap.** implements java.io.Serializable { *; }

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep line numbers for crash debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
