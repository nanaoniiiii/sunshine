package com.connect_screen.mirror.job;

import com.alkaidlab.sunshine.service.SunshineRuntimeManager;

public class SunshineKeyboard {
    public static void handleKeyboardEvent(int keyCode, boolean isDown, int modifiers) {
        SunshineRuntimeManager.getInstance().handleKeyboardEvent(keyCode, isDown, modifiers);
    }
}
