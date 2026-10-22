package com.alkaidlab.sunshine.api

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.alkaidlab.sunshine.BuildConfig
import com.alkaidlab.sunshine.data.*
import com.alkaidlab.sunshine.service.AppCatalog
import com.alkaidlab.sunshine.service.StreamingSettings
import com.alkaidlab.sunshine.service.SunshineLogManager
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import com.google.gson.Gson
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
                uri == "/api/controller" && method == Method.GET -> jsonResponse(com.alkaidlab.sunshine.controller.ControllerAdapter.status.value)
                uri == "/api/virtual-screen" && method == Method.GET -> jsonResponse(SunshineRuntimeManager.getInstance().virtualScreenState.value)
                uri == "/api/virtual-screen/create" && method == Method.POST -> {
                    val result = kotlinx.coroutines.runBlocking { SunshineRuntimeManager.getInstance().createVirtualScreen() }
                    jsonResponse(SimpleStatusResponse(result.applied.toString(), result.message))
                }
                uri == "/api/virtual-screen/exit" && method == Method.POST -> {
                    val result = kotlinx.coroutines.runBlocking { SunshineRuntimeManager.getInstance().exitVirtualScreen() }
                    jsonResponse(SimpleStatusResponse(result.applied.toString(), result.message))
                }
                uri == "/api/virtual-screen/panel" && method == Method.POST -> {
                    val value = try { com.google.gson.JsonParser.parseString(parseBody(session)).asJsonObject["off"] } catch (_: Exception) { null }
                    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean)
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"off must be a boolean\"}")
                    else {
                        val result = kotlinx.coroutines.runBlocking { SunshineRuntimeManager.getInstance().setVirtualScreenPanelOff(value.asBoolean) }
                        jsonResponse(SimpleStatusResponse(result.applied.toString(), result.message))
                    }
                }
                uri.startsWith("/api/runtime/sessions/") && method == Method.POST -> handleVideoControl(session)
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
        val primary = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        (primary ?: wm.defaultDisplay).getRealMetrics(metrics)

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
            encoder = "AMediaCodec · $codecTag",
            encoderH264 = confMap["encoder_h264"] ?: "auto",
            encoderHevc = confMap["encoder_hevc"] ?: "auto",
            encoderOptions = com.alkaidlab.sunshine.service.EncoderCapabilities.encoders,
            channels = confMap["channels"] ?: "2",
            minBitrate = confMap["min_bitrate"] ?: "5000",
            maxBitrate = confMap["max_bitrate"] ?: "50000",
            hevcMode = hevcMode,
            audioSink = confMap["audio_sink"] ?: "media_projection",
            autoRotate = confMap["auto_rotate"] ?: "true",
            rotationCompat = confMap["rotation_compat"] ?: "false",
            autoScale = confMap["auto_scale"] ?: "true",
            virtualScreenMode = confMap["virtual_screen_mode"] ?: "screen_off",
            virtualScreenDesktop = confMap["virtual_screen_desktop"] ?: "sunshine",
            virtualScreenDensity = confMap["virtual_screen_density"] ?: "240",
            experimentalSamsungDex = (confMap["virtual_screen_desktop"] == "system").toString(),
            experimentalDesktopHighRefresh = confMap["experimental_desktop_high_refresh"] ?: "true",
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
        val incoming = try { com.alkaidlab.sunshine.service.ConfigRequest.parse(body) }
            catch (_: Exception) { return@synchronized newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(SimpleStatusResponse("false", error = "Configuration must be an object with scalar values"))) }
        val current = parseProperties(configFile.readText()).toMutableMap()
        current.putAll(incoming)
        // Old clients' explicit DeX toggles select the same profile. Existing
        // installations without the new choice still default to Sunshine HOME.
        if ("virtual_screen_desktop" !in incoming && "experimental_samsung_dex" in incoming)
            current["virtual_screen_desktop"] = if (incoming["experimental_samsung_dex"] == "true") "system" else "sunshine"
        StreamingSettings.validate(current)?.let { error ->
            return@synchronized newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                gson.toJson(SimpleStatusResponse("false", error = error)))
        }
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
        SunshineLogManager.log("CONFIG", "Host settings and native bitrate bounds applied; encoder FPS/codec settings apply to new sessions")
        jsonResponse(SimpleStatusResponse("true", "码率限制、画面调度与缓冲设置已应用；编码器选项和帧率在下次连接生效，编码模式需空闲重启"))
    }

    private fun handleVideoControl(session: IHTTPSession): Response {
        fun error(status: Response.Status, message: String) = newFixedLengthResponse(
            status, "application/json", gson.toJson(SimpleStatusResponse("false", error = message)))
        val parts = session.uri.removePrefix("/api/runtime/sessions/").split('/')
        if (parts.size != 2) return error(Response.Status.NOT_FOUND, "Unknown session action")
        val id = parts[0].toLongOrNull()?.takeIf { it > 0 }
            ?: return error(Response.Status.BAD_REQUEST, "Invalid session ID")
        val runtime = SunshineRuntimeManager.getInstance()
        val native = runtime.diagnostics().video.find { it.sessionId == id }
            ?: return error(Response.Status.NOT_FOUND, "Session is no longer active")
        if (parts[1] == "fullscreen") {
            val result = kotlinx.coroutines.runBlocking { runtime.fullscreenSession(id) }
            return if (result.applied) jsonResponse(SimpleStatusResponse("true", result.message))
            else error(Response.Status.CONFLICT, result.message)
        }
        val operation = when (parts[1]) {
            "bitrate" -> 1
            "keyframe" -> 2
            "disconnect" -> 3
            "fec" -> 4
            "adaptive" -> 5
            else -> return error(Response.Status.NOT_FOUND, "Unknown session action")
        }
        var value = 0
        if (operation == 1) {
            value = try {
                val objectValue = com.google.gson.JsonParser.parseString(parseBody(session)).asJsonObject["bitrate_kbps"]
                val raw = objectValue?.asString.orEmpty()
                raw.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "bitrate_kbps must be an integer")
            } catch (_: Exception) { return error(Response.Status.BAD_REQUEST, "Invalid bitrate request") }
            val ceiling = minOf(runtime.configuredMaxBitrate, native.requestedBitrateKbps, native.controlCeilingKbps)
            val floor = minOf(runtime.configuredMinBitrate, ceiling)
            if (value !in floor..ceiling) return error(Response.Status.BAD_REQUEST, "bitrate_kbps must be within $floor..$ceiling")
        }
        if (operation == 4 || operation == 5) {
            value = try {
                val objectValue=com.google.gson.JsonParser.parseString(parseBody(session)).asJsonObject
                if (operation==4) {
                    objectValue["percentage"]?.asString?.toIntOrNull()?.takeIf { it in 0..35 }
                        ?: return error(Response.Status.BAD_REQUEST,"percentage must be an integer in 0..35")
                } else {
                    val enabled=objectValue["enabled"]
                    if(enabled==null || !enabled.isJsonPrimitive || !enabled.asJsonPrimitive.isBoolean)
                        return error(Response.Status.BAD_REQUEST,"enabled must be a boolean")
                    if(enabled.asBoolean) 1 else 0
                }
            } catch (_: Exception) { return error(Response.Status.BAD_REQUEST,"Invalid network control request") }
        }
        if (!runtime.controlVideo(id, operation, value)) return error(Response.Status.CONFLICT, "Session or configuration changed; refresh and retry")
        return jsonResponse(SimpleStatusResponse("true", "Request queued; inspect video diagnostics for driver result"))
    }

    private fun handleGetApps(): Response = jsonResponse(AppCatalog.read(appsFile))

    private fun handleSaveApp(session: IHTTPSession): Response {
        val item = gson.fromJson(parseBody(session), AppItem::class.java)
        if (item.name.isBlank()) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Application name is required")
        AppCatalog.save(appsFile, item)
        return jsonResponse(SimpleStatusResponse("true", "App saved"))
    }

    private fun handleDeleteApp(idOrName: String): Response {
        AppCatalog.delete(appsFile, idOrName)
        return jsonResponse(SimpleStatusResponse("true", "App deleted"))
    }

    private fun handleGetClients(): Response {
        val text = stateFile.readText()
        val rootMap = gson.fromJson(text, Map::class.java)
        val root = (rootMap["root"] as? Map<*, *>) ?: emptyMap<String, Any>()
        val rawDevices = (root["named_devices"] as? List<*>) ?: emptyList<Any>()
        val runtime = SunshineRuntimeManager.getInstance()

        val clients = rawDevices.mapNotNull { item ->
            if (item is Map<*, *>) {
                val name = item["name"]?.toString() ?: "Unknown"
                val uuid = item["uuid"]?.toString() ?: ""
                ClientItem(name = name, uuid = uuid, active = runtime.isClientActive(uuid))
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
