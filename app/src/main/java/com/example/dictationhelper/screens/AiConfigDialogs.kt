/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dictationhelper.llm.AiConfigManager
import com.example.dictationhelper.llm.AiProfile

@Composable
fun ConfigPickerDialog(
    title: String,
    currentId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf<AiProfile?>(null) }
    var showDelete by remember { mutableStateOf<AiProfile?>(null) }

    if (showCreate) {
        ConfigEditDialog(
            title = "新建配置",
            initial = AiProfile(),
            onSave = { profile ->
                val id = AiConfigManager.create(profile.name, profile.apiUrl, profile.apiKey, profile.model)
                AiConfigManager.save(context)
                onSelect(id)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }

    if (showEdit != null) {
        ConfigEditDialog(
            title = "编辑配置",
            initial = showEdit!!,
            onSave = { profile ->
                AiConfigManager.update(profile.id, profile.name, profile.apiUrl, profile.apiKey, profile.model)
                AiConfigManager.save(context)
                showEdit = null
            },
            onDismiss = { showEdit = null }
        )
    }

    if (showDelete != null) {
        AlertDialog(
            onDismissRequest = { showDelete = null },
            title = { Text("删除配置") },
            text = { Text("确定要删除「${showDelete!!.name}」吗？") },
            confirmButton = {
                Button(onClick = {
                    AiConfigManager.delete(showDelete!!.id)
                    AiConfigManager.save(context)
                    showDelete = null
                }, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = null }) { Text("取消") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                AiConfigManager.profiles.forEach { profile ->
                    val isSelected = profile.id == currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                onSelect(profile.id)
                                AiConfigManager.save(context)
                            },
                            modifier = Modifier.weight(1f),
                            colors = if (isSelected)
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            else
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                        ) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    "${profile.name}${if (isSelected) " ✓" else ""}",
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    profile.model,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        TextButton(
                            onClick = { showEdit = profile }
                        ) {
                            Text("编辑", fontSize = 12.sp)
                        }
                        if (AiConfigManager.profiles.size > 1) {
                            TextButton(
                                onClick = { showDelete = profile }
                            ) {
                                Text("删", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ 新建配置")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun ConfigEditDialog(
    title: String,
    initial: AiProfile,
    onSave: (AiProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var apiUrl by remember { mutableStateOf(initial.apiUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    placeholder = { Text("如：OpenAI / Groq / 本地") }
                )
                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    label = { Text("API 地址") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    singleLine = true,
                    placeholder = { Text("gpt-4o / gpt-4.1-nano / llama-3.3-70b") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(AiProfile(initial.id, name, apiUrl, apiKey, model)) },
                enabled = name.isNotBlank() && apiUrl.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
