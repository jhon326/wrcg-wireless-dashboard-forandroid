package com.pacenote.wrcgdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

final class DashboardView extends View {
    interface SettingsGestureListener {
        void onSettingsGesture();
    }

    private static final int BACKGROUND = Color.rgb(5, 7, 8);
    private static final int INACTIVE = Color.rgb(16, 22, 25);
    private static final int GREEN = Color.rgb(57, 227, 156);
    private static final int YELLOW = Color.rgb(255, 197, 78);
    private static final int ORANGE = Color.rgb(255, 91, 43);
    private static final int RED = Color.rgb(255, 48, 72);
    private static final int GREEN_DARK = Color.rgb(12, 109, 72);
    private static final int YELLOW_DARK = Color.rgb(142, 101, 18);
    private static final int ORANGE_DARK = Color.rgb(143, 39, 13);
    private static final int RED_DARK = Color.rgb(126, 8, 21);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();
    private final RectF speedBox = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float speedKph;
    private float rpm;
    private float maxRpm = 8000f;
    private String gear = "N";
    private String connectionState = "CONNECT";
    private String connectionDetail = "等待电脑";
    private String endpoint = "未配置";
    private boolean receiving;
    private boolean redline;
    private boolean flashOn;
    private boolean flashEnabled = true;
    private boolean noFlashInFifth;
    private boolean noFlashInSixth;
    private boolean flashRunning;
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
                return;
            }
            flashOn = !flashOn;
            invalidate();
            handler.postDelayed(this, 55L);
        }
    };

    DashboardView(Context context) {
        super(context);
        setBackgroundColor(BACKGROUND);
        setClickable(true);
        setLongClickable(true);
        setKeepScreenOn(true);
        Typeface condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        textPaint.setTypeface(condensedBold);
        gearPaint.setTypeface(condensedBold);
        gearPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        accentPaint.setColor(ORANGE);
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
        postInvalidateOnAnimation();
    }

    void setConnectionState(String state, String detail) {
        connectionState = state;
        connectionDetail = detail;
        if (!"LIVE".equals(state)) {
            receiving = false;
            stopRedline();
        }
        invalidate();
    }

    void setEndpoint(String value) {
        endpoint = value;
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
        flashOn = false;
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        drawBands(canvas, width, height);
        drawRpm(canvas, width, height);
        drawGear(canvas, width, height);
        drawSpeed(canvas, width, height);
        drawConnection(canvas, width, height);
    }

    private void drawBands(Canvas canvas, float width, float height) {
        float columnWidth = width / 6f;
        int activePairs = redline
            ? 3
            : rpm >= bandThresholds[2] ? 3
            : rpm >= bandThresholds[1] ? 2
            : rpm >= bandThresholds[0] ? 1
            : 0;
        for (int index = 0; index < 6; index += 1) {
            int pair = Math.min(index, 5 - index);
            boolean active = activePairs > pair;
            int light;
            if (redline) {
                light = flashOn ? RED : RED_DARK;
            } else if (pair == 0) {
                light = theme == 1 ? Color.rgb(55, 209, 255) : GREEN;
            } else if (pair == 1) {
                light = YELLOW;
            } else {
                light = ORANGE;
            }
            float left = index * columnWidth;
            paint.setColor(active ? light : INACTIVE);
            canvas.drawRect(left, 0, left + columnWidth, height, paint);
            paint.setColor(Color.BLACK);
            canvas.drawRect(left + columnWidth - 1f, 0, left + columnWidth, height, paint);
        }
    }

    private void drawRpm(Canvas canvas, float width, float height) {
        float size = Math.max(15f, height * 0.044f);
        textPaint.setTextSize(size);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        String value = Math.round(rpm) + " RPM";
        textPaint.getTextBounds(value, 0, value.length(), textBounds);
        float baseline = height * 0.075f - textBounds.exactCenterY();
        canvas.drawText(value, width / 2f, baseline, textPaint);
    }

    private void drawGear(Canvas canvas, float width, float height) {
        gearPaint.setTextAlign(Paint.Align.CENTER);
        gearPaint.setTextSize(100f);
        gearPaint.getTextBounds(gear, 0, gear.length(), textBounds);
        float targetHeight = height * 0.80f;
        float scale = targetHeight / Math.max(1f, textBounds.height());
        gearPaint.setTextSize(100f * scale);
        gearPaint.getTextBounds(gear, 0, gear.length(), textBounds);
        float baseline = height / 2f - textBounds.exactCenterY();
        gearPaint.setColor(redline ? Color.rgb(255, 72, 88) : theme == 1 ? Color.WHITE : ORANGE);
        gearPaint.setShadowLayer(
            Math.max(8f, targetHeight * 0.042f),
            0f,
            Math.max(4f, targetHeight * 0.014f),
            Color.BLACK
        );
        canvas.drawText(gear, width / 2f, baseline, gearPaint);
        gearPaint.clearShadowLayer();
    }

    private void drawSpeed(Canvas canvas, float width, float height) {
        float size = Math.max(21f, Math.min(height * 0.083f, width * 0.038f));
        textPaint.setTextSize(size);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        String value = String.format(java.util.Locale.US, "%03d", Math.round(speedKph));
        String unit = " KM/H";
        textPaint.getTextBounds(value + unit, 0, value.length() + unit.length(), textBounds);
        float boxWidth = textBounds.width() + size * 1.4f;
        float boxHeight = textBounds.height() + size * 0.6f;
        float centerX = width / 2f;
        float centerY = height - boxHeight * 0.78f;
        speedBox.set(centerX - boxWidth / 2f, centerY - boxHeight / 2f,
            centerX + boxWidth / 2f, centerY + boxHeight / 2f);
        paint.setColor(Color.argb(190, 4, 6, 7));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(speedBox, boxHeight / 2f, boxHeight / 2f, paint);

        textPaint.getTextBounds(value, 0, value.length(), textBounds);
        float baseline = centerY - textBounds.exactCenterY();
        float combinedWidth = textPaint.measureText(value + unit);
        float startX = centerX - combinedWidth / 2f;
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, startX, baseline, textPaint);
        Paint unitPaint = new Paint(textPaint);
        unitPaint.setTextSize(size * 0.32f);
        unitPaint.setColor(Color.rgb(132, 143, 147));
        canvas.drawText(unit.trim(), startX + textPaint.measureText(value) + size * 0.28f,
            baseline - size * 0.06f, unitPaint);
    }

    private void drawConnection(Canvas canvas, float width, float height) {
        float size = Math.max(9f, height * 0.027f);
        paint.setColor("LIVE".equals(connectionState) ? GREEN : "CONNECT".equals(connectionState) ? YELLOW : RED);
        canvas.drawCircle(size * 0.5f, height - size * 0.9f, size * 0.27f, paint);
        textPaint.setTextSize(size);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(Color.rgb(150, 161, 165));
        canvas.drawText(connectionState + "  " + endpoint, size * 1.15f, height - size * 0.65f, textPaint);
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
