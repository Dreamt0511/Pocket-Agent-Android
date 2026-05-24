# ProGuard 规则 - Pocket Agent

# Keep Chaquopy
-keep class com.chaquo.python.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep models
-keep class com.pocketagent.app.core.** { *; }
-keep class com.pocketagent.app.domain.** { *; }

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

# 液态玻璃 UI 组件
-keep class com.pocketagent.app.ui.theme.** { *; }
-keep class com.pocketagent.app.ui.components.** { *; }