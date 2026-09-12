package com.example.dictationhelper.matching

import com.example.dictationhelper.model.WordItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private val words = listOf(
        WordItem(id = "1", text = "take care of", meaningZh = "照顾", type = "phrase", aliases = listOf("takes care of")),
        WordItem(id = "2", text = "achievement", meaningZh = "成就", type = "word")
    )

    @Test
    fun parsesChineseEnglishAndWordCountConstraints() {
        val parsed = Matcher.parseInput("中文是照顾，三个词，t开头")

        assertEquals("照顾", parsed.meaningZh)
        assertEquals("t", parsed.firstLetter)
        assertEquals(3, parsed.wordCount)
    }

    @Test
    fun matchesPhraseByMeaningAndWordCount() {
        val results = Matcher.match(
            MatchConstraints(meaningZh = "照顾", wordCount = 3),
            words
        )

        assertEquals("take care of", results.first().word.text)
        assertEquals(1.0, results.first().confidence, 0.001)
    }

    @Test
    fun matchesAliasesAndRuleActions() {
        val aliasResults = Matcher.match(MatchConstraints(englishPartial = "takes care"), words)
        assertEquals("take care of", aliasResults.first().word.text)

        assertEquals("NEXT_ITEM", RuleParser.parse("下一个").action)
        assertEquals("REJECT_CANDIDATE", RuleParser.parse("not this one").action)
        assertTrue(RuleParser.parse("...").action == "UNKNOWN")
    }
}
