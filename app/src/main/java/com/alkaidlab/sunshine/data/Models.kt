package com.alkaidlab.sunshine.data

import android.view.Surface
import com.google.gson.annotations.SerializedName

data class ConfigResponse(
    val status: String = "true",
    val platform: String = "android",
    val version: String = "v2026.823.92134",
    @SerializedName("display_devices") val displayDevices: List<DisplayDeviceNode> = emptyList(),
    val adapters: List<AdapterNode> = emptyList(),
    val port: String = "47989",
    @SerializedName("web_port") val webPort: String = "47990",
    val encoder: String = "AMediaCodec (Hardware)",
    @SerializedName("channels") val channels: String = "2",
    @SerializedName("min_bitrate") val minBitrate: String = "5000",
    @SerializedName("max_bitrate") val maxBitrate: String = "50000",
    @SerializedName("hevc_mode") val hevcMode: String = "enabled",
    @SerializedName("audio_sink") val audioSink: String = "media_projection",
    @SerializedName("auto_rotate") val autoRotate: String = "true",
    @SerializedName("auto_scale") val autoScale: String = "true",
    @SerializedName("address_family") val addressFamily: String = "both",
    @SerializedName("ipv6_address") val ipv6Address: String? = null
)

data class DisplayDeviceNode(
    @SerializedName("device_id") val deviceId: String,
    val data: String
)

data class AdapterNode(
    val name: String
)

data class AppItem(
    val id: String? = null,
    val name: String,
    @SerializedName("image-path") val imagePath: String? = "desktop",
    val cmd: String? = "",
    val output: String? = "",
    @SerializedName("auto-detach") val autoDetach: String? = "true",
    @SerializedName("wait-all") val waitAll: String? = "true"
)

data class AppsResponse(
    val env: Map<String, String> = emptyMap(),
    val apps: List<AppItem> = emptyList()
)

data class ClientItem(
    val name: String,
    val uuid: String,
    val active: Boolean = false,
    val cert: String? = null
)

data class ClientsResponse(
    val status: String = "true",
    @SerializedName("named_certs") val namedCerts: List<ClientItem> = emptyList()
)

data class PinRequest(
    val pin: String
)

data class RenameClientRequest(
    val uuid: String,
    val name: String
)

data class UnpairClientRequest(
    val uuid: String
)

data class SimpleStatusResponse(
    val status: String = "true",
    val message: String? = null,
    val error: String? = null
)

data class RuntimeSession(
    @SerializedName("client_name") val clientName: String,
    @SerializedName("client_ip") val clientIp: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codec: String,
    @SerializedName("start_time") val startTime: Long,
    @Transient val surface: Surface? = null,
    @Transient val videoThreadId: Long = 0L
)

data class RuntimeSessionsResponse(
    val sessions: List<RuntimeSession> = emptyList()
)

data class PerfResponse(
    val active: Boolean = false,
    val fps: Double = 0.0,
    @SerializedName("encode_time_ms") val encodeTimeMs: Double = 0.0,
    @SerializedName("network_latency_ms") val networkLatencyMs: Double = 0.0,
    @SerializedName("bitrate_kbps") val bitrateKbps: Int = 0
)

data class LogsResponse(
    val logs: List<String> = emptyList()
)
