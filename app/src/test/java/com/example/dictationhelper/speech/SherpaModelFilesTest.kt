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
    fun prefersInt8EvenWhenTheFp32VariantSortsFirst() {
        model("encoder-aaa.onnx")
        val int8 = model("encoder-zzz.int8.onnx")

        assertEquals(int8, SherpaModelFiles.find(folder.root, "encoder"))
    }

    @Test
    fun fallsBackToFp32WhenOnlyOneVariantExists() {
        val fp32 = model("joiner-epoch-99-avg-1.onnx")

        assertEquals(fp32, SherpaModelFiles.find(folder.root, "joiner"))
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
        val encoder = model("encoder-epoch-99-avg-1.int8.onnx")
        val decoder = model("decoder-epoch-99-avg-1.int8.onnx")
        val joiner = model("joiner-epoch-99-avg-1.int8.onnx")
        val tokens = model("tokens.txt")
        val fp32Encoder = model("encoder-epoch-99-avg-1.onnx")
        val fp32Decoder = model("decoder-epoch-99-avg-1.onnx")
        val fp32Joiner = model("joiner-epoch-99-avg-1.onnx")
        val samples = folder.newFolder("test_wavs")
        val sample = File(samples, "0.wav").apply { writeText("audio") }

        SherpaModelFiles.pruneUnusedWeights(folder.root)

        listOf(encoder, decoder, joiner, tokens).forEach { assertTrue("${it.name} was removed", it.exists()) }
        listOf(fp32Encoder, fp32Decoder, fp32Joiner, sample).forEach { assertFalse("${it.name} was kept", it.exists()) }
    }
}
