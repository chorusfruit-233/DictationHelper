/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.matching

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.dictationhelper.model.WordItem

class DictationState(val words: List<WordItem>) {
    var confirmedItems by mutableStateOf<List<WordItem>>(emptyList())
    var currentCandidates by mutableStateOf<List<MatchResult>>(emptyList())
    var rejectedIds by mutableStateOf<Set<String>>(emptySet())

    fun confirmedCount(): Int = confirmedItems.size
    fun totalCount(): Int = words.size

    fun setCandidates(results: List<MatchResult>) {
        currentCandidates = results
    }

    fun confirm(index: Int) {
        val result = currentCandidates.getOrNull(index) ?: return
        confirmedItems = confirmedItems + result.word
        rejectedIds = rejectedIds + result.word.id
        currentCandidates = currentCandidates.toMutableList().also { it.removeAt(index) }
    }

    fun rejectCandidate(index: Int) {
        val result = currentCandidates.getOrNull(index) ?: return
        rejectedIds = rejectedIds + result.word.id
        currentCandidates = currentCandidates.toMutableList().also { it.removeAt(index) }
    }

    fun rejectTopCandidate(): MatchResult? {
        val top = currentCandidates.firstOrNull() ?: return null
        rejectedIds = rejectedIds + top.word.id
        currentCandidates = currentCandidates.drop(1)
        return top
    }

    fun clearRound() {
        currentCandidates = emptyList()
    }

    fun reset() {
        confirmedItems = emptyList()
        currentCandidates = emptyList()
        rejectedIds = emptySet()
    }

    fun removeConfirmed(index: Int) {
        confirmedItems = confirmedItems.toMutableList().also { it.removeAt(index) }
    }
}
