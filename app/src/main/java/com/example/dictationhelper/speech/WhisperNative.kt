/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

internal object WhisperNative {
    init {
        System.loadLibrary("dictation-whisper")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(context: Long)
    external fun transcribe(context: Long, threads: Int, audio: FloatArray, language: String?): String
}
