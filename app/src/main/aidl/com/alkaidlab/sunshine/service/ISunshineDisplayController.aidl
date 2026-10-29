package com.alkaidlab.sunshine.service;
import android.view.Surface;
import android.view.MotionEvent;
import com.alkaidlab.sunshine.service.ISunshineDesktopCallback;
interface ISunshineDisplayController {
    int createDisplay(int width, int height, int density, in Surface surface, String mode, IBinder owner, int fps, boolean systemDesktop, boolean highRefresh) = 0;
    void closeDisplay() = 1;
    boolean launchApp(String component) = 2;
    boolean injectMotion(in MotionEvent event) = 3;
    boolean injectKey(int keyCode, boolean down, int modifiers) = 4;
    String snapshot() = 5;
    boolean blankMainScreen(IBinder owner) = 6;
    boolean prepareLocalDex(IBinder owner) = 7;
    void attachOwner(IBinder owner) = 8;
    boolean setMainPanelOff(boolean off) = 9;
    boolean showDesktopControls(ISunshineDesktopCallback callback) = 10;
    String desktopTasks() = 11;
    boolean desktopAction(String action, int taskId) = 12;
    String configureDesktop(int width, int height, boolean samsungDex, IBinder owner, boolean fullscreen) = 13;
    String reconfigureDisplay(int width, int height, in Surface surface, boolean fullscreen) = 14;
    void destroy() = 16777114;
}
