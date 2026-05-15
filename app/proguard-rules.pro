# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep class hilt_aggregated_deps.** { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class **_Impl { *; }

# ── Kotlin Serialization ──────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keep @kotlinx.serialization.Serializable class * { *; }

# ── Paho MQTT ─────────────────────────────────────────────────────────────────
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# ── OkHttp / Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── Orbis models (serialized over MQTT — must not be renamed) ─────────────────
-keep class com.orbis.agent.model.** { *; }

# ── AndroidX Security (EncryptedSharedPreferences) ───────────────────────────
-keep class androidx.security.crypto.** { *; }

# ── Keep stack traces readable in crash reports ───────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
