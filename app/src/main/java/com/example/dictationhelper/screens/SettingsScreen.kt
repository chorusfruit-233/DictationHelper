/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dictationhelper.BuildConfig
import com.example.dictationhelper.data.WordRepository
import com.example.dictationhelper.llm.AiConfigManager
import com.example.dictationhelper.llm.AiProfile
import com.example.dictationhelper.llm.DICTATION_PROMPT_TEMPLATE
import com.example.dictationhelper.speech.VoskModelManager
import com.example.dictationhelper.ui.theme.ThemeSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var showWordListManage by remember { mutableStateOf(false) }
    var showDictationConfig by remember { mutableStateOf(false) }
    var showVisionConfig by remember { mutableStateOf(false) }
    var showDictationPrompt by remember { mutableStateOf(false) }
    var showVisionPrompt by remember { mutableStateOf(false) }
    var showClearData by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === 词库管理 ===
            item {
                SettingsSectionHeader("词库管理")
                SettingsCard {
                    SettingsClickRow(
                        label = "当前词表",
                        summary = WordRepository.currentName(),
                        onClick = { showWordListManage = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsInfoRow(
                        label = "单词数量",
                        summary = "${WordRepository.words.size} 个"
                    )
                }
            }

            // === AI 配置 ===
            item {
                SettingsSectionHeader("AI 配置")
                SettingsCard {
                    val dc = AiConfigManager.getConfig(AiConfigManager.dictationConfigId)
                    SettingsClickRow(
                        label = "听写 AI",
                        summary = dc?.let { "${it.name} / ${it.model}" } ?: "未配置",
                        onClick = { showDictationConfig = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    val vc = AiConfigManager.getConfig(AiConfigManager.visionConfigId)
                    SettingsClickRow(
                        label = "视觉 AI",
                        summary = vc?.let { "${it.name} / ${it.model}" } ?: "未配置",
                        onClick = { showVisionConfig = true }
                    )
                }
            }

            // === Prompt 编辑 ===
            item {
                SettingsSectionHeader("Prompt 编辑")
                SettingsCard {
                    val prefs = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    val dictPrompt = prefs.getString("dictation_prompt", "") ?: ""
                    val visPrompt = prefs.getString("vision_prompt", "") ?: ""
                    SettingsClickRow(
                        label = "听写 AI Prompt",
                        summary = if (dictPrompt.isBlank()) "使用默认提示词" else "已自定义 (${dictPrompt.length}字)",
                        onClick = { showDictationPrompt = true }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickRow(
                        label = "视觉 AI Prompt",
                        summary = if (visPrompt.isBlank()) "使用默认提示词" else "已自定义 (${visPrompt.length}字)",
                        onClick = { showVisionPrompt = true }
                    )
                }
            }

            // === 主题 ===
            item {
                SettingsSectionHeader("主题")
                SettingsCard {
                    // Theme mode
                    Text(
                        text = "主题模式",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                            val selected = ThemeSettings.themeMode == value
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    ThemeSettings.themeMode = value
                                    ThemeSettings.save(context)
                                },
                                label = { Text(label, fontSize = 13.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // Dynamic color (Monet)
                    SettingsSwitchRow(
                        label = "Material You (Monet)",
                        description = "根据系统壁纸自动提取配色（Android 12+）",
                        checked = ThemeSettings.useDynamicColor,
                        onCheckedChange = {
                            ThemeSettings.useDynamicColor = it
                            ThemeSettings.save(context)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // AMOLED dark
                    SettingsSwitchRow(
                        label = "AMOLED 纯黑",
                        description = "深色模式使用纯黑背景（适合 OLED 屏幕）",
                        checked = ThemeSettings.amoledDark,
                        onCheckedChange = {
                            ThemeSettings.amoledDark = it
                            ThemeSettings.save(context)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // UI Scale slider
                    val sliderRange = 0.75f..1.5f
                    val scalePercent = ((ThemeSettings.uiScale - sliderRange.start) / (sliderRange.endInclusive - sliderRange.start))
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "界面缩放", fontSize = 16.sp)
                            Text(
                                text = "${(ThemeSettings.uiScale * 100).toInt()}%",
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "调整全局字体和元素大小",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Slider(
                            value = ThemeSettings.uiScale,
                            onValueChange = { ThemeSettings.uiScale = it },
                            onValueChangeFinished = { ThemeSettings.save(context) },
                            valueRange = sliderRange,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("75%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("150%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    // Predictive back
                    SettingsSwitchRow(
                        label = "预测性返回手势",
                        description = "使用新版返回动画（Android 14+，需重启 App 生效）",
                        checked = ThemeSettings.predictiveBack,
                        onCheckedChange = {
                            ThemeSettings.predictiveBack = it
                            ThemeSettings.save(context)
                        }
                    )
                }
            }

            // === 功能开关 ===
            item {
                SettingsSectionHeader("功能开关")
                SettingsCard {
                    SettingsSwitchRow(
                        label = "AI 语义解析",
                        description = "听写时优先使用 AI 解析老师说的话",
                        checked = ThemeSettings.useLlm,
                        onCheckedChange = {
                            ThemeSettings.useLlm = it
                            ThemeSettings.save(context)
                        }
                    )
                }
            }

            // === 语音设置 ===
            item {
                SettingsSectionHeader("语音设置")
                SettingsCard {
                    val cnReady = VoskModelManager.isModelReady(context, "zh-CN")
                    val enReady = VoskModelManager.isModelReady(context, "en-US")
                    var showCnModelDialog by remember { mutableStateOf(false) }
                    var showEnModelDialog by remember { mutableStateOf(false) }
                    val anyReady = cnReady || enReady

                    SettingsSwitchRow(
                        label = "离线语音识别 (Vosk)",
                        description = when {
                            cnReady && enReady -> "已安装中/英双语模型，可离线使用"
                            cnReady -> "已安装中文模型，可离线使用"
                            enReady -> "已安装英文模型，可离线使用"
                            else -> "使用离线语音引擎，无需网络。需先安装语音模型"
                        },
                        checked = ThemeSettings.useVoskOffline,
                        onCheckedChange = {
                            if (it && !anyReady) {
                                showCnModelDialog = true
                            } else {
                                ThemeSettings.useVoskOffline = it
                                ThemeSettings.save(context)
                            }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsClickRow(
                        label = "中文模型 (zh-CN)",
                        summary = if (cnReady) "已安装 ✓" else "未安装",
                        summaryColor = if (cnReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { showCnModelDialog = true }
                    )

                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    SettingsClickRow(
                        label = "英文模型 (en-US)",
                        summary = if (enReady) "已安装 ✓" else "未安装",
                        summaryColor = if (enReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { showEnModelDialog = true }
                    )

                    if (showCnModelDialog) {
                        VoskModelDownloadDialog(
                            lang = "zh-CN",
                            label = "中文语音模型",
                            hint = "vosk-model-small-cn-0.22.zip (~42MB)",
                            onDismiss = { showCnModelDialog = false }
                        )
                    }
                    if (showEnModelDialog) {
                        VoskModelDownloadDialog(
                            lang = "en-US",
                            label = "英文语音模型",
                            hint = "vosk-model-small-en-us-0.15.zip (~40MB)",
                            onDismiss = { showEnModelDialog = false }
                        )
                    }
                }
            }

            // === 数据管理 ===
            item {
                SettingsSectionHeader("数据管理")
                SettingsCard {
                    SettingsClickRow(
                        label = "清除所有词库数据",
                        summary = "重置",
                        summaryColor = MaterialTheme.colorScheme.error,
                        onClick = { showClearData = true }
                    )
                }
            }

            // === 关于 ===
            item {
                SettingsSectionHeader("关于")
                SettingsCard {
                    SettingsInfoRow(
                        label = "应用名称",
                        summary = "听写助手"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsInfoRow(
                        label = "版本",
                        summary = BuildConfig.VERSION_NAME
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsInfoRow(
                        label = "构建时间",
                        summary = BuildConfig.BUILD_TIME
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsInfoRow(
                        label = "许可证",
                        summary = "GPL-3.0-or-later"
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickRow(
                        label = "源代码",
                        summary = "GitHub",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/chorusfruit-233/DictationHelper"))
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickRow(
                        label = "问题反馈",
                        summary = "Issues",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/chorusfruit-233/DictationHelper/issues"))
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickRow(
                        label = "开源许可证",
                        summary = "第三方库",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/chorusfruit-233/DictationHelper/blob/main/THIRD_PARTY_LICENSES.md"))
                            context.startActivity(intent)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Copyright (C) 2026 chorusfruit-233\nLicensed under GPLv3 or any later version",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // === Dialogs ===
    if (showWordListManage) {
        WordListManagementDialog(onDismiss = { showWordListManage = false })
    }
    if (showDictationConfig) {
        ConfigPickerDialog(
            title = "听写 AI 配置",
            currentId = AiConfigManager.dictationConfigId,
            onSelect = {
                AiConfigManager.dictationConfigId = it
                AiConfigManager.save(context)
                showDictationConfig = false
            },
            onDismiss = { showDictationConfig = false }
        )
    }
    if (showVisionConfig) {
        ConfigPickerDialog(
            title = "视觉 AI 配置",
            currentId = AiConfigManager.visionConfigId,
            onSelect = {
                AiConfigManager.visionConfigId = it
                AiConfigManager.save(context)
                showVisionConfig = false
            },
            onDismiss = { showVisionConfig = false }
        )
    }
    if (showDictationPrompt) {
        PromptEditDialog(
            title = "听写 AI Prompt",
            prefsKey = "dictation_prompt",
            defaultValue = DICTATION_PROMPT_TEMPLATE,
            onDismiss = { showDictationPrompt = false }
        )
    }
    if (showVisionPrompt) {
        PromptEditDialog(
            title = "视觉 AI Prompt",
            prefsKey = "vision_prompt",
            defaultValue = DEFAULT_VISION_PROMPT,
            onDismiss = { showVisionPrompt = false }
        )
    }
    if (showClearData) {
        AlertDialog(
            onDismissRequest = { showClearData = false },
            title = { Text("清除数据") },
            text = { Text("确定要清除所有词库数据吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        WordRepository.words.clear()
                        WordRepository.lists.clear()
                        WordRepository.init(context)
                        WordRepository.save(context)
                        showClearData = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearData = false }) { Text("取消") }
            }
        )
    }
}

// === MD3E Settings Components ===

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
fun SettingsClickRow(
    label: String,
    summary: String,
    summaryColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = summary,
            fontSize = 14.sp,
            color = summaryColor,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun SettingsInfoRow(label: String, summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = summary,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun SettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 16.sp)
            Text(
                text = description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// === Reusable Dialogs ===

@Composable
fun WordListManagementDialog(onDismiss: () -> Unit) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建词表") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("词表名称") },
                    singleLine = true,
                    placeholder = { Text("例如：Unit 2") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            WordRepository.createList(newName)
                            newName = ""
                            showCreate = false
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理词表") },
        text = {
            Column {
                WordRepository.lists.forEach { list ->
                    val isCurrent = list.id == WordRepository.currentListId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { WordRepository.switchToList(list.id) },
                            modifier = Modifier.weight(1f),
                            colors = if (isCurrent)
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            else
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                        ) {
                            Text(
                                "${list.name} (${list.words.size}词)${if (isCurrent) " ✓" else ""}"
                            )
                        }
                        if (WordRepository.lists.size > 1) {
                            TextButton(
                                onClick = { WordRepository.deleteList(list.id) }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        newName = ""
                        showCreate = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ 新建词表")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
fun PromptEditDialog(
    title: String,
    prefsKey: String,
    defaultValue: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    val saved = prefs.getString(prefsKey, "") ?: ""
    var text by remember { mutableStateOf(saved.ifBlank { defaultValue }) }
    val isModified = saved.isNotBlank() && text != defaultValue

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (saved.isNotBlank()) {
                    Text(
                        text = "已自定义 Prompt（${text.length} 字）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                } else {
                    Text(
                        text = "使用系统默认 Prompt，可直接编辑覆盖",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 14,
                    label = { Text("Prompt 内容") }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (text.isBlank() || text == defaultValue) {
                    prefs.edit().remove(prefsKey).apply()
                } else {
                    prefs.edit().putString(prefsKey, text).apply()
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    prefs.edit().remove(prefsKey).apply()
                    text = defaultValue
                }) { Text("重置为默认", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
fun VoskModelDownloadDialog(
    lang: String,
    label: String,
    hint: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("idle") }
    var progress by remember { mutableStateOf(0f) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "importing"
        VoskModelManager.importFromUri(context, lang, uri) { success ->
            status = if (success) "done" else "error"
            if (success) {
                ThemeSettings.useVoskOffline = true
                ThemeSettings.save(context)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (status != "importing") onDismiss() },
        title = { Text("安装$label") },
        text = {
            Column {
                when (status) {
                    "idle" -> {
                        Text(
                            "需要 $hint\n" +
                            "可从以下渠道获取：\n\n" +
                            "1. alphacephei.com/vosk/models\n" +
                            "2. GitHub: k2-fsa/collected_models\n" +
                            "3. Gitee 镜像或网盘分享\n\n" +
                            "下载 zip 文件后，点击下方按钮选择文件导入",
                            fontSize = 13.sp
                        )
                    }
                    "importing" -> {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("导入中... ${(progress * 100).toInt()}%")
                    }
                    "done" -> {
                        Text("导入成功！${label}现在可以使用了。")
                    }
                    "error" -> {
                        Text(
                            "导入失败: ${VoskModelManager.downloadError.value ?: "未知错误"}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (status) {
                "idle" -> {
                    Button(onClick = { filePicker.launch("application/zip") }) {
                        Text("选择 zip 文件")
                    }
                }
                "done" -> {
                    Button(onClick = onDismiss) { Text("完成") }
                }
                "error" -> {
                    Row {
                        Button(onClick = { status = "idle" }) { Text("重试") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onDismiss) { Text("关闭") }
                    }
                }
                else -> {
                    Button(onClick = {}, enabled = false) { Text("导入中...") }
                }
            }
        },
        dismissButton = {
            if (status != "importing") {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )

    if (status == "importing") {
        LaunchedEffect(Unit) {
            while (status == "importing") {
                progress = VoskModelManager.downloadProgress.floatValue
                kotlinx.coroutines.delay(200)
            }
        }
    }
}

