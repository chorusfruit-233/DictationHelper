# Third Party Licenses

This project uses third-party libraries, models, and resources. Their licenses are separate from the license of DictationHelper itself.

## AndroidX / Jetpack Compose

- Components: Activity Compose, Compose UI, Material3, Lifecycle, Core KTS
- License: Apache License 2.0
- Source: https://developer.android.com/jetpack

## Kotlin

- License: Apache License 2.0
- Source: https://kotlinlang.org

## OkHttp

- License: Apache License 2.0
- Source: https://square.github.io/okhttp/

## Vosk Android SDK

- Library: `com.alphacephei:vosk-android`
- License: Apache License 2.0
- Source: https://alphacephei.com/vosk/

## sherpa-onnx Android runtime

- Library: `sherpa-onnx` Android native runtime and Kotlin API, version 1.13.8
- License: Apache License 2.0
- Source: https://github.com/k2-fsa/sherpa-onnx

The bundled native runtime includes ONNX Runtime components, licensed under the MIT License.

The optional bundled sherpa-onnx model is downloaded from the official sherpa-onnx release
and is not included in the standard APK. Verify the model license before redistribution.

## Vosk Speech Models

Before distributing APK files that include offline speech models, verify and document the license of each model package.

| Model | Version | Source | License |
|-------|---------|--------|---------|
| vosk-model-small-cn | 0.22 | https://alphacephei.com/vosk/models | Apache License 2.0 |
| vosk-model-small-en-us | 0.15 | https://alphacephei.com/vosk/models | Apache License 2.0 |

If this APK includes offline speech recognition models, see this file for model sources and licenses.

## Material Icons Extended

- License: Apache License 2.0
- Source: https://developer.android.com/reference/kotlin/androidx/compose/material/icons/package-summary
