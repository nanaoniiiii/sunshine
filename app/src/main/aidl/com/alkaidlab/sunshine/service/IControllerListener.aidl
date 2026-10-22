package com.alkaidlab.sunshine.service;
interface IControllerListener {
    oneway void state(String device, int buttons, float lx, float ly, float rx, float ry, float lt, float rt, long eventTimeMs);
    oneway void key(String device, int code, boolean down);
    oneway void devices(String json);
    oneway void removed(String device);
    oneway void error(String message);
}
