package com.example.dictationhelper.data

import com.example.dictationhelper.model.WordItem

object SampleData {
    val defaultWords: List<WordItem> = listOf(
        WordItem(
            id = "unit1_001",
            type = "word",
            text = "achievement",
            meaningZh = "成就；成绩",
            partOfSpeech = "n."
        ),
        WordItem(
            id = "unit1_002",
            type = "phrase",
            text = "take care of",
            meaningZh = "照顾；照料",
            partOfSpeech = ""
        ),
        WordItem(
            id = "unit1_003",
            type = "word",
            text = "environment",
            meaningZh = "环境",
            partOfSpeech = "n."
        )
    )
}
