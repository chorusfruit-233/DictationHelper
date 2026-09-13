/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

import java.io.File

/**
 * File level checks for whisper.cpp models, free of Android types so they can be unit tested.
 */
internal object WhisperModelFiles {
    /** GGML_FILE_MAGIC as written by ggml; on disk its little endian bytes read "lmgg". */
    private const val GGML_FILE_MAGIC = 0x67676d6c

    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /**
     * True when [file] starts with a magic the runtime understands: the ggml model magic in
     * its on-disk byte order, or the ASCII GGUF magic.
     */
    fun hasWhisperMagic(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                var offset = 0
                while (offset < magic.size) {
                    val count = input.read(magic, offset, magic.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset < magic.size) return false
                littleEndianInt(magic) == GGML_FILE_MAGIC || magic.contentEquals(GGUF_MAGIC)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun littleEndianInt(bytes: ByteArray): Int =
        (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
}
