package com.firedash;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Painter backed by an Android Canvas. */
final class AndroidPainter implements Painter {
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface SERIF = Typeface.create(Typeface.SERIF, Typeface.ITALIC);

    private final Canvas canvas;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    AndroidPainter(Canvas canvas) {
        this.canvas = canvas;
        shape.setStrokeCap(Paint.Cap.ROUND);
        shape.setStrokeJoin(Paint.Join.ROUND);
    }

    private void setText(Font font, float size, float tracking) {
        textPaint.setTypeface(font == Font.BOLD ? BOLD : font == Font.SERIF_ITALIC ? SERIF : REGULAR);
        textPaint.setTextSize(size);
        textPaint.setLetterSpacing(tracking);
    }

    @Override
    public void fillRect(float x, float y, float w, float h, int color) {
        shape.setStyle(Paint.Style.FILL);
        shape.setColor(color);
        canvas.drawRect(x, y, x + w, y + h, shape);
    }

    @Override
    public void fillRoundRect(float x, float y, float w, float h, float r, int color) {
        shape.setStyle(Paint.Style.FILL);
        shape.setColor(color);
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, shape);
    }

    @Override
    public void fillGradient(float x, float y, float w, float h, int top, int bottom) {
        Paint g = new Paint();
        g.setShader(new LinearGradient(0, y, 0, y + h, top, bottom, Shader.TileMode.CLAMP));
        g.setDither(true);
        canvas.drawRect(x, y, x + w, y + h, g);
    }

    @Override
    public void strokeRect(float x, float y, float w, float h, float stroke, int color) {
        shape.setStyle(Paint.Style.STROKE);
        shape.setStrokeWidth(stroke);
        shape.setColor(color);
        float half = stroke / 2;
        canvas.drawRect(x + half, y + half, x + w - half, y + h - half, shape);
    }

    @Override
    public float textWidth(String s, Font font, float size, float tracking) {
        setText(font, size, tracking);
        return textPaint.measureText(s);
    }

    @Override
    public void text(String s, Font font, float size, float tracking, float x, float y, int color) {
        setText(font, size, tracking);
        textPaint.setColor(color);
        canvas.drawText(s, x, y, textPaint);
    }

    @Override
    public void path(Icons.PathData p, float x, float y, float k, Integer fill, Integer stroke, float strokeWidth) {
        path.reset();
        float[] c = p.c;
        int i = 0;
        while (i < c.length) {
            int op = (int) c[i++];
            if (op == Icons.MOVE) { path.moveTo(x + c[i] * k, y + c[i + 1] * k); i += 2; }
            else if (op == Icons.LINE) { path.lineTo(x + c[i] * k, y + c[i + 1] * k); i += 2; }
            else if (op == Icons.CUBIC) {
                path.cubicTo(x + c[i] * k, y + c[i + 1] * k, x + c[i + 2] * k, y + c[i + 3] * k, x + c[i + 4] * k, y + c[i + 5] * k);
                i += 6;
            } else path.close();
        }
        if (fill != null) {
            shape.setStyle(Paint.Style.FILL);
            shape.setColor(fill);
            canvas.drawPath(path, shape);
        }
        if (stroke != null) {
            shape.setStyle(Paint.Style.STROKE);
            shape.setStrokeWidth(strokeWidth);
            shape.setColor(stroke);
            canvas.drawPath(path, shape);
        }
    }

    @Override
    public void circle(float cx, float cy, float r, Integer fill, Integer stroke, float strokeWidth) {
        if (fill != null) {
            shape.setStyle(Paint.Style.FILL);
            shape.setColor(fill);
            canvas.drawCircle(cx, cy, r, shape);
        }
        if (stroke != null) {
            shape.setStyle(Paint.Style.STROKE);
            shape.setStrokeWidth(strokeWidth);
            shape.setColor(stroke);
            canvas.drawCircle(cx, cy, r, shape);
        }
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float strokeWidth, int color) {
        shape.setStyle(Paint.Style.STROKE);
        shape.setStrokeWidth(strokeWidth);
        shape.setColor(color);
        canvas.drawLine(x1, y1, x2, y2, shape);
    }
}
