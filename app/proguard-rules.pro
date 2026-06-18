# Strip debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep Vosk native library — critical for JNI
-keep class org.vosk.LibVosk { *; }
-keep class org.vosk.Recognizer { *; }
-keep class org.vosk.Model { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNA — Vosk depends on it, R8 must not touch field names
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keepclassmembers class com.sun.jna.Pointer {
    long peer;
    long original;
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Keep JSON parsing
-keep class org.json.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep data classes used in JSON serialization
-keep class com.example.dictationhelper.model.** { *; }
-keep class com.example.dictationhelper.data.** { *; }
-keep class com.example.dictationhelper.llm.** { *; }
-keep class com.example.dictationhelper.matching.** { *; }
-keep class com.example.dictationhelper.speech.** { *; }

# Keep the EditableWordItem data class for Json parsing
-keep class com.example.dictationhelper.screens.EditableWordItem { *; }
