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
    private boolean settingsVisible;
    private boolean latencyCheckActive;
    private String lastConnectionState = "尚未连接";
    private long lastLatencyMs = -1L;

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
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        serverUrl = preferences.getString(PREF_SERVER, null);

        dashboard = new DashboardView(this);
        dashboard.setSettingsGestureListener(this::openSettings);
        applyDashboardSettings();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 7, 8));
        root.addView(dashboard, fullScreenParams());
        settingsPanel = buildSettingsShell();
        settingsPanel.setVisibility(View.GONE);
        root.addView(settingsPanel, fullScreenParams());
        setContentView(root);
        hideSystemUi();

        if (serverUrl == null) {
            dashboard.setEndpoint("NOT CONFIGURED");
            dashboard.setConnectionState("CONFIGURE", "打开设置连接电脑");
            dashboard.postDelayed(this::openSettings, 450L);
        } else {
            connect(serverUrl);
        }
        if (getIntent().getBooleanExtra("open_settings", false)) {
            dashboard.postDelayed(this::openSettings, 350L);
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
        hideSystemUi();
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
        if (hasFocus) hideSystemUi();
    }

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
            "PACENOTE WRCG 0.3.0\n"
                + "原生 Android Canvas 无线拉力仪表\n"
                + "WebSocket 数据链路 · 30 Hz 更新"
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
            Button back = createTextButton("返回");
            back.setOnClickListener(view -> onBack.run());
            header.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
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
            Button close = createTextButton("关闭");
            close.setOnClickListener(view -> closeSettings());
            header.addView(close);
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
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(16, 22, 24));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), Color.rgb(42, 51, 55));
        row.setBackground(background);

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
        arrow.setTextColor(Color.rgb(255, 109, 64));
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
            new String[]{"经典六柱", "高对比六柱"}
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(adapter);
        themeSpinner.setSelection(preferences.getInt(PREF_THEME, 0));
        addCard(content, themeSpinner, createHelp("选择当前仪表主题，后续可继续增加自定义主题。"));

        Button save = createActionButton("保存主题");
        save.setOnClickListener(view -> {
            preferences.edit().putInt(PREF_THEME, themeSpinner.getSelectedItemPosition()).apply();
            applyDashboardSettings();
            Toast.makeText(this, "主题已保存", Toast.LENGTH_SHORT).show();
        });
        content.addView(save);
        showSubpage(page);
    }

    private void openEffectsPage() {
        LinearLayout page = buildPageShell("转数与效果", this::closeSubpage);
        LinearLayout content = (LinearLayout) page.findViewWithTag("content");
        content.addView(createHelp("每一对左右灯柱同时点亮；高于爆闪阈值时触发红色效果。"));

        ThresholdControl outer = addThresholdControl(
            content, "外侧灯柱", preferences.getInt(PREF_BAND_1, DEFAULT_BAND_1)
        );
        ThresholdControl middle = addThresholdControl(
            content, "中间灯柱", preferences.getInt(PREF_BAND_2, DEFAULT_BAND_2)
        );
        ThresholdControl inner = addThresholdControl(
            content, "内侧灯柱", preferences.getInt(PREF_BAND_3, DEFAULT_BAND_3)
        );
        ThresholdControl redline = addThresholdControl(
            content, "爆闪触发", preferences.getInt(PREF_REDLINE, DEFAULT_REDLINE)
        );

        addSectionTitle(content, "爆闪设置");
        Switch flashSwitch = createSwitch(
            "红转区全屏爆闪", preferences.getBoolean(PREF_FLASH, true)
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

        Button save = createActionButton("保存转数与效果");
        save.setOnClickListener(view -> {
            int outerRpm = outer.getRpm();
            int middleRpm = middle.getRpm();
            int innerRpm = inner.getRpm();
            int redlineRpm = redline.getRpm();
            if (!(outerRpm < middleRpm && middleRpm < innerRpm && innerRpm < redlineRpm)) {
                Toast.makeText(
                    this,
                    "转数阈值必须按外侧 → 中间 → 内侧 → 爆闪递增",
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
        content.addView(save);
        showSubpage(page);
    }

    private void openConnectionPage() {
        LinearLayout page = buildPageShell("连接设置", this::closeSubpage);
        LinearLayout content = (LinearLayout) page.findViewWithTag("content");

        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setText(serverUrl == null ? DEFAULT_SERVER : serverUrl);
        connectionStatusView = createHelp(lastConnectionState);
        Button reconnect = createActionButton("保存地址并重连");
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
        Button test = createActionButton("开始 10 次延迟检查");
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
        hideSystemUi();
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
        settingsPanel.animate().cancel();
        settingsPanel.setVisibility(View.VISIBLE);
        settingsPanel.setTranslationY(Math.max(root.getHeight(), dp(500)));
        settingsPanel.animate()
            .translationY(0f)
            .setDuration(280L)
            .setInterpolator(new DecelerateInterpolator())
            .start();
        registerSettingsBack();
        hideSystemUi();
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
        if (settingsVisible) navigateBack();
        else super.onBackPressed();
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
        title.setTextColor(Color.rgb(255, 91, 43));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(18), 0, dp(8));
        parent.addView(title);
    }

    private TextView createHelp(String text) {
        TextView help = new TextView(this);
        help.setText(text);
        help.setTextSize(12);
        help.setTextColor(Color.rgb(146, 157, 161));
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
        card.setPadding(dp(15), dp(12), dp(15), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(16, 22, 24));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), Color.rgb(42, 51, 55));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(7);
        params.bottomMargin = dp(7);
        card.setLayoutParams(params);
        return card;
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

    private Button createActionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
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

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        if (decorView == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
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
                uiHandler.removeCallbacks(latencyHeartbeat);
                uiHandler.postDelayed(latencyHeartbeat, 1200L);
            } else {
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
