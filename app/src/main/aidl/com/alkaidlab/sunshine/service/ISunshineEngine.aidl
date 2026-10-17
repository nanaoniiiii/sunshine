package com.alkaidlab.sunshine.service;
import com.alkaidlab.sunshine.service.ISunshineEngineHost;

interface ISunshineEngine {
    void start(ISunshineEngineHost host, String name, String cert, String key, String state, boolean hevc);
    boolean restartIfIdle(long revision);
    void shutdown();
    void submitPin(String pin);
    void startAudio(int samplesPerPacket);
    void configureVideo(int minimum, int maximum, int maxFps);
    void configureCanvas(int sourceWidth, int sourceHeight, boolean followOrientation, boolean rotationCompat, int sourceDisplayId, int sourceFps);
    void configureEncoders(String avc, String hevc, in String[] avcNames, in String[] hevcNames);
    String videoSnapshot();
    boolean controlVideo(long sessionId, int operation, int value);
    void setCaptureAuthorized(boolean authorized);
}
