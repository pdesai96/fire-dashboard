package com.firedash;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

/** Full-screen view that hands drawing to Renderer and remembers where it was touched. */
final class DashboardView extends View {
    final Renderer renderer = new Renderer();
    float lastX = -1, lastY = -1;

    DashboardView(Context context) {
        super(context);
        setKeepScreenOn(true);
        setClickable(true);
        setLongClickable(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderer.nowMs = System.currentTimeMillis();
        renderer.draw(new AndroidPainter(canvas), getWidth(), getHeight());
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_UP) {
            lastX = e.getX();
            lastY = e.getY();
        }
        return super.onTouchEvent(e);
    }

    /** The action under the last touch, or null. */
    String actionAtLastTouch() {
        for (int i = renderer.hits.size() - 1; i >= 0; i--) {
            Renderer.Hit h = renderer.hits.get(i);
            if (h.contains(lastX, lastY)) return h.action;
        }
        return null;
    }
}
