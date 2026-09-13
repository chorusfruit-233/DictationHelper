/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.util.Properties

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.security.SecureRandom
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateIconColorTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val palette = listOf("#38BDF8", "#A78BFA", "#F472B6", "#34D399", "#FBBF24", "#FB7185", "#22D3EE")
        val color = palette[SecureRandom().nextInt(palette.size)]
        val valuesDir = outputDirectory.get().dir("values").asFile
        valuesDir.mkdirs()
        valuesDir.resolve("icon_color.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><resources><color name=\"icon_d_color\">$color</color></resources>"
        )
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Give each built APK a fresh accent color while keeping the icon itself a
// simple, recognizable letter D. The generated resource is never checked in.
val iconColorDir = layout.buildDirectory.dir("generated/iconColor/res")
val generateIconColor by tasks.registering(GenerateIconColorTask::class) {
    outputDirectory.set(iconColorDir)
    outputs.upToDateWhen { false }
}

android {
    sourceSets["main"].res.srcDir(iconColorDir.get().asFile)
    namespace = "com.example.dictationhelper"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.example.dictationhelper"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }

        buildConfigField("String", "BUILD_TIME", "\"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date())}\"")
    }

    // Load signing config from properties file (local) or env vars (CI)
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val releaseSigningConfig = if (keystorePropertiesFile.exists()) {
        val props = Properties().apply { load(keystorePropertiesFile.inputStream()) }
        signingConfigs.create("release") {
            storeFile = file(props["storeFile"] as String)
            storePassword = props["storePassword"] as String
            keyAlias = props["keyAlias"] as String
            keyPassword = props["keyPassword"] as String
        }
    } else {
        signingConfigs.create("releaseCi") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "dummy.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "dummy"
            keyAlias = System.getenv("KEY_ALIAS") ?: "dummy"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "dummy"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = releaseSigningConfig
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            excludes += emptySet<String>()
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/whisper/CMakeLists.txt")
        }
    }
}

tasks.named("preBuild").configure { dependsOn(generateIconColor) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("org.apache.commons:commons-compress:1.27.1")
}
