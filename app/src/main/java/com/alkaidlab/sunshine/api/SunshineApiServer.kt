package com.alkaidlab.sunshine.api

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.alkaidlab.sunshine.BuildConfig
import com.alkaidlab.sunshine.data.*
import com.alkaidlab.sunshine.service.StreamingSettings
import com.alkaidlab.sunshine.service.SunshineLogManager
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.UUID

class SunshineApiServer(
    private val context: Context,
    port: Int = 47990
) : NanoHTTPD(port) {

    private val gson = com.google.gson.GsonBuilder().serializeNulls().create()
    private val configLock = Any()
    private val filesDir: File = context.filesDir
    private val configFile: File get() = File(filesDir, "sunshine.conf")
    private val appsFile: File get() = File(filesDir, "apps.json")
    private val stateFile: File get() = File(filesDir, "sunshine_state.json")

    init {
        ensureInitialFiles()
    }

    private fun ensureInitialFiles() {
        if (!configFile.exists()) {
            val defaultConfig = """
                # Sunshine Configuration for Android
                port = 47989
                web_port = 47990
                encoder = amediacodec
                hevc_mode = enabled
                audio_sink = media_projection
                min_bitrate = 5000
                max_bitrate = 50000
                auto_rotate = true
                auto_scale = true
                address_family = both
                max_stream_fps = 240
                repeat_frames = true
                audio_buffer_ms = 40
                wifi_low_latency = true
            """.trimIndent()
            configFile.writeText(defaultConfig)
        } else {
            val text = configFile.readText()
            if (!text.contains("address_family")) {
                configFile.appendText("\naddress_family = both\n")
            }
        }

        if (!appsFile.exists()) {
            val defaultApps = """
                {
                  "env": {},
                  "apps": [
                    {
                      "name": "Desktop",
                      "image-path": "desktop",
                      "output": "",
                      "auto-detach": "true",
                      "wait-all": "true"
                    },
                    {
                      "name": "桌面 (Phone Display)",
                      "image-path": "desktop",
                      "output": "",
                      "auto-detach": "true",
                      "wait-all": "true"
                    }
                  ]
                }
            """.trimIndent()
            appsFile.writeText(defaultApps)
        }

        if (!stateFile.exists()) {
            val defaultState = """
                {
                  "root": {
                    "uniqueid": "${UUID.randomUUID()}",
                    "named_devices": []
                  }
                }
            """.trimIndent()
            stateFile.writeText(defaultState)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Only log state-mutating requests (POST/DELETE) to avoid flooding the real-time log stream
        if (method != Method.GET) {
            SunshineLogManager.log("API", "${method.name} $uri")
        }

        return try {
            when {
                uri == "/api/config" && method == Method.GET -> handleGetConfig()
                uri == "/api/config" && method == Method.POST -> handleSaveConfig(session)
                uri == "/api/apps" && method == Method.GET -> handleGetApps()
                uri == "/api/apps" && method == Method.POST -> handleSaveApp(session)
                uri.startsWith("/api/apps/") && method == Method.DELETE -> handleDeleteApp(uri.removePrefix("/api/apps/"))
                uri == "/api/clients/list" && method == Method.GET -> handleGetClients()
                uri == "/api/clients/rename" && method == Method.POST -> handleRenameClient(session)
                uri == "/api/clients/unpair" && method == Method.POST -> handleUnpairClient(session)
                uri == "/api/clients/unpair-all" && method == Method.POST -> handleUnpairAll()
                uri == "/api/pin" && method == Method.POST -> handleSubmitPin(session)
                uri == "/api/runtime/sessions" && method == Method.GET -> handleGetRuntimeSessions()
                uri == "/api/encoders" && method == Method.GET -> jsonResponse(mapOf("encoders" to com.alkaidlab.sunshine.service.EncoderCapabilities.encoders))
                uri == "/api/diagnostics" && method == Method.GET -> jsonResponse(SunshineRuntimeManager.getInstance().diagnostics())
                uri == "/api/perf/current" && method == Method.GET -> handleGetPerfCurrent()
                uri == "/api/logs" && method == Method.GET -> handleGetLogs()
                uri == "/api/restart" && method == Method.POST -> handleRestart()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Not Found\"}")
            }
        } catch (e: Exception) {
            SunshineLogManager.log("API_ERR", "Error handling $uri: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"status\":\"false\",\"error\":\"${e.message}\"}")
        }
    }

    private fun handleGetConfig(): Response {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val devices = listOf(
            DisplayDeviceNode("0", "Android Screen: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi")
        )
        val adapters = listOf(
            AdapterNode("${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})")
        )

        val confMap = parseProperties(configFile.readText())
        val hevcMode = confMap["hevc_mode"] ?: "enabled"
        val codecTag = if (hevcMode != "disabled") "HEVC/H.265" else "H.264"
        val response = ConfigResponse(
            status = "true",
            platform = "android",
            version = BuildConfig.VERSION_NAME,
            displayDevices = devices,
            adapters = adapters,
            port = confMap["port"] ?: "47989",
            webPort = confMap["web_port"] ?: "47990",
            encoder = "AMediaCodec (${Build.HARDWARE} · $codecTag)",
            channels = confMap["channels"] ?: "2",
            minBitrate = confMap["min_bitrate"] ?: "5000",
            maxBitrate = confMap["max_bitrate"] ?: "50000",
            hevcMode = hevcMode,
            audioSink = confMap["audio_sink"] ?: "media_projection",
            autoRotate = confMap["auto_rotate"] ?: "true",
            autoScale = confMap["auto_scale"] ?: "true",
            addressFamily = confMap["address_family"] ?: "both",
            maxStreamFps = confMap["max_stream_fps"] ?: "240",
            repeatFrames = confMap["repeat_frames"] ?: "true",
            audioBufferMs = confMap["audio_buffer_ms"] ?: "40",
            wifiLowLatency = confMap["wifi_low_latency"] ?: "true",
            ipv6Address = com.alkaidlab.sunshine.service.SunshineService.currentIpv6
        )
        return jsonResponse(response)
    }

    private fun handleSaveConfig(session: IHTTPSession): Response = synchronized(configLock) {
        val body = parseBody(session)
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        val incoming: Map<String, String> = gson.fromJson(body, mapType)
            ?: return@synchronized newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(SimpleStatusResponse("false", error = "Missing configuration")))
        StreamingSettings.validate(incoming)?.let { error ->
            return@synchronized newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(SimpleStatusResponse("false", error = error)))
        }
        val current = parseProperties(configFile.readText()).toMutableMap()
        current.putAll(incoming)
        // Atomic replace prevents simultaneous readers from observing a truncated configuration.
        val atomic = android.util.AtomicFile(configFile)
        val output = atomic.startWrite()
        try {
            output.write(current.entries.joinToString("\n", postfix = "\n") { "${it.key} = ${it.value}" }.toByteArray())
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
        SunshineRuntimeManager.getInstance().reloadConfig(context)
        SunshineLogManager.log("CONFIG", "Host streaming settings applied; native startup settings take effect after idle restart")
        jsonResponse(SimpleStatusResponse("true", "帧率、补帧、音频缓冲与 Wi-Fi 设置已应用；编码模式需空闲重启后生效"))
    }

    private fun handleGetApps(): Response {
        val text = appsFile.readText()
        val apps = gson.fromJson(text, AppsResponse::class.java)
        return jsonResponse(apps)
    }

    private fun handleSaveApp(session: IHTTPSession): Response {
        val body = parseBody(session)
        val item = gson.fromJson(body, AppItem::class.java)

        val text = appsFile.readText()
        val current = gson.fromJson(text, AppsResponse::class.java)
        val list = current.apps.toMutableList()

        val existingIndex = list.indexOfFirst { it.name == item.name }
        if (existingIndex >= 0) {
            list[existingIndex] = item
        } else {
            list.add(item)
        }

        val updated = current.copy(apps = list)
        appsFile.writeText(gson.toJson(updated))
        return jsonResponse(SimpleStatusResponse("true", "App saved"))
    }

    private fun handleDeleteApp(idOrName: String): Response {
        val text = appsFile.readText()
        val current = gson.fromJson(text, AppsResponse::class.java)
        val list = current.apps.filterNot { it.name == idOrName || it.id == idOrName }
        val updated = current.copy(apps = list)
        appsFile.writeText(gson.toJson(updated))
        return jsonResponse(SimpleStatusResponse("true", "App deleted"))
    }

    private fun handleGetClients(): Response {
        val text = stateFile.readText()
        val rootMap = gson.fromJson(text, Map::class.java)
        val root = (rootMap["root"] as? Map<*, *>) ?: emptyMap<String, Any>()
        val rawDevices = (root["named_devices"] as? List<*>) ?: emptyList<Any>()
        val runtime = SunshineRuntimeManager.getInstance()
        val activeSessions = runtime.getActiveSessions()
        val activeCount = activeSessions.size
        val totalDevices = rawDevices.size

        val clients = rawDevices.mapIndexedNotNull { idx, item ->
            if (item is Map<*, *>) {
                val name = item["name"]?.toString() ?: "Unknown"
                val uuid = item["uuid"]?.toString() ?: ""
                val isRecentActive = activeCount > 0 && idx >= (totalDevices - activeCount).coerceAtLeast(0)
                ClientItem(name = name, uuid = uuid, active = runtime.isClientActive(uuid) || isRecentActive)
            } else null
        }
        return jsonResponse(ClientsResponse("true", clients))
    }

    private fun handleRenameClient(session: IHTTPSession): Response {
        val body = parseBody(session)
        val req = gson.fromJson(body, RenameClientRequest::class.java)

        val text = stateFile.readText()
        val map = gson.fromJson(text, MutableMap::class.java) as MutableMap<String, Any>
        val root = (map["root"] as? MutableMap<String, Any>) ?: mutableMapOf()
        val devices = (root["named_devices"] as? List<*>)?.map { it as MutableMap<String, Any> }?.toMutableList() ?: mutableListOf()

        devices.find { it["uuid"] == req.uuid }?.set("name", req.name)
        root["named_devices"] = devices
        map["root"] = root
        stateFile.writeText(gson.toJson(map))

        return jsonResponse(SimpleStatusResponse("true", "Client renamed"))
    }

    private fun handleUnpairClient(session: IHTTPSession): Response {
        val body = parseBody(session)
        val req = gson.fromJson(body, UnpairClientRequest::class.java)

        val text = stateFile.readText()
        val map = gson.fromJson(text, MutableMap::class.java) as MutableMap<String, Any>
        val root = (map["root"] as? MutableMap<String, Any>) ?: mutableMapOf()
        val devices = (root["named_devices"] as? List<*>)?.mapNotNull { it as? Map<String, Any> }?.filterNot { it["uuid"] == req.uuid } ?: emptyList()

        root["named_devices"] = devices
        map["root"] = root
        stateFile.writeText(gson.toJson(map))

        return jsonResponse(SimpleStatusResponse("true", "Client unpaired"))
    }

    private fun handleUnpairAll(): Response {
        val text = stateFile.readText()
        val map = gson.fromJson(text, MutableMap::class.java) as MutableMap<String, Any>
        val root = (map["root"] as? MutableMap<String, Any>) ?: mutableMapOf()
        root["named_devices"] = emptyList<Any>()
        map["root"] = root
        stateFile.writeText(gson.toJson(map))

        return jsonResponse(SimpleStatusResponse("true", "All clients unpaired"))
    }

    private fun handleSubmitPin(session: IHTTPSession): Response {
        val body = parseBody(session)
        val req = try {
            gson.fromJson(body, PinRequest::class.java)
        } catch (_: Exception) { null }

        val pin = req?.pin?.trim() ?: ""
        if (pin.isEmpty()) {
            return jsonResponse(SimpleStatusResponse("false", "PIN is empty"))
        }

        SunshineLogManager.log("PIN", "Submitting PIN: $pin")
        val ok = SunshineRuntimeManager.getInstance().submitPin(pin)
        return if (ok) {
            jsonResponse(SimpleStatusResponse("true", "PIN submitted"))
        } else {
            jsonResponse(SimpleStatusResponse("false", "Server not ready or PIN rejected"))
        }
    }

    private fun handleGetRuntimeSessions(): Response {
        val sessions = SunshineRuntimeManager.getInstance().getActiveSessions()
        return jsonResponse(RuntimeSessionsResponse(sessions))
    }

    private fun handleGetPerfCurrent(): Response {
        val perf = SunshineRuntimeManager.getInstance().getPerfSnapshot()
        return jsonResponse(perf)
    }

    private fun handleGetLogs(): Response {
        val logs = SunshineLogManager.getRecentLogs()
        return jsonResponse(LogsResponse(logs))
    }

    private fun handleRestart(): Response {
        val scheduled = SunshineRuntimeManager.getInstance().restart()
        return jsonResponse(SimpleStatusResponse(
            if (scheduled) "true" else "false",
            if (scheduled) "已请求后台重启；新连接进入时会取消" else "仍有连接或服务未启动，未执行重启"
        ))
    }

    private fun parseBody(session: IHTTPSession): String {
        return try {
            val lengthStr = session.headers.entries
                .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
                ?.value
            val contentLength = lengthStr?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val buffer = ByteArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val read = session.inputStream.read(buffer, total, contentLength - total)
                    if (read == -1) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8)
            } else {
                val map = HashMap<String, String>()
                session.parseBody(map)
                map["postData"] ?: ""
            }
        } catch (e: Exception) {
            SunshineLogManager.log("API_ERR", "Error reading body: ${e.message}")
            ""
        }
    }

    private fun jsonResponse(data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun parseProperties(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                val parts = trimmed.split("=", limit = 2)
                result[parts[0].trim()] = parts[1].trim()
            }
        }
        return result
    }
}
