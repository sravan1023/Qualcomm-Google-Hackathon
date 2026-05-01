# LiteRT
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Qualcomm QNN delegate
-keep class com.qualcomm.qti.** { *; }
-dontwarn com.qualcomm.qti.**

# Keep model class names referenced from JNI / native delegate boundaries
-keepclasseswithmembers class * {
    native <methods>;
}
