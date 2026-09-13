/*
 * Copyright (C) 2026 chorusfruit-233
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.dictationhelper.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportUnitParsingTest {
    @Test
    fun recognisesEnglishUnitHeadings() {
        assertEquals("Unit 1", detectUnitLabel("Unit 1"))
        assertEquals("Unit 3", detectUnitLabel("UNIT 3 My day"))
        assertEquals("Unit 1-2", detectUnitLabel("Units 1 - 2 复习"))
        assertEquals("Module 2A", detectUnitLabel("Module 2A"))
        assertEquals("Unit one", detectUnitLabel("unit one"))
    }

    @Test
    fun recognisesChineseUnitHeadings() {
        assertEquals("第3单元", detectUnitLabel("第3单元"))
        assertEquals("第三单元", detectUnitLabel("第三单元 单词表"))
        assertEquals("第2课", detectUnitLabel("第 2 课"))
    }

    @Test
    fun leavesVocabularyLinesAlone() {
        assertNull(detectUnitLabel("unit 单元"))
        assertNull(detectUnitLabel("module 模块"))
        assertNull(detectUnitLabel("part 部分"))
        assertNull(detectUnitLabel("university 大学"))
        assertNull(detectUnitLabel("unit test 单元测试"))
        assertNull(detectUnitLabel("achievement 成就"))
        assertNull(detectUnitLabel(""))
    }

    @Test
    fun attachesUnitsToTheFollowingWords() {
        val items = parseTextLines(
            """
            Unit 1
            apple 苹果
            banana 香蕉
            Unit 2
            chair 椅子
            """.trimIndent()
        )

        assertEquals(3, items.size)
        assertEquals(listOf("apple", "banana"), items.filter { it.unit == "Unit 1" }.map { it.text })
        assertEquals(listOf("chair"), items.filter { it.unit == "Unit 2" }.map { it.text })
    }

    @Test
    fun wordsBeforeAnyHeadingKeepNoUnit() {
        val items = parseTextLines(
            """
            apple 苹果
            Unit 2
            chair 椅子
            """.trimIndent()
        )

        assertEquals("", items.first { it.text == "apple" }.unit)
        assertEquals("Unit 2", items.first { it.text == "chair" }.unit)
    }

    @Test
    fun headingLinesDoNotBecomeVocabulary() {
        val items = parseTextLines(
            """
            Unit 1
            第2单元
            apple 苹果
            """.trimIndent()
        )

        assertEquals(listOf("apple"), items.map { it.text })
        assertEquals("第2单元", items.single().unit)
    }
}
