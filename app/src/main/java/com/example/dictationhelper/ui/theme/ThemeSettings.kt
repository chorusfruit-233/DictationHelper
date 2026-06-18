package com.example.dictationhelper.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ThemeSettings {
    var themeMode by mutableStateOf("system")   // system / light / dark
    var useDynamicColor by mutableStateOf(true)
    var amoledDark by mutableStateOf(false)
    var predictiveBack by mutableStateOf(true)
    var uiScale by mutableFloatStateOf(1.0f)
    var useLlm by mutableStateOf(false)
    var useVoskOffline by mutableStateOf(false)

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        themeMode = prefs.getString("theme_mode", "system") ?: "system"
        useDynamicColor = prefs.getBoolean("use_dynamic_color", true)
        amoledDark = prefs.getBoolean("amoled_dark", false)
        predictiveBack = prefs.getBoolean("predictive_back", true)
        uiScale = prefs.getFloat("ui_scale", 1.0f)
        useLlm = prefs.getBoolean("use_llm", false)
        useVoskOffline = prefs.getBoolean("use_vosk_offline", false)
    }

    fun save(context: Context) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", themeMode)
            .putBoolean("use_dynamic_color", useDynamicColor)
            .putBoolean("amoled_dark", amoledDark)
            .putBoolean("predictive_back", predictiveBack)
            .putFloat("ui_scale", uiScale)
            .putBoolean("use_llm", useLlm)
            .putBoolean("use_vosk_offline", useVoskOffline)
            .apply()
    }
}
