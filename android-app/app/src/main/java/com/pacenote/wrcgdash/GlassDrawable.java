package com.pacenote.wrcgdash;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 毛玻璃质感背景：半透明渐变填充 + 高光描边 + 按压态增亮。
 * 通过 View 的状态（pressed）自动切换，配合外层的缩放动画形成点击反馈。
 */
final class GlassDrawable extends Drawable {

    private final float cornerRadius;
    private final int tint;
    private final float strokeWidth;
    private final int baseTintAlpha;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean pressed;

    GlassDrawable(float radiusPx, int tintColor, float strokeWidthPx, int tintAlpha) {
        this.cornerRadius = radiusPx;
        this.tint = tintColor;
        this.strokeWidth = strokeWidthPx;
        this.baseTintAlpha = tintAlpha;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokeWidthPx);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean nowPressed = false;
        for (int value : state) {
            if (value == android.R.attr.state_pressed) {
                nowPressed = true;
                break;
            }
        }
        if (nowPressed == pressed) return false;
        pressed = nowPressed;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        rect.set(bounds);
        float radius = Math.min(cornerRadius, Math.min(rect.width(), rect.height()) / 2f);

        int tintAlpha = pressed ? Math.min(255, baseTintAlpha + 55) : baseTintAlpha;
        int highlightAlpha = pressed ? 34 : Math.max(14, baseTintAlpha / 4);

        int topColor = Color.argb(
            tintAlpha,
            Color.red(tint),
            Color.green(tint),
            Color.blue(tint)
        );
        int bottomColor = Color.argb(highlightAlpha, 255, 255, 255);

        fill.setShader(new LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            topColor, bottomColor,
            Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(rect, radius, radius, fill);

        int borderAlpha = pressed ? 210 : 78;
        stroke.setColor(Color.argb(
            borderAlpha,
            Math.min(255, Color.red(tint) + 60),
            Math.min(255, Color.green(tint) + 60),
            Math.min(255, Color.blue(tint) + 60)
        ));
        float inset = strokeWidth / 2f;
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, radius - inset, radius - inset, stroke);
        rect.inset(-inset, -inset);

        // 顶部高光线，强化玻璃感
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, strokeWidth * 0.6f));
        edge.setColor(Color.argb(pressed ? 70 : 40, 255, 255, 255));
        canvas.drawLine(
            rect.left + radius * 0.6f,
            rect.top + strokeWidth,
            rect.right - radius * 0.6f,
            rect.top + strokeWidth,
            edge
        );
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
