package com.alkaidlab.sunshine.data

import android.view.Surface
import com.alkaidlab.sunshine.BuildConfig
import com.google.gson.annotations.SerializedName

data class ConfigResponse(
    val status: String = "true",
    val platform: String = "android",
    val version: String = BuildConfig.VERSION_NAME,
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
    @SerializedName("max_stream_fps") val maxStreamFps: String = "240",
    @SerializedName("repeat_frames") val repeatFrames: String = "true",
    @SerializedName("audio_buffer_ms") val audioBufferMs: String = "40",
    @SerializedName("wifi_low_latency") val wifiLowLatency: String = "true",
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
    @Transient val videoThreadId: Long = 0L,
    @Transient val audioEnabled: Boolean = false,
    @SerializedName("session_id") val sessionId: Long? = null
)

data class RuntimeSessionsResponse(
    val sessions: List<RuntimeSession> = emptyList()
)

data class PerfResponse(
    val active: Boolean = false,
    val fps: Double = 0.0,
    @SerializedName("encode_time_ms") val encodeTimeMs: Double = 0.0,
    @SerializedName("network_latency_ms") val networkLatencyMs: Double? = null,
    @SerializedName("bitrate_kbps") val bitrateKbps: Int = 0,
    @SerializedName("gpu_handoff_ms") val gpuHandoffMs: Double = 0.0,
    @SerializedName("network_probe_ip") val networkProbeIp: String? = null,
    @SerializedName("network_sample_age_ms") val networkSampleAgeMs: Long? = null,
    @SerializedName("packet_loss_percent") val packetLossPercent: Double? = null
)

data class FrameTargetDiagnostics(
    val width: Int, val height: Int,
    @SerializedName("requested_fps") val requestedFps: Int,
    @SerializedName("effective_fps") val effectiveFps: Int,
    @SerializedName("measured_fps") val measuredFps: Double
)

data class FrameDiagnostics(
    @SerializedName("capture_fps") val captureFps: Double = 0.0,
    @SerializedName("submitted_frames") val submittedFrames: Long = 0,
    @SerializedName("repeated_frames") val repeatedFrames: Long = 0,
    @SerializedName("coalesced_frames") val coalescedFrames: Long = 0,
    @SerializedName("surface_failures") val surfaceFailures: Long = 0,
    @SerializedName("slow_handoffs") val slowHandoffs: Long = 0,
    @SerializedName("handoff_p95_ms") val handoffP95Ms: Double = 0.0,
    @SerializedName("handoff_max_ms") val handoffMaxMs: Double = 0.0,
    @SerializedName("capture_age_ms") val captureAgeMs: Double? = null,
    @SerializedName("pending_texture_limit") val pendingTextureLimit: Int = 1,
    val targets: List<FrameTargetDiagnostics> = emptyList()
)

data class AudioDiagnostics(
    val capturing: Boolean,
    @SerializedName("buffer_limit_ms") val bufferLimitMs: Int,
    @SerializedName("queued_ms") val queuedMs: Double,
    @SerializedName("overflow_dropped_ms") val overflowDroppedMs: Double
)

data class DiagnosticsResponse(
    val frames: FrameDiagnostics,
    val audio: AudioDiagnostics,
    val perf: PerfResponse,
    val capabilities: Map<String, String>,
    val video: List<NativeVideoSession> = emptyList()
)

data class NativeVideoSession(
    @SerializedName("session_id") val sessionId: Long,
    @SerializedName("thread_id") val threadId: Long,
    val codec: String,
    val encoder: String,
    @SerializedName("requested_fps") val requestedFps: Int,
    @SerializedName("configured_fps") val configuredFps: Int,
    @SerializedName("requested_bitrate_kbps") val requestedBitrateKbps: Int,
    @SerializedName("desired_bitrate_kbps") val desiredBitrateKbps: Int,
    @SerializedName("applied_bitrate_kbps") val appliedBitrateKbps: Int,
    @SerializedName("bitrate_status") val bitrateStatus: Int,
    @SerializedName("idr_status") val idrStatus: Int,
    @SerializedName("idr_requests") val idrRequests: Long,
    @SerializedName("encoded_frames") val encodedFrames: Long,
    @SerializedName("encoded_bytes") val encodedBytes: Long,
    val keyframes: Long,
    @SerializedName("invalid_buffers") val invalidBuffers: Long,
    @SerializedName("encoded_fps") val encodedFps: Double,
    @SerializedName("encoded_bitrate_kbps") val encodedBitrateKbps: Double,
    @SerializedName("submit_to_output_p95_ms") val submitToOutputP95Ms: Double?
)

data class EncoderCapability(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    @SerializedName("width_alignment") val widthAlignment: Int,
    @SerializedName("height_alignment") val heightAlignment: Int,
    @SerializedName("max_width") val maxWidth: Int,
    @SerializedName("max_height") val maxHeight: Int,
    @SerializedName("low_latency_feature") val lowLatencyFeature: Boolean
)

data class LogsResponse(
    val logs: List<String> = emptyList()
)
