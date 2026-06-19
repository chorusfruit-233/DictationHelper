package com.example.dictationhelper.model

data class WordList(
    val id: String,
    val name: String,
    val words: List<WordItem> = emptyList()
)

data class WordItem(
    val id: String,
    val type: String,
    val text: String,
    val meaningZh: String,
    val partOfSpeech: String = "",
    val aliases: List<String> = emptyList()
)
