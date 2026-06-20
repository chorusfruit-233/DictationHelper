/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.dictationhelper.data.WordRepository
import com.example.dictationhelper.llm.AiConfigManager
import com.example.dictationhelper.llm.LlmClient
import com.example.dictationhelper.llm.LlmConfig
import com.example.dictationhelper.matching.DictationState
import com.example.dictationhelper.matching.MatchResult
import com.example.dictationhelper.matching.Matcher
import com.example.dictationhelper.matching.ParsedAction
import com.example.dictationhelper.matching.RuleParser
import com.example.dictationhelper.model.WordItem
import com.example.dictationhelper.speech.VoskRecognizer
import com.example.dictationhelper.ui.theme.ThemeSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val dictationState = remember(WordRepository.currentListId) { DictationState(WordRepository.words.toList()) }
    var inputText by remember { mutableStateOf("") }
    var actionLabel by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<MatchResult>>(emptyList()) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var speechLang by remember { mutableStateOf("zh-CN") }

    val appPrefs = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    var llmProcessing by remember { mutableStateOf(false) }
    var llmRawContent by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun processAction(parsed: ParsedAction, prefix: String = "") {
        actionLabel = prefix + actionToLabel(parsed.action)
        when (parsed.action) {
            "NEXT_ITEM" -> {
                dictationState.clearRound()
                candidates = emptyList()
            }
            "PREVIOUS_ITEM" -> {
                dictationState.clearRound()
                candidates = emptyList()
            }
            "REJECT_CANDIDATE" -> {
                val rejected = dictationState.rejectTopCandidate()
                candidates = dictationState.currentCandidates
                if (rejected != null) {
                    actionLabel += "（已排除：${rejected.word.text}）"
                }
            }
            "REPEAT_CURRENT" -> {
                candidates = dictationState.currentCandidates
            }
            "FIND_WORD" -> {
                parsed.constraints?.let {
                    val results = Matcher.match(it, WordRepository.words)
                    dictationState.setCandidates(results)
                    candidates = dictationState.currentCandidates
                }
            }
            "UNKNOWN" -> {
                candidates = emptyList()
            }
        }
        inputText = ""
    }

    fun handleInput(text: String) {
        val usingLlm = ThemeSettings.useLlm
        val config = AiConfigManager.getDictationConfig()
        if (usingLlm && config.apiKey.isNotBlank()) {
            llmProcessing = true
            actionLabel = "🤖 AI 解析中..."
            scope.launch {
                val customPrompt = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    .getString("dictation_prompt", "")?.takeIf { it.isNotBlank() }
                val result = LlmClient.parse(text, config, WordRepository.words, customPrompt)
                llmProcessing = false
                llmRawContent = result.rawContent ?: ""
                when {
                    result.action != null -> processAction(result.action, "🤖 ")
                    result.error != null -> {
                        val fallback = RuleParser.parse(text)
                        processAction(fallback)
                        actionLabel = "🤖 ${result.error}\n→ 已回退规则解析"
                    }
                }
            }
        } else {
            processAction(RuleParser.parse(text))
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) {
            handleInput(text)
        }
    }

    val voskRecognizer = remember {
        VoskRecognizer().apply {
            onResult = { text ->
                android.util.Log.d("Dictation", "Vosk onResult: '$text'")
                handleInput(text)
            }
        }
    }
    val useVosk = ThemeSettings.useVoskOffline
    LaunchedEffect(useVosk, speechLang) {
        Log.d("Dictation", "LaunchedEffect: useVosk=$useVosk speechLang=$speechLang")
        if (useVosk) {
            val ok = voskRecognizer.init(context, speechLang)
            Log.d("Dictation", "init result=$ok")
        }
    }
    DisposableEffect(Unit) {
        onDispose { voskRecognizer.destroy() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听写匹配") },
                actions = {
                    TextButton(onClick = {
                        dictationState.reset()
                        candidates = emptyList()
                        actionLabel = ""
                    }) {
                        Text("重置")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                ProgressSection(
                    confirmed = dictationState.confirmedCount(),
                    total = dictationState.totalCount()
                )
            }

            // --- microphone section ---
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { speechLang = "zh-CN" },
                        modifier = Modifier.weight(1f).height(36.dp),
                        colors = if (speechLang == "zh-CN")
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        else
                            ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("中文", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { speechLang = "en-US" },
                        modifier = Modifier.weight(1f).height(36.dp),
                        colors = if (speechLang == "en-US")
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        else
                            ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("English", fontSize = 13.sp)
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        Log.d("Dictation", "button clicked: useVosk=$useVosk hasPerm=$hasPermission")
                        if (!hasPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else if (useVosk) {
                            if (voskRecognizer.isListening) {
                                voskRecognizer.stopListening()
                            } else {
                                voskRecognizer.startListening()
                            }
                        } else {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "说出老师的话...")
                            }
                            try {
                                speechLauncher.launch(intent)
                            } catch (e: Exception) {
                                actionLabel = "语音识别启动失败：${e.localizedMessage}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = if (useVosk && voskRecognizer.isListening)
                        ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    else
                        ButtonDefaults.buttonColors()
                ) {
                    Text(
                        when {
                            !hasPermission -> "授予麦克风权限"
                            useVosk && voskRecognizer.isListening -> "停止听"
                            else -> "语音输入"
                        }
                    )
                }
            }

            if (useVosk) {
                voskRecognizer.error?.let { err ->
                    item {
                        Text(text = err, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (voskRecognizer.partialText.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = voskRecognizer.partialText,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            // --- AI toggle ---
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = ThemeSettings.useLlm,
                            onCheckedChange = {
                                ThemeSettings.useLlm = it
                            }
                        )
                        Text(
                            text = if (llmProcessing) "AI 解析中..." else "AI 语义解析",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            // --- manual text input ---

            item {
                Text(
                    text = "手动输入",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入老师说的话...") },
                    singleLine = false,
                    minLines = 2
                )
            }

            item {
                Button(
                    onClick = { handleInput(inputText) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = inputText.isNotBlank()
                ) {
                    Text("说完了", fontSize = 16.sp)
                }
            }

            if (actionLabel.isNotEmpty()) {
                item {
                    Text(
                        text = actionLabel,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (llmRawContent.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(
                            text = "AI 原始输出:\n$llmRawContent",
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (candidates.isNotEmpty()) {
                item {
                    Text(
                        text = "匹配结果（${candidates.size}个）",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                itemsIndexed(candidates) { index, result ->
                    CandidateCard(
                        result = result,
                        onConfirm = {
                            dictationState.confirm(index)
                            candidates = dictationState.currentCandidates
                        },
                        onReject = {
                            dictationState.rejectCandidate(index)
                            candidates = dictationState.currentCandidates
                        }
                    )
                }
            }

            if (dictationState.confirmedItems.isNotEmpty()) {
                item {
                    Text(
                        text = "已确认（${dictationState.confirmedCount()}个）",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    ConfirmedWordsRow(
                        words = dictationState.confirmedItems,
                        onRemove = { index ->
                            dictationState.removeConfirmed(index)
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun ProgressSection(confirmed: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "已确认 $confirmed / $total",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${if (total > 0) (confirmed * 100 / total) else 0}%",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (total > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { confirmed.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun CandidateCard(
    result: MatchResult,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.confidence >= 0.67)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = result.word.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "置信度：${(result.confidence * 100).toInt()}%",
                    fontSize = 14.sp,
                    color = if (result.confidence >= 0.67)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.word.meaningZh,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (result.word.partOfSpeech.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = result.word.partOfSpeech,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 12.sp
                        )
                    }
                }
            }
            if (result.reasons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "原因：${result.reasons.joinToString("、")}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确认")
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("不是")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmedWordsRow(
    words: List<WordItem>,
    onRemove: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        words.forEachIndexed { index, word ->
            SuggestionChip(
                onClick = { onRemove(index) },
                label = {
                    Text(
                        text = word.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

private fun actionToLabel(action: String): String = when (action) {
    "NEXT_ITEM" -> "→ 下一个，等待新线索"
    "PREVIOUS_ITEM" -> "→ 上一个，等待新线索"
    "REPEAT_CURRENT" -> "→ 重复当前"
    "REJECT_CANDIDATE" -> "→ 不是这个"
    "FIND_WORD" -> "→ 根据线索匹配"
    "UNKNOWN" -> "→ 无法理解"
    else -> ""
}
