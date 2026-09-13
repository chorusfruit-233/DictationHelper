/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SherpaModelFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun model(name: String): File = folder.newFile(name)

    @Test
    fun prefersFp32EvenWhenTheInt8VariantSortsFirst() {
        model("encoder-aaa.int8.onnx")
        val fp32 = model("encoder-zzz.onnx")

        assertEquals(fp32, SherpaModelFiles.find(folder.root, "encoder"))
    }

    @Test
    fun fallsBackToInt8WhenOnlyQuantizedWeightsExist() {
        val int8 = model("joiner-epoch-99-avg-1.int8.onnx")

        assertEquals(int8, SherpaModelFiles.find(folder.root, "joiner"))
    }

    @Test
    fun findsTokensAndReportsMissingFiles() {
        val tokens = model("tokens.txt")

        assertEquals(tokens, SherpaModelFiles.find(folder.root, "tokens"))
        assertNull(SherpaModelFiles.find(folder.root, "decoder"))
        assertNull(SherpaModelFiles.find(File(folder.root, "absent"), "encoder"))
    }

    @Test
    fun ignoresOtherExtensionsAndUnrelatedNames() {
        model("encoder-epoch-99-avg-1.onnx.bak")
        model("tokens.txt.bak")

        assertNull(SherpaModelFiles.find(folder.root, "encoder"))
        assertNull(SherpaModelFiles.find(folder.root, "tokens"))
    }

    @Test
    fun pruneKeepsSelectedWeightsAndDropsTheRest() {
        val encoder = model("encoder-epoch-99-avg-1.onnx")
        val decoder = model("decoder-epoch-99-avg-1.onnx")
        val joiner = model("joiner-epoch-99-avg-1.onnx")
        val tokens = model("tokens.txt")
        val int8Encoder = model("encoder-epoch-99-avg-1.int8.onnx")
        val int8Decoder = model("decoder-epoch-99-avg-1.int8.onnx")
        val int8Joiner = model("joiner-epoch-99-avg-1.int8.onnx")
        val samples = folder.newFolder("test_wavs")
        val sample = File(samples, "0.wav").apply { writeText("audio") }

        SherpaModelFiles.pruneUnusedWeights(folder.root)

        listOf(encoder, decoder, joiner, tokens).forEach { assertTrue("${it.name} was removed", it.exists()) }
        listOf(int8Encoder, int8Decoder, int8Joiner, sample).forEach { assertFalse("${it.name} was kept", it.exists()) }
    }
}
