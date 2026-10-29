package com.alkaidlab.sunshine.ui.desktop

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.drawable.ColorDrawable
import com.alkaidlab.sunshine.SunshineApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DesktopCacheLifecycleTest {
    @Test fun coveringDesktopRetainsIconsButRealMemoryPressureEvictsThem() {
        DesktopArtworkCache.clear()
        var loads = 0
        fun load() = DesktopArtworkCache.get("sample/.Main") { loads++; ColorDrawable(0xff224466.toInt()) }
        val first = load()
        SunshineApp().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertSame(first, load())
        assertEquals(1, loads)
        SunshineApp().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertNotSame(first, load())
        assertEquals(2, loads)
        DesktopArtworkCache.clear()
    }
    @Test fun changedPackageInvalidatesItsIconsWithoutClearingOthers() {
        DesktopArtworkCache.clear()
        val a = DesktopArtworkCache.get("a/.Main") { ColorDrawable(0xff111111.toInt()) }
        val b = DesktopArtworkCache.get("b/.Main") { ColorDrawable(0xff222222.toInt()) }
        DesktopArtworkCache.invalidate("a")
        assertNull(DesktopArtworkCache.peek("a/.Main"))
        assertSame(b, DesktopArtworkCache.peek("b/.Main"))
        assertNotNull(a)
        DesktopArtworkCache.clear()
    }
}
