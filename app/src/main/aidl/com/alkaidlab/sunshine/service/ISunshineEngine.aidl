package com.alkaidlab.sunshine.service;
import com.alkaidlab.sunshine.service.ISunshineEngineHost;

interface ISunshineEngine {
    void start(ISunshineEngineHost host, String name, String cert, String key, String state, boolean hevc);
    boolean restartIfIdle(long revision);
    void shutdown();
    void submitPin(String pin);
    void startAudio(int samplesPerPacket);
}
