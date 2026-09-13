/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.speech

import java.io.File

/**
 * Locates and prunes the files of a sherpa-onnx streaming model directory.
 * Free of Android types so the selection rules can be unit tested.
 */
internal object SherpaModelFiles {
    private const val QUANTIZED_MARKER = "int8"
    val WEIGHT_KINDS = listOf("encoder", "decoder", "joiner")

    /**
     * Picks the file for [kind] deterministically. Archives ship an int8 and an fp32
     * variant of every network, and directory order is filesystem dependent, so without
     * this ordering the engine could load a different precision on each install.
     *
     * fp32 comes first, which is the variant the app ships and reports as the default;
     * int8 is used only for models that offer nothing else. To flip the preference back,
     * invert the first sort key.
     */
    fun find(root: File, kind: String): File? {
        if (!root.exists()) return null
        return root.walkTopDown()
            .filter { it.isFile && matchesKind(it.name, kind) }
            .sortedWith(compareBy({ if (it.name.contains(QUANTIZED_MARKER)) 1 else 0 }, { it.name }))
            .firstOrNull()
    }

    fun matchesKind(name: String, kind: String): Boolean = when (kind) {
        "tokens" -> name == "tokens.txt"
        "encoder", "decoder", "joiner" -> name.startsWith(kind) && name.endsWith(".onnx")
        else -> false
    }

    /** Human readable precision of a weight file, for showing what is actually installed. */
    fun precisionOf(file: File): String = if (file.name.contains(QUANTIZED_MARKER)) "int8" else "fp32"

    /**
     * Deletes the weights [find] did not select, so installing the official archive does
     * not keep both precisions of every network on the device, and drops the sample audio.
     */
    fun pruneUnusedWeights(root: File) {
        val kept = WEIGHT_KINDS.mapNotNull { find(root, it) }.toSet()
        WEIGHT_KINDS.flatMap { kind ->
            root.walkTopDown().filter { it.isFile && matchesKind(it.name, kind) && it !in kept }.toList()
        }.forEach { it.delete() }
        root.walkTopDown().filter { it.isDirectory && it.name == "test_wavs" }.toList().forEach { it.deleteRecursively() }
    }
}
