package com.alkaidlab.sunshine.service

import android.media.MediaCodecList
import com.alkaidlab.sunshine.data.EncoderCapability

/** Advertises device encoder capabilities, not features guaranteed by the bundled native engine. */
object EncoderCapabilities {
    val encoders: List<EncoderCapability> by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.flatMap { info ->
                info.supportedTypes.filter { it == "video/avc" || it == "video/hevc" }.mapNotNull { mime ->
                    try {
                        val capabilities = info.getCapabilitiesForType(mime)
                        val video = capabilities.videoCapabilities ?: return@mapNotNull null
                        EncoderCapability(info.name, mime, info.isHardwareAccelerated,
                            video.widthAlignment, video.heightAlignment,
                            video.supportedWidths.upper, video.supportedHeights.upper,
                            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && capabilities.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    } catch (_: RuntimeException) { null }
                }
            }
        } catch (_: RuntimeException) { emptyList() }
    }
}
