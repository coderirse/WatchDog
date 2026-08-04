# WatchDog ProGuard 规则

# === R8 full mode 泛型反射保护（Retrofit/Gson 必需） ===
# R8 全量模式下会剥离方法泛型签名，导致 Gson 反射解析 Response<T> 时抛
# java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType。
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class retrofit2.Call
-keep,allowobfuscation,allowshrinking class okhttp3.ResponseBody
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# === 保留泛型签名 & 注解（Gson/Retrofit 反射必需） ===
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# === Gson ===
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson 反射用到的 JDK 类
-keep class sun.misc.Unsafe { *; }
-keep class java.lang.reflect.ParameterizedType { *; }
-keep class java.lang.reflect.Type { *; }

# === All API model classes — must NOT be obfuscated ===
-keep class com.example.watchdog.data.api.** { *; }
-keep class com.example.watchdog.data.model.** { *; }

# === Retrofit ===
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
# 保留 Retrofit Service 接口及其方法签名
-keep interface com.example.watchdog.data.api.*Api { *; }
-keep class * implements com.example.watchdog.data.api.*Api { *; }

# === OkHttp ===
-dontwarn okhttp3.**
-dontwarn okio.**

# === Coil ===
-dontwarn coil.**

# === Compose ===
-dontwarn androidx.compose.**
