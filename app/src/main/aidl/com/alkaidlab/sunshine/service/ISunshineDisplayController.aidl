package com.alkaidlab.sunshine.service;
import android.view.Surface;
import android.view.MotionEvent;
interface ISunshineDisplayController {
    int createDisplay(int width, int height, int density, in Surface surface, String mode, IBinder owner, int fps, boolean samsungDex, boolean highRefresh) = 0;
    void closeDisplay() = 1;
    boolean launchApp(String component) = 2;
    boolean injectMotion(in MotionEvent event) = 3;
    boolean injectKey(int keyCode, boolean down, int modifiers) = 4;
    String snapshot() = 5;
    boolean blankMainScreen(IBinder owner) = 6;
    boolean prepareLocalDex(IBinder owner) = 7;
    void attachOwner(IBinder owner) = 8;
    boolean setMainPanelOff(boolean off) = 9;
    void destroy() = 16777114;
}
