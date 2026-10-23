package com.alkaidlab.sunshine.api

import android.app.Application
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SunshineApiServerStateTest {
    private lateinit var server: SunshineApiServer
    private lateinit var state: File
    private val fixture = """{"root":{"uniqueid":"fixture-host","opaque":"keep","named_devices":[
        {"uuid":"a","name":"A","cert":"test-a"},{"uuid":"b","name":"B","cert":"test-b"}]}}"""

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        state = File(context.filesDir, "sunshine_state.json")
        File(state.path + ".bak").delete()
        File(state.path + ".new").delete()
        server = SunshineApiServer(context, 0)
        state.writeText(fixture)
    }

    private fun session(uri: String, body: String? = null, beforeBody: () -> Unit = {}): NanoHTTPD.IHTTPSession =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NanoHTTPD.IHTTPSession::class.java)) { proxy, method, args ->
            when (method.name) {
                "getUri" -> uri
                "getMethod" -> if (body == null) NanoHTTPD.Method.GET else NanoHTTPD.Method.POST
                "getHeaders", "getParms", "getParameters" -> mutableMapOf<String, String>()
                "getInputStream" -> ByteArrayInputStream(body.orEmpty().toByteArray())
                "parseBody" -> {
                    beforeBody()
                    @Suppress("UNCHECKED_CAST")
                    (args!![0] as MutableMap<String, String>)["postData"] = body.orEmpty()
                    null
                }
                "getRemoteIpAddress", "getRemoteHostName" -> "127.0.0.1"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "FixtureSession"
                else -> null
            }
        } as NanoHTTPD.IHTTPSession

    private fun list(): JsonObject = server.serve(session("/api/clients/list")).use { response ->
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        JsonParser.parseString(response.data.bufferedReader().readText()).asJsonObject
    }
    private fun mutate(path: String, body: String = "{}") = server.serve(session(path, body)).use {
        assertEquals(NanoHTTPD.Response.Status.OK, it.status)
    }

    @Test fun concurrentReadRenameAndUnpairPreserveBothUpdates() {
        list() // Initialize the benign runtime before concurrent request timing.
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(20) { round ->
                state.writeText(fixture)
                val start = CountDownLatch(1)
                val rename = pool.submit { start.await(); mutate("/api/clients/rename", """{"uuid":"a","name":"new-$round"}""") }
                val unpair = pool.submit { start.await(); mutate("/api/clients/unpair", """{"uuid":"b"}""") }
                val reader = pool.submit { start.await(); repeat(10) { list() } }
                start.countDown()
                listOf(rename, unpair, reader).forEach { it.get(10, TimeUnit.SECONDS) }
                val root = JsonParser.parseString(state.readText()).asJsonObject.getAsJsonObject("root")
                assertEquals("fixture-host", root.get("uniqueid").asString)
                assertEquals("keep", root.get("opaque").asString)
                val clients = root.getAsJsonArray("named_devices")
                assertEquals(1, clients.size())
                assertEquals("a", clients[0].asJsonObject.get("uuid").asString)
                assertEquals("new-$round", clients[0].asJsonObject.get("name").asString)
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun incompletePostBodyDoesNotBlockClientReads() {
        list()
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val post = pool.submit {
                server.serve(session("/api/clients/rename", """{"uuid":"a","name":"new"}""") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }).close()
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val read = pool.submit<JsonObject> { list() }
            try { assertEquals(2, read.get(3, TimeUnit.SECONDS).getAsJsonArray("named_certs").size()) }
            finally { release.countDown() }
            post.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun malformedMutationReturnsValidJsonError() {
        server.serve(session("/api/clients/rename", "[malformed")).use { response ->
            assertEquals(NanoHTTPD.Response.Status.INTERNAL_ERROR, response.status)
            val json = JsonParser.parseString(response.data.bufferedReader().readText()).asJsonObject
            assertEquals("false", json.get("status").asString)
            assertTrue(json.has("error"))
        }
        assertEquals(2, list().getAsJsonArray("named_certs").size())
    }
}
