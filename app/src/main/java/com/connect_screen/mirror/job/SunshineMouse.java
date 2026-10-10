package com.connect_screen.mirror.job;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.alkaidlab.sunshine.service.SunshineLogManager;
import com.alkaidlab.sunshine.service.SunshineRuntimeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SunshineMouse {
    private static final String TAG = "SunshineMouse";

    public static class Point {
        public float x;
        public float y;

        public Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static Context appContext = null;
    private static final Map<Integer, Point> pointers = new ConcurrentHashMap<>();
    private static final Set<Integer> bufferedMove = Collections.synchronizedSet(new HashSet<>());
    private static volatile Point currentMousePoint = null;
    private static volatile boolean isLeftButtonDown = false;
    private static volatile long downTime = 0;

    private static float defaultDisplayWidth = 1600f;  // Longer dimension of phone (e.g. 1600 on S20+)
    private static float defaultDisplayHeight = 720f;  // Shorter dimension of phone (e.g. 720 on S20+)
    private static float screenWidth = 1280f;          // Moonlight stream width
    private static float screenHeight = 720f;          // Moonlight stream height

    private static float landscapeMirrorWidth = 1280f;
    private static float landscapeMirrorHeight = 720f;
    private static float portraitMirrorWidth = 720f;
    private static float portraitMirrorHeight = 720f;

    public static void setContext(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
            updateDisplayDimensions();
        }
    }

    public static void updateDisplayDimensions() {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
                if (display != null) {
                    android.graphics.Point size = new android.graphics.Point();
                    display.getRealSize(size);
                    if (size.x > 0 && size.y > 0) {
                        defaultDisplayWidth = Math.max(size.x, size.y);
                        defaultDisplayHeight = Math.min(size.x, size.y);
                        Log.i(TAG, "Queried real physical display: " + (int)defaultDisplayWidth + "x" + (int)defaultDisplayHeight);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to query real display size: " + t.getMessage());
        }
    }

    public static void initialize(int width, int height) {
        pointers.clear();
        bufferedMove.clear();
        currentMousePoint = null;
        isLeftButtonDown = false;
        downTime = 0;

        if (width > 0) screenWidth = width;
        if (height > 0) screenHeight = height;

        updateDisplayDimensions();

        float displayAspect = defaultDisplayWidth / defaultDisplayHeight; // e.g. 1600 / 720 = 2.222

        // Landscape mirror dimensions
        landscapeMirrorHeight = screenHeight;
        float lmw = screenHeight * displayAspect;
        landscapeMirrorWidth = lmw;
        if (lmw > screenWidth) {
            landscapeMirrorWidth = screenWidth;
            landscapeMirrorHeight = screenWidth / displayAspect;
        }

        // Portrait mirror dimensions
        portraitMirrorHeight = screenHeight;
        float pmw = screenHeight / displayAspect;
        portraitMirrorWidth = pmw;
        if (pmw > screenWidth) {
            portraitMirrorWidth = screenWidth;
            portraitMirrorHeight = screenWidth * displayAspect;
        }

        SunshineLogManager.log("MOUSE", "Initialized mapper - physical: " + (int)defaultDisplayWidth + "x" + (int)defaultDisplayHeight +
                ", stream: " + (int)screenWidth + "x" + (int)screenHeight +
                ", landMirror: " + (int)landscapeMirrorWidth + "x" + (int)landscapeMirrorHeight +
                ", portMirror: " + (int)portraitMirrorWidth + "x" + (int)portraitMirrorHeight);
    }

    /**
     * Translates normalized coordinates [0.0, 1.0] from Moonlight into exact
     * physical screen pixel coordinates on the Android device.
     */
    public static Point translate(float normX, float normY) {
        Context ctx = appContext;
        boolean isLandscape = true;
        if (ctx != null) {
            try {
                isLandscape = (ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
            } catch (Throwable ignored) {}
        }

        float streamX = normX * screenWidth;
        float streamY = normY * screenHeight;

        if (isLandscape) {
            return translateLandscape(streamX, streamY);
        } else {
            return translatePortrait(streamX, streamY);
        }
    }

    private static Point translateLandscape(float x, float y) {
        float fW = screenWidth;
        float lmW = landscapeMirrorWidth;
        float fH = screenHeight;
        float lmH = landscapeMirrorHeight;

        float offsetX = (fW - lmW) / 2.0f;
        float offsetY = (fH - lmH) / 2.0f;

        float clampedX = Math.max(0.0f, Math.min(lmW, x - offsetX));
        float clampedY = Math.max(0.0f, Math.min(lmH, y - offsetY));

        float targetX = (lmW > 0.0f) ? (clampedX / lmW) * defaultDisplayWidth : 0.0f;
        float targetY = (lmH > 0.0f) ? (clampedY / lmH) * defaultDisplayHeight : 0.0f;

        targetX = Math.max(0.0f, Math.min(defaultDisplayWidth - 1.0f, targetX));
        targetY = Math.max(0.0f, Math.min(defaultDisplayHeight - 1.0f, targetY));

        return new Point(targetX, targetY);
    }

    private static Point translatePortrait(float x, float y) {
        float fW = screenWidth;
        float pmW = portraitMirrorWidth;
        float fH = screenHeight;
        float pmH = portraitMirrorHeight;

        float offsetX = (fW - pmW) / 2.0f;
        float offsetY = (fH - pmH) / 2.0f;

        float clampedX = Math.max(0.0f, Math.min(pmW, x - offsetX));
        float clampedY = Math.max(0.0f, Math.min(pmH, y - offsetY));

        float targetX = (pmW > 0.0f) ? (clampedX / pmW) * defaultDisplayHeight : 0.0f;
        float targetY = (pmH > 0.0f) ? (clampedY / pmH) * defaultDisplayWidth : 0.0f;

        targetX = Math.max(0.0f, Math.min(defaultDisplayHeight - 1.0f, targetX));
        targetY = Math.max(0.0f, Math.min(defaultDisplayWidth - 1.0f, targetY));

        return new Point(targetX, targetY);
    }

    /**
     * Called by Native libsunshine when Moonlight sends touch packets.
     * action: 1 = DOWN, 2 = UP, 3 = MOVE, 4 = CANCEL/UP, 7 = CANCEL ALL
     */
    public static void handleTouchPacket(int action, int flags, int pointerId, float x, float y, float pressure, float contactArea, float reserved) {
        int pointerIndex = pointerId % 10;
        Point pt = translate(x, y);

        SunshineLogManager.log("TOUCH", "Touch: act=" + action + " ptr=" + pointerIndex + " -> (" + (int)pt.x + "," + (int)pt.y + ")");

        switch (action) {
            case 1: // DOWN
                handleTouchEventDown(pointerIndex, pt.x, pt.y);
                break;
            case 2: // UP
                handleTouchEventUp(pointerIndex, pt.x, pt.y, false);
                break;
            case 3: // MOVE
                handleTouchEventMove(pointerIndex, pt.x, pt.y);
                break;
            case 4: // CANCEL
                handleTouchEventUp(pointerIndex, pt.x, pt.y, true);
                break;
            case 7: // CANCEL ALL
                handleTouchEventCancelAll();
                break;
            default:
                Log.w(TAG, "Unknown touch action: " + action);
                break;
        }
    }

    /**
     * Called by Native libsunshine when client sends mouse position packet.
     * Only generates ACTION_MOVE if the left mouse button is actively pressed down (dragging).
     * When the button is not pressed, it merely updates the cursor position without injecting
     * touch events, preventing phantom touches and sticky selection.
     */
    public static void handleAbsMouseMovePacket(float x, float y, float width, float height) {
        float normX = (width > 0.0f) ? (x / width) : x;
        float normY = (height > 0.0f) ? (y / height) : y;
        Point pt = translate(normX, normY);
        currentMousePoint = pt;

        if (isLeftButtonDown) {
            handleTouchEventMove(0, pt.x, pt.y);
        }
    }

    /**
     * Called by Native libsunshine when client clicks mouse button.
     * isRelease: true = RELEASE (UP), false = PRESS (DOWN)
     */
    public static void handleLeftMouseButton(boolean isRelease) {
        Point pt = currentMousePoint;
        if (pt == null) {
            pt = translate(0.5f, 0.5f);
            currentMousePoint = pt;
        }

        SunshineLogManager.log("MOUSE", "Mouse " + (isRelease ? "UP" : "DOWN") + " at (" + (int)pt.x + "," + (int)pt.y + ")");

        if (isRelease) {
            isLeftButtonDown = false;
            handleTouchEventUp(0, pt.x, pt.y, false);
        } else {
            if (!isLeftButtonDown) {
                isLeftButtonDown = true;
                handleTouchEventDown(0, pt.x, pt.y);
            }
        }
    }

    private static synchronized void handleTouchEventDown(int pointerId, float x, float y) {
        bufferedMove.clear();

        long now = SystemClock.uptimeMillis();
        boolean isFirst = pointers.isEmpty();
        if (isFirst) {
            downTime = now;
        }

        pointers.put(pointerId, new Point(x, y));

        List<Integer> idList = new ArrayList<>(pointers.keySet());
        Collections.sort(idList);
        int index = idList.indexOf(pointerId);

        int actionCode;
        if (isFirst) {
            actionCode = MotionEvent.ACTION_DOWN;
        } else {
            actionCode = (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_DOWN;
        }

        MotionEvent event = obtainMotionEvent(downTime, now, actionCode, idList);
        inject(event, "TOUCH_DOWN (ptr=" + pointerId + ", total=" + idList.size() + ")");
    }

    private static synchronized void handleTouchEventMove(int pointerId, float x, float y) {
        Point p = pointers.get(pointerId);
        if (p == null) {
            // Pointer is not active/down - ignore move to prevent orphan events
            return;
        }
        p.x = x;
        p.y = y;

        if (pointers.size() <= 1) {
            // Single pointer (mouse drag or single finger): trigger immediately for ultra-low latency
            triggerTouchEventMove();
        } else {
            if (bufferedMove.contains(pointerId) || bufferedMove.size() == pointers.size()) {
                bufferedMove.clear();
                triggerTouchEventMove();
            } else {
                bufferedMove.add(pointerId);
            }
        }
    }

    private static synchronized void triggerTouchEventMove() {
        if (pointers.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now < downTime) now = downTime;
        List<Integer> idList = new ArrayList<>(pointers.keySet());
        Collections.sort(idList);

        MotionEvent event = obtainMotionEvent(downTime, now, MotionEvent.ACTION_MOVE, idList);
        inject(event, "TOUCH_MOVE (total=" + idList.size() + ")");
    }

    private static synchronized void handleTouchEventUp(int pointerId, float x, float y, boolean isCancel) {
        Point p = pointers.get(pointerId);
        if (p == null) return;

        bufferedMove.clear();

        p.x = x;
        p.y = y;

        List<Integer> idList = new ArrayList<>(pointers.keySet());
        Collections.sort(idList);
        int index = idList.indexOf(pointerId);
        if (index < 0) return;

        int actionCode;
        if (isCancel) {
            actionCode = MotionEvent.ACTION_CANCEL;
        } else if (idList.size() <= 1) {
            actionCode = MotionEvent.ACTION_UP;
        } else {
            actionCode = (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT) | MotionEvent.ACTION_POINTER_UP;
        }

        long now = SystemClock.uptimeMillis();
        if (now < downTime) now = downTime;
        MotionEvent event = obtainMotionEvent(downTime, now, actionCode, idList);
        inject(event, "TOUCH_UP (ptr=" + pointerId + ", remaining=" + (idList.size() - 1) + ")");

        pointers.remove(pointerId);
        if (pointers.isEmpty()) {
            downTime = 0;
            bufferedMove.clear();
        }
    }

    private static synchronized void handleTouchEventCancelAll() {
        if (pointers.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now < downTime) now = downTime;
        List<Integer> idList = new ArrayList<>(pointers.keySet());
        Collections.sort(idList);

        MotionEvent event = obtainMotionEvent(downTime, now, MotionEvent.ACTION_CANCEL, idList);
        inject(event, "TOUCH_CANCEL_ALL");
        pointers.clear();
        bufferedMove.clear();
        isLeftButtonDown = false;
        downTime = 0;
    }

    private static MotionEvent obtainMotionEvent(long downTime, long eventTime, int action, List<Integer> idList) {
        int count = idList.size();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];

        for (int i = 0; i < count; i++) {
            int id = idList.get(i);
            Point p = pointers.get(id);

            props[i] = new MotionEvent.PointerProperties();
            props[i].id = id;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;

            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = (p != null) ? p.x : 0f;
            coords[i].y = (p != null) ? p.y : 0f;
            coords[i].pressure = 1.0f;
            coords[i].size = 1.0f;
        }

        return MotionEvent.obtain(
                downTime, eventTime, action, count, props, coords, 0, 0,
                1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        );
    }

    private static void inject(MotionEvent event, String tag) {
        if (event == null) return;
        SunshineRuntimeManager.getInstance().injectMotionEvent(event);
    }
}
