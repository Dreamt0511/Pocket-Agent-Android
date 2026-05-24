# ProGuard 规则 - Pocket Agent

# Keep all app classes (R8 过度裁剪导致安装失败)
-keep class com.pocketagent.app.** { *; }

# Compose
-keep class androidx.compose.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# compose-markdown
-keep class dev.jeziellago.compose.markdowntext.** { *; }
