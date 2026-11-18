package com.firedash;

/** Drawing surface used by Renderer. Coordinates and sizes are in pixels. */
interface Painter {
    enum Font { REGULAR, BOLD, SERIF_ITALIC }

    void fillRect(float x, float y, float w, float h, int color);

    void strokeRect(float x, float y, float w, float h, float stroke, int color);

    /** Width of text in pixels. tracking is extra letter spacing in ems. */
    float textWidth(String s, Font font, float size, float tracking);

    /** Draws text with its left end at x and baseline at y. */
    void text(String s, Font font, float size, float tracking, float x, float y, int color);

    /** Fills and/or strokes a path given in a 64x64 design space, placed at (x, y) and scaled by k. */
    void path(Icons.PathData p, float x, float y, float k, Integer fill, Integer stroke, float strokeWidth);

    void circle(float cx, float cy, float r, Integer fill, Integer stroke, float strokeWidth);

    void line(float x1, float y1, float x2, float y2, float strokeWidth, int color);
}
