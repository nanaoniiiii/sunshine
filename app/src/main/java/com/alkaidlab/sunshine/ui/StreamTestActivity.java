package com.alkaidlab.sunshine.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

/** Explicitly launched visual diagnostic. Contains no audio source or playback code. */
public final class StreamTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(new ProbeView());
    }
    final class ProbeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String touch = "Touch: waiting";
        private long frames;
        private boolean animate = getIntent().getBooleanExtra("animate", true);
        ProbeView() { super(StreamTestActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(18, 30, 50));
            paint.setColor(Color.CYAN); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(8);
            canvas.drawRect(20, 20, getWidth() - 20, getHeight() - 20, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(getWidth() / 28f); paint.setColor(Color.WHITE);
            canvas.drawText("SUNSHINE SILENT VIDEO TEST", 40, 100, paint);
            canvas.drawText("Frame: " + ++frames + "   Audio: OFF", 40, 180, paint);
            canvas.drawText(touch, 40, 260, paint);
            paint.setColor(Color.GREEN);
            float x = 80 + (animate ? SystemClock.uptimeMillis() / 10 % Math.max(1, getWidth() - 160) : 0);
            canvas.drawCircle(x, getHeight() / 2f, 40, paint);
            if (animate) postInvalidateOnAnimation();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            touch = "Touch " + event.getActionMasked() + ": " + Math.round(event.getX()) + "," + Math.round(event.getY());
            Log.i("SunshineVisualTest", touch + " size=" + getWidth() + "x" + getHeight());
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            invalidate(); return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
