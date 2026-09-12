package com.example.dictationhelper.data

import androidx.test.core.app.ApplicationProvider
import com.example.dictationhelper.model.WordItem
import com.example.dictationhelper.model.WordList
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WordStorageInstrumentedTest {
    @Test
    fun saveAndLoadPreservesListsAndSpecialCharacters() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val words = listOf(
            WordItem("w1", "word", "quote \"test\"", "含逗号，和换行\n释义", aliases = listOf("quoted"))
        )
        WordStorage.saveAll(context, listOf(WordList("l1", "测试词表", words)), "l1")

        val loaded = WordStorage.loadAll(context)
        assertEquals("l1", loaded.currentListId)
        assertEquals(words, loaded.lists.single().words)
        File(context.filesDir, "word_bank.json").delete()
    }
}
