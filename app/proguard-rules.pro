# MediaPipe
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }

# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
