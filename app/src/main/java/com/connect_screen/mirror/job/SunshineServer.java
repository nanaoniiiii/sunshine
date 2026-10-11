package com.connect_screen.mirror.job;

import android.view.Surface;
import com.alkaidlab.sunshine.service.SunshineEngineBridge;

public class SunshineServer {

    public static native void enableH265();
    public static native boolean exitServer();
    public static native void setCertPath(String path);
    public static native void setFileStatePath(String path);
    public static native void setPkeyPath(String path);
    public static native void setSunshineName(String name);
    public static native void start();
    public static native void startAudioRecording(Object audioRecord, int packetDuration);
    public static native void submitPin(String pin);
    public static native void cleanup();
    public static native void configureVideo(int minimum, int maximum, int maxFps);
    public static native String videoSnapshot();
    public static native boolean controlVideo(long sessionId, int operation, int value);

    static {
        System.loadLibrary("crypto");
        System.loadLibrary("ssl");
        System.loadLibrary("sunshine");
    }

    // Callbacks invoked by libsunshine.so C++ layer
    public static void onPinRequested() {
        SunshineEngineBridge.pinRequested();
    }

    public static void createVirtualDisplay(int width, int height, int fps, int packetDuration, Surface surface, boolean shouldSendAudio) {
        SunshineEngineBridge.streamStarted(width, height, fps, packetDuration, surface, shouldSendAudio);
    }

    public static void stopVirtualDisplay() {
        SunshineEngineBridge.streamStopped();
    }

    public static void showEncoderError(String error) {
        SunshineEngineBridge.encoderError(error);
    }

    public static void onConnectScreenClientDiscovered(String client) {
        SunshineEngineBridge.clientDiscovered(client);
    }

    public static void setConnectScreenServerUuid(String uuid) {
        SunshineEngineBridge.serverUuid(uuid);
    }
}
