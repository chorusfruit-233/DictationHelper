/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.screens

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dictationhelper.data.WordRepository
import com.example.dictationhelper.llm.AiConfigManager
import com.example.dictationhelper.llm.LlmClient
import com.example.dictationhelper.model.WordItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EditableWordItem(
    val id: String,
    val text: String,
    val meaningZh: String,
    val partOfSpeech: String = "",
    val aliases: String = "",
    val type: String,
    val include: Boolean = true,
    /** Textbook unit this word belongs to; empty means it goes to the current list. */
    val unit: String = ""
)

internal val DEFAULT_VISION_PROMPT = """
请识别这张图片中的英语单词表，整理成 JSON 格式。
注意：
- 识别所有英文单词或短语及其对应的中文释义
- 为每个单词生成词形变体 aliases，包括：过去式、过去分词、现在分词、单复数、比较级、最高级等形式
- 如果图片中有单元名或标题，也请提取
- 短语（多个词组成的）type 设为 "phrase"，单个词 type 设为 "word"
- 不要输出任何解释，只输出以下格式的 JSON：

{
  "bookName": "书名",
  "unitName": "单元名（整页同属一个单元时填写）",
  "items": [
    {"text": "英文单词或短语", "unit": "该词所属单元（一页含多个单元时填写，否则留空）", "meaningZh": "中文释义", "partOfSpeech": "词性（如 n./v./adj.，没有则留空）", "aliases": ["过去式", "过去分词", "单复数等变体"]}
  ]
}
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visionJob by remember { mutableStateOf<Job?>(null) }

    var inputText by remember { mutableStateOf("") }
    var parsedItems = remember { mutableStateListOf<EditableWordItem>() }
    var showReview by remember { mutableStateOf(false) }
    var splitByUnit by remember { mutableStateOf(false) }
    var parseMessage by remember { mutableStateOf("") }
    var aiLoading by remember { mutableStateOf(false) }

    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var pickedImageBase64 by remember { mutableStateOf("") }
    var pickedImageType by remember { mutableStateOf("jpeg") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        pickedImageUri = uri
        scope.launch {
            val result = loadImageBase64(context, uri)
            pickedImageBase64 = result.first
            pickedImageType = result.second
            parseMessage = if (result.first.isEmpty()) "图片读取失败或超过 12 MB 限制" else ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入词表") }
            )
        }
    ) { innerPadding ->
        if (!showReview) {
            InputView(
                inputText = inputText,
                onInputChange = { inputText = it },
                onParse = {
                    parsedItems.clear()
                    parseMessage = ""
                    val items = parseInput(inputText)
                    if (items.isEmpty()) {
                        parseMessage = "未能解析到任何单词，请检查格式"
                    } else {
                        parsedItems.addAll(items)
                        splitByUnit = items.any { it.unit.isNotBlank() }
                        showReview = true
                    }
                },
                parseMessage = parseMessage,
                hasImage = pickedImageBase64.isNotEmpty(),
                aiLoading = aiLoading,
                onPickImage = { imagePicker.launch("image/*") },
                onVisionAi = {
                    visionJob?.cancel()
                    aiLoading = true
                    visionJob = scope.launch {
                        val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                        val prompt = prefs.getString("vision_prompt", "")?.takeIf { it.isNotBlank() }
                            ?: DEFAULT_VISION_PROMPT
                        val result = LlmClient.parseVision(
                            pickedImageBase64, pickedImageType, prompt,
                            AiConfigManager.getVisionConfig()
                        )
                        aiLoading = false
                        if (result.error != null) {
                            parseMessage = "AI 错误: ${result.error}"
                        } else if (result.rawContent != null) {
                            parseMessage = "AI 返回:\n${result.rawContent.take(500)}"
                            val items = parseInput(result.rawContent)
                            if (items.isEmpty()) {
                                parseMessage += "\n未能解析为词库 JSON"
                            } else {
                                parsedItems.clear()
                                parsedItems.addAll(items)
                                splitByUnit = items.any { it.unit.isNotBlank() }
                                showReview = true
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            ReviewView(
                items = parsedItems,
                splitByUnit = splitByUnit,
                onSplitByUnitChange = { splitByUnit = it },
                onSave = {
                    val selected = parsedItems.filter { it.include }
                    val byUnit = selected.filter { it.unit.isNotBlank() }
                    if (splitByUnit && byUnit.isNotEmpty()) {
                        val plain = selected.filter { it.unit.isBlank() }.map { it.toWordItem() }
                        if (plain.isNotEmpty()) WordRepository.addWords(plain)
                        var firstListId = ""
                        byUnit.groupBy { it.unit.trim() }.forEach { (unit, items) ->
                            val listId = WordRepository.addWordsToNamedList(unit, items.map { it.toWordItem() })
                            if (firstListId.isEmpty()) firstListId = listId
                        }
                        if (firstListId.isNotEmpty()) WordRepository.switchToList(firstListId)
                    } else {
                        WordRepository.addWords(selected.map { it.toWordItem() })
                    }
                    WordRepository.save(context)
                    parsedItems.clear()
                    inputText = ""
                    showReview = false
                },
                onBackToInput = {
                    showReview = false
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

@Composable
private fun InputView(
    inputText: String,
    onInputChange: (String) -> Unit,
    onParse: () -> Unit,
    parseMessage: String,
    hasImage: Boolean,
    aiLoading: Boolean,
    onPickImage: () -> Unit,
    onVisionAi: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // --- Vision AI section ---
        Text(
            text = "视觉 AI 识别",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "拍照发给 AI，自动识别单词表并转为词库 JSON",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPickImage,
                modifier = Modifier.weight(1f),
                enabled = !aiLoading
            ) {
                Text(if (hasImage) "已选图 ✓" else "拍照 / 选图", fontSize = 14.sp)
            }
            Button(
                onClick = onVisionAi,
                modifier = Modifier.weight(1f),
                enabled = hasImage && !aiLoading
            ) {
                Text(if (aiLoading) "识别中..." else "AI 识别", fontSize = 14.sp)
            }
        }

        if (aiLoading) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // --- Manual input section ---
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "手动粘贴词表",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "支持格式：JSON 或 每行\"英文 - 中文\"\n也可粘贴 ChatGPT 等 AI 输出的 JSON 结果",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            placeholder = {
                Text(
                    "例如：\n" +
                            "achievement - 成就；成绩\n" +
                            "take care of : 照顾；照料\n\n" +
                            "或粘贴 JSON 格式词表"
                )
            },
            singleLine = false
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onParse,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = inputText.isNotBlank()
        ) {
            Text("解析", fontSize = 16.sp)
        }

        if (parseMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Text(
                    text = parseMessage,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private sealed interface ReviewRow {
    data class UnitHeader(val unit: String, val count: Int) : ReviewRow
    data class Entry(val index: Int) : ReviewRow
}

private fun buildReviewRows(items: List<EditableWordItem>, groupByUnit: Boolean): List<ReviewRow> {
    if (!groupByUnit) return items.indices.map { ReviewRow.Entry(it) }
    val rows = mutableListOf<ReviewRow>()
    var lastUnit: String? = null
    items.forEachIndexed { index, item ->
        val unit = item.unit.trim()
        if (unit != lastUnit) {
            rows.add(ReviewRow.UnitHeader(unit.ifEmpty { "未指定单元" }, items.count { it.unit.trim() == unit }))
            lastUnit = unit
        }
        rows.add(ReviewRow.Entry(index))
    }
    return rows
}

@Composable
private fun ReviewView(
    items: MutableList<EditableWordItem>,
    splitByUnit: Boolean,
    onSplitByUnitChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onBackToInput: () -> Unit,
    modifier: Modifier
) {
    val checkedCount = items.count { it.include }
    val unitCount = items.map { it.unit.trim() }.filter { it.isNotEmpty() }.distinct().size
    val rows = remember(items.toList(), splitByUnit) { buildReviewRows(items.toList(), splitByUnit) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (unitCount > 0) "解析到 ${items.size} 个词、$unitCount 个单元" else "解析到 ${items.size} 个词，请校对",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onBackToInput) {
                Text("重新输入")
            }
        }

        if (unitCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = splitByUnit, onCheckedChange = onSplitByUnitChange)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("按单元分配到词表", fontSize = 14.sp)
                    Text(
                        text = if (splitByUnit) "每个单元保存为独立词表，同名词表会追加" else "全部保存到当前词表",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = rows,
                key = { row ->
                    when (row) {
                        is ReviewRow.UnitHeader -> "unit_${row.unit}"
                        is ReviewRow.Entry -> items[row.index].id
                    }
                }
            ) { row ->
                when (row) {
                    is ReviewRow.UnitHeader -> Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(row.unit, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("${row.count} 词", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    is ReviewRow.Entry -> {
                        val index = row.index
                        EditableItemCard(
                            item = items[index],
                            onTextChange = { items[index] = items[index].copy(text = it) },
                            onMeaningChange = { items[index] = items[index].copy(meaningZh = it) },
                            onIncludeChange = { items[index] = items[index].copy(include = it) },
                            onTypeChange = { items[index] = items[index].copy(type = it) },
                            onPartOfSpeechChange = { items[index] = items[index].copy(partOfSpeech = it) },
                            onAliasesChange = { items[index] = items[index].copy(aliases = it) },
                            onUnitChange = { items[index] = items[index].copy(unit = it) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBackToInput,
                modifier = Modifier.weight(1f)
            ) {
                Text("取消")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                enabled = checkedCount > 0
            ) {
                Text("保存选中（$checkedCount）")
            }
        }
    }
}

@Composable
private fun EditableItemCard(
    item: EditableWordItem,
    onTextChange: (String) -> Unit,
    onMeaningChange: (String) -> Unit,
    onIncludeChange: (Boolean) -> Unit,
    onTypeChange: (String) -> Unit,
    onPartOfSpeechChange: (String) -> Unit,
    onAliasesChange: (String) -> Unit,
    onUnitChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.include)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.include,
                    onCheckedChange = onIncludeChange
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = item.type == "word",
                    onClick = { onTypeChange("word") },
                    label = { Text("单词", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = item.type == "phrase",
                    onClick = { onTypeChange("phrase") },
                    label = { Text("短语", fontSize = 11.sp) },
                    modifier = Modifier.height(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = item.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("英文") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = item.meaningZh,
                onValueChange = onMeaningChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("中文释义") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = item.partOfSpeech,
                onValueChange = onPartOfSpeechChange,
                modifier = Modifier.width(100.dp),
                label = { Text("词性") },
                singleLine = true,
                placeholder = { Text("n./v./adj.") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = item.aliases,
                onValueChange = onAliasesChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("词形变体（逗号分隔）") },
                singleLine = true,
                placeholder = { Text("例如：achieved, achieving, achieves") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = item.unit,
                onValueChange = onUnitChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("单元（留空则存入当前词表）") },
                singleLine = true,
                placeholder = { Text("例如：Unit 1") }
            )
        }
    }
}

private suspend fun loadImageBase64(context: android.content.Context, uri: Uri): Pair<String, String> {
    return withContext(Dispatchers.IO) {
        try {
            val maxBytes = 12L * 1024 * 1024
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > maxBytes) return@withContext "" to "jpeg"
            }
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.use { it.readBytes() } ?: return@withContext "" to "jpeg"
            if (bytes.size > maxBytes) return@withContext "" to "jpeg"
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val type = mimeType.substringAfterLast("/", "jpeg")
            Base64.encodeToString(bytes, Base64.NO_WRAP) to type
        } catch (_: Exception) {
            "" to "jpeg"
        }
    }
}

private fun parseInput(text: String): List<EditableWordItem> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()

    val jsonItems = tryParseJson(trimmed)
    if (jsonItems != null) return jsonItems

    return parseTextLines(trimmed)
}

private fun tryParseJson(text: String): List<EditableWordItem>? {
    return try {
        val json = JSONObject(text)
        val items = json.getJSONArray("items")
        val pageUnit = json.optString("unitName", "").trim()
        val result = mutableListOf<EditableWordItem>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val english = item.optString("text", "")
            result.add(
                EditableWordItem(
                    id = item.optString("id", "import_${System.currentTimeMillis()}_$i"),
                    text = english,
                    meaningZh = item.optString("meaningZh", ""),
                    partOfSpeech = item.optString("partOfSpeech", ""),
                    aliases = item.optJSONArray("aliases")?.let { arr ->
                        (0 until arr.length()).joinToString(", ") { arr.getString(it) }
                    } ?: "",
                    type = if (english.contains(" ")) "phrase" else "word",
                    unit = item.optString("unit", "").trim().ifEmpty { pageUnit }
                )
            )
        }
        if (result.isEmpty()) null else result
    } catch (_: Exception) {
        null
    }
}

/**
 * Recognises a textbook unit heading such as "Unit 1", "Module 2A" or "第三单元" and returns
 * the label to group by, or null when the line is an ordinary entry. A heading may carry a
 * title ("Unit 1 My day"), but a plain vocabulary line such as "unit 单元" is not a heading
 * because no number follows the keyword.
 */
internal fun detectUnitLabel(line: String): String? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val english = ENGLISH_UNIT_HEADER.find(trimmed)
    if (english != null) {
        // Normalise the plural keyword so "Units 1-2" and "Unit 1" name their lists consistently.
        val keyword = english.groupValues[1].lowercase().removeSuffix("s").replaceFirstChar { it.uppercase() }
        val number = english.groupValues[2].replace(Regex("""\s+"""), "")
        return "$keyword $number"
    }
    val chinese = CHINESE_UNIT_HEADER.find(trimmed)
    if (chinese != null) {
        val number = chinese.groupValues[1]
        val kind = chinese.groupValues[2]
        return "第$number$kind"
    }
    return null
}

private val ENGLISH_UNIT_HEADER = Regex(
    """^(units?|modules?|chapters?|parts?)\s*([0-9]{1,3}[A-Za-z]?(?:\s*[-–~,]\s*[0-9]{1,3}[A-Za-z]?)*|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\b""",
    RegexOption.IGNORE_CASE
)

private val CHINESE_UNIT_HEADER = Regex("""^第\s*([0-9]{1,3}|[一二三四五六七八九十百零两]+)\s*(单元|课|模块|部分)""")

internal fun parseTextLines(text: String): List<EditableWordItem> {
    val result = mutableListOf<EditableWordItem>()
    val timestamp = System.currentTimeMillis()
    var currentUnit = ""
    text.lines().forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEachIndexed

        val unitLabel = detectUnitLabel(trimmed)
        if (unitLabel != null) {
            currentUnit = unitLabel
            return@forEachIndexed
        }

        val delimiterMatch = Regex("""\s*[—–\-:：,，=→\t]\s*""").find(trimmed)
        val (english, chinese) = if (delimiterMatch != null) {
            val pos = delimiterMatch.range.first
            trimmed.substring(0, pos).trim() to trimmed.substring(delimiterMatch.range.last + 1).trim()
        } else {
            val spaceMatch = Regex("""^(.+?)\s+([\u4e00-\u9fff].*)$""").find(trimmed)
            if (spaceMatch != null) {
                spaceMatch.groupValues[1].trim() to spaceMatch.groupValues[2].trim()
            } else {
                trimmed to ""
            }
        }

        val type = if (english.contains(" ")) "phrase" else "word"
        result.add(
            EditableWordItem(
                id = "import_${timestamp}_$index",
                text = english,
                meaningZh = chinese,
                type = type,
                unit = currentUnit
            )
        )
    }
    return result
}

private fun EditableWordItem.toWordItem() = WordItem(
    id = id,
    type = type,
    text = text.trim(),
    meaningZh = meaningZh.trim(),
    partOfSpeech = partOfSpeech.trim(),
    aliases = aliases.split(",").map { it.trim() }.filter { it.isNotBlank() }
)
