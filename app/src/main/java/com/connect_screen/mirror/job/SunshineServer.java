package com.connect_screen.mirror.job;

import android.view.Surface;
import com.alkaidlab.sunshine.service.SunshineRuntimeManager;

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

    static {
        System.loadLibrary("crypto");
        System.loadLibrary("ssl");
        System.loadLibrary("sunshine");
    }

    // Callbacks invoked by libsunshine.so C++ layer
    public static void onPinRequested() {
        SunshineRuntimeManager.getInstance().onPinRequested();
    }

    public static void createVirtualDisplay(int width, int height, int fps, int packetDuration, Surface surface, boolean shouldSendAudio) {
        SunshineMouse.initialize(width, height);
        SunshineRuntimeManager.getInstance().onStreamStarted(width, height, fps, packetDuration, surface, shouldSendAudio);
    }

    public static void stopVirtualDisplay() {
        SunshineRuntimeManager.getInstance().onStreamStopped();
    }

    public static void showEncoderError(String error) {
        SunshineRuntimeManager.getInstance().onEncoderError(error);
    }

    public static void onConnectScreenClientDiscovered(String client) {
        SunshineRuntimeManager.getInstance().onClientDiscovered(client);
    }

    public static void setConnectScreenServerUuid(String uuid) {
        SunshineRuntimeManager.getInstance().setServerUuid(uuid);
    }
}
