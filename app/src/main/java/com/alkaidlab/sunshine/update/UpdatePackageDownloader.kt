package com.alkaidlab.sunshine.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray

internal data class DownloadSource(val url: String, val label: String)
internal data class DownloadSpec(val sources: List<DownloadSource>, val size: Long, val sha256: String?)
internal data class DownloadProgress(val bytes: Long, val total: Long, val bytesPerSecond: Long, val source: String)

/** Immutable release URLs, exact byte ranges and final digest; no arbitrary mirror service. */
internal class UpdatePackageDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).followSslRedirects(false).build(),
    private val parallelism: Int = 4,
    private val slowWindowMs: Long = 4000,
    private val slowBytesPerSecond: Long = 32768,
) {
    private val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    private class NoRanges : IOException("下载源不支持分段，改用完整文件下载")
    private class SlowSource : IOException("当前下载源较慢，尝试备用源")
    fun cancel() { cancelled.set(true); calls.forEach { it.cancel() } }
    private fun checkActive() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("下载已暂停") }

    fun download(spec: DownloadSpec, destination: File, progress: (DownloadProgress) -> Unit) {
        require(spec.sources.isNotEmpty() && spec.size in -1..MAX_BYTES && spec.size != 0L)
        require(spec.sha256 == null || spec.sha256.matches(Regex("[a-f0-9]{64}")))
        check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true)
        val directory = File(destination.parentFile, destination.name + ".parts")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建断点目录" }
        val count = if (spec.size >= 1048576) parallelism.coerceIn(1, 4) else 1
        val fingerprint = listOf(spec.size, spec.sha256, count, spec.sources.map { it.url }.sorted()).joinToString("\n")
        val metadata = File(directory, "metadata")
        if (!metadata.isFile || metadata.readText() != fingerprint) {
            clearParts(directory)
            metadata.writeText(fingerprint)
        }
        try {
            if (spec.size > 0) {
                try { ranged(spec, destination, directory, count, progress); return }
                catch (_: NoRanges) { checkActive(); clearParts(directory); metadata.writeText(fingerprint) }
            }
            whole(spec, destination, directory, progress)
        } catch (error: Exception) {
            File(directory, "assembled").delete()
            throw error
        }
    }

    private fun ranged(spec: DownloadSpec, destination: File, directory: File, count: Int,
                       progress: (DownloadProgress) -> Unit) {
        val sizes = LongArray(count) { index -> (spec.size * (index + 1) / count) - (spec.size * index / count) }
        val files = Array(count) { File(directory, "part$it") }
        val completed = AtomicLongArray(count)
        for (i in files.indices) {
            if (files[i].length() > sizes[i]) check(files[i].delete())
            completed.set(i, files[i].length())
        }
        val initial = files.sumOf { it.length() }
        val used = ConcurrentHashMap.newKeySet<String>()
        val started = System.nanoTime()
        var lastReport = 0L
        val reportLock = Any()
        fun report(force: Boolean = false) = synchronized(reportLock) {
            val now = System.nanoTime()
            if (force || now - lastReport >= 250_000_000) {
                lastReport = now
                val bytes = (0 until count).sumOf { completed.get(it) }
                val seconds = ((now - started) / 1e9).coerceAtLeast(.1)
                progress(DownloadProgress(bytes, spec.size, ((bytes - initial).coerceAtLeast(0) / seconds).toLong(),
                    used.sorted().joinToString(" / ").ifEmpty { "断点续传" }))
            }
        }
        val pool = Executors.newFixedThreadPool(count) { task -> Thread(task, "Sunshine-Update-Part").apply { isDaemon = true } }
        val completion = ExecutorCompletionService<Unit>(pool)
        try {
            for (i in 0 until count) completion.submit(java.util.concurrent.Callable {
                val start = spec.size * i / count
                var failure: IOException? = null
                var unsupported = 0
                for (attempt in 0 until spec.sources.size * 2) {
                    checkActive()
                    if (files[i].length() == sizes[i]) return@Callable Unit
                    val source = spec.sources[attempt % spec.sources.size]
                    used.add(source.label)
                    val offset = start + files[i].length()
                    val end = start + sizes[i] - 1
                    try {
                        request(source, "bytes=$offset-$end") { response ->
                            if (response.code == 200) throw NoRanges()
                            checkIo(response.code == 206) { "下载源返回 HTTP ${response.code}" }
                            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                            checkIo(match != null && match.groupValues[1].toLong() == offset &&
                                match.groupValues[2].toLong() == end && match.groupValues[3].toLong() == spec.size) { "下载分段范围不符" }
                            copy(response, files[i], true, end - offset + 1,
                                allowSlowSwitch = attempt == 0 && spec.sources.size > 1) { _ ->
                                completed.set(i, files[i].length()); report()
                            }
                        }
                        return@Callable Unit
                    } catch (error: IOException) {
                        checkActive()
                        failure = error
                        if (error is NoRanges) unsupported++
                    }
                }
                if (unsupported == spec.sources.size * 2) throw NoRanges()
                throw failure ?: IOException("下载未完成，请继续下载")
            })
            repeat(count) {
                try { completion.take().get() }
                catch (error: java.util.concurrent.ExecutionException) { throw error.cause ?: error }
            }
            checkActive()
            publish(spec, destination, directory, files.toList())
            report(true)
        } finally {
            calls.forEach { it.cancel() }
            pool.shutdownNow()
            pool.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    private fun whole(spec: DownloadSpec, destination: File, directory: File, progress: (DownloadProgress) -> Unit) {
        val file = File(directory, "whole")
        var failure: IOException? = null
        for (source in spec.sources) {
            checkActive()
            try {
                val started = System.nanoTime()
                request(source, null) { response ->
                    checkIo(response.code == 200) { "下载源返回 HTTP ${response.code}" }
                    val length = response.body?.contentLength() ?: -1
                    checkIo(length <= MAX_BYTES && (spec.size < 0 || length < 0 || length == spec.size)) { "安装包长度不符" }
                    val total = spec.size.takeIf { it > 0 } ?: length
                    var bytes = 0L
                    var lastReport = 0L
                    copy(response, file, false, total, spec.sources.size > 1 && source == spec.sources.first()) { added ->
                        bytes += added
                        val now = System.nanoTime()
                        if (now - lastReport >= 250_000_000) {
                            lastReport = now
                            progress(DownloadProgress(bytes, total, (bytes / ((now - started) / 1e9).coerceAtLeast(.1)).toLong(), source.label))
                        }
                    }
                }
                publish(spec, destination, directory, listOf(file))
                progress(DownloadProgress(destination.length(), destination.length(), 0, source.label))
                return
            } catch (error: IOException) { checkActive(); failure = error }
        }
        throw failure ?: IOException("下载源暂不可用，请重试")
    }

    private fun request(source: DownloadSource, range: String?, block: (okhttp3.Response) -> Unit) {
        checkActive()
        val request = Request.Builder().url(source.url).header("User-Agent", "Sunshine-Android-Updater")
            .header("Accept-Encoding", "identity").apply { if (range != null) header("Range", range) }.build()
        val call = client.newCall(request)
        calls.add(call)
        try { checkActive(); call.execute().use(block) } finally { calls.remove(call) }
    }

    private fun copy(response: okhttp3.Response, file: File, append: Boolean, expected: Long,
                     allowSlowSwitch: Boolean, progress: (Long) -> Unit) {
        val body = response.body ?: throw IOException("安装包内容为空")
        var copied = 0L
        var windowBytes = 0L
        var windowStarted = System.nanoTime()
        body.byteStream().use { input ->
            java.io.FileOutputStream(file, append).use { output ->
                val buffer = ByteArray(32768)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    checkActive()
                    copied += count
                    checkIo(copied <= MAX_BYTES && (expected < 0 || copied <= expected)) { "下载内容超出预期范围" }
                    output.write(buffer, 0, count)
                    progress(count.toLong())
                    windowBytes += count
                    val elapsed = (System.nanoTime() - windowStarted) / 1_000_000
                    if (allowSlowSwitch && elapsed >= slowWindowMs) {
                        if (windowBytes * 1000 / elapsed.coerceAtLeast(1) < slowBytesPerSecond) throw SlowSource()
                        windowBytes = 0; windowStarted = System.nanoTime()
                    }
                }
                checkIo(expected < 0 || copied == expected) { "下载中断，已保留断点" }
                output.fd.sync()
            }
        }
    }

    private fun publish(spec: DownloadSpec, destination: File, directory: File, parts: List<File>) {
        val assembled = File(directory, "assembled")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        java.io.FileOutputStream(assembled).use { output ->
            for (part in parts) part.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkActive()
                    val count = input.read(buffer); if (count < 0) break
                    size += count
                    checkIo(size <= MAX_BYTES) { "安装包过大" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            }
            output.fd.sync()
        }
        checkIo(size > 0 && (spec.size < 0 || size == spec.size)) { "安装包大小不符" }
        if (spec.sha256 != null && digest.digest().joinToString("") { "%02x".format(it) } != spec.sha256) {
            clearParts(directory)
            throw IOException("安装包 SHA-256 不符，请重新下载")
        }
        checkIo(!destination.exists() || destination.delete()) { "无法替换安装包" }
        checkIo(assembled.renameTo(destination)) { "无法保存安装包" }
        clearParts(directory)
    }

    private fun clearParts(directory: File) {
        for (name in listOf("metadata", "whole") + (0..3).map { "part$it" }) File(directory, name).delete()
    }
    private fun checkIo(value: Boolean, message: () -> String) { if (!value) throw IOException(message()) }
    companion object { private const val MAX_BYTES = 268435456L }
}
