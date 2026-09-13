/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WhisperModelFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun model(name: String, magic: ByteArray): File =
        folder.newFile(name).apply { writeBytes(magic + ByteArray(16)) }

    @Test
    fun acceptsTheMagicOfTheOfficialGgmlModels() {
        // ggml-tiny.bin and ggml-base-q5_1.bin start with 6c 6d 67 67.
        val model = model("ggml-base-q5_1.bin", byteArrayOf(0x6c, 0x6d, 0x67, 0x67))

        assertTrue(WhisperModelFiles.hasWhisperMagic(model))
    }

    @Test
    fun acceptsGgufAndRejectsTheAsciiGgmlSpelling() {
        assertTrue(WhisperModelFiles.hasWhisperMagic(model("model.gguf", "GGUF".toByteArray())))
        // "ggml" as ASCII is the big endian byte order, which no shipping model uses.
        assertFalse(WhisperModelFiles.hasWhisperMagic(model("model-bigendian.bin", "ggml".toByteArray())))
    }

    @Test
    fun rejectsOtherFormatsAndMissingFiles() {
        assertFalse(WhisperModelFiles.hasWhisperMagic(model("archive.zip", byteArrayOf(0x50, 0x4b, 0x03, 0x04))))
        assertFalse(WhisperModelFiles.hasWhisperMagic(File(folder.root, "absent.bin")))
    }

    @Test
    fun rejectsFilesTooShortToCarryAMagic() {
        val truncated = folder.newFile("truncated.bin").apply { writeBytes(byteArrayOf(0x6c, 0x6d)) }

        assertFalse(WhisperModelFiles.hasWhisperMagic(truncated))
    }
}
