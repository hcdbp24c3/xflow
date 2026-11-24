# kotlinx.serialization keeps its own rules; the app parses X responses as
# JsonElement trees, so there are no reflective model classes to keep.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# JavaScript bridges are called by name from WebView pages.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
