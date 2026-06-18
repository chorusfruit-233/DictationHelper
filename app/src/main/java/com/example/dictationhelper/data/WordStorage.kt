package com.example.dictationhelper.data

import android.content.Context
import com.example.dictationhelper.model.WordItem
import com.example.dictationhelper.model.WordList
import org.json.JSONObject

data class StorageData(
    val lists: List<WordList>,
    val currentListId: String = ""
)

object WordStorage {
    private const val FILENAME = "word_bank.json"

    fun loadAll(context: Context): StorageData {
        return try {
            val text = context.openFileInput(FILENAME).bufferedReader().readText()
                .ifBlank { return StorageData(emptyList()) }
            val json = JSONObject(text)
            val listsJson = json.optJSONArray("lists") ?: return StorageData(emptyList())
            val lists = (0 until listsJson.length()).map { i ->
                val obj = listsJson.getJSONObject(i)
                val itemsJson = obj.optJSONArray("words") ?: org.json.JSONArray()
                val words = (0 until itemsJson.length()).map { j ->
                    val item = itemsJson.getJSONObject(j)
                    WordItem(
                        id = item.optString("id", ""),
                        type = item.optString("type", "word"),
                        text = item.optString("text", ""),
                        meaningZh = item.optString("meaningZh", ""),
                        partOfSpeech = item.optString("partOfSpeech", "")
                    )
                }
                WordList(
                    id = obj.optString("id", System.currentTimeMillis().toString()),
                    name = obj.optString("name", ""),
                    words = words
                )
            }
            StorageData(
                lists = lists,
                currentListId = json.optString("currentListId", lists.firstOrNull()?.id ?: "")
            )
        } catch (_: Exception) {
            StorageData(emptyList())
        }
    }

    fun saveAll(context: Context, lists: List<WordList>, currentListId: String) {
        try {
            val json = JSONObject().apply {
                put("currentListId", currentListId)
                put("lists", org.json.JSONArray().apply {
                    lists.forEach { list ->
                        put(JSONObject().apply {
                            put("id", list.id)
                            put("name", list.name)
                            put("words", org.json.JSONArray().apply {
                                list.words.forEach { w ->
                                    put(JSONObject().apply {
                                        put("id", w.id)
                                        put("type", w.type)
                                        put("text", w.text)
                                        put("meaningZh", w.meaningZh)
                                        put("partOfSpeech", w.partOfSpeech)
                                    })
                                }
                            })
                        })
                    }
                })
            }
            context.openFileOutput(FILENAME, Context.MODE_PRIVATE).use {
                it.write(json.toString().toByteArray())
            }
        } catch (_: Exception) {
        }
    }
}
