# ----------------------------------------------------------------------------
# 1. General Android & Debugging
# ----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable  # Essential for Crashlytics stack traces
-keepattributes *Annotation* # Required for Retrofit, Gson, Moshi, etc.
-keepattributes Signature                   # Required for Kotlin generic types
-keepattributes EnclosingMethod             # Required for some reflection checks

# ----------------------------------------------------------------------------
# 2. YOUR DATA MODELS (CRITICAL)
# ----------------------------------------------------------------------------
# R8 changes variable names (e.g., "userName" -> "a"). 
# JSON parsers require exact names to match APIs.
# We explicitly keep all classes in your data/model packages.

-keep class com.blurr.voice.data.** { *; }
-keep class com.blurr.voice.v2.message_manager.models.** { *; }
-keep class com.blurr.voice.v2.llm.models.** { *; }
# If you have other packages with data classes, add them here.

# ----------------------------------------------------------------------------
# 3. Serializers (Gson, Moshi, Kotlinx)
# ----------------------------------------------------------------------------
# Gson (Uses Unsafe and Reflection)
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.examples.android.model.** { *; }
-keep class com.google.gson.** { *; }

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepnames class com.squareup.moshi.** { *; }

# Kotlinx Serialization
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.KSerializer {
    static kotlinx.serialization.KSerializer getSerializer(...);
}

# ----------------------------------------------------------------------------
# 4. Firebase & Firestore
# ----------------------------------------------------------------------------
# Firestore uses reflection to map documents to objects. 
# If you use .toObject(Class.class), those classes MUST be kept (covered in Section 2).
-keepattributes InnerClasses
-dontwarn com.google.firebase.**

# ----------------------------------------------------------------------------
# 5. Networking & Coroutines
# ----------------------------------------------------------------------------
# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Coroutines (prevents FastServiceLoader crashes)
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }

# ----------------------------------------------------------------------------
# 7. UI Automator (Since you included it in 'implementation')
# ----------------------------------------------------------------------------
-dontwarn androidx.test.uiautomator.**

# ----------------------------------------------------------------------------
# 8. The system-assistant role
# ----------------------------------------------------------------------------
# AssistantSessionService is instantiated by the platform from the `sessionService`
# string in res/xml/voice_interaction_service.xml, and the recognition service from
# the `recognitionService` string beside it. Neither reference is visible to R8, so
# both classes can be stripped -- and a stripped session service means the assistant
# role is granted but no popup is ever created, which is very hard to tell apart from
# the platform simply ignoring us.
-keep class com.blurr.voice.assistant.AssistantSessionService { *; }
-keep class com.blurr.voice.assistant.AssistantRecognitionService { *; }