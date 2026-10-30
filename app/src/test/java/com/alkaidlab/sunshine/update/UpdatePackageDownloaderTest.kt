package com.alkaidlab.sunshine.update

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class UpdatePackageDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(1048597) { (it * 31 % 251).toByte() }
    private val digest get() = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private lateinit var server: ServerSocket
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "DownloadTestHTTP").apply { isDaemon = true } }
    private val requests = ConcurrentLinkedQueue<String>()
    private var mode: (HttpExchange, Int, Int) -> Unit = { exchange, start, end -> send(exchange, start, end) }
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before fun startServer() {
        server = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        workers.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                workers.execute {
                    val exchange = HttpExchange(socket)
                    try {
                        val range = exchange.requestHeaders.getFirst("Range")
                        requests.add(exchange.requestURI.path + " " + range)
                        val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range.orEmpty())
                        val start = match?.groupValues?.get(1)?.toInt() ?: 0
                        val end = match?.groupValues?.get(2)?.toInt() ?: payload.lastIndex
                        mode(exchange, start, end)
                    } catch (_: IOException) {} catch (_: InterruptedException) {} finally { exchange.close() }
                }
            }
        }
    }
    @After fun stopServer() { server.close(); workers.shutdownNow() }

    private class Headers {
        val values = linkedMapOf<String, String>()
        fun getFirst(name: String) = values.entries.firstOrNull { it.key.equals(name, true) }?.value
        fun add(name: String, value: String) { values[name] = value }
    }
    private class HttpExchange(private val socket: Socket) {
        val requestHeaders = Headers()
        val responseHeaders = Headers()
        val responseBody get() = socket.getOutputStream()
        val requestURI: URI
        init {
            socket.soTimeout = 2000
            val input = socket.getInputStream().bufferedReader()
            requestURI = URI(input.readLine().split(' ')[1])
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                requestHeaders.add(line.substringBefore(':'), line.substringAfter(':').trim())
            }
        }
        fun sendResponseHeaders(status: Int, length: Long) {
            val text = buildString {
                append("HTTP/1.1 $status OK\r\nConnection: close\r\nContent-Length: ${length.coerceAtLeast(0)}\r\n")
                responseHeaders.values.forEach { (key, value) -> append("$key: $value\r\n") }
                append("\r\n")
            }
            responseBody.write(text.toByteArray(Charsets.US_ASCII)); responseBody.flush()
        }
        fun close() { socket.close() }
    }
    private fun send(exchange: HttpExchange, start: Int, end: Int) {
        val ranged = exchange.requestHeaders.getFirst("Range") != null
        if (ranged) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
        exchange.sendResponseHeaders(if (ranged) 206 else 200, (end - start + 1).toLong())
        exchange.responseBody.write(payload, start, end - start + 1)
    }
    private fun spec(vararg paths: String) = DownloadSpec(paths.map { DownloadSource(base + it, it) }, payload.size.toLong(), digest)
    private fun output() = File(temporary.root, "release.apk")
    private fun engine(parts: Int = 4) = UpdatePackageDownloader(client, parts)

    @Test fun parallelRangesProduceExactFileAndBoundedProgress() {
        val progress = CopyOnWriteArrayList<DownloadProgress>()
        engine().download(spec("/file"), output(), progress::add)
        assertArrayEquals(payload, output().readBytes())
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.contains("bytes=") })
        assertTrue(progress.all { it.bytes in 0..payload.size.toLong() })
        assertEquals(payload.size.toLong(), progress.last().bytes)
    }

    @Test fun unavailablePrimaryUsesOnlyConfiguredBackup() {
        mode = { exchange, start, end ->
            if (exchange.requestURI.path == "/bad") exchange.sendResponseHeaders(503, -1) else send(exchange, start, end)
        }
        engine().download(spec("/bad", "/backup"), output()) {}
        assertArrayEquals(payload, output().readBytes())
        assertTrue(requests.any { it.startsWith("/bad ") })
        assertTrue(requests.any { it.startsWith("/backup ") })
    }

    @Test fun wrongContentRangeIsRejectedBeforeWritingAndBackupIsVerified() {
        mode = { exchange, start, end ->
            if (exchange.requestURI.path == "/wrong") {
                exchange.responseHeaders.add("Content-Range", "bytes ${start + 1}-$end/${payload.size}")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
            } else send(exchange, start, end)
        }
        engine(1).download(spec("/wrong", "/backup"), output()) {}
        assertArrayEquals(payload, output().readBytes())
        assertTrue(requests.any { it == "/backup bytes=0-${payload.lastIndex}" })
    }

    @Test fun ignoredRangesFallBackToWholeFileWithoutDuplicatedBytes() {
        mode = { exchange, _, _ ->
            exchange.sendResponseHeaders(200, payload.size.toLong()); exchange.responseBody.write(payload)
        }
        engine().download(spec("/file"), output()) {}
        assertArrayEquals(payload, output().readBytes())
        assertTrue(requests.any { it.endsWith(" null") })
    }

    @Test fun failedTransferResumesVerifiedRangePositionsOnNextAttempt() {
        val interrupt = AtomicBoolean(true)
        mode = { exchange, start, end ->
            if (interrupt.get()) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                exchange.responseBody.write(payload, start, minOf(32768, end - start + 1))
            } else send(exchange, start, end)
        }
        try { engine(1).download(spec("/file"), output()) {}; fail("Truncated file accepted") } catch (_: IOException) {}
        val partial = File(temporary.root, "release.apk.parts/part0").length()
        assertTrue(partial in 1 until payload.size.toLong())
        requests.clear(); interrupt.set(false)
        engine(1).download(spec("/file"), output()) {}
        assertEquals("/file bytes=$partial-${payload.lastIndex}", requests.first())
        assertArrayEquals(payload, output().readBytes())
    }

    @Test fun wrongDigestNeverPublishesAnApkAndInvalidatesBadParts() {
        try { engine().download(spec("/file").copy(sha256 = "0".repeat(64)), output()) {}; fail("Bad digest accepted") }
        catch (error: IOException) { assertTrue(error.message.orEmpty().contains("SHA-256")) }
        assertFalse(output().exists())
        assertFalse(File(temporary.root, "release.apk.parts/part0").exists())
        engine().download(spec("/file"), output()) {}
        assertArrayEquals(payload, output().readBytes())
    }

    @Test fun slowButDrippingSourceSwitchesWithoutLosingItsPrefix() {
        mode = { exchange, start, end ->
            if (exchange.requestURI.path == "/slow") {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                var position = start
                while (position <= end) {
                    val count = minOf(1024, end - position + 1)
                    exchange.responseBody.write(payload, position, count); exchange.responseBody.flush()
                    position += count; Thread.sleep(40)
                }
            } else send(exchange, start, end)
        }
        UpdatePackageDownloader(client, 1, slowWindowMs = 120, slowBytesPerSecond = 65536)
            .download(spec("/slow", "/backup"), output()) {}
        assertArrayEquals(payload, output().readBytes())
        assertTrue(requests.any { it.startsWith("/backup bytes=") && !it.startsWith("/backup bytes=0-") })
    }

    @Test fun cancellationInterruptsNetworkAndNextDownloadResumesTheSavedPrefix() {
        val slow = AtomicBoolean(true)
        mode = { exchange, start, end ->
            if (slow.get()) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                var position = start
                while (position <= end) {
                    val count = minOf(1024, end - position + 1)
                    exchange.responseBody.write(payload, position, count); exchange.responseBody.flush()
                    position += count; Thread.sleep(40)
                }
            } else send(exchange, start, end)
        }
        val firstBytes = CountDownLatch(1)
        val downloader = engine(1)
        val background = Executors.newSingleThreadExecutor()
        try {
            val task = background.submit { downloader.download(spec("/file"), output()) { if (it.bytes > 0) firstBytes.countDown() } }
            assertTrue(firstBytes.await(2, TimeUnit.SECONDS))
            downloader.cancel()
            try { task.get(2, TimeUnit.SECONDS); fail("Cancelled download completed") }
            catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
            val prefix = File(temporary.root, "release.apk.parts/part0").length()
            assertTrue(prefix > 0)
            requests.clear(); slow.set(false)
            engine(1).download(spec("/file"), output()) {}
            assertEquals("/file bytes=$prefix-${payload.lastIndex}", requests.first())
            assertArrayEquals(payload, output().readBytes())
        } finally { background.shutdownNow() }
    }
}
