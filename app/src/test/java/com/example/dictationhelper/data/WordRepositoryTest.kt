/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.data

import com.example.dictationhelper.model.WordItem
import com.example.dictationhelper.model.WordList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WordRepositoryTest {
    private fun word(id: String, text: String) = WordItem(id = id, type = "word", text = text, meaningZh = "")

    @Before
    fun reset() {
        WordRepository.lists.clear()
        WordRepository.words.clear()
        WordRepository.lists.add(WordList(id = "default", name = "默认词表"))
        WordRepository.currentListId = "default"
    }

    @Test
    fun importsIntoSeparateListsPerUnit() {
        val unitOneId = WordRepository.addWordsToNamedList("Unit 1", listOf(word("1", "apple"), word("2", "banana")))
        val unitTwoId = WordRepository.addWordsToNamedList("Unit 2", listOf(word("3", "chair")))

        assertTrue(unitOneId != unitTwoId)
        assertEquals(listOf("apple", "banana"), WordRepository.lists.first { it.id == unitOneId }.words.map { it.text })
        assertEquals(listOf("chair"), WordRepository.lists.first { it.id == unitTwoId }.words.map { it.text })
        assertEquals("默认词表", WordRepository.lists.first { it.id == "default" }.name)
        assertTrue(WordRepository.lists.first { it.id == "default" }.words.isEmpty())
    }

    @Test
    fun reusesAnExistingListAndSkipsDuplicates() {
        val first = WordRepository.addWordsToNamedList("Unit 1", listOf(word("1", "apple")))
        val second = WordRepository.addWordsToNamedList("Unit 1", listOf(word("2", "Apple"), word("3", "banana")))

        assertEquals(first, second)
        assertEquals(2, WordRepository.lists.size)
        assertEquals(listOf("apple", "banana"), WordRepository.lists.first { it.id == first }.words.map { it.text })
    }

    @Test
    fun addingToTheCurrentListKeepsTheVisibleWordsInSync() {
        val id = WordRepository.addWordsToNamedList("Unit 1", listOf(word("1", "apple")))
        WordRepository.switchToList(id)

        WordRepository.addWordsToNamedList("Unit 1", listOf(word("2", "banana")))

        assertEquals("Unit 1", WordRepository.currentName())
        assertEquals(listOf("apple", "banana"), WordRepository.words.map { it.text })
    }
}
