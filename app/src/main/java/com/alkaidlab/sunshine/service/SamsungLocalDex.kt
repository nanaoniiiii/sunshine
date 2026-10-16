package com.alkaidlab.sunshine.service

import android.content.Context
import android.content.pm.ApplicationInfo
import dalvik.system.DexClassLoader
import com.alkaidlab.sunshine.service.headless.LocalPcPeer
import com.alkaidlab.sunshine.service.headless.PcCrypto
import java.nio.file.Files
import java.util.zip.ZipFile

/** Owns only the locally negotiated PC receiver; never stops an unrelated DeX session. */
internal class SamsungLocalDex(private val context: Context) : AutoCloseable {
    private var peer: LocalPcPeer? = null
    val stage: String get() = peer?.stage ?: "idle"
    val failure: Exception? get() = peer?.failure

    fun start() {
        check(peer == null)
        loadDeviceCrypto(context)
        val receiver = LocalPcPeer()
        peer = receiver
        try { receiver.start() } catch (error: Exception) { close(); throw error }
    }

    override fun close() { val receiver = peer; peer = null; receiver?.close() }

    companion object {
        fun supported(context: Context): Boolean {
            if (!android.os.Process.is64Bit()) return false
            val systemPackage = try {
                context.packageManager.getApplicationInfo("com.sec.android.app.dexonpc", 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
            } catch (_: Exception) { false }
            if (!systemPackage) return false
            // Some firmware keeps a package stub after removing the PC transport.
            return try {
                Class.forName("com.samsung.android.desktopmode.DesktopModeFeature").getField("SUPPORT_DEX_ON_PC").getBoolean(null)
            } catch (_: Exception) { true } // Older compatible firmware may lack this feature field.
        }
        private var loader: ClassLoader? = null
        // Keep one loader for the user-service lifetime: native libraries cannot be loaded twice by different loaders.
        @Synchronized private fun loadDeviceCrypto(context: Context) {
            loader?.let { PcCrypto.configure(it); return }
            val info = context.packageManager.getApplicationInfo("com.sec.android.app.dexonpc", 0)
            check(info.flags and ApplicationInfo.FLAG_SYSTEM != 0) { "DeX for PC 必须是本机三星系统应用" }
            check(android.os.Process.is64Bit()) { "本机 DeX 接收端暂需 64 位用户服务" }
            val directory = Files.createTempDirectory(java.io.File("/data/local/tmp").toPath(), "sunshine-dex-").toFile()
            try {
                ZipFile(info.sourceDir).use { apk ->
                    for (name in listOf("libc++_shared.so", "libCryptoppManager.so")) {
                        val entry = apk.getEntry("lib/arm64-v8a/$name") ?: error("三星 DeX 缺少 $name")
                        check(entry.size in 1..16_777_216) { "三星 DeX 库大小异常" }
                        apk.getInputStream(entry).use { input -> directory.resolve(name).outputStream().use { input.copyTo(it) } }
                    }
                }
                val value = DexClassLoader(info.sourceDir, directory.absolutePath, directory.absolutePath, SamsungLocalDex::class.java.classLoader)
                PcCrypto.configure(value)
                // Force loading while the extracted device-owned libraries are available.
                Class.forName("com.sec.android.sidesync.cryptopp.CryptoppNativeManager", true, value)
                loader = value
            } finally {
                // Loaded libraries stay mapped; do not leave copies in shared temporary storage.
                directory.listFiles()?.forEach { it.delete() }
                directory.delete()
            }
        }
    }
}
