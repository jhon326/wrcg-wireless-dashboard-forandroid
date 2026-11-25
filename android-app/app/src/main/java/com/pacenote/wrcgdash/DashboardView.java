package com.pacenote.wrcgdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * 指针式汽车速度表：左侧圆形表盘 + 指针，右侧挡位与数字读数。
 */
final class DashboardView extends View {
    interface SettingsGestureListener {
        void onSettingsGesture();
    }

    private static final int BACKGROUND = Color.rgb(5, 7, 8);
    private static final int GREEN = Color.rgb(57, 227, 156);
    private static final int YELLOW = Color.rgb(255, 197, 78);
    private static final int ORANGE = Color.rgb(255, 91, 43);
    private static final int RED = Color.rgb(255, 48, 72);
    private static final int TRACK = Color.rgb(20, 25, 29);
    private static final int RING = Color.rgb(38, 46, 51);
    private static final int WHITE = Color.rgb(238, 243, 246);
    private static final int MUTED = Color.rgb(136, 147, 153);

    /** 表盘起始角与总扫描角（0° 指向右侧，顺时针为正）。 */
    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path needlePath = new Path();
    private final RectF oval = new RectF();
    private final RectF ringRect = new RectF();
    private final Rect textBounds = new Rect();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float speedKph;
    private float rpm;
    private float maxRpm = 8000f;
    private float rpmDisplay;
    private String gear = "N";
    private String connectionState = "OFFLINE";
    private String connectionDetail = "等待电脑";
    private String endpoint = "未配置";
    private boolean receiving;
    private boolean redline;
    private boolean flashOn;
    private boolean flashEnabled = true;
    private boolean noFlashInFifth;
    private boolean noFlashInSixth;
    private boolean flashRunning;
    private boolean needleAnimating;
    private int theme;
    private int[] bandThresholds = {3600, 4800, 6100};
    private int redlineThreshold = 7000;
    private SettingsGestureListener settingsGestureListener;
    private boolean multiTouchGesture;
    private boolean multiTouchTriggered;
    private float multiTouchStartY;

    private final Runnable staleRunnable = () -> {
        receiving = false;
        stopRedline();
        invalidate();
    };

    private final Runnable flashRunnable = new Runnable() {
        @Override
        public void run() {
            if (!redline || !shouldFlash()) {
                flashRunning = false;
                flashOn = true;
                invalidate();
                return;
            }
            flashOn = !flashOn;
            invalidate();
            handler.postDelayed(this, 55L);
        }
    };

    private final Runnable needleRunnable = new Runnable() {
        @Override
        public void run() {
            float delta = rpm - rpmDisplay;
            if (Math.abs(delta) < 8f) {
                rpmDisplay = rpm;
                needleAnimating = false;
                invalidate();
                return;
            }
            rpmDisplay += delta * 0.22f;
            invalidate();
            postOnAnimation(this);
        }
    };

    DashboardView(Context context) {
        super(context);
        setBackgroundColor(BACKGROUND);
        setClickable(true);
        setLongClickable(true);
        setKeepScreenOn(true);

        Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        text.setTypeface(condensed);
        big.setTypeface(condensed);
        needlePaint.setStyle(Paint.Style.FILL);
        hubPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
    }

    void setTelemetry(float newSpeedKph, float newRpm, float newMaxRpm, String newGear) {
        speedKph = Math.max(0, newSpeedKph);
        rpm = Math.max(0, newRpm);
        maxRpm = Math.max(1000, newMaxRpm);
        gear = newGear == null || newGear.isEmpty() ? "N" : newGear;
        receiving = true;
        handler.removeCallbacks(staleRunnable);
        handler.postDelayed(staleRunnable, 700L);

        boolean nextRedline = rpm >= redlineThreshold;
        if (nextRedline && !redline) {
            redline = true;
            flashOn = true;
        } else if (!nextRedline) {
            stopRedline();
        }
        updateFlashAnimation();
        startNeedleAnimation();
    }

