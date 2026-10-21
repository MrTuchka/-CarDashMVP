package com.sidis.cardash;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements ObdClient.Listener {
    private static final int REQ_BT = 1001;
    private static final int REQ_LOCATION = 1002;
    private static final int REQ_EXPORT_LOG = 1003;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VehicleState demo = new VehicleState();
    private VehicleState current = demo;
    private ObdClient obdClient;

    private TextView statusText, clockText, voltageTop;
    private FrameLayout rightPanel, sleepOverlay, pipContainer;
    private WebView mainWeb, pipWeb;
    private CarClusterView clusterView;
    private View turnCard, speedLimitBadge, mediaCard;
    private boolean demoMode = true;
    private boolean powerConnected = true;
    private boolean parkingActive = false;
    private BluetoothDevice lastObdDevice;
    private float lastX, lastY;
    private final StringBuilder diagnosticLog = new StringBuilder();
    private String pendingExportLog;

    private final Runnable parkingRunnable = this::enterParkingMode;

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                handler.removeCallbacks(parkingRunnable);
                enterDrivingMode();
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                handler.removeCallbacks(parkingRunnable);
                handler.postDelayed(parkingRunnable, 8000);
                setStatus("POWER LOST · SLEEP IN 8s");
            }
        }
    };

    private final Runnable clockRunnable = new Runnable() {
        @Override public void run() {
            if (clockText != null) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault());
                clockText.setText(sdf.format(new java.util.Date()));
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable demoRunnable = new Runnable() {
        private double t = 0;
        @Override public void run() {
            if (demoMode) {
                t += 0.08;
                demo.speedKmh = Math.max(0, (int) (72 + 18 * Math.sin(t)));
                demo.rpm = Math.max(780, (int) (2150 + 680 * Math.sin(t * 1.25)));
                demo.coolantC = 88 + (int) (2 * Math.sin(t * 0.2));
                demo.voltage = 14.2 + 0.12 * Math.sin(t * 0.5);
                demo.fuelPercent = 52.0;
                demo.consumptionL100 = 6.8 + 1.1 * Math.abs(Math.sin(t * 1.1));
                demo.rangeKm = 340;
                demo.connected = true;
                demo.source = "DEMO";
                current = demo;
                renderVehicleState();
            }
            handler.postDelayed(this, 180);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        immersive();
        buildUi();
        appendDiagnostic("APP START  CarDash " + BuildConfig.VERSION_NAME);
        appendDiagnostic("DEVICE  " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

        IntentFilter powerFilter = new IntentFilter();
        powerFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        powerFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerReceiver, powerFilter);

        detectInitialPowerState();
        requestNeededPermissions();
        handler.post(clockRunnable);
        handler.post(demoRunnable);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(powerReceiver); } catch (Exception ignored) {}
        if (obdClient != null) obdClient.stop();
        handler.removeCallbacksAndMessages(null);
        if (mainWeb != null) mainWeb.destroy();
        if (pipWeb != null) pipWeb.destroy();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(4, 8, 12));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        shell.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        shell.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(buildLeftPanel(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.335f));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(26, 33, 40));
        content.addView(divider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        rightPanel = new FrameLayout(this);
        rightPanel.setBackgroundColor(Color.rgb(7, 12, 17));
        content.addView(rightPanel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.665f));
        showMap();

        shell.addView(buildBottomBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        sleepOverlay = new FrameLayout(this);
        sleepOverlay.setBackgroundColor(Color.BLACK);
        TextView parking = text("PARKING MODE", 18, Color.rgb(98, 108, 118), true);
        TextView hint = text("ACC OFF · LOW POWER", 11, Color.rgb(72, 80, 87), false);
        LinearLayout sleepText = new LinearLayout(this);
        sleepText.setOrientation(LinearLayout.VERTICAL);
        sleepText.setGravity(Gravity.CENTER);
        sleepText.addView(parking);
        sleepText.addView(hint);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        sleepOverlay.addView(sleepText, sp);
        sleepOverlay.setVisibility(View.GONE);
        root.addView(sleepOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(22), 0, dp(20), 0);
        bar.setBackgroundColor(Color.rgb(5, 10, 14));

        clockText = text("--:--", 25, Color.WHITE, false);
        bar.addView(clockText);

        TextView date = text("   Вт, 20 жов", 13, Color.rgb(151, 164, 177), false);
        bar.addView(date);

        TextView weather = text("☁   8°C", 14, Color.rgb(224, 231, 237), false);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wp.leftMargin = dp(44);
        bar.addView(weather, wp);

        statusText = text("●  DEMO", 13, Color.rgb(47, 224, 120), true);
        bar.addView(statusText);

        TextView wifi = text("   Wi‑Fi", 12, Color.rgb(176, 190, 203), false);
        LinearLayout.LayoutParams wifip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wifip.leftMargin = dp(18);
        bar.addView(wifi, wifip);

        voltageTop = text("14.2 V", 14, Color.WHITE, false);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.leftMargin = dp(18);
        bar.addView(voltageTop, vp);
        return bar;
    }

    private View buildLeftPanel() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(5, 10, 15));

        clusterView = new CarClusterView(this);
        frame.addView(clusterView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(18), 0, dp(18), dp(12));
        controls.setGravity(Gravity.BOTTOM);

        Button demoButton = compactButton("DEMO", v -> enableDemo(), false);
        Button obdButton = compactButton("CONNECT OBD", v -> chooseObdDevice(), true);
        controls.addView(demoButton, new LinearLayout.LayoutParams(0, dp(46), 0.36f));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, dp(46), 0.64f);
        op.leftMargin = dp(8);
        controls.addView(obdButton, op);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60), Gravity.BOTTOM);
        frame.addView(controls, cp);
        return frame;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackgroundColor(Color.rgb(5, 10, 14));

        bar.addView(navButton("⌂", "Головна", v -> showMap(), true), navParams());
        bar.addView(navButton("◆", "Карти", v -> showMap(), false), navParams());
        bar.addView(navButton("♫", "Музика", v -> toast("Media controls — next MVP"), false), navParams());
        bar.addView(navButton("▶", "YouTube", v -> showYouTube(), false), navParams());
        bar.addView(navButton("▰", "Авто", v -> chooseObdDevice(), false), navParams());
        bar.addView(navButton("≡", "Діагностика", v -> showDiagnostics(), false), navParams());
        return bar;
    }

    private LinearLayout.LayoutParams navParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private View navButton(String icon, String label, View.OnClickListener l, boolean selected) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(2), dp(3), dp(2), dp(3));
        if (selected) wrap.setBackground(outlinedRound(Color.rgb(8, 22, 34), Color.rgb(17, 96, 156), dp(13), 1));
        wrap.setOnClickListener(l);

        TextView i = text(icon, 23, selected ? Color.rgb(26,137,255) : Color.rgb(185,197,208), true);
        i.setGravity(Gravity.CENTER);
        TextView t = text(label, 11, selected ? Color.rgb(54,156,255) : Color.rgb(168,181,194), false);
        t.setGravity(Gravity.CENTER);
        wrap.addView(i);
        wrap.addView(t);
        return wrap;
    }

    private Button compactButton(String s, View.OnClickListener l, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(10);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(rounded(primary ? Color.rgb(18, 113, 199) : Color.rgb(22, 31, 39), dp(12)));
        b.setOnClickListener(l);
        return b;
    }

    private void showMap() {
        showWeb("https://www.openstreetmap.org");
        showMapOverlays(true);
    }

    private void showYouTube() {
        showWeb("https://m.youtube.com");
        showMapOverlays(false);
    }

    private void showWeb(String url) {
        clearMainWeb();
        mainWeb = createWebView();
        rightPanel.addView(mainWeb, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mainWeb.loadUrl(url);
        ensureRightOverlays();
        keepPipOnTop();
    }

    private void ensureRightOverlays() {
        if (turnCard == null) {
            turnCard = buildTurnCard();
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(310), dp(92), Gravity.TOP | Gravity.START);
            tp.setMargins(dp(18), dp(18), 0, 0);
            rightPanel.addView(turnCard, tp);
        }
        if (speedLimitBadge == null) {
            TextView speed = text("80", 18, Color.rgb(28,28,28), true);
            speed.setGravity(Gravity.CENTER);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(Color.WHITE);
            gd.setStroke(dp(4), Color.rgb(216,44,44));
            speed.setBackground(gd);
            speedLimitBadge = speed;
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.TOP | Gravity.END);
            sp.setMargins(0, dp(20), dp(18), 0);
            rightPanel.addView(speedLimitBadge, sp);
        }
        if (mediaCard == null) {
            mediaCard = buildMediaCard();
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(420), dp(170), Gravity.BOTTOM | Gravity.END);
            mp.setMargins(0, 0, dp(18), dp(18));
            rightPanel.addView(mediaCard, mp);
        }
    }

    private void showMapOverlays(boolean show) {
        ensureRightOverlays();
        int vis = show ? View.VISIBLE : View.GONE;
        turnCard.setVisibility(vis);
        speedLimitBadge.setVisibility(vis);
        mediaCard.setVisibility(View.VISIBLE);
    }

    private View buildTurnCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(8), dp(16), dp(8));
        card.setBackground(outlinedRound(Color.rgb(14,22,29), Color.rgb(67,85,100), dp(16), 1));

        TextView arrow = text("↗", 38, Color.WHITE, true);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(text("2.3 km", 25, Color.WHITE, true));
        copy.addView(text("Наступний поворот", 12, Color.rgb(177,192,205), false));
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return card;
    }

    private View buildMediaCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(12));
        card.setBackground(outlinedRound(Color.rgb(9,16,22), Color.rgb(48,64,76), dp(18), 1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView album = text("♫", 26, Color.rgb(30,215,96), true);
        album.setGravity(Gravity.CENTER);
        album.setBackground(rounded(Color.rgb(20,42,36), dp(12)));
        top.addView(album, new LinearLayout.LayoutParams(dp(66), dp(66)));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(dp(14), 0, 0, 0);
        meta.addView(text("Media", 11, Color.rgb(133,150,164), false));
        meta.addView(text("Nothing playing", 19, Color.WHITE, false));
        top.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button youtube = compactButton("YouTube", v -> showYouTube(), true);
        youtube.setBackground(rounded(Color.rgb(204,26,26), dp(10)));
        top.addView(youtube, new LinearLayout.LayoutParams(dp(92), dp(42)));
        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(mediaControl("|◀"));
        controls.addView(mediaControl("▶"));
        controls.addView(mediaControl("▶|"));
        card.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return card;
    }

    private TextView mediaControl(String s) {
        TextView v = text(s, 21, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(34), 0, dp(34), 0);
        return v;
    }

    private WebView createWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.rgb(12, 24, 34));
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setGeolocationEnabled(true);
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
                    callback.invoke(origin, false, false);
                }
            }
        });
        return w;
    }

    private void clearMainWeb() {
        if (mainWeb != null) {
            rightPanel.removeView(mainWeb);
            mainWeb.destroy();
            mainWeb = null;
        }
    }

    private void togglePip() {
        if (pipContainer == null) {
            pipContainer = new FrameLayout(this);
            pipContainer.setBackground(outlinedRound(Color.rgb(7,9,12), Color.rgb(55,67,77), dp(12), 1));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(360), dp(235), Gravity.TOP | Gravity.END);
            p.setMargins(dp(10), dp(118), dp(18), dp(10));
            rightPanel.addView(pipContainer, p);

            pipWeb = createWebView();
            FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            wp.topMargin = dp(32);
            pipContainer.addView(pipWeb, wp);
            pipWeb.loadUrl("https://m.youtube.com");

            TextView drag = text("YouTube   ···", 11, Color.rgb(205,214,222), true);
            drag.setGravity(Gravity.CENTER_VERTICAL);
            drag.setPadding(dp(12), 0, 0, 0);
            drag.setBackgroundColor(Color.rgb(24,28,33));
            FrameLayout.LayoutParams dpv = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32), Gravity.TOP);
            pipContainer.addView(drag, dpv);
            drag.setOnTouchListener((v, event) -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pipContainer.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - lastX;
                        float dy = event.getRawY() - lastY;
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.leftMargin = Math.max(0, Math.min(rightPanel.getWidth() - pipContainer.getWidth(), (int) (pipContainer.getX() + dx)));
                        lp.topMargin = Math.max(0, Math.min(rightPanel.getHeight() - pipContainer.getHeight(), (int) (pipContainer.getY() + dy)));
                        pipContainer.setLayoutParams(lp);
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return true;
                }
            });
            keepPipOnTop();
            toast("YouTube PiP: drag by the top bar");
        } else {
            rightPanel.removeView(pipContainer);
            if (pipWeb != null) pipWeb.destroy();
            pipWeb = null;
            pipContainer = null;
        }
    }

    private void keepPipOnTop() {
        if (pipContainer != null) pipContainer.bringToFront();
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    private void chooseObdDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null) { toast("Bluetooth is not available on this tablet"); return; }
        if (!adapter.isEnabled()) { toast("Turn Bluetooth on first"); return; }

        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            toast("Pair the OBD scanner in Android Bluetooth settings first");
            return;
        }

        ArrayList<BluetoothDevice> devices = new ArrayList<>(bonded);
        devices.sort(Comparator.comparing(d -> {
            try { return d.getName() == null ? "" : d.getName(); }
            catch (SecurityException e) { return ""; }
        }));

        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String name;
            try { name = d.getName(); } catch (SecurityException e) { name = "Bluetooth device"; }
            names[i] = (name == null ? "Bluetooth device" : name) + "\n" + d.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Choose paired OBD scanner")
                .setItems(names, (dialog, which) -> connectObd(devices.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectObd(BluetoothDevice device) {
        if (obdClient != null) obdClient.stop();
        demoMode = false;
        lastObdDevice = device;
        appendDiagnostic("CONNECT REQUEST  " + safeBluetoothName(device) + "  " + safeBluetoothAddress(device));
        setStatus("CONNECTING…");
        obdClient = new ObdClient(device, this);
        obdClient.start();
    }

    private void enableDemo() {
        if (obdClient != null) { obdClient.stop(); obdClient = null; }
        lastObdDevice = null;
        demoMode = true;
        current = demo;
        setStatus("●  DEMO");
        if (statusText != null) statusText.setTextColor(Color.rgb(47,224,120));
    }

    @Override public void onState(VehicleState state) {
        if (!demoMode) {
            current = state;
            renderVehicleState();
            setStatus(state.connected ? "●  OBD" : "OBD DISCONNECTED");
            if (statusText != null) statusText.setTextColor(state.connected ? Color.rgb(47,224,120) : Color.rgb(255,95,95));
        }
    }

    @Override public void onStatus(String message) {
        appendDiagnostic("UI STATUS  " + message);
        if (!demoMode) setStatus(message.toUpperCase(Locale.getDefault()));
    }

    @Override public void onDiagnostic(String line) {
        appendDiagnostic(line);
    }

    private void renderVehicleState() {
        if (clusterView != null) clusterView.setState(current);
        if (voltageTop != null) {
            voltageTop.setText(current.voltage > 0 ? String.format(Locale.getDefault(), "%.1f V", current.voltage) : "-- V");
        }
    }

    private void detectInitialPowerState() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        powerConnected = plugged != 0;
        if (powerConnected) enterDrivingMode();
        else setStatus("BATTERY · DEMO");
    }

    private void enterParkingMode() {
        powerConnected = false;
        parkingActive = true;
        if (!demoMode && obdClient != null) {
            obdClient.stop();
            obdClient = null;
        }
        sleepOverlay.setVisibility(View.VISIBLE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (mainWeb != null) mainWeb.onPause();
        if (pipWeb != null) pipWeb.onPause();
    }

    private void enterDrivingMode() {
        powerConnected = true;
        boolean wasParking = parkingActive;
        parkingActive = false;
        sleepOverlay.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.78f;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (mainWeb != null) mainWeb.onResume();
        if (pipWeb != null) pipWeb.onResume();
        wakeScreenBriefly();
        immersive();
        if (wasParking && !demoMode && lastObdDevice != null && obdClient == null) {
            handler.postDelayed(() -> connectObd(lastObdDevice), 1200);
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreenBriefly() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP |
                PowerManager.ON_AFTER_RELEASE,
                "CarDash:PowerWake");
        wl.acquire(2500);
    }

    private void appendDiagnostic(String line) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new java.util.Date());
        diagnosticLog.append(time).append("  ").append(line == null ? "" : line).append('\n');
        if (diagnosticLog.length() > 60000) diagnosticLog.delete(0, 12000);
    }

    private String safeBluetoothName(BluetoothDevice device) {
        if (device == null) return "none";
        try { return device.getName() == null ? "Bluetooth device" : device.getName(); }
        catch (SecurityException ignored) { return "Bluetooth device"; }
    }

    private String safeBluetoothAddress(BluetoothDevice device) {
        if (device == null) return "--";
        try { return device.getAddress(); }
        catch (SecurityException ignored) { return "address unavailable"; }
    }

    private String buildDiagnosticReport() {
        StringBuilder report = new StringBuilder();
        report.append("CarDash Diagnostics\n");
        report.append("App: ").append(BuildConfig.VERSION_NAME).append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n");
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n");
        report.append("Android: ").append(Build.VERSION.RELEASE).append(" · API ").append(Build.VERSION.SDK_INT).append("\n");
        report.append("Power: ").append(powerConnected ? "CONNECTED" : "DISCONNECTED").append("\n");
        report.append("Mode: ").append(demoMode ? "DEMO" : "OBD").append("\n");
        report.append("OBD device: ").append(safeBluetoothName(lastObdDevice)).append(" · ").append(safeBluetoothAddress(lastObdDevice)).append("\n");
        report.append(String.format(Locale.getDefault(),
                "Vehicle: speed=%d km/h · rpm=%d · coolant=%d C · voltage=%.2f V · fuel=%.1f%% · consumption=%.2f L/100 · range=%d km\n",
                current.speedKmh, current.rpm, current.coolantC, current.voltage, current.fuelPercent, current.consumptionL100, current.rangeKm));
        report.append("\n--- OBD / APP LOG ---\n");
        report.append(diagnosticLog);
        return report.toString();
    }

    private void showDiagnostics() {
        ScrollView scroll = new ScrollView(this);
        TextView logView = text(buildDiagnosticReport(), 11, Color.rgb(220,230,238), false);
        logView.setTextIsSelectable(true);
        logView.setGravity(Gravity.TOP | Gravity.START);
        logView.setPadding(dp(14), dp(12), dp(14), dp(18));
        logView.setTypeface(Typeface.MONOSPACE);
        scroll.addView(logView, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("CarDash · Діагностика")
                .setView(scroll)
                .setPositiveButton("EXPORT LOG", (d, which) -> exportDiagnostics())
                .setNeutralButton("CLEAR", (d, which) -> { diagnosticLog.setLength(0); toast("Diagnostic log cleared"); })
                .setNegativeButton("CLOSE", null)
                .create();
        dialog.setOnShowListener(d -> {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
        dialog.show();
    }

    private void exportDiagnostics() {
        pendingExportLog = buildDiagnosticReport();
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "cardash-diagnostics-" + stamp + ".txt");
        startActivityForResult(intent, REQ_EXPORT_LOG);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_LOG && resultCode == RESULT_OK && data != null && data.getData() != null && pendingExportLog != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) {
                    out.write(pendingExportLog.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    toast("Diagnostic log saved");
                }
            } catch (Exception e) {
                toast("Could not save log: " + e.getMessage());
            } finally {
                pendingExportLog = null;
            }
        }
    }

    private void setStatus(String s) {
        if (statusText != null) statusText.setText(s);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable outlinedRound(int fill, int stroke, int radius, int strokeDp) {
        GradientDrawable d = rounded(fill, radius);
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private final class CarClusterView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private VehicleState state = demo;

        CarClusterView(Context c) { super(c); }

        void setState(VehicleState s) {
            state = s == null ? demo : s;
            invalidate();
        }

        private float d(float v) { return v * getResources().getDisplayMetrics().density; }
        private void txt(String s, float x, float y, float sp, int color, boolean bold, Paint.Align align) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextSize(d(sp));
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextAlign(align);
            canvasDrawText(s, x, y, p);
        }

        private Canvas activeCanvas;
        private void canvasDrawText(String s, float x, float y, Paint paint) {
            if (activeCanvas != null) activeCanvas.drawText(s, x, y, paint);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            activeCanvas = canvas;
            float w = getWidth();
            float h = getHeight();
            canvas.drawColor(Color.rgb(5,10,15));

            float top = d(16);
            float heroH = d(145);
            float centerX = w * 0.73f;
            float gaugeR = Math.min(d(58), w * 0.16f);

            txt(String.valueOf(state.speedKmh), w * 0.30f, top + d(82), 66, Color.WHITE, true, Paint.Align.CENTER);
            txt("km/h", w * 0.30f, top + d(112), 17, Color.rgb(145,160,174), false, Paint.Align.CENTER);

            RectF arc = new RectF(centerX-gaugeR, top+d(14), centerX+gaugeR, top+d(14)+gaugeR*2);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d(7));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.rgb(39,47,57));
            canvas.drawArc(arc, 140, 260, false, p);
            p.setColor(Color.rgb(24,126,255));
            float prog = Math.max(0f, Math.min(1f, state.rpm / 6500f));
            canvas.drawArc(arc, 140, 260*prog, false, p);
            txt(String.valueOf(state.rpm), centerX, top+d(77), 24, Color.WHITE, true, Paint.Align.CENTER);
            txt("RPM", centerX, top+d(99), 11, Color.rgb(153,166,179), false, Paint.Align.CENTER);

            float roadTop = top + heroH;
            float roadBottom = roadTop + d(150);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(8,14,20));
            canvas.drawRoundRect(new RectF(d(18), roadTop, w-d(18), roadBottom), d(18), d(18), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d(2));
            p.setColor(Color.rgb(25,37,48));
            canvas.drawRoundRect(new RectF(d(18), roadTop, w-d(18), roadBottom), d(18), d(18), p);

            p.setStrokeWidth(d(3));
            p.setColor(Color.rgb(78,96,111));
            canvas.drawLine(d(32), roadBottom, w*0.43f, roadTop+d(32), p);
            canvas.drawLine(w-d(32), roadBottom, w*0.57f, roadTop+d(32), p);
            p.setStrokeWidth(d(1.6f));
            p.setColor(Color.rgb(37,93,139));
            canvas.drawLine(w*0.28f, roadBottom, w*0.47f, roadTop+d(36), p);
            canvas.drawLine(w*0.72f, roadBottom, w*0.53f, roadTop+d(36), p);

            float carX = w/2f;
            float carY = roadTop + d(86);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(51,61,72));
            canvas.drawRoundRect(new RectF(carX-d(50), carY, carX+d(50), carY+d(42)), d(12), d(12), p);
            p.setColor(Color.rgb(25,32,40));
            canvas.drawRoundRect(new RectF(carX-d(27), carY+d(5), carX+d(27), carY+d(21)), d(6), d(6), p);
            p.setColor(Color.rgb(255,61,64));
            canvas.drawRoundRect(new RectF(carX-d(39), carY+d(29), carX-d(19), carY+d(34)), d(2), d(2), p);
            canvas.drawRoundRect(new RectF(carX+d(19), carY+d(29), carX+d(39), carY+d(34)), d(2), d(2), p);

            float cardTop = roadBottom + d(10);
            float cardBottom = h - d(126);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(8,14,19));
            canvas.drawRoundRect(new RectF(d(18), cardTop, w-d(18), cardBottom), d(16), d(16), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d(1));
            p.setColor(Color.rgb(26,38,48));
            canvas.drawRoundRect(new RectF(d(18), cardTop, w-d(18), cardBottom), d(16), d(16), p);

            String[] labels = {"Миттєва витрата", "Запас ходу", "Рівень пального", "Температура двигуна", "Напруга АКБ"};
            String[] values = {
                    state.consumptionL100 >= 0 && state.consumptionL100 < 99 ? String.format(Locale.getDefault(), "%.1f L/100 km", state.consumptionL100) : "-- L/100 km",
                    state.rangeKm >= 0 ? state.rangeKm + " km" : "-- km",
                    state.fuelPercent >= 0 ? String.format(Locale.getDefault(), "%.0f %%", state.fuelPercent) : "-- %",
                    state.coolantC > -30 ? state.coolantC + " °C" : "-- °C",
                    state.voltage > 0 ? String.format(Locale.getDefault(), "%.1f V", state.voltage) : "-- V"
            };
            String[] icons = {"F", "R", "%", "T", "V"};
            int[] iconColors = {Color.rgb(227,238,247), Color.rgb(255,205,46), Color.rgb(255,205,46), Color.rgb(234,242,249), Color.rgb(45,225,114)};

            float innerH = cardBottom-cardTop;
            float rowH = innerH/5f;
            for (int i=0;i<5;i++) {
                float y = cardTop + i*rowH;
                if (i>0) {
                    p.setColor(Color.rgb(29,39,48));
                    p.setStrokeWidth(d(1));
                    canvas.drawLine(d(68), y, w-d(28), y, p);
                }
                txt(icons[i], d(38), y + rowH*0.55f, 16, iconColors[i], true, Paint.Align.CENTER);
                txt(values[i], d(76), y + rowH*0.43f, 18, Color.WHITE, false, Paint.Align.LEFT);
                txt(labels[i], d(76), y + rowH*0.72f, 10.5f, Color.rgb(137,153,168), false, Paint.Align.LEFT);
                if (i==2) {
                    float bx0 = w*0.61f, bx1 = w-d(28), by = y+rowH*0.64f;
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.rgb(31,42,51));
                    canvas.drawRoundRect(new RectF(bx0,by,bx1,by+d(6)), d(3), d(3), p);
                    p.setColor(Color.rgb(45,225,114));
                    float frac = (float)Math.max(0, Math.min(1, state.fuelPercent/100.0));
                    canvas.drawRoundRect(new RectF(bx0,by,bx0+(bx1-bx0)*frac,by+d(6)), d(3), d(3), p);
                }
            }

            float tripY = cardBottom + d(10);
            txt("Trip A", d(20), tripY+d(15), 9.5f, Color.rgb(131,147,161), false, Paint.Align.LEFT);
            txt("126.4 km", d(20), tripY+d(38), 15, Color.WHITE, false, Paint.Align.LEFT);
            p.setColor(Color.rgb(47,58,68));
            p.setStrokeWidth(d(1));
            canvas.drawLine(w*0.52f, tripY, w*0.52f, tripY+d(42), p);
            txt("Сер. швидкість", w*0.58f, tripY+d(15), 9.5f, Color.rgb(131,147,161), false, Paint.Align.LEFT);
            txt("54 km/h", w*0.58f, tripY+d(38), 15, Color.WHITE, false, Paint.Align.LEFT);

            activeCanvas = null;
        }
    }
}
