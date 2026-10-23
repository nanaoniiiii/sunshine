package com.alkaidlab.sunshine.service

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric 基建的示例单测。
 *
 * EncoderCapabilities 强绑定 android.media.MediaCodecList：纯 JVM 单测里只能拿到
 * android.jar 的 stub（调用即抛 "not mocked"），Robolectric 下才能真正走
 * MediaCodecList 枚举逻辑。只做结构断言，不 assert 编解码器具体数量/型号，
 * 因为 shadow 环境与真机的编解码器列表本就不同。
 */
@RunWith(RobolectricTestRunner::class)
// 用无害的基类 Application 代替生产 manifest 声明的 SunshineApp：这些单测不需要
// 生产启动流程（SunshineApp.onCreate 会初始化 AppUpdater并发起真实更新网络请求，
// 还会填充进程级运行时单体，导致测试产生外部流量/状态泄漏或离线环境下不稳定）。
@Config(application = Application::class)
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
