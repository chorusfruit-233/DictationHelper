package com.example.dictationhelper.matching

data class ParsedAction(
    val action: String,
    val constraints: MatchConstraints? = null,
    val index: Int? = null
)

object RuleParser {

    fun parse(input: String): ParsedAction {
        val trimmed = input.trim().lowercase()

        if (Regex("""不是这个|not this|不对|错了|错误|排除|not that|no thats wrong|wrong""").containsMatchIn(trimmed)) {
            return ParsedAction(action = "REJECT_CANDIDATE")
        }

        if (Regex("""下一个|next\b|往前|前进|next one|下一个词""").containsMatchIn(trimmed)) {
            return ParsedAction(action = "NEXT_ITEM")
        }

        if (Regex("""上一个|previous|后退|回退|前一个|prev|go back""").containsMatchIn(trimmed)) {
            return ParsedAction(action = "PREVIOUS_ITEM")
        }

        if (Regex("""重复|repeat|再来一遍|再说一遍|再一遍|again|say again""").containsMatchIn(trimmed)) {
            return ParsedAction(action = "REPEAT_CURRENT")
        }

        val constraints = Matcher.parseInput(input)
        if (listOfNotNull(constraints.meaningZh, constraints.firstLetter, constraints.wordCount, constraints.englishPartial).isNotEmpty()) {
            return ParsedAction(action = "FIND_WORD", constraints = constraints)
        }

        return ParsedAction(action = "UNKNOWN")
    }
}
