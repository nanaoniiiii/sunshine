package com.alkaidlab.sunshine.service;
import android.view.Surface;
import android.view.MotionEvent;

interface ISunshineEngineHost {
    int[] streamStarted(long threadId, int width, int height, int fps, int packetDuration, in Surface surface, boolean audio, int appId, String clientId);
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
    float[] readAudio(int count, int mode);
}
