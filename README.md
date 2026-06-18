# Dictation Helper

英语听写辅助工具，帮助个人学习、课前复习、课后自测和错词整理。

## 功能

- **词库管理** — 创建多个词表，管理英文单词/短语及中文释义和词性
- **听写匹配** — 手动输入或语音识别老师说的话，实时匹配词库中最可能的单词
- **离线语音识别** — 基于 Vosk 的完全离线语音识别，支持中英文双模型
- **AI 语义解析** — 接入 OpenAI 兼容的 LLM，将老师自然语言指令解析为结构化查询
- **视觉 AI 导入** — 拍照课本单词表，由视觉大模型自动识别并转为词库 JSON
- **Material 3 主题** — 支持深色/浅色模式、Monet 动态配色、AMOLED 纯黑、界面缩放

## 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| 构建 | Gradle + AGP 9.x |
| 语音识别 | Vosk (离线) / Android RecognizerIntent (在线) |
| AI 接口 | OpenAI 兼容 API (GPT-4o / Groq / DeepSeek / Ollama) |
| 图标 | Adaptive Icon + Vector Drawable |
| CI/CD | GitHub Actions |

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/chorusfruit-233/DictationHelper.git
cd DictationHelper

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK（需要 keystore.properties）
./gradlew assembleRelease
```

## 离线语音模型

如需离线语音识别，请在设置中安装 Vosk 模型：

- 中文模型：`vosk-model-small-cn-0.22` (~42MB)
- 英文模型：`vosk-model-small-en-us-0.15` (~40MB)

下载 zip 后在 App 设置中导入。

## 许可证

本项目采用 **GNU General Public License v3.0**。详见 [LICENSE](LICENSE)。