    void setConnectionState(String state, String detail) {
        connectionState = state;
        connectionDetail = detail == null ? "" : detail;
        if (!"LIVE".equals(state)) {
            receiving = false;
            stopRedline();
        }
        invalidate();
    }

    void setEndpoint(String value) {
        endpoint = value == null ? "未配置" : value;
        invalidate();
    }

    void setSettingsGestureListener(SettingsGestureListener listener) {
        settingsGestureListener = listener;
    }

    void configure(
        int[] thresholds,
        int newRedlineThreshold,
        boolean redlineFlash,
        boolean newNoFlashInFifth,
        boolean newNoFlashInSixth,
        int newTheme
    ) {
        if (thresholds != null && thresholds.length == 3) {
            bandThresholds = thresholds.clone();
        }
        redlineThreshold = Math.max(1000, newRedlineThreshold);
        flashEnabled = redlineFlash;
        noFlashInFifth = newNoFlashInFifth;
        noFlashInSixth = newNoFlashInSixth;
        theme = Math.max(0, Math.min(1, newTheme));
        if (!flashEnabled) {
            handler.removeCallbacks(flashRunnable);
            flashOn = true;
            flashRunning = false;
        }
        updateRedlineState();
        invalidate();
    }

    private void updateRedlineState() {
        boolean nextRedline = receiving && rpm >= redlineThreshold;
        if (nextRedline) {
            redline = true;
            flashOn = true;
        } else {
            stopRedline();
        }
        updateFlashAnimation();
    }

    private void stopRedline() {
        redline = false;
        flashOn = true;
        flashRunning = false;
        handler.removeCallbacks(flashRunnable);
    }

    private boolean shouldFlash() {
        return flashEnabled
            && !(noFlashInFifth && "5".equals(gear))
            && !(noFlashInSixth && "6".equals(gear));
    }

    private void updateFlashAnimation() {
        if (redline && shouldFlash()) {
            if (!flashRunning) {
                flashRunning = true;
                handler.removeCallbacks(flashRunnable);
                handler.post(flashRunnable);
            }
        } else {
            flashRunning = false;
            flashOn = true;
            handler.removeCallbacks(flashRunnable);
        }
    }

