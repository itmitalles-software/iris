# ── Kotlin / Coroutines ─────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**


# ── Room ─────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }
-dontwarn androidx.room.**

# ── Gson / Serialization ─────────────────────────────────────────────────
# Keep all data model classes — Gson uses reflection to access fields
-keep class io.wutz.apollo.data.model.** { *; }
-keep class io.wutz.apollo.data.ws.JsonRpc* { *; }
-keep class io.wutz.apollo.data.ws.WsEvent* { *; }
-keep class io.wutz.apollo.data.ws.WsMethods { *; }
-keep class io.wutz.apollo.ui.chat.ChatMessage { *; }
-keep class io.wutz.apollo.ui.chat.MessageRole { *; }
-keep class io.wutz.apollo.ui.chat.ToolStatus { *; }

# Generic type signatures used by serialization
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── OkHttp / Retrofit ────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# ── AndroidX Security Crypto ─────────────────────────────────────────────
# EncryptedSharedPreferences / MasterKey resolve primitives via Tink's
# reflection-based Registry. Without -keep, R8 obfuscates those classes and
# token reads silently fail (401 on every API call, "Session expired").
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn androidx.security.crypto.**

# ── Tink / Error Prone ───────────────────────────────────────────────────
# Tink references com.google.errorprone.annotations.Immutable which isn't
# shipped as a runtime dependency. Suppress the R8 missing-class error.
-dontwarn com.google.errorprone.annotations.Immutable

# ── Compose ───────────────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── BuildConfig ───────────────────────────────────────────────────────────
-keep class io.wutz.apollo.BuildConfig { *; }

# ── Navigation (NavKey Names) ─────────────────────────────────────────────
# Keep screen object class names because their simpleNames are used for bottom nav selection and storage.
-keep class * extends androidx.navigation3.runtime.NavKey { *; }

