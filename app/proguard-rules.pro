# Strip debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep Vosk native library
-keep class org.vosk.** { *; }

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
