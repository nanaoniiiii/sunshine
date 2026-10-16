package com.alkaidlab.sunshine.api

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SunshineApiClientTest {
    @Test fun loopbackControlRequestBypassesUnreachableSystemProxy() {
        val previous = ProxySelector.getDefault()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 3000
        val executor = Executors.newSingleThreadExecutor()
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    // OpenJDK's Socket implementation also asks about SOCKS;
                    // this fixture models an unreachable HTTP proxy, not SOCKS.
                    if (uri.scheme == "socket") return listOf(Proxy.NO_PROXY)
                    error("Loopback HTTP request was incorrectly sent to system proxy")
                }
                override fun connectFailed(uri: URI, address: SocketAddress, error: java.io.IOException) { throw AssertionError(error) }
            })
            val served = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    assertTrue(reader.readLine().startsWith("GET /api/config "))
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                }
                true
            }
            SunshineApiClient.okHttpClient.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/api/config").build()).execute().use {
                assertEquals(200, it.code); assertEquals("{}", it.body!!.string())
            }
            assertTrue(served.get(3, TimeUnit.SECONDS))
        } finally { ProxySelector.setDefault(previous); server.close(); executor.shutdownNow() }
    }
}
