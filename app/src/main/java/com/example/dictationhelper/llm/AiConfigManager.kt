/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.llm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

data class AiProfile(
    val id: String = System.currentTimeMillis().toString(),
    val name: String = "默认",
    val apiUrl: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "gpt-4.1-nano"
) {
    fun toLlmConfig() = LlmConfig(apiUrl, apiKey, model)
}

object AiConfigManager {
    private const val PREFS_NAME = "ai_configs_v2"

    val profiles = mutableStateListOf<AiProfile>()
    var dictationConfigId by mutableStateOf("")
    var visionConfigId by mutableStateOf("")

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("profiles", null)

        profiles.clear()
        if (jsonStr != null) {
            try {
                val arr = org.json.JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    profiles.add(
                        AiProfile(
                            id = obj.optString("id", System.currentTimeMillis().toString()),
                            name = obj.optString("name", "默认"),
                            apiUrl = obj.optString("apiUrl", LlmConfig().apiUrl),
                            apiKey = obj.optString("apiKey", ""),
                            model = obj.optString("model", LlmConfig().model)
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }

        dictationConfigId = prefs.getString("dictationConfigId", "")?.takeIf { it.isNotEmpty() }
            ?: profiles.firstOrNull()?.id ?: ""
        visionConfigId = prefs.getString("visionConfigId", "")?.takeIf { it.isNotEmpty() }
            ?: profiles.firstOrNull()?.id ?: ""

        // Migrate from old single config
        if (profiles.isEmpty()) {
            val oldPrefs = context.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)
            val oldKey = oldPrefs.getString("api_key", null)
            if (oldKey != null) {
                val profile = AiProfile(
                    name = "默认",
                    apiUrl = oldPrefs.getString("api_url", LlmConfig().apiUrl) ?: LlmConfig().apiUrl,
                    apiKey = oldKey,
                    model = oldPrefs.getString("model", LlmConfig().model) ?: LlmConfig().model
                )
                profiles.add(profile)
                dictationConfigId = profile.id
                visionConfigId = profile.id
                // Clear old prefs
                oldPrefs.edit().clear().apply()
            }
        }

        // Ensure we have at least one profile
        if (profiles.isEmpty()) {
            val default = AiProfile(name = "默认")
            profiles.add(default)
            dictationConfigId = default.id
            visionConfigId = default.id
        }

        save(context)
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(
                "profiles",
                org.json.JSONArray().apply {
                    profiles.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("apiUrl", p.apiUrl)
                            put("apiKey", p.apiKey)
                            put("model", p.model)
                        })
                    }
                }.toString()
            )
            putString("dictationConfigId", dictationConfigId)
            putString("visionConfigId", visionConfigId)
            apply()
        }
    }

    fun getDictationConfig(): LlmConfig =
        profiles.find { it.id == dictationConfigId }?.toLlmConfig() ?: LlmConfig()

    fun getVisionConfig(): LlmConfig =
        profiles.find { it.id == visionConfigId }?.toLlmConfig() ?: LlmConfig()

    fun create(name: String, apiUrl: String, apiKey: String, model: String): String {
        val id = System.currentTimeMillis().toString()
        profiles.add(AiProfile(id, name, apiUrl, apiKey, model))
        return id
    }

    fun update(id: String, name: String, apiUrl: String, apiKey: String, model: String) {
        val index = profiles.indexOfFirst { it.id == id }
        if (index >= 0) {
            profiles[index] = AiProfile(id, name, apiUrl, apiKey, model)
        }
    }

    fun delete(id: String) {
        if (profiles.size <= 1) return
        profiles.removeAll { it.id == id }
        if (dictationConfigId == id) dictationConfigId = profiles.first().id
        if (visionConfigId == id) visionConfigId = profiles.first().id
    }

    fun getConfig(id: String): AiProfile? = profiles.find { it.id == id }
}
