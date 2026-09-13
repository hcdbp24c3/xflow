# Flutter specific ProGuard rules
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-keep class io.flutter.embedding.** { *; }

# MediaKit
-keep class com.google.android.exoplayer2.** { *; }
-keep class tv.danmaku.ijk.** { *; }
-keep class xyz.luan.audioplayers.** { *; }

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
