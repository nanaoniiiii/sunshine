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
    @SerializedName("encoder_h264") val encoderH264: String = "auto",
    @SerializedName("encoder_hevc") val encoderHevc: String = "auto",
    @SerializedName("encoder_options") val encoderOptions: List<EncoderCapability> = emptyList(),
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
    @SerializedName("wait-all") val waitAll: String? = "true",
    @SerializedName("single_app") val singleApp: Boolean = false
)

data class AppsResponse(
    val env: Map<String, String> = emptyMap(),
    val apps: List<AppItem> = emptyList(),
    @SerializedName("next_id") val nextId: Int = 2
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
    @SerializedName("session_id") val sessionId: Long? = null,
    @SerializedName("app_id") val appId: Int = 1,
    @SerializedName("client_uuid") val clientUuid: String? = null,
    @SerializedName("app_name") val appName: String? = null
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
    @SerializedName("measured_fps") val measuredFps: Double,
    @SerializedName("thread_id") val threadId: Long = 0,
    @SerializedName("gpu_handoff_ms") val gpuHandoffMs: Double? = null,
    val viewport: List<Int> = emptyList()
)

data class FrameDiagnostics(
    @SerializedName("capture_width") val captureWidth: Int = 0,
    @SerializedName("capture_height") val captureHeight: Int = 0,
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
    val video: List<NativeVideoSession> = emptyList(),
    @SerializedName("encoder_error") val encoderError: String? = null
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
    @SerializedName("submit_to_output_p95_ms") val submitToOutputP95Ms: Double?,
    @SerializedName("fec_percentage") val fecPercentage: Int,
    @SerializedName("pending_fec_percentage") val pendingFecPercentage: Int,
    @SerializedName("nominal_video_fec_budget_kbps") val nominalVideoFecBudgetKbps: Int,
    @SerializedName("control_ceiling_kbps") val controlCeilingKbps: Int,
    @SerializedName("adaptive_enabled") val adaptiveEnabled: Boolean,
    @SerializedName("adaptive_state") val adaptiveState: String,
    @SerializedName("control_rtt_ms") val controlRttMs: Int?,
    @SerializedName("fec_report_age_ms") val fecReportAgeMs: Long?,
    @SerializedName("fec_reports") val fecReports: Long,
    @SerializedName("fec_recovered_blocks") val fecRecoveredBlocks: Long,
    @SerializedName("fec_unrecoverable_blocks") val fecUnrecoverableBlocks: Long,
    @SerializedName("reported_missing_data_packets") val reportedMissingDataPackets: Long,
    @SerializedName("fec_duplicate_reports") val fecDuplicateReports: Long,
    @SerializedName("fec_rejected_reports") val fecRejectedReports: Long,
    @SerializedName("fec_changes") val fecChanges: Long,
    @SerializedName("encoder_choice") val encoderChoice: String = "auto",
    @SerializedName("encoder_attempts") val encoderAttempts: Int = 0,
    @SerializedName("encoder_reported_latency_frames") val encoderReportedLatencyFrames: Int? = null,
    @SerializedName("codec_profile_idc") val codecProfileIdc: Int? = null,
    @SerializedName("codec_level_idc") val codecLevelIdc: Int? = null,
    @SerializedName("queue_frames") val queueFrames: Int = 0,
    @SerializedName("queue_bytes") val queueBytes: Long = 0,
    @SerializedName("queue_peak_frames") val queuePeakFrames: Int = 0,
    @SerializedName("queue_peak_bytes") val queuePeakBytes: Long = 0,
    @SerializedName("queue_dropped_frames") val queueDroppedFrames: Long = 0,
    @SerializedName("queue_waiting_idr") val queueWaitingIdr: Boolean = false,
    @SerializedName("queue_limit_ms") val queueLimitMs: Int = 80,
    @SerializedName("queue_oldest_age_ms") val queueOldestAgeMs: Double? = null,
    @SerializedName("queue_delay_p95_ms") val queueDelayP95Ms: Double? = null,
    @SerializedName("fec_compute_p95_ms") val fecComputeP95Ms: Double? = null,
    @SerializedName("pacing_wait_p95_ms") val pacingWaitP95Ms: Double? = null,
    @SerializedName("socket_send_p95_ms") val socketSendP95Ms: Double? = null,
    @SerializedName("frame_send_p95_ms") val frameSendP95Ms: Double? = null,
    @SerializedName("sent_data_packets") val sentDataPackets: Long = 0,
    @SerializedName("sent_parity_packets") val sentParityPackets: Long = 0,
    @SerializedName("sent_udp_payload_bytes") val sentUdpPayloadBytes: Long = 0,
    @SerializedName("sent_udp_payload_kbps") val sentUdpPayloadKbps: Double = 0.0,
    @SerializedName("send_errors") val sendErrors: Long = 0,
    @SerializedName("actual_parity_ratio") val actualParityRatio: Double? = null,
    @SerializedName("network_control_updates") val networkControlUpdates: Long = 0
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
