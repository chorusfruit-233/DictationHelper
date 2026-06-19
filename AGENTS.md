# AGENTS.md — DictationHelper

## Build commands

```bash
./gradlew assembleDebug              # debug APK (no signing needed)
./gradlew assembleRelease            # release APK (needs signing config)
./gradlew clean assembleRelease      # full clean release build
```

## Environment

- MinSdk **29** (raised for `android.icu.text.Transliterator`)
- TargetSdk 36, AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10
- `buildConfig = true` must be enabled in `buildFeatures` (`BuildConfig.BUILD_TIME` used)
- `org.gradle.configuration-cache=true` in `gradle.properties`

## Release signing

Two modes, detected at configuration time in `app/build.gradle.kts`:

| Mode | Source | Used by |
|------|--------|---------|
| Local | `keystore.properties` at project root | developer machine |
| CI | env vars `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | GitHub Actions |

The CI workflow decodes `secrets.KEYSTORE_BASE64` → `app/dictation-helper.jks`, then sets `KEYSTORE_FILE=dictation-helper.jks` (path relative to `app/` module).

## ProGuard / R8

Release builds use `isMinifyEnabled = true` + `isShrinkResources = true`. The rules in `app/proguard-rules.pro` are **critical**:

- Vosk: `-keep class org.vosk.*` and `-keepclasseswithmembernames class * { native <methods>; }`
- JNA: `-keepclassmembers class com.sun.jna.Pointer { long peer; long original; }` — R8 renaming these field names breaks JNI lookups
- `Log.d/v/i` are stripped via `-assumenosideeffects`

If Vosk/JNA errors appear in Release but not Debug, check ProGuard rules first.

## App architecture

### Shared state (critical to understand)

- **`ThemeSettings`** (object, `ui/theme/ThemeSettings.kt`): reactive singleton using `mutableStateOf` for all persistent settings. Used by dictation screen, settings screen, and theme. Settings changed anywhere reflect everywhere without Activity recreate.
- **`WordRepository`** (object, `data/WordRepository.kt`): manages multiple `WordList` objects. `words` is a `mutableStateListOf` synced from the current list. `addWords()` auto-saves list state. `currentListId` drives which list is active.
- **`AiConfigManager`** (object, `llm/AiConfigManager.kt`): manages multiple AI API profiles. `dictationConfigId` and `visionConfigId` are separate — dictation and import can use different AI configs.

### Navigation

`MainActivity.kt` → `DictationHelperApp()` → `Scaffold` with `NavigationBar` (4 tabs: 词库/听写/导入/设置). Each tab is a separate composable with its own `Scaffold` + `TopAppBar`. No Navigation Compose library — simple `when (currentTab)` switching.

### Vosk offline speech

- `VoskRecognizer`: wraps Vosk's `Recognizer`, uses `AudioRecord` at 16kHz mono. Recognition thread posts results to main thread via `Handler(Looper.getMainLooper())` — same pattern as online `RecognizerIntent` callback.
- `VoskModelManager`: models stored in `files/vosk_model_cn` or `files/vosk_model_en`; CI-bundled models in `assets/vosk_model_cn` are auto-copied to files on first use (`isModelReady()` triggers the copy, not just `getModelPath()`).
- Model validation: checks `am/final.mdl` + `conf/model.conf` exist (not file count).

### LLM integration

- `LlmClient.parse()` for dictation AI, `LlmClient.parseVision()` for image import
- Dictation prompt template is `DICTATION_PROMPT_TEMPLATE` (in `LlmClient.kt`) with `{wordBank}` placeholder replaced at runtime
- Custom user prompt (from Settings) overrides the template
- Pinyin conversion uses `android.icu.text.Transliterator.getInstance("Han-Latin")` (API 29+)

## CI quirks

- Configuration cache (`.gradle/configuration-cache`) is explicitly cached via `actions/cache@v4` — `setup-gradle@v4` only caches dependencies, not the config cache
- Vosk model zips (~82MB) are cached at `/tmp/vosk-cache` with key `vosk-models-cn-en`
- Release tag uses `versionName` from `app/build.gradle.kts` (parsed with grep), not `run_number`
- Workflow outputs two APKs when `bundle_vosk` is checked: standard + with-models variant

## Compose conventions

- No Navigation Compose — pure state-based tab switching
- `rememberSaveable` for tab index (survives Activity recreation from theme changes)
- `ThemeSettings` uses `mutableStateOf` so Compose recompiles on setting changes without `recreate()`
- Each screen manages its own `Scaffold` + `TopAppBar` nested inside the main `NavigationBar` Scaffold

## Potential footguns

- Don't use `mutableStateOf` delegate (`by`) in non-Compose objects — use explicit `.value` access (see `VoskModelManager` fix history)
- Don't strip Vosk/JNA native libs with R8 — keep rules are mandatory
- Don't use `catch (e: Exception)` when `Error` subclasses may be thrown (e.g. `NoClassDefFoundError` in Vosk native loading)
- `WordRepository.words` is a `SnapshotStateList` — mutation order matters (clear then addAll, not replace)
- The dictation screen's `handleInput` is a local function — must be defined before any `remember`/`LaunchedEffect` that references it
