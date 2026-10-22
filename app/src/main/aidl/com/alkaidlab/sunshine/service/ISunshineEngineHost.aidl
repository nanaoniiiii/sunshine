package com.alkaidlab.sunshine.service;
import android.view.Surface;
import android.view.MotionEvent;

interface ISunshineEngineHost {
    int[] streamStarted(long threadId, int width, int height, int fps, int packetDuration, in Surface surface, boolean audio, int appId, String clientId, String clientAddress);
    String appCatalog();
    boolean prepareApp(int appId);
    void streamStopped(long threadId, long revision);
    oneway void pinRequested();
    oneway void clientDiscovered(String client);
    oneway void serverUuid(String uuid);
    oneway void encoderError(String error);
    oneway void remoteInput();
    oneway void motionEvent(in MotionEvent event);
    oneway void keyboardEvent(int keyCode, boolean down, int modifiers);
    boolean streamPaused(long threadId);
    boolean streamResized(long threadId, int width, int height, in Surface surface);
    float[] readAudio(int count, int mode);
    oneway void videoFrameProgress(long threadId, long presentationTimeUs, boolean released);
    oneway void controllerState(int id, int buttons, float lx, float ly, float rx, float ry, float lt, float rt);
    oneway void controllerRemoved(int id);
}
