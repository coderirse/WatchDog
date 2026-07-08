# WatchDog ProGuard 规则

# === Gson ===
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.example.watchdog.data.api.** { *; }
-keep class com.google.gson.** { *; }

# === Retrofit + OkHttp ===
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**

# === Coil ===
-dontwarn coil.**

# === Compose ===
-dontwarn androidx.compose.**