    private void startNeedleAnimation() {
        if (needleAnimating) return;
        needleAnimating = true;
        postOnAnimation(needleRunnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float radius = Math.min(height * 0.43f, width * 0.27f);
        float centerX = Math.max(radius * 1.06f, Math.min(width * 0.31f, radius * 1.16f));
        float centerY = height * 0.50f;

        drawBackdrop(canvas, width, height, centerX, centerY, radius);
        drawDial(canvas, centerX, centerY, radius);
        drawGear(canvas, centerX, centerY, radius);
        drawNeedle(canvas, centerX, centerY, radius);
        drawReadout(canvas, width, height, centerX + radius * 1.20f);
        drawStatus(canvas, width, height);
    }

    private void drawBackdrop(Canvas canvas, float width, float height, float cx, float cy, float radius) {
        glowPaint.setShader(new RadialGradient(
            cx, cy, radius * 1.7f,
            new int[]{Color.argb(46, 255, 91, 43), Color.argb(0, 255, 91, 43)},
            null,
            Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, radius * 1.7f, glowPaint);

        glowPaint.setShader(new RadialGradient(
            width * 0.5f, height * 1.1f, width * 0.8f,
            new int[]{Color.argb(24, 0, 0, 0), Color.argb(0, 0, 0, 0)},
            null,
            Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0, 0, width, height, glowPaint);
        glowPaint.setShader(null);
    }

    private void drawDial(Canvas canvas, float cx, float cy, float radius) {
        // 表盘底色
        paint.setShader(new RadialGradient(
            cx, cy, radius,
            new int[]{Color.rgb(13, 17, 20), Color.rgb(7, 10, 12)},
            null,
            Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        // 外圈
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.5f, radius * 0.008f));
        stroke.setColor(RING);
        canvas.drawCircle(cx, cy, radius, stroke);

        float bandRadius = radius * 0.86f;
        float bandWidth = radius * 0.070f;
        ringRect.set(cx - bandRadius, cy - bandRadius, cx + bandRadius, cy + bandRadius);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(bandWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);

        // 未点亮底环
        paint.setColor(TRACK);
        canvas.drawArc(ringRect, START_ANGLE, SWEEP_ANGLE, false, paint);

        // 分段着色
        float span1 = angleFor(bandThresholds[0]);
        float span2 = angleFor(bandThresholds[1]);
        float span3 = angleFor(bandThresholds[2]);
        float end = START_ANGLE + SWEEP_ANGLE;

        paint.setColor(theme == 1 ? Color.rgb(55, 209, 255) : GREEN);
        canvas.drawArc(ringRect, START_ANGLE, span1 - START_ANGLE, false, paint);
        paint.setColor(YELLOW);
        canvas.drawArc(ringRect, span1, span2 - span1, false, paint);
        paint.setColor(ORANGE);
        canvas.drawArc(ringRect, span2, span3 - span2, false, paint);

        int redColor = redline ? (flashOn ? RED : Color.rgb(126, 8, 21)) : RED;
        paint.setColor(redColor);
        canvas.drawArc(ringRect, span3, end - span3, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // 红转高亮
        if (redline) {
            stroke.setStrokeWidth(bandWidth * 1.9f);
            stroke.setColor(Color.argb(flashOn ? 90 : 30, 255, 48, 72));
            canvas.drawArc(ringRect, span3, end - span3, false, stroke);
        }
        stroke.setStyle(Paint.Style.STROKE);

        drawTicks(canvas, cx, cy, radius);
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float radius) {
        int step = maxRpm <= 8000f ? 250 : 500;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

        for (float value = 0; value <= maxRpm + 1f; value += step) {
            boolean major = Math.abs(value % 1000f) < 1f;
            float angle = (float) Math.toRadians(angleFor(value));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            float inner = radius * (major ? 0.900f : 0.925f);
            float outer = radius * 0.975f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(major ? radius * 0.016f : radius * 0.008f);
            paint.setColor(major ? Color.rgb(196, 206, 212) : Color.rgb(92, 103, 110));
            canvas.drawLine(
                cx + cos * inner, cy + sin * inner,
                cx + cos * outer, cy + sin * outer,
                paint
            );

            if (major) {
                text.setTextSize(radius * 0.115f);
                text.setColor(Color.rgb(178, 189, 195));
                String label = String.valueOf(Math.round(value / 1000f));
                float labelRadius = radius * 0.775f;
                text.getTextBounds(label, 0, label.length(), textBounds);
                canvas.drawText(
                    label,
                    cx + cos * labelRadius,
                    cy + sin * labelRadius - textBounds.exactCenterY(),
                    text
                );
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawNeedle(Canvas canvas, float cx, float cy, float radius) {
        float angle = (float) Math.toRadians(angleFor(rpmDisplay));
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);

        float tip = radius * 0.80f;
        float tail = radius * 0.16f;
        float halfWidth = radius * 0.026f;

        needlePath.reset();
        needlePath.moveTo(cx + cos * tip, cy + sin * tip);
        needlePath.lineTo(cx - sin * halfWidth, cy + cos * halfWidth);
        needlePath.lineTo(cx - cos * tail, cy - sin * tail);
        needlePath.lineTo(cx + sin * halfWidth, cy - cos * halfWidth);
        needlePath.close();

        int needleColor = redline ? RED : (theme == 1 ? Color.rgb(55, 209, 255) : ORANGE);
        needlePaint.setColor(needleColor);
        needlePaint.setShadowLayer(radius * 0.045f, 0f, 0f,
            Color.argb(redline ? 200 : 130, Color.red(needleColor), Color.green(needleColor), Color.blue(needleColor)));
        canvas.drawPath(needlePath, needlePaint);
        needlePaint.clearShadowLayer();

        // 指针高光
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(radius * 0.012f);
        paint.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawLine(
            cx + cos * radius * 0.10f, cy + sin * radius * 0.10f,
            cx + cos * radius * 0.68f, cy + sin * radius * 0.68f,
            paint
        );

        // 中心轴
        hubPaint.setColor(Color.rgb(12, 16, 19));
        canvas.drawCircle(cx, cy, radius * 0.085f, hubPaint);
        hubPaint.setColor(Color.rgb(48, 57, 63));
        canvas.drawCircle(cx, cy, radius * 0.070f, hubPaint);
        hubPaint.setColor(needleColor);
        canvas.drawCircle(cx, cy, radius * 0.032f, hubPaint);
    }

    private void drawGear(Canvas canvas, float cx, float cy, float radius) {
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        big.setTextSize(radius * 0.50f);
        big.setColor(redline ? Color.rgb(255, 72, 88) : Color.rgb(216, 224, 229));
        big.getTextBounds(gear, 0, gear.length(), textBounds);
        float baseline = cy + radius * 0.60f - textBounds.exactCenterY();
        canvas.drawText(gear, cx, baseline, big);
    }

    private void drawReadout(Canvas canvas, float width, float height, float left) {
        float columnWidth = width - left - height * 0.05f;
        if (columnWidth <= 0) return;
        float centerX = left + columnWidth / 2f;

        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(height * 0.036f);
        text.setColor(MUTED);
        text.setLetterSpacing(0.24f);
        text.getTextBounds("SPEED", 0, 5, textBounds);
        canvas.drawText("SPEED", centerX, height * 0.315f, text);
        text.setLetterSpacing(0f);

        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        big.setTextSize(height * 0.30f);
        big.setColor(WHITE);
        String speed = String.format(java.util.Locale.US, "%d", Math.round(speedKph));
        big.getTextBounds(speed, 0, speed.length(), textBounds);
        canvas.drawText(speed, centerX, height * 0.545f, big);

        text.setTextSize(height * 0.042f);
        text.setColor(MUTED);
        text.setLetterSpacing(0.18f);
        canvas.drawText("KM/H", centerX, height * 0.615f, text);
        text.setLetterSpacing(0f);

        text.setTextSize(height * 0.040f);
        text.setColor(Color.rgb(196, 206, 212));
        String rpmLine = Math.round(rpm) + " RPM";
        canvas.drawText(rpmLine, centerX, height * 0.700f, text);

        if (!receiving) {
            text.setTextSize(height * 0.032f);
            text.setColor(MUTED);
            canvas.drawText(receiving ? "" : "等待遥测数据", centerX, height * 0.760f, text);
        }
    }

    private void drawStatus(Canvas canvas, float width, float height) {
        float size = Math.max(10f, height * 0.030f);
        int dotColor = "LIVE".equals(connectionState)
            ? GREEN
            : "CONNECT".equals(connectionState) ? YELLOW : RED;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(dotColor);
        float dotRadius = size * 0.26f;
        float dotX = size * 0.7f;
        float dotY = height - size * 0.85f;
        canvas.drawCircle(dotX, dotY, dotRadius, paint);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextSize(size);
        text.setColor(Color.rgb(142, 153, 159));
        String label = connectionState + "  " + endpoint;
        canvas.drawText(label, dotX + dotRadius * 2.1f, dotY + size * 0.35f, text);
    }

    private float angleFor(float value) {
        if (maxRpm <= 0f) return START_ANGLE;
        float ratio = Math.max(0f, Math.min(1f, value / maxRpm));
        return START_ANGLE + SWEEP_ANGLE * ratio;
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() >= 2) {
                float averageY = (event.getY(0) + event.getY(1)) / 2f;
                if (!multiTouchGesture) {
                    multiTouchGesture = true;
                    multiTouchTriggered = false;
                    multiTouchStartY = averageY;
                } else if (!multiTouchTriggered && multiTouchStartY - averageY > getHeight() * 0.12f) {
                    multiTouchTriggered = true;
                    if (settingsGestureListener != null) settingsGestureListener.onSettingsGesture();
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            multiTouchGesture = false;
            multiTouchTriggered = false;
        }
        return super.onTouchEvent(event);
    }
}
