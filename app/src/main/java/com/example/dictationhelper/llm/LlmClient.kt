/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.llm

import com.example.dictationhelper.matching.MatchConstraints
import com.example.dictationhelper.matching.ParsedAction
import com.example.dictationhelper.model.WordItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LlmConfig(
    val apiUrl: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "gpt-4.1-nano"
)

data class LlmParseResult(
    val action: ParsedAction? = null,
    val error: String? = null,
    val rawContent: String? = null
)

val DICTATION_PROMPT_TEMPLATE = """
你是一个英语听写助手的语义解析模块。你的任务是把老师说的话解析成结构化动作。
不要说任何其他话，只输出一个 JSON 对象。不要用 markdown 代码块包裹。

{wordBank}

动作类型(action)：
- FIND_WORD：根据线索查找单词或短语
- NEXT_ITEM：表示"下一个""next"
- PREVIOUS_ITEM：表示"上一个""previous"  
- REPEAT_CURRENT：表示"重复""repeat""再说一遍"
- REJECT_CANDIDATE：表示"不是这个""不对""排除"
- UNKNOWN：无法理解的话

constraints 字段说明（仅 FIND_WORD 需要）：
- meaningZh：中文释义线索，如"照顾""成就"
- englishPartial：英文片段线索，如"take care""env""care"
- firstLetter：首字母，如"t"
- wordCount：短语由几个词组成，如 3

重要规则：
- 用户说的英文必须作为 englishPartial，即使不完整
- 首字母必须以 firstLetter 传递，不要放在 englishPartial 中
- 中文线索提取 meaningZh
- 数字+个词 提取 wordCount

语音识别谐音处理（极重要）：
语音识别可能产生谐音错误。用户消息中可能包含拼音辅助，请利用拼音作为发音线索。
1. 英文词被识别为中文谐音字：
   结合拼音发音线索，对照词库还原英文，作为 englishPartial。
2. 中文释义被识别为同音别字：
   对照词库中的 meaningZh 纠错（可利用拼音），返回正确的 meaningZh。
不要把这些谐音当成 meaningZh（它们不是中文释义，而是发音近似）。

示例输入输出：
输入："中文是照顾，三个词，t开头"
输出：{"action":"FIND_WORD","constraints":{"meaningZh":"照顾","firstLetter":"t","wordCount":3}}

输入："下一个"
输出：{"action":"NEXT_ITEM"}

输入："take care"
输出：{"action":"FIND_WORD","constraints":{"englishPartial":"take care"}}

输入："推克 凯尔"（语音谐音-英文）
输出：{"action":"FIND_WORD","constraints":{"englishPartial":"take care"}}

输入："找顾"（语音谐音-中文释义）
输出：{"action":"FIND_WORD","constraints":{"meaningZh":"照顾"}}

输入："env"
输出：{"action":"FIND_WORD","constraints":{"englishPartial":"env"}}

输入："not this one"
输出：{"action":"REJECT_CANDIDATE"}
""".trimIndent()

object LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun buildPrompt(words: List<WordItem>, customPrompt: String?): String {
        val wordListJson = words.joinToString(", ") { w ->
            """{"text":"${w.text}","meaningZh":"${w.meaningZh}","type":"${w.type}","partOfSpeech":"${w.partOfSpeech}","aliases":${w.aliases.joinToString(",") { "\"$it\"" }.let { "[$it]" }}}"""
        }
        val template = if (!customPrompt.isNullOrBlank()) customPrompt else DICTATION_PROMPT_TEMPLATE
        return template.replace("{wordBank}", "当前词库：[$wordListJson]")
    }

    private fun toPinyin(text: String): String {
        if (text.none { it in '\u4e00'..'\u9fff' }) return ""
        return try {
            val transliterator = android.icu.text.Transliterator.getInstance("Han-Latin")
            transliterator.transliterate(text)
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun parse(input: String, config: LlmConfig, words: List<WordItem>, customPrompt: String? = null): LlmParseResult {
        if (config.apiUrl.isBlank()) return LlmParseResult(error = "API 地址未配置")
        if (config.apiKey.isBlank()) return LlmParseResult(error = "API Key 未配置")

        val prompt = buildPrompt(words, customPrompt)
        val pinyin = toPinyin(input)
        val userContent = if (pinyin.isNotEmpty()) "用户说：$input\n拼音：$pinyin" else input

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", config.model)
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", prompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userContent)
                        })
                    })
                    put("temperature", 0.0)
                    put("max_tokens", 200)
                }

                val body = requestBody.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(config.apiUrl)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    val msg = responseBody?.let { try { JSONObject(it).optString("error", it.take(200)) } catch (_: Exception) { it.take(200) } } 
                        ?: response.message
                    return@withContext LlmParseResult(error = "HTTP ${response.code}: $msg")
                }

                if (responseBody == null) return@withContext LlmParseResult(error = "AI 返回空内容")

                val json = JSONObject(responseBody)
                val choices = json.getJSONArray("choices")
                if (choices.length() == 0) return@withContext LlmParseResult(error = "AI 未返回选择")

                val content = choices
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                val action = contentToParsedAction(content)
                LlmParseResult(
                    action = action,
                    rawContent = content,
                    error = if (action == null) "无法解析 AI 回复" else null
                )
            } catch (e: Exception) {
                LlmParseResult(error = e.message ?: "未知网络错误")
            }
        }
    }

    private fun contentToParsedAction(content: String): ParsedAction? {
        return try {
            // Try to extract JSON from response (handle markdown code blocks)
            val jsonStr = when {
                content.startsWith("{") -> content
                content.contains("```json") -> content
                    .substringAfter("```json")
                    .substringBefore("```")
                    .trim()
                content.contains("```") -> content
                    .substringAfter("```")
                    .substringBefore("```")
                    .trim()
                else -> content
            }

            val json = JSONObject(jsonStr)
            val action = json.optString("action", "UNKNOWN").uppercase()

            val constraintsJson = json.optJSONObject("constraints")
            val constraints = if (constraintsJson != null) {
                MatchConstraints(
                    meaningZh = constraintsJson.optString("meaningZh", "").ifEmpty { null },
                    englishPartial = constraintsJson.optString("englishPartial", "").ifEmpty { null },
                    firstLetter = constraintsJson.optString("firstLetter", "").ifEmpty { null },
                    wordCount = if (constraintsJson.has("wordCount"))
                        constraintsJson.optInt("wordCount") else null
                )
            } else null

            ParsedAction(action = action, constraints = constraints)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun parseVision(
        base64Image: String,
        imageType: String,
        prompt: String,
        config: LlmConfig
    ): LlmParseResult {
        if (config.apiUrl.isBlank()) return LlmParseResult(error = "API 地址未配置")
        if (config.apiKey.isBlank()) return LlmParseResult(error = "API Key 未配置")

        return withContext(Dispatchers.IO) {
            try {
                val visionModel = config.model.ifBlank { "gpt-4o" }
                val requestBody = JSONObject().apply {
                    put("model", visionModel)
                    put("messages", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", org.json.JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", prompt)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/$imageType;base64,$base64Image")
                                    })
                                })
                            })
                        })
                    })
                    put("max_tokens", 4000)
                }

                val body = requestBody.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(config.apiUrl)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful) {
                    val msg = responseBody?.let {
                        try { JSONObject(it).optString("error", it.take(200)) }
                        catch (_: Exception) { it.take(200) }
                    } ?: response.message
                    return@withContext LlmParseResult(error = "HTTP ${response.code}: $msg")
                }

                if (responseBody == null) return@withContext LlmParseResult(error = "AI 返回空内容")

                val json = JSONObject(responseBody)
                val choices = json.getJSONArray("choices")
                if (choices.length() == 0) return@withContext LlmParseResult(error = "AI 未返回选择")

                val content = choices
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                LlmParseResult(rawContent = content)
            } catch (e: Exception) {
                LlmParseResult(error = e.message ?: "未知网络错误")
            }
        }
    }
}
