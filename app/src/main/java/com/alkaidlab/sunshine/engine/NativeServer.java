package com.alkaidlab.sunshine.engine;

import android.view.Surface;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import com.alkaidlab.sunshine.service.SunshineEngineBridge;

/** Java entry points and callbacks for this project's Android Sunshine backend. */
public final class NativeServer {
    private NativeServer() {}
    static {
        System.loadLibrary("crypto");
        System.loadLibrary("ssl");
        System.loadLibrary("sunshine");
    }
    public static native void enableH265();
    public static native boolean exitServer();
    public static native void setCertPath(String path);
    public static native void setFileStatePath(String path);
    public static native void setPkeyPath(String path);
    public static native void setSunshineName(String name);
    public static native void start();
    public static native void startAudioRecording(Object source, int samplesPerPacket);
    public static native void submitPin(String pin);
    public static native void cleanup();
    public static native void configureVideo(int minimum, int maximum, int maxFps);
    public static native void configureCanvas(int width, int height, boolean followOrientation, int sourceFps);
    public static native void configureEncoders(String avc, String hevc, String[] avcNames, String[] hevcNames);
    public static native String videoSnapshot();
    public static native boolean controlVideo(long sessionId, int operation, int value);
    public static String getAppCatalog() { return SunshineEngineBridge.appCatalog(); }
    public static int getAvcProfile(String encoderName) {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!codec.isEncoder() || !codec.getName().equals(encoderName)) continue;
                MediaCodecInfo.CodecProfileLevel[] levels = codec.getCapabilitiesForType("video/avc").profileLevels;
                int[] preferred = {MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline};
                for (int profile : preferred) {
                    for (MediaCodecInfo.CodecProfileLevel level : levels) {
                        if (level.profile == profile) return profile;
                    }
                }
            }
        } catch (RuntimeException ignored) { }
        return 0;
    }
    public static boolean prepareApp(int appId) { return SunshineEngineBridge.prepareApp(appId); }
    public static int getBitrateMode(String encoderName, String mime) {
        try {
            for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (codec.isEncoder() && codec.getName().equals(encoderName) &&
                        codec.getCapabilitiesForType(mime).getEncoderCapabilities().isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                    return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
                }
            }
        } catch (RuntimeException ignored) { }
        return MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
    }
    public static void onPinRequested() { SunshineEngineBridge.pinRequested(); }
    public static void onRemoteInput() { SunshineEngineBridge.remoteInput(); }
    public static int setWorkerPriority(int priority) {
        try { android.os.Process.setThreadPriority(priority); } catch (RuntimeException ignored) { }
        return android.os.Process.getThreadPriority(android.os.Process.myTid());
    }
    public static void onVideoFrameProgress(long threadId, long presentationTimeUs, boolean released) {
        SunshineEngineBridge.videoFrameProgress(threadId, presentationTimeUs, released);
    }
    public static void createVirtualDisplay(int width, int height, int fps, int audioMs,
            Surface surface, boolean audio, int appId, String clientId, String clientAddress) {
        SunshineEngineBridge.streamStarted(width, height, fps, audioMs, surface, audio, appId, clientId, clientAddress);
    }
    public static void stopVirtualDisplay() { SunshineEngineBridge.streamStopped(); }
    public static boolean pauseVirtualDisplay() { return SunshineEngineBridge.streamPaused(); }
    public static boolean resizeVirtualDisplay(int width, int height, Surface surface) {
        return SunshineEngineBridge.streamResized(width, height, surface);
    }
    public static void showEncoderError(String message) { SunshineEngineBridge.encoderError(message); }
    public static void onClientPaired(String client) { SunshineEngineBridge.clientDiscovered(client); }
    public static void onServerUuid(String uuid) { SunshineEngineBridge.serverUuid(uuid); }
}
