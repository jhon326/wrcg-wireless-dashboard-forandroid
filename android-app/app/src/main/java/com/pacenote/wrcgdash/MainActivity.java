package com.pacenote.wrcgdash;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements TelemetryClient.Listener {
    private static final String PREFS = "wrcg_dashboard";
    private static final String PREF_SERVER = "server_url";
    private static final String PREF_THEME = "theme";
    private static final String PREF_BAND_1 = "band_threshold_1";
    private static final String PREF_BAND_2 = "band_threshold_2";
    private static final String PREF_BAND_3 = "band_threshold_3";
    private static final String PREF_REDLINE = "redline_threshold";
    private static final String PREF_FLASH = "redline_flash";
    private static final String PREF_NO_FLASH_FIFTH = "no_flash_5";
    private static final String PREF_NO_FLASH_SIXTH = "no_flash_6";
    private static final String DEFAULT_SERVER = "";
    private static final int DEFAULT_BAND_1 = 3600;
    private static final int DEFAULT_BAND_2 = 4800;
    private static final int DEFAULT_BAND_3 = 6100;
    private static final int DEFAULT_REDLINE = 7000;

    private static final int BACKGROUND = Color.rgb(5, 7, 8);
    private static final int ORANGE = Color.rgb(255, 91, 43);
    private static final int TEXT_MAIN = Color.rgb(236, 241, 244);
    private static final int TEXT_MUTED = Color.rgb(140, 151, 157);
    private static final int GLASS_NEUTRAL = Color.rgb(150, 162, 170);

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final List<Long> latencySamples = new ArrayList<>();

    private DashboardView dashboard;
    private TelemetryClient client;
    private SharedPreferences preferences;
    private String serverUrl;
    private FrameLayout root;
    private FrameLayout settingsPanel;
    private View settingsHome;
    private View activeSubpage;
    private EditText serverInput;
    private TextView connectionStatusView;
    private TextView latencyStatusView;
    private OnBackInvokedCallback settingsBackCallback;
    private OnBackInvokedCallback defaultBackCallback;
    private boolean settingsVisible;
    private boolean latencyCheckActive;
    private String lastConnectionState = "尚未连接";
    private long lastLatencyMs = -1L;

    private View homeView;
    private EditText homeAddressInput;
    private TextView homeStatus;
    private TextView homeStatusDetail;
    private boolean dashboardVisible;

    private final Runnable latencyHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (client != null && !latencyCheckActive) client.ping();
            uiHandler.postDelayed(this, 3000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        serverUrl = preferences.getString(PREF_SERVER, null);

        dashboard = new DashboardView(this);
        dashboard.setSettingsGestureListener(this::openSettings);
        applyDashboardSettings();
        dashboard.setVisibility(View.GONE);

        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.addView(dashboard, fullScreenParams());

        homeView = buildHome();
        root.addView(homeView, fullScreenParams());

        settingsPanel = buildSettingsShell();
        settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel, fullScreenParams());
        setContentView(root);

        updateHomeStatus("尚未连接", serverUrl == null ? "请填写电脑的局域网地址" : "上次连接：" + serverUrl);
        applyImmersive(false);
        registerDefaultBack();

        if (getIntent().getBooleanExtra("open_settings", false)) {
            uiHandler.postDelayed(this::openSettings, 350L);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("open_settings", false)) openSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive(dashboardVisible || settingsVisible);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        unregisterSettingsBack();
        if (client != null) client.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive(dashboardVisible || settingsVisible);
    }

    // ------------------------------------------------------------------
    // 竖屏主页
    // ------------------------------------------------------------------

    private View buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.rgb(12, 16, 20), BACKGROUND}
        );
        scroll.setBackground(background);
        scroll.setClipToPadding(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22), dp(54), dp(22), dp(30));
        scroll.addView(column, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView kicker = new TextView(this);
        kicker.setText("PACENOTE");
        kicker.setTextSize(12);
        kicker.setLetterSpacing(0.32f);
        kicker.setTextColor(ORANGE);
        kicker.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(kicker);

        TextView title = new TextView(this);
        title.setText("WRCG 无线仪表");
        title.setTextSize(30);
        title.setTextColor(TEXT_MAIN);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(6);
        column.addView(title, titleParams);

        TextView subtitle = new TextView(this);
        subtitle.setText("手机留在竖屏操作，开启记速器后自动横屏显示仪表。");
        subtitle.setTextSize(13);
        subtitle.setTextColor(TEXT_MUTED);
        subtitle.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subParams.topMargin = dp(8);
        column.addView(subtitle, subParams);

        column.addView(buildAddressCard(), cardLayoutParams(22));

        TextView launch = createGlassButton("开启记速器", true);
        launch.setTextSize(17);
        launch.setOnClickListener(view -> enterDashboard());
        column.addView(launch, cardLayoutParams(24));

        TextView settingsButton = createGlassButton("设置", false);
        settingsButton.setOnClickListener(view -> openSettings());
        column.addView(settingsButton, cardLayoutParams(10));

        homeStatus = new TextView(this);
        homeStatus.setTextSize(13);
        homeStatus.setTextColor(TEXT_MAIN);
        homeStatus.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(26);
        column.addView(homeStatus, statusParams);

        homeStatusDetail = new TextView(this);
        homeStatusDetail.setTextSize(12);
        homeStatusDetail.setTextColor(TEXT_MUTED);
        homeStatusDetail.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(4);
        column.addView(homeStatusDetail, detailParams);

        return scroll;
    }

    private View buildAddressCard() {
        LinearLayout card = createCard();

        TextView label = new TextView(this);
        label.setText("电脑局域网地址");
        label.setTextSize(12);
        label.setTextColor(TEXT_MUTED);
        label.setLetterSpacing(0.12f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(label);

        homeAddressInput = new EditText(this);
        homeAddressInput.setSingleLine(true);
        homeAddressInput.setBackground(null);
        homeAddressInput.setHint("192.168.1.100:8787");
        homeAddressInput.setHintTextColor(Color.rgb(88, 98, 104));
        homeAddressInput.setTextColor(TEXT_MAIN);
        homeAddressInput.setTextSize(20);
        homeAddressInput.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        homeAddressInput.setInputType(
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        homeAddressInput.setPadding(0, dp(10), 0, dp(8));
        if (serverUrl != null && !serverUrl.isEmpty()) {
            homeAddressInput.setText(serverUrl);
        }
        card.addView(homeAddressInput);

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(40, 255, 255, 255));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, dp(1))
        );
        card.addView(divider, dividerParams);

        TextView hint = new TextView(this);
        hint.setText("只填 IP 也行，会自动补全 ws:// 和 8787 端口");
        hint.setTextSize(11);
        hint.setTextColor(Color.rgb(120, 131, 137));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hintParams.topMargin = dp(9);
        card.addView(hint, hintParams);
        return card;
    }

    private void enterDashboard() {
        String raw = homeAddressInput == null ? "" : homeAddressInput.getText().toString();
        String normalized = normalizeServerUrl(raw);
        if (normalized == null) {
            Toast.makeText(this, "请填写电脑的局域网地址，例如 192.168.1.100", Toast.LENGTH_SHORT).show();
            shake(homeAddressInput);
            return;
        }
        hideKeyboard();
        serverUrl = normalized;
        preferences.edit().putString(PREF_SERVER, normalized).apply();
        if (homeAddressInput != null) homeAddressInput.setText(normalized);

        applyDashboardSettings();
        connect(normalized);
        updateHomeStatus("正在连接 " + hostLabel(normalized), normalized);

        dashboardVisible = true;
        homeView.setVisibility(View.GONE);
        dashboard.setVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        applyImmersive(true);
    }

    private void exitDashboard() {
        dashboardVisible = false;
        dashboard.setVisibility(View.GONE);
        homeView.setVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        applyImmersive(false);
        if (client != null) {
            client.shutdown();
            client = null;
        }
        updateHomeStatus("等待连接", "回到竖屏主页");
    }

    private void updateHomeStatus(String title, String detail) {
        if (homeStatus != null) homeStatus.setText(title);
        if (homeStatusDetail != null) homeStatusDetail.setText(detail == null ? "" : detail);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void shake(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setTranslationX(0f);
        view.animate()
            .translationX(dp(9))
            .setDuration(60L)
            .withEndAction(() -> view.animate()
                .translationX(-dp(9))
                .setDuration(60L)
                .withEndAction(() -> view.animate()
                    .translationX(0f)
                    .setDuration(60L)
                    .start())
                .start())
            .start();
    }

    // ------------------------------------------------------------------
    // 毛玻璃控件
    // ------------------------------------------------------------------

    private TextView createGlassButton(String label, boolean primary) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(primary ? 16 : 14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(TEXT_MAIN);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        int tint = primary ? ORANGE : GLASS_NEUTRAL;
        button.setBackground(new GlassDrawable(dp(18), tint, dp(1.2f), primary ? 108 : 60));
        button.setPadding(dp(18), dp(17), dp(18), dp(17));
        if (primary) {
            button.setShadowLayer(dp(18), 0f, dp(6), Color.argb(110, 255, 91, 43));
        }
        applyPressFeedback(button);
        return button;
    }

    private void applyPressFeedback(View view) {
        view.setOnTouchListener((target, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    target.animate().cancel();
                    target.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .setDuration(90L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    target.animate().cancel();
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(190L)
                        .setInterpolator(new OvershootInterpolator(2.4f))
                        .start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------
    // 设置面板
    // ------------------------------------------------------------------

    private FrameLayout buildSettingsShell() {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.rgb(7, 10, 12));
        settingsHome = buildSettingsHome();
        panel.addView(settingsHome, fullScreenParams());
        return panel;
    }

    private View buildSettingsHome() {
        LinearLayout page = buildPageShell("设置", null);
        LinearLayout content = (LinearLayout) page.findViewWithTag("content");

        addNavigationRow(content, "主题", this::openThemePage);
        addNavigationRow(content, "转数与效果", this::openEffectsPage);
        addNavigationRow(content, "连接设置", this::openConnectionPage);
        addNavigationRow(content, "延迟检查", this::openLatencyPage);

        addSectionTitle(content, "关于软件");
        TextView about = createHelp(
            "PACENOTE WRCG 0.4.0\n"
                + "原生 Android Canvas 无线拉力仪表\n"
                + "指针式速度表 · WebSocket 30 Hz"
        );
        LinearLayout aboutCard = createCard();
        aboutCard.addView(about);
        content.addView(aboutCard);
        return page;
    }

    private LinearLayout buildPageShell(String title, Runnable onBack) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(7, 10, 12));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(7), dp(18), dp(7));
        page.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(58)
        ));

        if (onBack != null) {
            TextView back = createGlassButton("返回", false);
            back.setTextSize(13);
            back.setOnClickListener(view -> onBack.run());
            LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            );
            back.setPadding(dp(16), 0, dp(16), 0);
            header.addView(back, backParams);
        }

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(24);
        heading.setTextColor(Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(14), 0, 0, 0);
        header.addView(heading, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));

        if (onBack == null) {
            TextView close = createGlassButton("关闭", false);
            close.setTextSize(13);
            close.setPadding(dp(16), 0, dp(16), 0);
            close.setOnClickListener(view -> closeSettings());
            header.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            ));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        LinearLayout content = new LinearLayout(this);
        content.setTag("content");
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), dp(26));
        scroll.addView(content, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return page;
    }

    private void addNavigationRow(LinearLayout parent, String title, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(17), 0, dp(14), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> action.run());
        row.setBackground(new GlassDrawable(dp(14), GLASS_NEUTRAL, dp(1f), 48));
        applyPressFeedback(row);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(17);
        titleView.setTextColor(Color.rgb(229, 235, 237));
        row.addView(titleView, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(28);
        arrow.setTextColor(ORANGE);
        row.addView(arrow);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(57)
        );
        params.topMargin = dp(9);
        parent.addView(row, params);
    }

    private void openThemePage() {
        LinearLayout page = buildPageShell("主题", this::closeSubpage);
        LinearLayout content = (LinearLayout) page.findViewWithTag("content");
        Spinner themeSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            new String[]{"经典橙色指针", "冷蓝指针"}
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(adapter);
        themeSpinner.setSelection(preferences.getInt(PREF_THEME, 0));
        addCard(content, themeSpinner, createHelp("选择指针与刻度的高亮色，保存后立即生效。"));

        TextView save = createGlassButton("保存主题", true);
        save.setOnClickListener(view -> {
            preferences.edit().putInt(PREF_THEME, themeSpinner.getSelectedItemPosition()).apply();
            applyDashboardSettings();
            Toast.makeText(this, "主题已保存", Toast.LENGTH_SHORT).show();
        });
        content.addView(save, cardLayoutParams(12));
        showSubpage(page);
    }

    private void openEffectsPage() {
        LinearLayout page = buildPageShell("转数与效果", this::closeSubpage);
        LinearLayout content = (LinearLayout) page.findViewWithTag("content");
        content.addView(createHelp("表盘按阈值着色：绿 → 黄 → 橙 → 红，红转时表盘与指针爆闪。"));

        ThresholdControl outer = addThresholdControl(
            content, "绿色区结束", preferences.getInt(PREF_BAND_1, DEFAULT_BAND_1)
        );
        ThresholdControl middle = addThresholdControl(
            content, "黄色区结束", preferences.getInt(PREF_BAND_2, DEFAULT_BAND_2)
        );
        ThresholdControl inner = addThresholdControl(
            content, "橙色区结束", preferences.getInt(PREF_BAND_3, DEFAULT_BAND_3)
        );
        ThresholdControl redline = addThresholdControl(
            content, "爆闪触发", preferences.getInt(PREF_REDLINE, DEFAULT_REDLINE)
        );

        addSectionTitle(content, "爆闪设置");
        Switch flashSwitch = createSwitch(
            "红转区爆闪", preferences.getBoolean(PREF_FLASH, true)
        );
        Switch noFlashFifth = createSwitch(
            "5挡不爆闪", preferences.getBoolean(PREF_NO_FLASH_FIFTH, false)
        );
        Switch noFlashSixth = createSwitch(
            "6挡不爆闪", preferences.getBoolean(PREF_NO_FLASH_SIXTH, false)
        );
        LinearLayout flashCard = createCard();
        flashCard.addView(flashSwitch);
        flashCard.addView(noFlashFifth);
        flashCard.addView(noFlashSixth);
        content.addView(flashCard);

        TextView save = createGlassButton("保存转数与效果", true);
        save.setOnClickListener(view -> {
            int outerRpm = outer.getRpm();
            int middleRpm = middle.getRpm();
            int innerRpm = inner.getRpm();
            int redlineRpm = redline.getRpm();
            if (!(outerRpm < middleRpm && middleRpm < innerRpm && innerRpm < redlineRpm)) {
                Toast.makeText(
                    this,
                    "转数阈值必须按绿 → 黄 → 橙 → 爆闪递增",
                    Toast.LENGTH_LONG
                ).show();
                return;
            }
            preferences.edit()
                .putInt(PREF_BAND_1, outerRpm)
                .putInt(PREF_BAND_2, middleRpm)
                .putInt(PREF_BAND_3, innerRpm)
                .putInt(PREF_REDLINE, redlineRpm)
                .putBoolean(PREF_FLASH, flashSwitch.isChecked())
                .putBoolean(PREF_NO_FLASH_FIFTH, noFlashFifth.isChecked())
                .putBoolean(PREF_NO_FLASH_SIXTH, noFlashSixth.isChecked())
                .apply();
            applyDashboardSettings();
            Toast.makeText(this, "转数与爆闪设置已保存", Toast.LENGTH_SHORT).show();
        });
        content.addView(save, cardLayoutParams(12));
        showSubpage(page);
    }

    private void openConnectionPage() {
        LinearLayout page = buildPageShell("连接设置", this::closeSubpage);
        LinearLayout content = (LinearLayout) page.findViewWithTag("content");

        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setText(serverUrl == null ? DEFAULT_SERVER : serverUrl);
        serverInput.setHint("192.168.1.100:8787");
        connectionStatusView = createHelp(lastConnectionState);
        TextView reconnect = createGlassButton("保存地址并重连", true);
        reconnect.setOnClickListener(view -> reconnectFromSettings());
        LinearLayout card = createCard();
        card.addView(serverInput);
        card.addView(connectionStatusView);
        card.addView(reconnect);
        content.addView(card);
        content.addView(createHelp("地址可填写电脑 IPv4、IP:端口，或完整 ws:// 地址。"));
        showSubpage(page);
    }

    private void openLatencyPage() {
        LinearLayout page = buildPageShell("延迟检查", this::closeSubpage);
        LinearLayout content = (LinearLayout) page.findViewWithTag("content");
        latencyStatusView = createHelp(
            lastLatencyMs < 0 ? "往返延迟：尚未测量" : "当前往返延迟：" + lastLatencyMs + " ms"
        );
        TextView test = createGlassButton("开始 10 次延迟检查", true);
        test.setOnClickListener(view -> beginLatencyCheck());
        LinearLayout card = createCard();
        card.addView(latencyStatusView);
        card.addView(test);
        content.addView(card);
        content.addView(createHelp("结果包含最小值、平均值和最大值，用于判断 Wi-Fi 或热点链路质量。"));
        showSubpage(page);
    }

    private void showSubpage(View page) {
        if (activeSubpage != null) settingsPanel.removeView(activeSubpage);
        activeSubpage = page;
        settingsPanel.addView(page, fullScreenParams());
        page.post(() -> {
            page.setTranslationX(settingsPanel.getWidth());
            page.animate()
                .translationX(0f)
                .setDuration(260L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        });
        applyImmersive(true);
    }

    private void closeSubpage() {
        View page = activeSubpage;
        if (page == null) return;
        activeSubpage = null;
        serverInput = null;
        connectionStatusView = null;
        latencyStatusView = null;
        page.animate().cancel();
        page.animate()
            .translationX(settingsPanel.getWidth())
            .setDuration(220L)
            .setInterpolator(new AccelerateInterpolator())
            .withEndAction(() -> settingsPanel.removeView(page))
            .start();
    }

    private void openSettings() {
        if (settingsVisible) return;
        settingsVisible = true;
        if (activeSubpage != null) {
            settingsPanel.removeView(activeSubpage);
            activeSubpage = null;
            serverInput = null;
            connectionStatusView = null;
            latencyStatusView = null;
        }
        settingsHome.setVisibility(View.VISIBLE);
        settingsPanel.bringToFront();
        settingsPanel.animate().cancel();
        settingsPanel.setVisibility(View.VISIBLE);
        settingsPanel.setTranslationY(Math.max(root.getHeight(), dp(500)));
        settingsPanel.animate()
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(new DecelerateInterpolator())
            .start();
        registerSettingsBack();
        applyImmersive(true);
    }

    private void closeSettings() {
        if (!settingsVisible) return;
        unregisterSettingsBack();
        settingsVisible = false;
        settingsPanel.animate().cancel();
        settingsPanel.animate()
            .translationY(Math.max(root.getHeight(), dp(500)))
            .setDuration(220L)
            .setInterpolator(new AccelerateInterpolator())
            .withEndAction(() -> settingsPanel.setVisibility(View.GONE))
            .start();
        applyImmersive(dashboardVisible);
    }

    private void registerDefaultBack() {
        if (android.os.Build.VERSION.SDK_INT < 33 || defaultBackCallback != null) return;
        defaultBackCallback = this::handleBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            defaultBackCallback
        );
    }

    private void handleBack() {
        if (settingsVisible) {
            navigateBack();
            return;
        }
        if (dashboardVisible) {
            exitDashboard();
            return;
        }
        finish();
    }

    private void registerSettingsBack() {
        if (android.os.Build.VERSION.SDK_INT < 33 || settingsBackCallback != null) return;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            settingsBackCallback = new OnBackAnimationCallback() {
                @Override
                public void onBackStarted(BackEvent backEvent) {
                    if (activeSubpage != null) activeSubpage.animate().cancel();
                    else settingsPanel.animate().cancel();
                }

                @Override
                public void onBackProgressed(BackEvent backEvent) {
                    float progress = Math.max(0f, Math.min(1f, backEvent.getProgress()));
                    if (activeSubpage != null) {
                        activeSubpage.setTranslationX(settingsPanel.getWidth() * progress);
                    } else {
                        settingsPanel.setTranslationY(settingsPanel.getHeight() * progress);
                    }
                }

                @Override
                public void onBackCancelled() {
                    if (activeSubpage != null) {
                        activeSubpage.animate()
                            .translationX(0f)
                            .setDuration(180L)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    } else {
                        settingsPanel.animate()
                            .translationY(0f)
                            .setDuration(180L)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    }
                }

                @Override
                public void onBackInvoked() {
                    navigateBack();
                }
            };
        } else {
            settingsBackCallback = this::navigateBack;
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            settingsBackCallback
        );
    }

    private void navigateBack() {
        if (activeSubpage != null) closeSubpage();
        else closeSettings();
    }

    private void unregisterSettingsBack() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && settingsBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(settingsBackCallback);
            settingsBackCallback = null;
        }
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    private void reconnectFromSettings() {
        if (serverInput == null) return;
        String normalized = normalizeServerUrl(serverInput.getText().toString());
        if (normalized == null) {
            Toast.makeText(this, "地址格式无效", Toast.LENGTH_SHORT).show();
            return;
        }
        serverUrl = normalized;
        preferences.edit().putString(PREF_SERVER, normalized).apply();
        serverInput.setText(normalized);
        if (homeAddressInput != null) homeAddressInput.setText(normalized);
        connect(normalized);
        Toast.makeText(this, "正在连接 " + hostLabel(normalized), Toast.LENGTH_SHORT).show();
    }

    private void beginLatencyCheck() {
        if (client == null) {
            Toast.makeText(this, "尚未建立连接", Toast.LENGTH_SHORT).show();
            return;
        }
        latencyCheckActive = true;
        latencySamples.clear();
        if (latencyStatusView != null) latencyStatusView.setText("正在采样 0 / 10");
        uiHandler.removeCallbacks(latencyHeartbeat);
        for (int index = 0; index < 10; index += 1) {
            final int sample = index;
            uiHandler.postDelayed(() -> {
                if (latencyCheckActive) {
                    if (latencyStatusView != null) {
                        latencyStatusView.setText("正在采样 " + sample + " / 10");
                    }
                    if (client != null) client.ping();
                }
            }, index * 110L);
        }
        uiHandler.postDelayed(this::finishLatencyCheck, 1600L);
    }

    private void finishLatencyCheck() {
        latencyCheckActive = false;
        if (latencySamples.isEmpty()) {
            if (latencyStatusView != null) latencyStatusView.setText("测速失败：没有收到服务器响应");
        } else {
            long min = Long.MAX_VALUE;
            long max = 0L;
            long total = 0L;
            for (long sample : latencySamples) {
                min = Math.min(min, sample);
                max = Math.max(max, sample);
                total += sample;
            }
            lastLatencyMs = total / latencySamples.size();
            if (latencyStatusView != null) {
                latencyStatusView.setText(
                    "往返延迟：最小 " + min + " ms · 平均 " + lastLatencyMs
                        + " ms · 最大 " + max + " ms（" + latencySamples.size() + " 次）"
                );
            }
        }
        if (client != null) {
            uiHandler.removeCallbacks(latencyHeartbeat);
            uiHandler.postDelayed(latencyHeartbeat, 3000L);
        }
    }

    private void connect(String url) {
        if (client != null) client.shutdown();
        dashboard.setEndpoint(hostLabel(url));
        client = new TelemetryClient(this, url, this);
        client.start();
    }

    private void applyDashboardSettings() {
        dashboard.configure(
            new int[]{
                preferences.getInt(PREF_BAND_1, DEFAULT_BAND_1),
                preferences.getInt(PREF_BAND_2, DEFAULT_BAND_2),
                preferences.getInt(PREF_BAND_3, DEFAULT_BAND_3)
            },
            preferences.getInt(PREF_REDLINE, DEFAULT_REDLINE),
            preferences.getBoolean(PREF_FLASH, true),
            preferences.getBoolean(PREF_NO_FLASH_FIFTH, false),
            preferences.getBoolean(PREF_NO_FLASH_SIXTH, false),
            preferences.getInt(PREF_THEME, 0)
        );
    }

    private void addSectionTitle(LinearLayout parent, String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(17);
        title.setTextColor(ORANGE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(18), 0, dp(8));
        parent.addView(title);
    }

    private TextView createHelp(String text) {
        TextView help = new TextView(this);
        help.setText(text);
        help.setTextSize(12);
        help.setTextColor(TEXT_MUTED);
        help.setLineSpacing(0, 1.22f);
        return help;
    }

    private Switch createSwitch(String text, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(text);
        toggle.setTextSize(14);
        toggle.setChecked(checked);
        toggle.setPadding(0, dp(7), 0, dp(7));
        return toggle;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(new GlassDrawable(dp(16), GLASS_NEUTRAL, dp(1f), 42));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(7);
        params.bottomMargin = dp(7);
        card.setLayoutParams(params);
        return card;
    }

    private LinearLayout.LayoutParams cardLayoutParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private void addCard(LinearLayout parent, View... children) {
        LinearLayout card = createCard();
        for (View child : children) {
            if (child.getParent() != null) ((ViewGroup) child.getParent()).removeView(child);
            card.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        parent.addView(card);
    }

    private Button createTextButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        return button;
    }

    private ThresholdControl addThresholdControl(LinearLayout parent, String title, int value) {
        TextView label = new TextView(this);
        label.setTextSize(14);
        label.setTextColor(Color.WHITE);
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(110);
        int clamped = Math.max(1000, Math.min(12000, value));
        seekBar.setProgress(Math.round((clamped - 1000) / 100f));
        ThresholdControl control = new ThresholdControl(seekBar, label, title);
        control.refresh();
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                control.refresh();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        addCard(parent, label, seekBar);
        return control;
    }

    private static String normalizeServerUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (value.startsWith("http://")) value = "ws://" + value.substring(7);
        if (value.startsWith("https://")) value = "wss://" + value.substring(8);
        if (!value.startsWith("ws://") && !value.startsWith("wss://")) value = "ws://" + value;
        try {
            URI input = new URI(value);
            String scheme = input.getScheme();
            String host = input.getHost();
            if (scheme == null || host == null) return null;
            int port = input.getPort() > 0 ? input.getPort() : 8787;
            String path = input.getRawPath();
            if (path == null || path.isEmpty() || "/".equals(path)) path = "/ws";
            return new URI(scheme, null, host, port, path, null, null).toString();
        } catch (URISyntaxException error) {
            return null;
        }
    }

    private static String hostLabel(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 8787);
        } catch (RuntimeException ignored) {
            return url;
        }
    }

    private void applyImmersive(boolean immersive) {
        View decorView = getWindow().getDecorView();
        if (decorView == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(!immersive);
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                if (immersive) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                } else {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            if (immersive) {
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            } else {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        }
    }

    @Override
    public void onConnectionState(int state, String detail) {
        runOnUiThread(() -> {
            String stateLabel = state == TelemetryClient.STATE_CONNECTED ? "LIVE"
                : state == TelemetryClient.STATE_CONNECTING ? "CONNECT" : "OFFLINE";
            lastConnectionState = stateLabel + " · " + detail;
            dashboard.setConnectionState(stateLabel, detail);
            if (connectionStatusView != null) {
                connectionStatusView.setText(lastConnectionState + "\n"
                    + (serverUrl == null ? "" : serverUrl));
            }
            if (state == TelemetryClient.STATE_CONNECTED) {
                updateHomeStatus("已连接 · " + (serverUrl == null ? "" : hostLabel(serverUrl)),
                    "点击「开启记速器」进入横屏仪表");
                uiHandler.removeCallbacks(latencyHeartbeat);
                uiHandler.postDelayed(latencyHeartbeat, 1200L);
            } else if (state == TelemetryClient.STATE_CONNECTING) {
                updateHomeStatus("正在连接…", detail);
                uiHandler.removeCallbacks(latencyHeartbeat);
            } else {
                updateHomeStatus("未连接", detail + "\n确认电脑端服务已启动，且手机与电脑同一 Wi-Fi");
                uiHandler.removeCallbacks(latencyHeartbeat);
            }
        });
    }

    @Override
    public void onTelemetry(float speedKph, float rpm, float maxRpm, String gear) {
        runOnUiThread(() -> dashboard.setTelemetry(speedKph, rpm, maxRpm, gear));
    }

    @Override
    public void onLatency(long roundTripMs) {
        runOnUiThread(() -> {
            lastLatencyMs = roundTripMs;
            if (latencyCheckActive) {
                latencySamples.add(roundTripMs);
            } else if (latencyStatusView != null) {
                latencyStatusView.setText("当前往返延迟：" + lastLatencyMs + " ms");
            }
        });
    }

    private FrameLayout.LayoutParams fullScreenParams() {
        return new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private final class ThresholdControl {
        private final SeekBar seekBar;
        private final TextView label;
        private final String title;

        private ThresholdControl(SeekBar seekBar, TextView label, String title) {
            this.seekBar = seekBar;
            this.label = label;
            this.title = title;
        }

        private int getRpm() {
            return 1000 + seekBar.getProgress() * 100;
        }

        private void refresh() {
            label.setText(title + "：" + getRpm() + " RPM");
        }
    }
}
