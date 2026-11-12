package com.alkaidlab.sunshine.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SunshineConfParser] 的纯逻辑单测：解析语义必须与原 SunshineRuntimeManager.reloadConfig
 * 内联实现逐字一致（空行/注释/无等号行忽略、键值 trim、limit=2 分割、同名键后覆盖）。
 */
class SunshineConfParserTest {

    @Test fun ignoresBlankCommentAndMalformedLines() {
        val props = SunshineConfParser.parse(listOf(
            "",
            "   ",
            "# comment",
            "   # indented comment",
            "no-equals-line",
            "a=1",
        ))
        assertEquals(mapOf("a" to "1"), props)
    }

    @Test fun trimsKeysAndValues() {
        val props = SunshineConfParser.parse(listOf("  key  =  value  "))
        assertEquals("value", props["key"])
    }

    @Test fun lastDuplicateKeyWins() {
        val props = SunshineConfParser.parse(listOf("k=1", "k=2"))
        assertEquals("2", props["k"])
    }

    @Test fun valueKeepsEverythingAfterFirstEquals() {
        // split("=", limit = 2)：值里的等号保留
        val props = SunshineConfParser.parse(listOf("a=b=c"))
        assertEquals("b=c", props["a"])
    }

    @Test fun emptyValueIsKept() {
        val props = SunshineConfParser.parse(listOf("key="))
        assertTrue(props.containsKey("key"))
        assertEquals("", props["key"])
    }
}
