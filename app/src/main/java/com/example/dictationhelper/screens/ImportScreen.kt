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
import androidx.compose.foundation.lazy.itemsIndexed
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
    val include: Boolean = true
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
  "unitName": "单元名",
  "items": [
    {"text": "英文单词或短语", "meaningZh": "中文释义", "partOfSpeech": "词性（如 n./v./adj.，没有则留空）", "aliases": ["过去式", "过去分词", "单复数等变体"]}
  ]
}
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var parsedItems = remember { mutableStateListOf<EditableWordItem>() }
    var showReview by remember { mutableStateOf(false) }
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
                        showReview = true
                    }
                },
                parseMessage = parseMessage,
                hasImage = pickedImageBase64.isNotEmpty(),
                aiLoading = aiLoading,
                onPickImage = { imagePicker.launch("image/*") },
                onVisionAi = {
                    aiLoading = true
                    scope.launch {
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
                onSave = {
                    val toSave = parsedItems.filter { it.include }.map { it.toWordItem() }
                    WordRepository.addWords(toSave)
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

@Composable
private fun ReviewView(
    items: MutableList<EditableWordItem>,
    onSave: () -> Unit,
    onBackToInput: () -> Unit,
    modifier: Modifier
) {
    val checkedCount = items.count { it.include }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "解析到 ${items.size} 个词，请校对",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onBackToInput) {
                Text("重新输入")
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items) { index, item ->
                EditableItemCard(
                    item = item,
                    onTextChange = { items[index] = items[index].copy(text = it) },
                    onMeaningChange = { items[index] = items[index].copy(meaningZh = it) },
                    onIncludeChange = { items[index] = items[index].copy(include = it) },
                    onTypeChange = { items[index] = items[index].copy(type = it) },
                    onPartOfSpeechChange = { items[index] = items[index].copy(partOfSpeech = it) },
                    onAliasesChange = { items[index] = items[index].copy(aliases = it) }
                )
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
    onAliasesChange: (String) -> Unit
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
        }
    }
}

private suspend fun loadImageBase64(context: android.content.Context, uri: Uri): Pair<String, String> {
    return withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.use { it.readBytes() } ?: return@withContext "" to "jpeg"
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
                    type = if (english.contains(" ")) "phrase" else "word"
                )
            )
        }
        if (result.isEmpty()) null else result
    } catch (_: Exception) {
        null
    }
}

private fun parseTextLines(text: String): List<EditableWordItem> {
    val result = mutableListOf<EditableWordItem>()
    val timestamp = System.currentTimeMillis()
    text.lines().forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEachIndexed

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
                type = type
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
