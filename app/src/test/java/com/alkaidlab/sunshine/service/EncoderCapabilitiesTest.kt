package com.alkaidlab.sunshine.service

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric 基建的示例单测。
 *
 * EncoderCapabilities 强绑定 android.media.MediaCodecList：纯 JVM 单测里只能拿到
 * android.jar 的 stub（调用即抛 "not mocked"），Robolectric 下才能真正走
 * MediaCodecList 枚举逻辑。只做结构断言，不 assert 编解码器具体数量/型号，
 * 因为 shadow 环境与真机的编解码器列表本就不同。
 */
@RunWith(RobolectricTestRunner::class)
class EncoderCapabilitiesTest {
    @Test fun codecEnumerationDoesNotThrowAndEntriesAreWellFormed() {
        val encoders = EncoderCapabilities.encoders
        for (e in encoders) {
            assertTrue(e.name.isNotBlank())
            assertTrue(e.mime == "video/avc" || e.mime == "video/hevc")
            assertTrue(e.widthAlignment > 0)
            assertTrue(e.heightAlignment > 0)
            assertTrue(e.maxWidth > 0)
            assertTrue(e.maxHeight > 0)
        }
    }

    @Test fun encoderListIsCachedByLazy() {
        assertTrue(EncoderCapabilities.encoders === EncoderCapabilities.encoders)
    }
}
