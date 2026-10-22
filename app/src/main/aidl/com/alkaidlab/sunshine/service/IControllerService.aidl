package com.alkaidlab.sunshine.service;
import com.alkaidlab.sunshine.service.IControllerListener;
import android.view.MotionEvent;
interface IControllerService {
    void start(IControllerListener listener, IBinder owner) = 0;
    boolean grab(String device, boolean enabled) = 1;
    void exclude(String json) = 2;
    boolean gamepad(int id, int buttons, float lx, float ly, float rx, float ry, float lt, float rt) = 3;
    void releaseGamepad(int id) = 4;
    boolean motion(in MotionEvent event, int displayId, int actionButton) = 5;
    boolean systemAction(String action, int displayId) = 6;
    void stop() = 7;
    boolean enableNavigation(boolean enabled) = 8;
    String frequentApps(int count) = 9;
    boolean launchApp(String component, int displayId) = 10;
    void destroy() = 16777114;
}
