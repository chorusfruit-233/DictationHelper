/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.data

import android.content.Context
import com.example.dictationhelper.model.WordItem
import com.example.dictationhelper.model.WordList
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

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
                        partOfSpeech = item.optString("partOfSpeech", ""),
                        aliases = item.optJSONArray("aliases")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: emptyList()
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
                                        put("aliases", org.json.JSONArray().apply {
                                            w.aliases.forEach { put(it) }
                                        })
                                    })
                                }
                            })
                        })
                    }
                })
            }
            val target = File(context.filesDir, FILENAME)
            val temp = File(context.filesDir, "$FILENAME.tmp")
            FileOutputStream(temp).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
        }
    }
}
