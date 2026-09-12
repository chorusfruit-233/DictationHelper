/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.dictationhelper.model.WordItem
import com.example.dictationhelper.model.WordList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object WordRepository {
    val words = mutableStateListOf<WordItem>()
    val lists = mutableStateListOf<WordList>()
    var currentListId by mutableStateOf("")
    private var appContext: Context? = null
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val saved = WordStorage.loadAll(context)
        lists.clear()
        if (saved.lists.isNotEmpty()) {
            lists.addAll(saved.lists)
            currentListId = saved.currentListId.ifEmpty { lists.firstOrNull()?.id ?: "" }
        } else {
            val default = WordList(
                id = "default",
                name = "默认词表",
                words = emptyList()
            )
            lists.add(default)
            currentListId = default.id
        }
        syncWordsFromCurrent()
    }

    private fun syncWordsFromCurrent() {
        words.clear()
        val current = lists.find { it.id == currentListId }
        if (current != null) {
            words.addAll(current.words)
        } else if (lists.isNotEmpty()) {
            currentListId = lists.first().id
            words.addAll(lists.first().words)
        }
    }

    fun createList(name: String): String {
        val id = System.currentTimeMillis().toString()
        lists.add(WordList(id = id, name = name))
        switchToList(id)
        return id
    }

    fun deleteList(id: String) {
        if (lists.size <= 1) return
        val index = lists.indexOfFirst { it.id == id }
        if (index < 0) return
        lists.removeAt(index)
        if (currentListId == id) {
            currentListId = lists.first().id
            syncWordsFromCurrent()
        }
        scheduleSave()
    }

    fun renameList(id: String, newName: String) {
        val index = lists.indexOfFirst { it.id == id }
        if (index < 0) return
        lists[index] = lists[index].copy(name = newName)
        scheduleSave()
    }

    fun switchToList(id: String) {
        if (currentListId == id) return
        val target = lists.find { it.id == id }
        if (target == null) return
        saveCurrentListState()
        currentListId = id
        syncWordsFromCurrent()
        scheduleSave()
    }

    fun addWords(newWords: List<WordItem>) {
        val existingKeys = words.asSequence()
            .map { it.text.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        val uniqueWords = newWords.filter { word ->
            val key = word.text.trim().lowercase()
            key.isNotEmpty() && existingKeys.add(key)
        }
        words.addAll(uniqueWords)
        saveCurrentListState()
        scheduleSave()
    }

    private fun saveCurrentListState() {
        val index = lists.indexOfFirst { it.id == currentListId }
        if (index >= 0) {
            lists[index] = lists[index].copy(words = words.toList())
        }
    }

    fun save(context: Context) {
        saveCurrentListState()
        WordStorage.saveAll(context, lists.toList(), currentListId)
    }

    private fun scheduleSave() {
        val context = appContext ?: return
        val snapshot = lists.toList()
        val selectedId = currentListId
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(250)
            WordStorage.saveAll(context, snapshot, selectedId)
        }
    }

    fun currentName(): String = lists.find { it.id == currentListId }?.name ?: ""

    val isEmpty: Boolean get() = lists.isEmpty() || lists.all { it.words.isEmpty() }
}
