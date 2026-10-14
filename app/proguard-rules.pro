# ── Kotlin / Coroutines ─────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**

# ── Logging ──────────────────────────────────────────────────────────────
# Strip debug and verbose logs from minified builds; warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# ── Room 3 / kotlinx.serialization ──────────────────────────────────────
# No app rules needed: room3-runtime ships a keep rule for RoomDatabase
# subclasses, and kotlinx.serialization, Retrofit and SQLCipher ship their own
# R8 rules. API models are deserialized through generated serializers, not
# reflection, so they are shrunk and obfuscated like any other class.

# Generic type signatures and annotations read by Retrofit
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

# ── AndroidX Security Crypto / Tink ─────────────────────────────────────
# EncryptedSharedPreferences reads its keysets with Tink's shaded protobuf-lite
# runtime, which looks up message fields by name. Without that, token reads
# fail after obfuscation (401 on every API call, "Session expired"). Tink
# registers its key managers in code, so this field rule (the one Tink ships in
# META-INF/proguard/protobuf.pro, repeated here so it cannot get lost) is all
# it needs; a blanket keep of Tink would retain ~9,000 unused methods.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
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

