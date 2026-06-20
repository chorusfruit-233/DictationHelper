# Dictation Helper — 英语听写助手

> **声明**：这是一个零基础编程者的 vibe coding 作品。作者在没有任何 Kotlin / Android 开发经验的情况下，全程通过 AI 辅助完成需求设计、代码编写、调试和发布。代码可能不够规范，但功能完整可用。项目以 GPLv3 开源，欢迎参考或改进。

基于 Android + Jetpack Compose 的离线优先英语听写辅助工具。支持语音识别、AI 语义解析、视觉识别导入、多词表管理等，帮助个人进行课前预习、课后复习和听写自测。

## 功能

### 词库管理
- 多词表支持（创建、切换、删除词表）
- 单词/短语条目管理（英文、中文释义、词性）
- 本地 JSON 文件持久化存储
- 首次启动为空词表，支持手动导入或视觉 AI 导入

### 听写匹配
- **手动输入**：输入老师说的话，自动匹配词库中最可能的单词
- **在线语音识别**：使用系统语音服务转文字
- **离线语音识别**：基于 [Vosk](https://alphacephei.com/vosk/) 的完全离线识别，无需网络
- 多种匹配方式：中文释义、英文片段、首字母、词数、读音模糊匹配
- 置信度评分 + 匹配原因展示
- 确认/排除候选词

### AI 语义解析
- 接入 OpenAI 兼容 LLM（GPT-4o / Groq / DeepSeek / Ollama 等）
- 将老师的自然语言指令解析为结构化查询
- 支持中英文谐音纠错（语音识别产生的同音字自动还原）
- 拼音辅助（自动将中文谐音转为拼音供 LLM 理解）
- 可自定义系统 Prompt
- 多 AI 配置管理（创建/编辑/删除）

### 视觉 AI 导入
- 拍照或从相册选择课本单词表
- 发送给视觉大模型（GPT-4o 等）自动识别单词表结构
- 直接输出标准词库 JSON
- 自定义 Prompt 模板

### 语音识别
- **在线模式**：Android RecognizerIntent（系统语音对话框）
- **离线模式**：Vosk 离线引擎，中英文双模型
- 模型手动导入（zip 文件选择）
- 中英文语言切换

### 主题外观
- Material Design 3 (MD3E) 规范
- 浅色 / 深色 / 跟随系统
- Material You (Monet) 动态配色
- AMOLED 纯黑模式
- 界面缩放（75% ~ 150%）

## 技术栈

| 层级 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| 构建 | Gradle 9.4.1 + AGP 9.2.1 |
| 离线语音 | Vosk Android SDK |
| 网络 | OkHttp 4 |
| AI 通信 | OpenAI 兼容 Chat Completions API |
| 持久化 | JSON 文件 + SharedPreferences |
| 图标 | Adaptive Icon + Vector Drawable |
| CI/CD | GitHub Actions (手动触发 + Release) |

## 项目结构

```
app/src/main/java/com/example/dictationhelper/
├── MainActivity.kt            # 入口 + 底栏导航 + 词库卡片
├── model/
│   └── WordItem.kt            # WordList + WordItem 数据模型
├── data/
│   ├── WordRepository.kt      # 词库仓库（多词表）
│   └── WordStorage.kt         # JSON 文件读写
├── matching/
│   ├── Matcher.kt             # 本地匹配引擎（中文/英文/读音）
│   ├── RuleParser.kt          # 规则解析器
│   └── DictationState.kt      # 听写状态机
├── llm/
│   ├── LlmClient.kt           # LLM API 客户端 + 视觉 AI
│   └── AiConfigManager.kt     # 多 AI 配置管理
├── speech/
│   ├── VoskRecognizer.kt      # Vosk 离线识别器
│   └── VoskModelManager.kt    # 模型文件管理 + 导入
├── screens/
│   ├── DictationScreen.kt     # 听写匹配页面
│   ├── ImportScreen.kt        # 词表导入页面
│   ├── SettingsScreen.kt      # 设置页面
│   └── AiConfigDialogs.kt     # AI 配置对话框
└── ui/theme/
    ├── Theme.kt               # Material 3 主题
    ├── ThemeSettings.kt       # 主题设置状态
    ├── Color.kt               # 调色板
    └── Type.kt                # 字体
```

## 快速开始

### 环境要求
- Android Studio (Latest)
- JDK 17+
- Android SDK 29+

### 构建

```bash
# 克隆
git clone https://github.com/chorusfruit-233/DictationHelper.git
cd DictationHelper

# Debug APK
./gradlew assembleDebug

# Release APK（本地需要 keystore.properties）
./gradlew assembleRelease
```

### keystore.properties 格式（本地 Release 构建需要）

```properties
storePassword=your_password
keyPassword=your_password
keyAlias=your_alias
storeFile=../app/your-keystore.jks
```

## 离线语音模型

在 App 设置 → 语音设置中安装：

| 语言 | 文件名 | 大小 |
|------|--------|------|
| 中文 | `vosk-model-small-cn-0.22.zip` | ~42MB |
| 英文 | `vosk-model-small-en-us-0.15.zip` | ~40MB |

下载 zip 后通过 App 导入即可离线使用。

## AI 配置

支持 OpenAI 兼容 API，常见配置示例：

| 服务 | API 地址 | 推荐模型 |
|------|----------|----------|
| OpenAI | `https://api.openai.com/v1/chat/completions` | `gpt-4o` |
| Groq (免费) | `https://api.groq.com/openai/v1/chat/completions` | `llama-3.3-70b-versatile` |
| DeepSeek | `https://api.deepseek.com/v1/chat/completions` | `deepseek-chat` |
| 本地 Ollama | `http://<IP>:11434/v1/chat/completions` | `llama3.2` |

## CI/CD

GitHub Actions 手动触发：Actions → **Build & Release** → **Run workflow**

可选参数：
- `Create Release` — 发布 GitHub Release（Tag 使用 `versionName`）
- `Also build with Vosk models` — 额外构建预装中英文离线模型的 APK

**构建产物**（Release APK，R8 优化 + 签名 + 去日志）：
- `DictationHelper-v{version}.apk` — 标准版
- `DictationHelper-v{version}-with-models.apk` — 预装中/英文离线语音模型

**缓存**：Gradle 依赖 + 配置缓存 + Vosk 模型 zip 均自动缓存，重复构建显著加速。

## 许可证

[GNU General Public License v3.0](LICENSE)

Copyright (C) 2026 chorusfruit-233
