package com.firedash;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/** Full-screen view that hands drawing to Renderer. */
final class DashboardView extends View {
    final Renderer renderer = new Renderer();

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
}
