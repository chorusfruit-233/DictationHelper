package com.example.dictationhelper.matching

import com.example.dictationhelper.model.WordItem

data class MatchConstraints(
    val meaningZh: String? = null,
    val firstLetter: String? = null,
    val wordCount: Int? = null,
    val englishPartial: String? = null
)

data class MatchResult(
    val word: WordItem,
    val confidence: Double,
    val reasons: List<String>
)

object Matcher {

    fun parseInput(input: String): MatchConstraints {
        val trimmed = input.trim()
        return MatchConstraints(
            meaningZh = parseMeaningZh(trimmed),
            firstLetter = parseFirstLetter(trimmed),
            wordCount = parseWordCount(trimmed),
            englishPartial = parseEnglishPartial(trimmed)
        )
    }

    fun match(
        constraints: MatchConstraints,
        words: List<WordItem>
    ): List<MatchResult> {
        val hasMeaning = constraints.meaningZh != null
        val hasFirstLetter = constraints.firstLetter != null
        val hasWordCount = constraints.wordCount != null
        val hasEnglish = constraints.englishPartial != null

        if (!hasMeaning && !hasFirstLetter && !hasWordCount && !hasEnglish) return emptyList()

        return words.map { word ->
            val reasons = mutableListOf<String>()
            var score = 0.0
            var maxScore = 0.0

            if (hasMeaning && word.meaningZh.isNotEmpty()) {
                maxScore += 1.0
                if (word.meaningZh.contains(constraints.meaningZh!!)) {
                    score += 1.0
                    reasons.add("中文释义匹配")
                } else {
                    val queryChars = constraints.meaningZh.toCharArray().filter { it in '\u4e00'..'\u9fff' }
                    if (queryChars.isNotEmpty()) {
                        val targetChars = word.meaningZh.toCharArray().toSet()
                        val overlap = queryChars.count { it in targetChars }
                        val ratio = overlap.toDouble() / queryChars.size
                        if (ratio >= 0.5) {
                            score += 0.6
                            reasons.add("中文释义模糊匹配（${(ratio * 100).toInt()}%字符重合）")
                        }
                    }
                }
            }

            if (hasFirstLetter && word.text.isNotEmpty()) {
                maxScore += 1.0
                if (word.text.lowercase().startsWith(constraints.firstLetter!!)) {
                    score += 1.0
                    reasons.add("首字母匹配")
                }
            }

            if (hasWordCount) {
                maxScore += 1.0
                val actualCount = word.text.split("\\s+".toRegex()).size
                if (actualCount == constraints.wordCount) {
                    score += 1.0
                    reasons.add("词数匹配（${actualCount}个词）")
                }
            }

            if (hasEnglish && word.text.isNotEmpty()) {
                maxScore += 1.0
                val query = constraints.englishPartial!!.lowercase()
                val target = word.text.lowercase()
                val queryWords = query.split("\\s+".toRegex())
                val targetWords = target.split("\\s+".toRegex())

                when {
                    target == query -> {
                        score += 1.0
                        reasons.add("英文完全匹配")
                    }
                    queryWords.all { qw -> targetWords.any { tw -> tw == qw || tw.startsWith(qw) } } -> {
                        score += 0.9
                        reasons.add("英文模糊匹配（全部词命中）")
                    }
                    target.contains(query) -> {
                        score += 0.7
                        reasons.add("英文包含匹配（\"$query\" 出现在 \"$target\" 中）")
                    }
                    queryWords.any { qw -> targetWords.any { tw -> tw.startsWith(qw) } } -> {
                        score += 0.5
                        reasons.add("英文部分匹配")
                    }
                    isPhoneticMatch(query, target) -> {
                        score += 0.4
                        reasons.add("读音模糊匹配")
                    }
                }
            }

            val confidence = if (maxScore > 0) score / maxScore else 0.0

            MatchResult(
                word = word,
                confidence = confidence.coerceAtMost(1.0),
                reasons = reasons
            )
        }.filter { it.confidence > 0 }
         .sortedByDescending { it.confidence }
         .let { results ->
             if (results.size == 1) {
                 listOf(results[0].copy(confidence = 1.0))
             } else {
                 results
             }
         }
    }

    private fun parseMeaningZh(text: String): String? {
        val patterns = listOf(
            Regex("""中文\s*[是：:]\s*([\u4e00-\u9fff；;，,、]+)"""),
            Regex("""中文\s+([\u4e00-\u9fff]+)"""),
            Regex("""意思是?\s*([\u4e00-\u9fff]+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].trim().replace(Regex("""[；;，,、]$"""), "")
            }
        }

        val commandWords = setOf(
            "下一个", "上一个", "开头", "个词", "几个", "中文",
            "第十五个", "第一个", "第十个", "重复", "再来一遍"
        )
        val trailing = Regex("""[\u4e00-\u9fff]{1,6}\s*$""").find(text)
        if (trailing != null) {
            val candidate = trailing.value.trim()
            if (candidate !in commandWords) {
                return candidate
            }
        }
        return null
    }

    private fun parseFirstLetter(text: String): String? {
        val patterns = listOf(
            Regex("""([a-zA-Z])\s*开头"""),
            Regex("""首字母\s*([a-zA-Z])""")
        )
        for (pattern in patterns) {
            val match = pattern.find(text.lowercase())
            if (match != null) {
                return match.groupValues[1].lowercase()
            }
        }
        return null
    }

    private fun parseWordCount(text: String): Int? {
        val cnNumbers = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
            "两" to 2
        )
        val patterns = listOf(
            Regex("""(\d+|(?:一|二|三|四|五|六|七|八|九|十|两))\s*个词"""),
            Regex("""词数\s*(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val token = match.groupValues[1]
                return cnNumbers[token] ?: token.toIntOrNull()
            }
        }
        return null
    }

    private fun parseEnglishPartial(text: String): String? {
        val lowered = text.lowercase()
        val patterns = listOf(
            Regex("""英文\s*[是：:]\s*([a-zA-Z\s]+)"""),
            Regex("""单词\s*[是：:]\s*([a-zA-Z\s]+)"""),
            Regex("""短语\s*[是：:]\s*([a-zA-Z\s]+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(lowered)
            val group = match?.groupValues?.get(1)?.trim()
            if (!group.isNullOrBlank() && group.length >= 2) {
                return group
            }
        }

        val standalone = Regex("""[a-zA-Z]{2,}(?:\s+[a-zA-Z]+)*""").find(lowered)
        val candidate = standalone?.value?.trim()
        if (!candidate.isNullOrBlank() && candidate.length >= 2) {
            return candidate
        }

        return null
    }

    private fun isPhoneticMatch(query: String, target: String): Boolean {
        val qKey = phoneticKey(query)
        val tKey = phoneticKey(target)
        if (qKey == tKey) return true
        if (qKey.length < 3 || tKey.length < 3) return false
        return levenshteinDistance(qKey, tKey) <= 2
    }

    private fun phoneticKey(word: String): String {
        var s = word.lowercase()
        s = s.replace("ph", "f")
        s = s.replace("ck", "k")
        s = s.replace(Regex("c(?=[iey])"), "s")
        s = s.replace("c", "k")
        s = s.replace("x", "ks")
        s = s.replace("qu", "kw")
        s = s.replace("tion", "shn")
        s = s.replace("sion", "shn")
        s = s.replace("gh", "")
        s = s.replace(Regex("[aeiouy]"), "")
        s = s.replace(Regex("(.)\\1+"), "$1")
        return s
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1])
                    dp[i - 1][j - 1]
                else
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[m][n]
    }
}
