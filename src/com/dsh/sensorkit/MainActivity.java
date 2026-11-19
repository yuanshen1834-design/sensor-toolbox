package com.dsh.sensorkit;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String VER = "4.7";

    private SensorHub hub;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Rtos.Mailbox sensorBox = new Rtos.Mailbox("sensor_box", 64);
    private final Rtos.Semaphore logSem = new Rtos.Semaphore("log_sem", 1);
    private final Rtos.Mutex devLock = new Rtos.Mutex("dev_lock");
    private final Rtos.RingLog kernelLog = new Rtos.RingLog(200);

    private TextView appBarTitle;
    private TextView appBarSub;
    private FrameLayout content;
    private Views.NavBar navBar;
    private int tab = 0;
    private View[] pages = new View[5];

    // 概览
    private Views.CompassView compassView;
    private Views.LevelView levelView;
    private TextView headingV;
    private TextView directionV;
    private TextView lightV;
    private TextView proxV;
    private TextView tiltV;
    private TextView summaryV;

    // 电池
    private TextView batLevel, batVolt, batCur, batPower, batTemp, batHealth, batStatus,
            batCycle, batTime, batCharge, batFull;
    private TextView sigSummary;
    private long lastSignalMs;
    private Views.Spark batSpark;
    private long lastBatUpdate;
    private double batLastCurrentMa;

    // 内核
    private TextView kUptime, kTicks, kTasks, kCtx, kDevices, kObj, kJitter, kLoad;
    private TextView kIpc, kLogView;
    private Views.RingGauge gaugeLoad, gaugeDev;
    private TextView scheduleState;
    private LinearLayout taskContainer, devContainer, timerContainer;
    private final List<TextView[]> taskRows = new ArrayList<>();
    private final List<TextView[]> devRows = new ArrayList<>();
    private final List<TextView[]> timerRows = new ArrayList<>();

    // 控制台
    private TextView consoleOut;
    private EditText consoleIn;
    private final List<String> consoleLines = new ArrayList<>();

    // 工具
    private TextView cameraStatus, nfcStatus, nfcData;
    private TextureView preview;
    private CameraCtl camera;
    private TextureView detectPreview;
    private Views.DetectionOverlay overlayView;
    private Detector detector;
    private TextView detStatus, detList, aiResult;
    private volatile boolean localDetect = true;
    private volatile boolean useNnapi = false;
    private SharedPreferences sp;
    private volatile byte[] frameNv21;
    private volatile int frameW, frameH;
    private volatile long lastDetectMs;
    private volatile int camRotation = 90;
    private volatile int rotOverride = -1; // -1=自动，其余为强制角度
    private Views.PointCloudView cloudView;
    private volatile long lastLocalHitMs;
    private volatile long lastOnlineTryMs;
    private volatile long onlineAtMs;
    private volatile long lastInferMs;
    private volatile java.util.List<Detector.Det> lastDets;
    private volatile long lastDetsAt;
    private volatile java.util.List<Detector.Det> onlineDets;

    private boolean scanOn, colorOn;
    private TextView scanResult, colorHex, nfcWriteStatus;
    private View colorSwatch;
    private EditText nfcInput;
    private String pendingWrite = null;
    private boolean pendingWriteUri = false;
    private Vibrator vibrator;
    private android.nfc.NfcAdapter nfcAdapter;
    private boolean nfcReading;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        App.mark("MainActivity.onCreate v" + VER);
        sp = getSharedPreferences("sensorkit", MODE_PRIVATE);
        rotOverride = sp.getInt("rot", -1);
        Window w = getWindow();
        w.setStatusBarColor(Md3.BG);
        w.setNavigationBarColor(Md3.BG);

        hub = new SensorHub();
        hub.init(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        bootKernel();

        setContentView(buildShell());
        initDetectorAsync();
        selectTab(0);
        logLine("内核启动完成，版本 " + VER);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.openAll();
        ui.post(ticker);
        startNfc();
        if (tab == 1 || tab == 2) startCameraIfPossible(tab == 2 ? detectPreview : preview);
    }

    @Override
    protected void onPause() {
        super.onPause();
        hub.stop();
        ui.removeCallbacks(ticker);
        if (camera != null) camera.stop();
        stopNfc();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Rtos.Scheduler.stopAll();
        if (camera != null) camera.stop();
    }

    // ---------------- 外壳 ----------------

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Md3.BG);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        int padH = Md3.dp(this, 20);
        bar.setPadding(padH, Md3.dp(this, 14), padH, Md3.dp(this, 8));
        root.addView(bar);

        appBarTitle = Md3.text(this, "传感器工具箱", 22, Md3.ON_SURFACE);
        appBarTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        bar.addView(appBarTitle);

        appBarSub = Md3.text(this, "v" + VER + " · 软实时传感器平台", 12, Md3.ON_SURFACE_VARIANT);
        bar.addView(appBarSub);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pages = new View[5];
        pages[0] = safePage("概览", this::buildOverview);
        pages[1] = safePage("工具", this::buildTools);
        pages[2] = safePage("识别", this::buildDetect);
        pages[3] = safePage("内核", this::buildKernel);
        pages[4] = safePage("控制台", this::buildConsole);
        for (View v : pages) {
            v.setVisibility(View.GONE);
            content.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        navBar = new Views.NavBar(this);
        navBar.setListener(this::selectTab);
        root.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 80)));

        return root;
    }

    interface PageBuilder {
        View build();
    }

    private View safePage(String name, PageBuilder builder) {
        try {
            return builder.build();
        } catch (Throwable t) {
            App.write(Thread.currentThread(), t);
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL);
            int p = Md3.dp(this, 16);
            l.setPadding(p, p, p, p);
            TextView t1 = Md3.text(this, name + "页初始化失败", 16, Md3.ERROR);
            l.addView(t1);
            TextView t2 = Md3.text(this, String.valueOf(t), 12, Md3.ON_SURFACE_VARIANT);
            l.addView(t2);
            return l;
        }
    }

    private void selectTab(int i) {
        tab = i;
        String[] titles = {"概览", "工具", "识别", "内核", "控制台"};
        String[] subs = {"传感器实时状态", "摄像头 · NFC · 手电筒", "离线目标检测 + AI 解读",
                "类 RT-Thread 内核", "msh 命令控制台"};
        appBarTitle.setText(titles[i]);
        appBarSub.setText("v" + VER + " · " + subs[i]);
        for (int k = 0; k < pages.length; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        }
        navBar.setSelected(i);
        if (i == 4) renderConsole();
        if (i == 1) {
            startCameraIfPossible(preview);
        } else if (i == 2) {
            startCameraIfPossible(detectPreview);
        } else if (camera != null) {
            camera.stop();
        }
        refresh();
    }

    private ScrollView scrollPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        return sv;
    }

    private LinearLayout pageRoot(ScrollView sv) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = Md3.dp(this, 16);
        l.setPadding(p, Md3.dp(this, 4), p, Md3.dp(this, 24));
        sv.addView(l);
        return l;
    }

    // ---------------- 概览 ----------------

    private View buildOverview() {
        ScrollView sv = scrollPage();
        LinearLayout root = pageRoot(sv);

        LinearLayout row = Md3.row(this);
        root.addView(row);

        LinearLayout lightCard = Md3.card(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = Md3.dp(this, 12);
        row.addView(lightCard, half);
        lightCard.addView(Md3.label(this, "环境光"));
        lightV = Md3.numeric(this, "—", 26);
        lightCard.addView(lightV);

        LinearLayout proxCard = Md3.card(this);
        row.addView(proxCard, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        proxCard.addView(Md3.label(this, "接近距离"));
        proxV = Md3.numeric(this, "—", 26);
        proxCard.addView(proxV);

        root.addView(Md3.spacer(this, 12));

        LinearLayout compassCard = Md3.card(this);
        compassCard.addView(Md3.title(this, "指南针"));
        compassView = new Views.CompassView(this);
        compassCard.addView(compassView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 260)));
        headingV = new TextView(this);
        headingV.setTextColor(Md3.ON_SURFACE);
        headingV.setTextSize(30);
        headingV.setGravity(Gravity.CENTER);
        compassCard.addView(headingV);
        directionV = new TextView(this);
        directionV.setTextColor(Md3.ON_SURFACE_VARIANT);
        directionV.setTextSize(13);
        directionV.setGravity(Gravity.CENTER);
        compassCard.addView(directionV);
        root.addView(compassCard, Md3.cardLp(this));

        LinearLayout levelCard = Md3.card(this);
        levelCard.addView(Md3.title(this, "水平仪"));
        levelView = new Views.LevelView(this);
        levelCard.addView(levelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 230)));
        tiltV = new TextView(this);
        tiltV.setTextColor(Md3.ON_SURFACE_VARIANT);
        tiltV.setTextSize(13);
        tiltV.setGravity(Gravity.CENTER);
        levelCard.addView(tiltV);
        root.addView(levelCard, Md3.cardLp(this));

        LinearLayout batCard = Md3.card(this);
        batCard.addView(Md3.title(this, "电池"));
        LinearLayout batRow = Md3.row(this);
        batRow.setPadding(0, Md3.dp(this, 6), 0, 0);
        batLevel = Md3.numeric(this, "—", 30);
        batRow.addView(batLevel);
        TextView pct = Md3.label(this, "% 电量");
        pct.setPadding(Md3.dp(this, 4), Md3.dp(this, 8), 0, 0);
        batRow.addView(pct);
        batCard.addView(batRow);
        batStatus = infoLine(batCard, "状态");
        batVolt = infoLine(batCard, "电压");
        batCur = infoLine(batCard, "电流");
        batPower = infoLine(batCard, "功率");
        batTemp = infoLine(batCard, "温度");
        batHealth = infoLine(batCard, "健康");
        batCycle = infoLine(batCard, "循环次数");
        batTime = infoLine(batCard, "可用时间");
        batCharge = infoLine(batCard, "当前电荷");
        batFull = infoLine(batCard, "满电容量");
        batSpark = new Views.Spark(this);
        batCard.addView(batSpark, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 64)));
        root.addView(batCard, Md3.cardLp(this));

        LinearLayout sigCard = Md3.card(this);
        sigCard.addView(Md3.title(this, "双卡信号"));
        sigSummary = Md3.text(this, "点下方按钮读取（需要电话权限）", 12, Md3.ON_SURFACE);
        sigSummary.setTypeface(android.graphics.Typeface.MONOSPACE);
        sigSummary.setPadding(0, Md3.dp(this, 6), 0, 0);
        sigSummary.setTextIsSelectable(true);
        sigCard.addView(sigSummary);
        TextView sigBtn = Md3.button(this, "读取 / 刷新", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbl.topMargin = Md3.dp(this, 10);
        sigBtn.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 9);
            } else {
                readSignal(true);
            }
        });
        sigCard.addView(sigBtn, sbl);
        root.addView(sigCard, Md3.cardLp(this));

        LinearLayout sumCard = Md3.card(this);
        sumCard.addView(Md3.title(this, "平台状态"));
        summaryV = Md3.text(this, "—", 13, Md3.ON_SURFACE_VARIANT);
        summaryV.setPadding(0, Md3.dp(this, 6), 0, 0);
        sumCard.addView(summaryV);
        root.addView(sumCard, Md3.cardLp(this));

        return sv;
    }

    // ---------------- 工具 ----------------

    private View buildTools() {
        ScrollView sv = scrollPage();
        LinearLayout root = pageRoot(sv);

        LinearLayout camCard = Md3.card(this);
        camCard.addView(Md3.title(this, "摄像头"));
        cameraStatus = Md3.text(this, "准备中…", 12, Md3.ON_SURFACE_VARIANT);
        cameraStatus.setPadding(0, Md3.dp(this, 4), 0, Md3.dp(this, 8));
        camCard.addView(cameraStatus);

        preview = new TextureView(this);
        LinearLayout.LayoutParams pv = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 200));
        camCard.addView(preview, pv);

        LinearLayout camBtns = Md3.row(this);
        camBtns.setPadding(0, Md3.dp(this, 12), 0, 0);
        TextView shoot = Md3.button(this, "拍照", Md3.PRIMARY, Md3.ON_PRIMARY);
        shoot.setOnClickListener(v -> {
            if (camera != null) camera.capture();
        });
        camBtns.addView(shoot);
        TextView torch = Md3.button(this, "手电筒", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        torch.setOnClickListener(v -> {
            if (camera != null) camera.toggleTorch(torch);
        });
        LinearLayout.LayoutParams tp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp2.leftMargin = Md3.dp(this, 10);
        camBtns.addView(torch, tp2);

        LinearLayout.LayoutParams rl2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl2.leftMargin = Md3.dp(this, 10);
        camBtns.addView(rotationChip(), rl2);
        camCard.addView(camBtns);
        root.addView(camCard, Md3.cardLp(this));

        LinearLayout qrCard = Md3.card(this);
        qrCard.addView(Md3.title(this, "扫码"));
        scanResult = Md3.text(this, "点「开始扫码」，对准二维码或条形码", 13, Md3.ON_SURFACE_VARIANT);
        scanResult.setPadding(0, Md3.dp(this, 8), 0, 0);
        scanResult.setTextIsSelectable(true);
        qrCard.addView(scanResult);
        LinearLayout qrBtns = Md3.row(this);
        qrBtns.setPadding(0, Md3.dp(this, 12), 0, 0);
        TextView scanBtn = Md3.button(this, "开始扫码", Md3.PRIMARY, Md3.ON_PRIMARY);
        scanBtn.setOnClickListener(v -> {
            scanOn = !scanOn;
            scanBtn.setText(scanOn ? "停止扫码" : "开始扫码");
            scanResult.setText(scanOn ? "对准二维码…" : "已停止扫码");
        });
        qrBtns.addView(scanBtn);
        TextView copyBtn = Md3.button(this, "复制结果", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        copyBtn.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("扫码结果",
                    scanResult.getText().toString()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbl.leftMargin = Md3.dp(this, 10);
        qrBtns.addView(copyBtn, cbl);
        qrCard.addView(qrBtns);
        root.addView(qrCard, Md3.cardLp(this));

        LinearLayout colorCard = Md3.card(this);
        colorCard.addView(Md3.title(this, "取色器"));
        LinearLayout colorRow = Md3.row(this);
        colorRow.setPadding(0, Md3.dp(this, 10), 0, 0);
        colorSwatch = new View(this);
        colorSwatch.setBackground(Md3.rounded(this, Md3.SURFACE_HIGH, 12));
        colorRow.addView(colorSwatch, new LinearLayout.LayoutParams(
                Md3.dp(this, 64), Md3.dp(this, 64)));
        colorHex = Md3.text(this, "# ——", 20, Md3.ON_SURFACE);
        colorHex.setTypeface(android.graphics.Typeface.MONOSPACE);
        colorHex.setPadding(Md3.dp(this, 16), 0, 0, 0);
        colorRow.addView(colorHex);
        colorCard.addView(colorRow);
        TextView colorBtn = Md3.button(this, "开始取色", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Md3.dp(this, 12);
        colorBtn.setOnClickListener(v -> {
            colorOn = !colorOn;
            colorBtn.setText(colorOn ? "停止取色" : "开始取色");
        });
        colorCard.addView(colorBtn, clp);
        root.addView(colorCard, Md3.cardLp(this));

        LinearLayout nfcCard = Md3.card(this);
        nfcCard.addView(Md3.title(this, "NFC 读卡"));
        nfcStatus = Md3.text(this, "检测中…", 13, Md3.ON_SURFACE_VARIANT);
        nfcStatus.setPadding(0, Md3.dp(this, 6), 0, 0);
        nfcCard.addView(nfcStatus);
        nfcData = Md3.text(this, "把卡片贴到手机背面 NFC 区域", 13, Md3.ON_SURFACE);
        nfcData.setTypeface(android.graphics.Typeface.MONOSPACE);
        nfcData.setPadding(0, Md3.dp(this, 6), 0, 0);
        nfcCard.addView(nfcData);
        TextView nfcBtn = Md3.button(this, "打开 NFC 设置", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        nfcBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_NFC_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams nb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nb.topMargin = Md3.dp(this, 12);
        nfcCard.addView(nfcBtn, nb);

        View div = new View(this);
        div.setBackgroundColor(Md3.OUTLINE_VARIANT);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dlp.topMargin = Md3.dp(this, 14);
        nfcCard.addView(div, dlp);

        TextView wTitle = Md3.title(this, "写入 NFC 标签（NDEF）");
        wTitle.setPadding(0, Md3.dp(this, 12), 0, Md3.dp(this, 6));
        nfcCard.addView(wTitle);

        nfcInput = new EditText(this);
        nfcInput.setHint("要写入的文字或网址，例如 https://example.com");
        nfcInput.setHintTextColor(Md3.OUTLINE);
        nfcInput.setTextColor(Md3.ON_SURFACE);
        nfcInput.setTextSize(13);
        nfcInput.setBackground(Md3.rounded(this, Md3.SURFACE_HIGH, 12));
        int ep = Md3.dp(this, 12);
        nfcInput.setPadding(ep, Md3.dp(this, 8), ep, Md3.dp(this, 8));
        nfcCard.addView(nfcInput);

        LinearLayout wBtnRow = Md3.row(this);
        wBtnRow.setPadding(0, Md3.dp(this, 10), 0, 0);
        TextView wText = Md3.button(this, "写入文本", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        wText.setOnClickListener(v -> armNfcWrite(false));
        wBtnRow.addView(wText);
        TextView wUri = Md3.button(this, "写入网址", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        wUri.setOnClickListener(v -> armNfcWrite(true));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = Md3.dp(this, 10);
        wBtnRow.addView(wUri, wlp);
        nfcCard.addView(wBtnRow);

        nfcWriteStatus = Md3.text(this, "", 12, Md3.ON_SURFACE_VARIANT);
        nfcWriteStatus.setPadding(0, Md3.dp(this, 8), 0, 0);
        nfcCard.addView(nfcWriteStatus);

        root.addView(nfcCard, Md3.cardLp(this));

        LinearLayout vibeCard = Md3.card(this);
        vibeCard.addView(Md3.title(this, "振动马达"));
        TextView vibeInfo = Md3.text(this, "—", 11, Md3.ON_SURFACE_VARIANT);
        vibeInfo.setPadding(0, Md3.dp(this, 4), 0, Md3.dp(this, 8));
        vibeCard.addView(vibeInfo);
        if (vibrator == null || !vibrator.hasVibrator()) {
            vibeInfo.setText("系统报告：本机没有振动马达");
        } else {
            boolean amp = false;
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    amp = vibrator.hasAmplitudeControl();
                } catch (Throwable ignored) {
                }
            }
            vibeInfo.setText("系统报告：可用" + (amp ? "（支持振幅控制）" : ""));
        }

        LinearLayout vibeRow = Md3.row(this);
        TextView shortV = Md3.button(this, "短振 60ms", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        shortV.setOnClickListener(v -> doVibrate(new long[]{0, 60}, -1));
        vibeRow.addView(shortV);
        TextView longV = Md3.button(this, "长振 800ms", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        LinearLayout.LayoutParams lv = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lv.leftMargin = Md3.dp(this, 8);
        longV.setOnClickListener(v -> doVibrate(new long[]{0, 800}, -1));
        vibeRow.addView(longV, lv);
        TextView patV = Md3.button(this, "节奏", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        LinearLayout.LayoutParams pvl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvl.leftMargin = Md3.dp(this, 8);
        patV.setOnClickListener(v -> doVibrate(new long[]{0, 80, 90, 160, 90, 320}, -1));
        vibeRow.addView(patV, pvl);
        LinearLayout.LayoutParams vrl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vrl.topMargin = Md3.dp(this, 10);
        vibeCard.addView(vibeRow, vrl);
        root.addView(vibeCard, Md3.cardLp(this));

        return sv;
    }

    // ---------------- 内核 ----------------

    private View buildKernel() {
        ScrollView sv = scrollPage();
        LinearLayout root = pageRoot(sv);

        LinearLayout gaugeRow = Md3.row(this);
        LinearLayout g1 = Md3.card(this);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gl.rightMargin = Md3.dp(this, 12);
        gaugeRow.addView(g1, gl);
        g1.setGravity(Gravity.CENTER_HORIZONTAL);
        gaugeDev = new Views.RingGauge(this);
        g1.addView(gaugeDev, new LinearLayout.LayoutParams(Md3.dp(this, 92), Md3.dp(this, 92)));
        g1.addView(Md3.label(this, "设备在线"));

        LinearLayout g2 = Md3.card(this);
        gaugeRow.addView(g2, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        g2.setGravity(Gravity.CENTER_HORIZONTAL);
        gaugeLoad = new Views.RingGauge(this);
        g2.addView(gaugeLoad, new LinearLayout.LayoutParams(Md3.dp(this, 92), Md3.dp(this, 92)));
        g2.addView(Md3.label(this, "节拍抖动"));
        root.addView(gaugeRow);
        root.addView(Md3.spacer(this, 12));

        LinearLayout info = Md3.card(this);
        info.addView(Md3.title(this, "调度器"));
        kUptime = infoLine(info, "运行时长");
        kTicks = infoLine(info, "系统节拍");
        kCtx = infoLine(info, "上下文切换");
        kJitter = infoLine(info, "节拍抖动");
        kLoad = infoLine(info, "内核占用");
        kObj = infoLine(info, "内核对象");
        kTasks = infoLine(info, "线程数");
        kDevices = infoLine(info, "设备数");
        scheduleState = Md3.text(this, "", 12, Md3.ON_SURFACE_VARIANT);
        scheduleState.setPadding(0, Md3.dp(this, 8), 0, 0);
        info.addView(scheduleState);

        LinearLayout btnRow = Md3.row(this);
        btnRow.setPadding(0, Md3.dp(this, 12), 0, 0);
        TextView pauseBtn = Md3.button(this, "暂停调度", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        pauseBtn.setOnClickListener(v -> {
            boolean p = !Rtos.Scheduler.paused();
            Rtos.Scheduler.setPaused(p);
            pauseBtn.setText(p ? "恢复调度" : "暂停调度");
            logLine(p ? "调度器暂停" : "调度器恢复");
        });
        btnRow.addView(pauseBtn);
        TextView addTaskBtn = Md3.button(this, "新建线程", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        addTaskBtn.setOnClickListener(v -> {
            int n = Rtos.Scheduler.tasks().size();
            Rtos.Scheduler.create("user_" + n, 5, 200, () -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            });
            logLine("新建线程 user_" + n + "，优先级 5");
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.leftMargin = Md3.dp(this, 10);
        btnRow.addView(addTaskBtn, al);
        info.addView(btnRow);
        root.addView(info, Md3.cardLp(this));

        LinearLayout taskCard = Md3.card(this);
        taskCard.addView(Md3.title(this, "线程管理"));
        taskContainer = new LinearLayout(this);
        taskContainer.setOrientation(LinearLayout.VERTICAL);
        taskCard.addView(taskContainer);
        root.addView(taskCard, Md3.cardLp(this));

        LinearLayout devCard = Md3.card(this);
        devCard.addView(Md3.title(this, "设备管理"));
        devContainer = new LinearLayout(this);
        devContainer.setOrientation(LinearLayout.VERTICAL);
        devCard.addView(devContainer);
        root.addView(devCard, Md3.cardLp(this));

        LinearLayout timerCard = Md3.card(this);
        timerCard.addView(Md3.title(this, "软定时器"));
        timerContainer = new LinearLayout(this);
        timerContainer.setOrientation(LinearLayout.VERTICAL);
        timerCard.addView(timerContainer);
        TextView addTimer = Md3.button(this, "新建 500ms 定时器", Md3.SURFACE_HIGH, Md3.ON_SURFACE);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Md3.dp(this, 10);
        final int[] c = {0};
        addTimer.setOnClickListener(v -> {
            int idx = Rtos.timers().size() + 1;
            Rtos.createTimer("timer_" + idx, 500, () -> { });
            logLine("新建定时器 timer_" + idx + "（500ms 周期）");
        });
        timerCard.addView(addTimer, tl);
        root.addView(timerCard, Md3.cardLp(this));

        LinearLayout ipcCard = Md3.card(this);
        ipcCard.addView(Md3.title(this, "IPC"));
        kIpc = Md3.text(this, "—", 12, Md3.ON_SURFACE);
        kIpc.setTypeface(android.graphics.Typeface.MONOSPACE);
        kIpc.setPadding(0, Md3.dp(this, 8), 0, 0);
        ipcCard.addView(kIpc);
        root.addView(ipcCard, Md3.cardLp(this));

        LinearLayout logCard = Md3.card(this);
        logCard.addView(Md3.title(this, "内核日志"));
        kLogView = Md3.text(this, "—", 11, Md3.ON_SURFACE_VARIANT);
        kLogView.setTypeface(android.graphics.Typeface.MONOSPACE);
        kLogView.setPadding(0, Md3.dp(this, 8), 0, 0);
        logCard.addView(kLogView);
        root.addView(logCard, Md3.cardLp(this));

        return sv;
    }

    private TextView infoLine(LinearLayout card, String key) {
        LinearLayout r = Md3.row(this);
        r.setPadding(0, Md3.dp(this, 4), 0, 0);
        TextView k = Md3.label(this, key);
        k.setWidth(Md3.dp(this, 86));
        r.addView(k);
        TextView v = Md3.text(this, "—", 13, Md3.ON_SURFACE);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        r.addView(v);
        card.addView(r);
        return v;
    }

    // ---------------- 控制台 ----------------

    static class CmdItem {
        String title;
        String sub;
        String cmd;
        java.util.List<String> next;

        CmdItem(String title, String sub, String cmd, java.util.List<String> next) {
            this.title = title;
            this.sub = sub;
            this.cmd = cmd;
            this.next = next;
        }
    }

    private final java.util.List<String> consoleStack = new java.util.ArrayList<>();
    private LinearLayout consoleItems;
    private TextView consolePath;

    private View buildConsole() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Md3.dp(this, 16);
        root.setPadding(p, Md3.dp(this, 4), p, Md3.dp(this, 10));

        LinearLayout bar = Md3.row(this);
        TextView back = Md3.chip(this, "‹ 返回", false);
        back.setOnClickListener(v -> {
            if (!consoleStack.isEmpty()) {
                consoleStack.remove(consoleStack.size() - 1);
                renderConsole();
            }
        });
        bar.addView(back);
        consolePath = Md3.text(this, "控制台", 12, Md3.ON_SURFACE_VARIANT);
        consolePath.setPadding(Md3.dp(this, 12), 0, 0, 0);
        bar.addView(consolePath, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        consoleItems = new LinearLayout(this);
        consoleItems.setOrientation(LinearLayout.VERTICAL);
        sv.addView(consoleItems);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout outCard = Md3.card(this);
        TextView t = Md3.label(this, "输出");
        outCard.addView(t);
        consoleOut = Md3.text(this, "点上面的命令…", 11, Md3.ON_SURFACE);
        consoleOut.setTypeface(android.graphics.Typeface.MONOSPACE);
        consoleOut.setPadding(0, Md3.dp(this, 6), 0, 0);
        consoleOut.setTextIsSelectable(true);
        ScrollView outScroll = new ScrollView(this);
        outScroll.addView(consoleOut);
        outCard.addView(outScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 104)));
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Md3.dp(this, 150));
        ol.topMargin = Md3.dp(this, 8);
        root.addView(outCard, ol);

        return root;
    }

    private void renderConsole() {
        if (consoleItems == null) return;
        consoleItems.removeAllViews();
        StringBuilder path = new StringBuilder("控制台");
        for (String s : consoleStack) path.append(" › ").append(s);
        consolePath.setText(path.toString());

        java.util.List<CmdItem> items = itemsFor();
        for (final CmdItem it : items) {
            LinearLayout row = Md3.card(this);
            row.setPadding(Md3.dp(this, 14), Md3.dp(this, 12), Md3.dp(this, 14), Md3.dp(this, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Md3.dp(this, 8);

            TextView title = Md3.text(this, it.title, 14, Md3.ON_SURFACE);
            title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
            row.addView(title);
            if (it.sub != null) {
                TextView sub = Md3.text(this, it.sub, 11, Md3.ON_SURFACE_VARIANT);
                sub.setPadding(0, Md3.dp(this, 3), 0, 0);
                row.addView(sub);
            }
            row.setOnClickListener(v -> {
                if (it.cmd != null) {
                    exec(it.cmd);
                    renderConsole();
                } else if (it.next != null) {
                    consoleStack.addAll(it.next);
                    renderConsole();
                }
            });
            consoleItems.addView(row, lp);
        }
    }

    private java.util.List<CmdItem> itemsFor() {
        java.util.List<CmdItem> out = new java.util.ArrayList<>();
        if (consoleStack.isEmpty()) {
            out.add(new CmdItem("系统信息", "运行时长 · 内存 · 节拍 · 版本", null,
                    java.util.Arrays.asList("系统信息")));
            out.add(new CmdItem("线程", "列表 · 挂起 · 恢复 · 删除", null,
                    java.util.Arrays.asList("线程")));
            out.add(new CmdItem("设备", "传感器开关与采样间隔", null,
                    java.util.Arrays.asList("设备")));
            out.add(new CmdItem("软定时器", "查看 · 新建 · 停止", null,
                    java.util.Arrays.asList("软定时器")));
            out.add(new CmdItem("IPC", "邮箱 · 信号量 · 互斥量", null,
                    java.util.Arrays.asList("IPC")));
            out.add(new CmdItem("调度器", "暂停 · 恢复", null,
                    java.util.Arrays.asList("调度器")));
            return out;
        }
        String head = consoleStack.get(0);
        if ("系统信息".equals(head)) {
            if (consoleStack.size() == 1) {
                out.add(new CmdItem("运行状态", "节拍、运行时长、抖动", "tick", null));
                out.add(new CmdItem("内存", "Java 堆占用", "free", null));
                out.add(new CmdItem("内核对象", "按类型统计", "list_object", null));
                out.add(new CmdItem("版本", "当前版本号", "version", null));
            }
        } else if ("线程".equals(head)) {
            if (consoleStack.size() == 1) {
                out.add(new CmdItem("线程列表", "名称·优先级·状态·占用", "ps", null));
                for (Rtos.Task t : Rtos.Scheduler.tasks()) {
                    out.add(new CmdItem(t.name, "P" + t.priority + " · " + t.stateCn(),
                            null, java.util.Arrays.asList("线程", t.name)));
                }
            } else {
                String name = consoleStack.get(1);
                out.add(new CmdItem("挂起", "暂停该线程", "suspend " + name, null));
                out.add(new CmdItem("恢复", "唤醒该线程", "resume " + name, null));
                out.add(new CmdItem("删除", "结束并移除", "del " + name, null));
            }
        } else if ("设备".equals(head)) {
            if (consoleStack.size() == 1) {
                out.add(new CmdItem("设备列表", "类型·状态·采样间隔", "list_device", null));
                for (Rtos.Device d : Rtos.devices()) {
                    out.add(new CmdItem(d.name, d.typeCn + " · " + (d.open ? "已打开" : "已关闭"),
                            null, java.util.Arrays.asList("设备", d.name)));
                }
            } else {
                String name = consoleStack.get(1);
                out.add(new CmdItem("打开", "开始接收数据", "open " + name, null));
                out.add(new CmdItem("关闭", "停止接收数据", "close " + name, null));
                out.add(new CmdItem("采样 10ms", "高频", "rate " + name + " 10", null));
                out.add(new CmdItem("采样 50ms", "中频", "rate " + name + " 50", null));
                out.add(new CmdItem("采样 100ms", "低频", "rate " + name + " 100", null));
                out.add(new CmdItem("采样 500ms", "省电", "rate " + name + " 500", null));
            }
        } else if ("软定时器".equals(head)) {
            if (consoleStack.size() == 1) {
                out.add(new CmdItem("定时器列表", "周期与触发次数", "list_timer", null));
                out.add(new CmdItem("新建 100ms", "高频定时器", "timer 100", null));
                out.add(new CmdItem("新建 500ms", "中频定时器", "timer 500", null));
                out.add(new CmdItem("新建 1000ms", "低频定时器", "timer 1000", null));
                for (Rtos.Timer tm : Rtos.timers()) {
                    out.add(new CmdItem("停止 " + tm.name,
                            tm.periodMs + "ms · 已触发 " + tm.triggered,
                            "stop_timer " + tm.name, null));
                }
            }
        } else if ("IPC".equals(head)) {
            out.add(new CmdItem("邮箱", "队列长度与投递统计", "list_mailbox", null));
            out.add(new CmdItem("信号量", "计数与超时", "list_sem", null));
            out.add(new CmdItem("互斥量", "持有者", "list_mutex", null));
        } else if ("调度器".equals(head)) {
            out.add(new CmdItem("暂停调度", "停止节拍推进", "pause", null));
            out.add(new CmdItem("恢复调度", "继续节拍", "resume", null));
            out.add(new CmdItem("当前状态", "节拍与切换次数", "tick", null));
        }
        return out;
    }

    // ---------------- 刷新 ----------------

    private void refresh() {
        try {
            refreshInner();
        } catch (Throwable t) {
            App.write(Thread.currentThread(), t);
        }
    }

    private void refreshInner() {
        List<Rtos.Device> devs = hub.devices();
        Rtos.Device light = hub.device(Sensor.TYPE_LIGHT);
        Rtos.Device prox = hub.device(Sensor.TYPE_PROXIMITY);
        Rtos.Device rot = hub.device(Sensor.TYPE_ROTATION_VECTOR);
        Rtos.Device acc = hub.device(Sensor.TYPE_ACCELEROMETER);

        if (tab == 0) {
            lightV.setText(light == null || light.values.length == 0 ? "—"
                    : String.format(Locale.CHINA, "%.0f lx", light.values[0]));
            proxV.setText(prox == null || prox.values.length == 0 ? "—"
                    : String.format(Locale.CHINA, "%.0f cm", prox.values[0]));

            float az = 0f;
            if (rot != null && rot.values.length >= 3) {
                float[] r = new float[9];
                float[] o = new float[3];
                android.hardware.SensorManager.getRotationMatrixFromVector(r, rot.values);
                android.hardware.SensorManager.getOrientation(r, o);
                az = (float) Math.toDegrees(o[0]);
                if (az < 0) az += 360f;
            }
            compassView.setAzimuth(az);
            headingV.setText(String.format(Locale.CHINA, "%.0f°", az));
            directionV.setText(directionName(az) + "　磁北方向为红色指针");

            if (acc != null && acc.values.length >= 3) {
                float ax = acc.values[0], ay = acc.values[1], az2 = acc.values[2];
                float tx = (float) Math.toDegrees(Math.atan2(ax, Math.sqrt(ay * ay + az2 * az2)));
                float ty = (float) Math.toDegrees(Math.atan2(ay, Math.sqrt(ax * ax + az2 * az2)));
                float tot = (float) Math.toDegrees(Math.atan2(Math.sqrt(ax * ax + ay * ay),
                        Math.abs(az2)));
                levelView.setTilt(tx, ty, tot);
                tiltV.setText(String.format(Locale.CHINA, "左右 %+.1f°　前后 %+.1f°　合计 %.1f°",
                        tx, ty, tot) + (tot < 1.5f ? "　·　已水平" : ""));
            }

            updateBattery();
            readSignal(false);

            summaryV.setText(String.format(Locale.CHINA,
                    "设备 %d 个，已开启 %d 个　|　节拍 %d　|　任务 %d 个　|　运行 %s",
                    devs.size(), Rtos.openDevices(), Rtos.Scheduler.ticks(),
                    Rtos.Scheduler.tasks().size(), uptimeText()));
        } else if (tab == 3) {
            List<Rtos.Task> tasks = Rtos.Scheduler.tasks();
            List<Rtos.Device> kdevs = Rtos.devices();
            List<Rtos.Timer> timers = Rtos.timers();

            int open = Rtos.openDevices();
            gaugeDev.set(kdevs.isEmpty() ? 0f : open / (float) kdevs.size(), open + "/" + kdevs.size());
            long jit = Rtos.Scheduler.tickJitterUs();
            gaugeLoad.set(Math.min(1f, jit / 2000f),
                    String.format(Locale.CHINA, "%.1fms", jit / 1000f));

            kUptime.setText(uptimeText());
            kTicks.setText(Rtos.Scheduler.ticks() + " 拍（10ms/拍）");
            kCtx.setText(Rtos.Scheduler.ctxSwitches() + " 次");
            kJitter.setText(String.format(Locale.CHINA, "均 %.2f ms / 峰 %.2f ms",
                    jit / 1000f, Rtos.Scheduler.tickJitterMaxUs() / 1000f));
            double cpu = 0;
            for (Rtos.Task t : tasks) cpu += t.cpuPercent();
            kLoad.setText(String.format(Locale.CHINA, "%.2f%%（线程合计）", cpu));
            kObj.setText(Rtos.objectCount() + " 个");
            kTasks.setText(tasks.size() + " 个");
            kDevices.setText(kdevs.size() + " 个，已打开 " + open + " 个");
            scheduleState.setText(Rtos.Scheduler.paused() ? "调度器：已暂停" : "调度器：运行中");

            if (taskRows.size() != tasks.size()) rebuildTaskRows(tasks);
            for (int i = 0; i < tasks.size() && i < taskRows.size(); i++) {
                Rtos.Task t = tasks.get(i);
                TextView[] v = taskRows.get(i);
                v[0].setText(t.name);
                v[1].setText(String.format(Locale.CHINA, "P%d %s %.1f%% 抖%s",
                        t.priority, t.stateCn(), t.cpuPercent(), jitterText(t.jitterUs)));
                v[2].setText(t.suspended ? "恢复" : "挂起");
            }

            if (devRows.size() != kdevs.size()) rebuildDevRows(kdevs);
            for (int i = 0; i < kdevs.size() && i < devRows.size(); i++) {
                Rtos.Device d = kdevs.get(i);
                TextView[] v = devRows.get(i);
                v[0].setText(d.name);
                v[1].setText(String.format(Locale.CHINA, "%s  %dms",
                        d.valueText(), d.avgIntervalUs / 1000));
                v[2].setText(d.rateMs + "ms");
                v[3].setText(d.open ? "开" : "关");
            }

            if (timerRows.size() != timers.size()) rebuildTimerRows(timers);
            for (int i = 0; i < timers.size() && i < timerRows.size(); i++) {
                Rtos.Timer tm = timers.get(i);
                TextView[] v = timerRows.get(i);
                v[0].setText(tm.name);
                v[1].setText(String.format(Locale.CHINA, "%dms 触发 %d 次", tm.periodMs, tm.triggered));
            }

            StringBuilder ipc = new StringBuilder();
            for (Rtos.Obj o : Rtos.objects(Rtos.ObjType.MAILBOX)) {
                Rtos.Mailbox mb = (Rtos.Mailbox) o;
                ipc.append(String.format(Locale.CHINA, "邮箱 %s  容量%d 队列%d 已发%d 丢%d\n",
                        mb.name, mb.capacity, mb.size(), mb.sent, mb.dropped));
            }
            for (Rtos.Obj o : Rtos.objects(Rtos.ObjType.SEMAPHORE)) {
                Rtos.Semaphore sem = (Rtos.Semaphore) o;
                ipc.append(String.format(Locale.CHINA, "信号量 %s  值%d 获取%d 超时%d\n",
                        sem.name, sem.value(), sem.takes, sem.timeouts));
            }
            for (Rtos.Obj o : Rtos.objects(Rtos.ObjType.MUTEX)) {
                Rtos.Mutex m = (Rtos.Mutex) o;
                ipc.append(String.format(Locale.CHINA, "互斥量 %s  持有者%s\n",
                        m.name, m.owner == null ? "无" : m.owner));
            }
            kIpc.setText(ipc.toString());

            StringBuilder logs = new StringBuilder();
            List<String> lines = kernelLog.newest(5);
            for (int i = lines.size() - 1; i >= 0; i--) logs.append(lines.get(i)).append('\n');
            kLogView.setText(logs.toString());
        }
    }

    private void doVibrate(long[] pattern, int repeat) {
        try {
            if (vibrator == null || !vibrator.hasVibrator()) {
                Toast.makeText(this, "这台设备没有振动马达", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, repeat));
            } else {
                vibrator.vibrate(pattern, repeat);
            }
            Toast.makeText(this, "已发送振动请求", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            App.write(Thread.currentThread(), t);
            Toast.makeText(this, "振动失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void readSignal(boolean manual) {
        long now = System.currentTimeMillis();
        if (!manual && now - lastSignalMs < 2500) return;
        lastSignalMs = now;
        if (sigSummary == null) return;
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            sigSummary.setText("需要「电话」权限才能读取双卡信息");
            return;
        }
        try {
            android.telephony.SubscriptionManager subs =
                    (android.telephony.SubscriptionManager) getSystemService(
                            android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            android.telephony.TelephonyManager base =
                    (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (subs == null || base == null) {
                sigSummary.setText("本机没有电话服务");
                return;
            }
            java.util.List<android.telephony.SubscriptionInfo> list =
                    subs.getActiveSubscriptionInfoList();
            StringBuilder sb = new StringBuilder();
            if (list == null || list.isEmpty()) {
                sb.append("未检测到已激活的 SIM 卡\n");
                sb.append("（可能未插卡，或系统拒绝提供订阅信息）\n");
            } else {
                for (android.telephony.SubscriptionInfo info : list) {
                    int slot = info.getSimSlotIndex() + 1;
                    int subId = info.getSubscriptionId();
                    android.telephony.TelephonyManager tm = base.createForSubscriptionId(subId);
                    CharSequence carrier = info.getCarrierName();
                    CharSequence disp = info.getDisplayName();
                    sb.append("卡").append(slot).append(" · ")
                      .append(carrier == null ? "未知运营商" : carrier.toString());
                    if (disp != null && disp.length() > 0
                            && !disp.toString().equals(carrier == null ? "" : carrier.toString())) {
                        sb.append("（").append(disp).append("）");
                    }
                    sb.append("\n");
                    try {
                        sb.append("  制式 ").append(netTypeName(tm.getDataNetworkType()));
                    } catch (Throwable t) {
                        sb.append("  制式 需权限");
                    }
                    try {
                        sb.append(" · ").append(serviceStateCn(tm.getServiceState()));
                    } catch (Throwable ignored) {
                    }
                    sb.append("\n");
                    try {
                        android.telephony.SignalStrength ss = tm.getSignalStrength();
                        if (ss != null) {
                            int lv = ss.getLevel();
                            StringBuilder bar = new StringBuilder();
                            for (int i = 0; i < 4; i++) bar.append(i < lv ? "▮" : "▯");
                            sb.append("  等级 ").append(lv).append("/4 ").append(bar);
                            Object cell = null;
                            try {
                                cell = ss.getClass().getMethod("getCellSignalStrength").invoke(ss);
                            } catch (Throwable ignored) {
                            }
                            if (cell != null) {
                                Object dbm = call(cell, "getDbm");
                                if (dbm instanceof Integer && (Integer) dbm < 0) {
                                    sb.append(" · ").append(dbm).append(" dBm");
                                }
                                appendMetric(sb, cell, "getRsrp", "RSRP", "dBm");
                                appendMetric(sb, cell, "getRsrq", "RSRQ", "dB");
                                appendMetric(sb, cell, "getRssnr", "SINR", "dB");
                                appendMetric(sb, cell, "getSsRsrp", "SS-RSRP", "dBm");
                                appendMetric(sb, cell, "getSsSinr", "SS-SINR", "dB");
                                appendMetric(sb, cell, "getRscp", "RSCP", "dBm");
                                appendMetric(sb, cell, "getEcNo", "Ec/No", "dB");
                                appendMetric(sb, cell, "getTimingAdvance", "TA", "");
                            }
                            sb.append("\n");
                        } else {
                            sb.append("  等级 无数据\n");
                        }
                    } catch (SecurityException se) {
                        sb.append("  等级 需电话权限\n");
                    }
                    try {
                        sb.append("  SIM ").append(simStateCn(tm.getSimState()))
                          .append(" · 漫游 ").append(tm.isNetworkRoaming() ? "是" : "否").append("\n");
                    } catch (Throwable ignored) {
                    }
                    try {
                        sb.append("  MCC/MNC ").append(info.getMcc()).append("/").append(info.getMnc())
                          .append(" · 国家 ").append(info.getCountryIso());
                        String num = info.getNumber();
                        if (num != null && num.length() > 0) sb.append(" · 号码 ").append(num);
                        sb.append("\n");
                    } catch (Throwable ignored) {
                    }
                    sb.append("\n");
                }
            }
            sigSummary.setText(sb.toString().trim());
        } catch (Throwable t) {
            sigSummary.setText("读取失败：" + t.getClass().getSimpleName() + "\n" + t.getMessage());
            App.write(Thread.currentThread(), t);
        }
    }

    private Object call(Object obj, String method) {
        try {
            return obj.getClass().getMethod(method).invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private void appendMetric(StringBuilder sb, Object cell, String method, String label, String unit) {
        Object v = call(cell, method);
        if (v instanceof Integer && (Integer) v != Integer.MAX_VALUE && (Integer) v != 0) {
            sb.append(" · ").append(label).append(" ").append(v);
            if (!unit.isEmpty()) sb.append(" ").append(unit);
        }
    }

    private String serviceStateCn(android.telephony.ServiceState st) {
        if (st == null) return "未知状态";
        switch (st.getState()) {
            case android.telephony.ServiceState.STATE_IN_SERVICE: return "服务中";
            case android.telephony.ServiceState.STATE_OUT_OF_SERVICE: return "无服务";
            case android.telephony.ServiceState.STATE_EMERGENCY_ONLY: return "仅紧急呼叫";
            case android.telephony.ServiceState.STATE_POWER_OFF: return "关机";
            default: return "未知";
        }
    }

    private String simStateCn(int st) {
        switch (st) {
            case android.telephony.TelephonyManager.SIM_STATE_READY: return "就绪";
            case android.telephony.TelephonyManager.SIM_STATE_ABSENT: return "无卡";
            case android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED: return "需 PIN";
            case android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED: return "需 PUK";
            case android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "网络锁";
            case android.telephony.TelephonyManager.SIM_STATE_UNKNOWN: return "未知";
            default: return "状态 " + st;
        }
    }

    private String netTypeName(int nt) {
        switch (nt) {
            case 20: return "5G NR";
            case 13: return "LTE";
            case 3: return "UMTS";
            case 9: return "HSPA";
            case 10: return "HSPA+";
            case 1: return "GPRS";
            case 2: return "EDGE";
            case 4: return "CDMA";
            case 11: return "iDEN";
            case 15: return "HSPAP";
            case 16: return "GSM";
            case 17: return "TD-SCDMA";
            case 18: return "IWLAN";
            default: return "类型 " + nt;
        }
    }

    private void updateBattery() {
        try {
            Intent it = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it == null) return;
            int level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int volt = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            int temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int health = it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            int plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            String tech = it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            int cycle = it.getIntExtra("android.os.extra.CYCLE_COUNT", -1);

            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int curUa = bm == null ? Integer.MIN_VALUE
                    : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            int chargeUah = bm == null ? Integer.MIN_VALUE
                    : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);

            if (level >= 0 && scale > 0) {
                batLevel.setText(String.valueOf(Math.round(level * 100f / scale)));
            }
            batStatus.setText(statusCn(status, plugged, tech));
            batVolt.setText(volt > 0 ? String.format(Locale.CHINA, "%.3f V（%d mV）",
                    volt / 1000f, volt) : "—");
            double curMa = curUa == Integer.MIN_VALUE ? 0 : curUa / 1000.0;
            batLastCurrentMa = curMa;
            if (curUa != Integer.MIN_VALUE) {
                batCur.setText(String.format(Locale.CHINA, "%+.0f mA（充电计数 %.0f mAh）",
                        curMa, chargeUah == Integer.MIN_VALUE ? 0 : chargeUah / 1000.0));
                batPower.setText(String.format(Locale.CHINA, "%.2f W",
                        Math.abs(volt / 1000.0 * curMa / 1000.0)));
            } else {
                batCur.setText("本机不提供电流读数");
                batPower.setText("—");
            }
            batTemp.setText(temp > 0 ? String.format(Locale.CHINA, "%.1f ℃",
                    temp / 10.0) : "—");
            if (chargeUah != Integer.MIN_VALUE && chargeUah > 0) {
                double nowMah = chargeUah / 1000.0;
                batCharge.setText(String.format(Locale.CHINA, "%.0f mAh", nowMah));
                if (level > 0 && scale > 0) {
                    double pct = level * 100.0 / scale;
                    batFull.setText(String.format(Locale.CHINA,
                            "约 %.0f mAh（按 %.0f%% 反推）", nowMah / pct * 100.0, pct));
                } else {
                    batFull.setText("—");
                }
            } else {
                batCharge.setText("系统未提供");
                batFull.setText("—");
            }
            batHealth.setText(healthCn(health));
            batCycle.setText(cycle > 0 ? cycle + " 次" : "系统未提供");
            if (curMa < -50 && chargeUah > 0) {
                double minutes = chargeUah / 1000.0 / Math.abs(curMa) * 60;
                batTime.setText(String.format(Locale.CHINA, "约 %d 小时 %d 分（按当前放电）",
                        (int) (minutes / 60), (int) (minutes % 60)));
            } else {
                batTime.setText(curMa > 50 ? "正在充电" : "—");
            }

            long now = System.currentTimeMillis();
            if (now - lastBatUpdate > 1000) {
                lastBatUpdate = now;
                if (batSpark != null) batSpark.push((float) curMa, "mA");
            }
        } catch (Throwable t) {
            App.write(Thread.currentThread(), t);
        }
    }

    private String statusCn(int status, int plugged, String tech) {
        String s;
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: s = "充电中"; break;
            case BatteryManager.BATTERY_STATUS_DISCHARGING: s = "放电中"; break;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: s = "未充电"; break;
            case BatteryManager.BATTERY_STATUS_FULL: s = "已充满"; break;
            default: s = "未知";
        }
        String via = "";
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) via = " · 交流电";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) via = " · USB";
        else if (plugged == 4) via = " · 无线充电";
        return s + via + (tech == null ? "" : " · " + tech);
    }

    private String healthCn(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "良好";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "过热";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "损坏";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "电压过高";
            case BatteryManager.BATTERY_HEALTH_COLD: return "过冷";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "故障";
            default: return "未知";
        }
    }

    private String uptimeText() {
        long s = Rtos.Scheduler.uptimeMs() / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", s / 60, s % 60);
    }

    private String directionName(float deg) {
        String[] names = {"正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北"};
        int i = Math.round(deg / 45f) % 8;
        return names[i];
    }

    // ---------------- 内核启动 ----------------

    private void bootKernel() {
        Rtos.Scheduler.init();
        if (!Rtos.Scheduler.tasks().isEmpty()) return;

        Rtos.Scheduler.create("sensor_poll", 8, 20, () -> {
            devLock.lock("sensor_poll");
            try {
                sensorBox.put(Long.valueOf(System.currentTimeMillis()));
            } finally {
                devLock.unlock();
            }
            sleepMs(20);
        });
        Rtos.Scheduler.create("ui_refresh", 6, 100, () -> sleepMs(100));
        Rtos.Scheduler.create("log_flush", 4, 300, () -> {
            if (logSem.take(5)) {
                sleepMs(50);
                logSem.release();
            }
        });
        Rtos.Scheduler.create("watchdog", 12, 1000, () -> sleepMs(1000));
        Rtos.Scheduler.create("idle", 1, 8, () -> sleepMs(8));

        Rtos.createTimer("tick_led", 100, () -> { });
        logLine("内核初始化完成：" + Rtos.Scheduler.tasks().size() + " 个线程，"
                + Rtos.devices().size() + " 个设备");
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private String jitterText(long us) {
        return us >= 1000 ? String.format(Locale.CHINA, "%.1fms", us / 1000f)
                : String.format(Locale.CHINA, "%dµs", us);
    }

    private void rebuildTaskRows(List<Rtos.Task> tasks) {
        if (taskContainer == null) return;
        taskContainer.removeAllViews();
        taskRows.clear();
        for (final Rtos.Task t : tasks) {
            LinearLayout r = Md3.row(this);
            r.setPadding(0, Md3.dp(this, 6), 0, 0);
            TextView name = Md3.text(this, t.name, 12, Md3.ON_SURFACE);
            name.setWidth(Md3.dp(this, 76));
            r.addView(name);
            TextView info = Md3.text(this, "", 10, Md3.ON_SURFACE_VARIANT);
            info.setTypeface(android.graphics.Typeface.MONOSPACE);
            r.addView(info, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView act = Md3.chip(this, "挂起", false);
            act.setOnClickListener(v -> {
                if (t.suspended) {
                    t.resume();
                    logLine("线程 " + t.name + " 恢复");
                } else {
                    t.suspend();
                    logLine("线程 " + t.name + " 挂起");
                }
            });
            r.addView(act);
            TextView del = Md3.chip(this, "删", false);
            del.setOnClickListener(v -> {
                logLine("线程 " + t.name + " 已删除");
                t.delete();
            });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.leftMargin = Md3.dp(this, 6);
            r.addView(del, dl);
            taskContainer.addView(r);
            taskRows.add(new TextView[]{name, info, act});
        }
    }

    private void rebuildDevRows(List<Rtos.Device> devs) {
        if (devContainer == null) return;
        devContainer.removeAllViews();
        devRows.clear();
        for (final Rtos.Device d : devs) {
            LinearLayout r = Md3.row(this);
            r.setPadding(0, Md3.dp(this, 6), 0, 0);
            TextView name = Md3.text(this, d.name, 12, Md3.ON_SURFACE);
            name.setWidth(Md3.dp(this, 66));
            r.addView(name);
            TextView info = Md3.text(this, "", 10, Md3.ON_SURFACE_VARIANT);
            info.setTypeface(android.graphics.Typeface.MONOSPACE);
            r.addView(info, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final TextView rate = Md3.chip(this, d.rateMs + "ms", false);
            rate.setOnClickListener(v -> {
                int[] steps = {10, 50, 100, 500};
                int cur = 0;
                for (int i = 0; i < steps.length; i++) if (steps[i] == d.rateMs) cur = i;
                int next = steps[(cur + 1) % steps.length];
                hub.setRate(d, next);
                logLine("设备 " + d.name + " 采样间隔 -> " + next + "ms");
            });
            r.addView(rate);
            final TextView sw = Md3.chip(this, d.open ? "开" : "关", d.open);
            sw.setOnClickListener(v -> {
                hub.setOpen(d, !d.open);
                logLine("设备 " + d.name + (d.open ? " 打开" : " 关闭"));
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.leftMargin = Md3.dp(this, 6);
            r.addView(sw, sl);
            devContainer.addView(r);
            devRows.add(new TextView[]{name, info, rate, sw});
        }
    }

    private void rebuildTimerRows(List<Rtos.Timer> timers) {
        if (timerContainer == null) return;
        timerContainer.removeAllViews();
        timerRows.clear();
        for (final Rtos.Timer tm : timers) {
            LinearLayout r = Md3.row(this);
            r.setPadding(0, Md3.dp(this, 6), 0, 0);
            TextView name = Md3.text(this, tm.name, 12, Md3.ON_SURFACE);
            name.setWidth(Md3.dp(this, 76));
            r.addView(name);
            TextView info = Md3.text(this, "", 10, Md3.ON_SURFACE_VARIANT);
            info.setTypeface(android.graphics.Typeface.MONOSPACE);
            r.addView(info, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView stop = Md3.chip(this, "停止", false);
            stop.setOnClickListener(v -> {
                tm.stop();
                logLine("定时器 " + tm.name + " 已停止");
            });
            r.addView(stop);
            timerContainer.addView(r);
            timerRows.add(new TextView[]{name, info});
        }
    }

    // ---------------- 控制台命令 ----------------

    private void logLine(String s) {
        kernelLog.add(s);
    }

    private void appendConsole(String s) {
        consoleLines.add(s);
        if (consoleLines.size() > 200) consoleLines.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String l : consoleLines) sb.append(l).append('\n');
        if (consoleOut != null) {
            consoleOut.setText(sb.toString());
        }
    }

    private void exec(String cmd) {
        String[] parts = cmd.trim().split("\\s+");
        String c = parts[0];
        appendConsole("msh > " + cmd);
        try {
            switch (c) {
                case "help":
                    appendConsole("msh 命令：\n"
                            + "  help                帮助\n"
                            + "  ps                  线程列表\n"
                            + "  list_device         设备列表\n"
                            + "  list_timer          软定时器\n"
                            + "  list_sem            信号量\n"
                            + "  list_mailbox        邮箱\n"
                            + "  list_object         内核对象统计\n"
                            + "  suspend <name>      挂起线程\n"
                            + "  resume <name>       恢复线程\n"
                            + "  del <name>          删除线程\n"
                            + "  open/close <name>   开关设备\n"
                            + "  rate <name> <ms>    设置采样间隔\n"
                            + "  timer <ms>          新建软定时器\n"
                            + "  free                内存\n"
                            + "  tick                节拍/运行时间/抖动\n"
                            + "  pause / resume      暂停/恢复调度\n"
                            + "  version             版本\n"
                            + "  clear               清屏");
                    break;
                case "ps":
                    for (Rtos.Task t : Rtos.Scheduler.tasks()) {
                        appendConsole(String.format(Locale.CHINA,
                                "%-12s P%-2d %-3s cpu=%.2f%% 抖动=%s",
                                t.name, t.priority, t.stateCn(), t.cpuPercent(),
                                jitterText(t.jitterUs)));
                    }
                    break;
                case "list_device":
                    for (Rtos.Device d : Rtos.devices()) {
                        appendConsole(String.format(Locale.CHINA, "%-8s %-4s %s %5dms  %s",
                                d.name, d.typeCn, d.open ? "[开]" : "[关]", d.rateMs, d.valueText()));
                    }
                    break;
                case "list_timer":
                    if (Rtos.timers().isEmpty()) appendConsole("没有定时器");
                    for (Rtos.Timer tm : Rtos.timers()) {
                        appendConsole(String.format(Locale.CHINA, "%-12s %5dms 触发 %d 次",
                                tm.name, tm.periodMs, tm.triggered));
                    }
                    break;
                case "list_mutex":
                    for (Rtos.Obj o : Rtos.objects(Rtos.ObjType.MUTEX)) {
                        Rtos.Mutex m = (Rtos.Mutex) o;
                        appendConsole(String.format(Locale.CHINA, "%-12s 持有者=%s 加锁=%d 竞争=%d",
                                m.name, m.owner == null ? "无" : m.owner, m.locks, m.contentions));
                    }
                    break;
                case "stop_timer": {
                    if (parts.length < 2) {
                        appendConsole("用法：stop_timer <name>");
                        break;
                    }
                    boolean found = false;
                    for (Rtos.Timer tm : Rtos.timers()) {
                        if (tm.name.equals(parts[1])) {
                            tm.stop();
                            appendConsole(tm.name + " 已停止");
                            found = true;
                        }
                    }
                    if (!found) appendConsole("没有这个定时器");
                    break;
                }
                case "list_sem":
                    for (Rtos.Obj o : Rtos.objects(Rtos.ObjType.SEMAPHORE)) {
                        Rtos.Semaphore sem = (Rtos.Semaphore) o;
                        appendConsole(String.format(Locale.CHINA, "%-12s 值=%d 获取=%d 超时=%d",
                                sem.name, sem.value(), sem.takes, sem.timeouts));
                    }
                    break;
                case "list_mailbox":
                    for (Rtos.Obj o : Rtos.objects(Rtos.ObjType.MAILBOX)) {
                        Rtos.Mailbox mb = (Rtos.Mailbox) o;
                        appendConsole(String.format(Locale.CHINA, "%-12s 队列=%d 已发=%d 丢弃=%d 峰值=%d",
                                mb.name, mb.size(), mb.sent, mb.dropped, mb.peak));
                    }
                    break;
                case "list_object":
                    for (Rtos.ObjType t : Rtos.ObjType.values()) {
                        appendConsole(String.format(Locale.CHINA, "%-6s %d 个", t.cn,
                                Rtos.objects(t).size()));
                    }
                    appendConsole("合计 " + Rtos.objectCount() + " 个内核对象");
                    break;
                case "suspend":
                case "resume":
                case "del": {
                    if (c.equals("resume") && parts.length < 2) {
                        Rtos.Scheduler.setPaused(false);
                        appendConsole("调度已恢复");
                        break;
                    }
                    Rtos.Task t = findTask(parts);
                    if (t == null) {
                        appendConsole("没有这个线程，用 ps 查看");
                        break;
                    }
                    if (c.equals("suspend")) { t.suspend(); appendConsole(t.name + " 已挂起"); }
                    else if (c.equals("resume")) { t.resume(); appendConsole(t.name + " 已恢复"); }
                    else { t.delete(); appendConsole(t.name + " 已删除"); }
                    break;
                }
                case "open":
                case "close": {
                    if (parts.length < 2) { appendConsole("用法：" + c + " <name>"); break; }
                    boolean found = false;
                    for (Rtos.Device d : Rtos.devices()) {
                        if (d.name.equals(parts[1])) {
                            hub.setOpen(d, c.equals("open"));
                            appendConsole(d.name + (c.equals("open") ? " 已打开" : " 已关闭"));
                            found = true;
                        }
                    }
                    if (!found) appendConsole("没有这个设备");
                    break;
                }
                case "rate": {
                    if (parts.length < 3) { appendConsole("用法：rate <name> <ms>"); break; }
                    for (Rtos.Device d : Rtos.devices()) {
                        if (d.name.equals(parts[1])) {
                            int ms = Integer.parseInt(parts[2]);
                            hub.setRate(d, ms);
                            appendConsole(d.name + " 采样间隔 " + ms + "ms");
                        }
                    }
                    break;
                }
                case "timer": {
                    int ms = parts.length > 1 ? Integer.parseInt(parts[1]) : 500;
                    int idx = Rtos.timers().size() + 1;
                    Rtos.createTimer("timer_" + idx, ms, () -> { });
                    appendConsole("已创建 timer_" + idx + "，周期 " + ms + "ms");
                    break;
                }
                case "free": {
                    Runtime rt = Runtime.getRuntime();
                    appendConsole(String.format(Locale.CHINA, "总 %.1fMB  已用 %.1fMB  空闲 %.1fMB",
                            rt.totalMemory() / 1048576f,
                            (rt.totalMemory() - rt.freeMemory()) / 1048576f,
                            rt.freeMemory() / 1048576f));
                    break;
                }
                case "tick":
                case "uptime":
                    appendConsole("节拍 " + Rtos.Scheduler.ticks() + "，运行 " + uptimeText()
                            + "，切换 " + Rtos.Scheduler.ctxSwitches() + "，抖动 "
                            + jitterText(Rtos.Scheduler.tickJitterUs()));
                    break;
                case "pause":
                    Rtos.Scheduler.setPaused(true);
                    appendConsole("调度已暂停");
                    break;
                case "resume_all":
                    Rtos.Scheduler.setPaused(false);
                    appendConsole("调度已恢复");
                    break;
                case "version":
                    appendConsole("传感器工具箱 v" + VER + " · 内核 0.2（软实时）");
                    break;
                case "clear":
                    consoleLines.clear();
                    appendConsole("");
                    break;
                default:
                    appendConsole("未知命令：" + c + "，输入 help 查看");
            }
        } catch (Exception e) {
            appendConsole("执行失败：" + e.getClass().getSimpleName());
        }
        logLine("msh: " + cmd);
    }

    private Rtos.Task findTask(String[] parts) {
        if (parts.length < 2) return null;
        for (Rtos.Task t : Rtos.Scheduler.tasks()) if (t.name.equals(parts[1])) return t;
        return null;
    }

    // ---------------- NFC ----------------

    private void startNfc() {
        try {
            nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this);
            if (nfcAdapter == null) {
                if (nfcStatus != null) nfcStatus.setText("这台设备没有 NFC");
                return;
            }
            if (!nfcAdapter.isEnabled()) {
                if (nfcStatus != null) nfcStatus.setText("NFC 未开启，点下方按钮去打开");
                return;
            }
            nfcAdapter.enableReaderMode(this, this::onTag, android.nfc.NfcAdapter.FLAG_READER_NFC_A
                    | android.nfc.NfcAdapter.FLAG_READER_NFC_B
                    | android.nfc.NfcAdapter.FLAG_READER_NFC_F
                    | android.nfc.NfcAdapter.FLAG_READER_NFC_V
                    | android.nfc.NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null);
            nfcReading = true;
            if (nfcStatus != null) nfcStatus.setText("NFC 已就绪，请贴卡");
        } catch (Exception e) {
            if (nfcStatus != null) nfcStatus.setText("NFC 初始化失败：" + e.getMessage());
        }
    }

    /** 有卡贴近：若已装填待写内容则执行写入，否则读取信息。 */
    private void onTag(Tag tag) {
        final String uid = bytesToHex(tag.getId());
        if (pendingWrite != null) {
            final String payload = pendingWrite;
            final boolean asUri = pendingWriteUri;
            pendingWrite = null;
            try {
                Ndef ndef = Ndef.get(tag);
                if (ndef == null) {
                    uiStatus("这张卡不是 NDEF 格式，无法写入");
                    return;
                }
                ndef.connect();
                NdefRecord rec = asUri
                        ? NdefRecord.createUri(payload)
                        : NdefRecord.createTextRecord("zh", payload);
                ndef.writeNdefMessage(new NdefMessage(rec));
                int size = ndef.getMaxSize();
                ndef.close();
                uiStatus("写入成功（容量 " + size + " 字节）：" + payload);
            } catch (Exception e) {
                uiStatus("写入失败：" + e.getClass().getSimpleName());
            }
            return;
        }
        final StringBuilder tech = new StringBuilder();
        for (String t : tag.getTechList()) {
            String simple = t.substring(t.lastIndexOf('.') + 1);
            if (tech.length() > 0) tech.append(" / ");
            tech.append(simple);
        }
        runOnUiThread(() -> {
            if (nfcStatus != null) nfcStatus.setText("已读到卡片");
            if (nfcData != null) nfcData.setText("UID：" + uid + "\n技术：" + tech);
        });
    }

    private void armNfcWrite(boolean asUri) {
        String text = nfcInput == null ? "" : nfcInput.getText().toString().trim();
        if (text.isEmpty()) {
            if (nfcWriteStatus != null) nfcWriteStatus.setText("先输入要写入的内容");
            return;
        }
        pendingWrite = text;
        pendingWriteUri = asUri;
        if (nfcWriteStatus != null) {
            nfcWriteStatus.setText("已就绪：请把标签贴到手机背面 NFC 区域（5 秒内）");
        }
    }

    private void uiStatus(String msg) {
        runOnUiThread(() -> {
            if (nfcWriteStatus != null) nfcWriteStatus.setText(msg);
        });
    }

    private void stopNfc() {
        try {
            if (nfcAdapter != null && nfcReading) nfcAdapter.disableReaderMode(this);
        } catch (Exception ignored) {
        }
        nfcReading = false;
    }

    private String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    // ---------------- 摄像头 ----------------

    private void startCameraIfPossible(final TextureView tv) {
        if (tv == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
            statusText("需要摄像头权限，授权后自动开启");
            return;
        }
        if (!tv.isAvailable()) {
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                    startCameraIfPossible(tv);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture s) {
                }
            });
            return;
        }
        if (camera == null) camera = new CameraCtl();
        if (camera.torch) camera.stop();
        camera.start(tv);
    }

    private void statusText(String s) {
        if (cameraStatus != null) cameraStatus.setText(s);
        if (detStatus != null) detStatus.setText(s);
    }

    // ---------------- 识别页 ----------------

    private View buildDetect() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Md3.dp(this, 16);
        root.setPadding(p, Md3.dp(this, 4), p, Md3.dp(this, 10));

        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.VERTICAL);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(Md3.rounded(this, Color.BLACK, 16));
        detectPreview = new TextureView(this);
        box.addView(detectPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayView = new Views.DetectionOverlay(this);
        box.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.66f);
        blp.bottomMargin = Md3.dp(this, 10);
        split.addView(box, blp);

        LinearLayout cloudCard = Md3.card(this);
        cloudCard.setPadding(Md3.dp(this, 8), Md3.dp(this, 8), Md3.dp(this, 8), Md3.dp(this, 8));
        TextView cloudTitle = Md3.label(this, "点云 · 物体轮廓");
        cloudTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        cloudCard.addView(cloudTitle);
        cloudView = new Views.PointCloudView(this);
        cloudCard.addView(cloudView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        split.addView(cloudCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.34f));

        root.addView(split, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        detStatus = Md3.text(this, "本地模型加载中…", 11, Md3.ON_SURFACE_VARIANT);
        detStatus.setPadding(0, Md3.dp(this, 8), 0, 0);
        root.addView(detStatus);

        detList = Md3.text(this, "画面里的物体：等待识别", 13, Md3.ON_SURFACE);
        detList.setTypeface(android.graphics.Typeface.MONOSPACE);
        detList.setPadding(0, Md3.dp(this, 6), 0, 0);
        root.addView(detList);

        aiResult = Md3.text(this, "", 12, Md3.ON_SURFACE_VARIANT);
        aiResult.setPadding(0, Md3.dp(this, 6), 0, 0);
        root.addView(aiResult);

        LinearLayout row = Md3.row(this);
        row.setPadding(0, Md3.dp(this, 10), 0, 0);
        final TextView localBtn = Md3.chip(this, "本地检测：开", true);
        localBtn.setOnClickListener(v -> {
            localDetect = !localDetect;
            localBtn.setText(localDetect ? "本地检测：开" : "本地检测：关");
            if (!localDetect && overlayView != null) overlayView.setDetections(null, 90, 640, 480);
        });
        row.addView(localBtn);

        TextView aiBtn = Md3.chip(this, "AI 中文解读", false);
        aiBtn.setOnClickListener(v -> aiDescribe());
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        al.leftMargin = Md3.dp(this, 8);
        row.addView(aiBtn, al);

        TextView keyBtn = Md3.chip(this, "设置 Key", false);
        keyBtn.setOnClickListener(v -> showKeyDialog());
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.leftMargin = Md3.dp(this, 8);
        row.addView(keyBtn, kl);

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.leftMargin = Md3.dp(this, 8);
        row.addView(rotationChip(), rl);

        TextView accBtn = Md3.chip(this, "加速：CPU", false);
        accBtn.setOnClickListener(v -> {
            useNnapi = !useNnapi;
            accBtn.setText(useNnapi ? "加速：NNAPI" : "加速：CPU");
            if (detector != null) {
                detector.close();
                detector = null;
            }
            detStatus.setText("正在切换加速后端…");
            initDetectorAsync();
        });
        LinearLayout.LayoutParams acl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acl.leftMargin = Md3.dp(this, 8);
        row.addView(accBtn, acl);

        TextView benchBtn = Md3.chip(this, "测速", false);
        benchBtn.setOnClickListener(v -> runBenchmark());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.leftMargin = Md3.dp(this, 8);
        row.addView(benchBtn, bl);
        root.addView(row);

        return root;
    }

    private String rotLabel() {
        return rotOverride < 0 ? "旋转：自动" : "旋转：" + rotOverride + "°";
    }

    private void initDetectorAsync() {
        if (sp == null) {
            sp = getSharedPreferences("sensorkit", MODE_PRIVATE);
            rotOverride = sp.getInt("rot", -1);
        }
        new Thread(() -> {
            final Detector d = new Detector();
            d.init(this, useNnapi);
            detector = d;
            runOnUiThread(() -> {
                if (detStatus != null) {
                    detStatus.setText(d.ready()
                            ? "本地模型就绪：" + d.inputSize() + "×" + d.inputSize()
                              + " 量化 int8，4 线程 CPU"
                            : "本地模型不可用：" + d.error());
                }
            });
        }).start();
    }

    private void aiDescribe() {
        final byte[] yuv = frameNv21;
        final int w = frameW, h = frameH;
        if (yuv == null) {
            aiResult.setText("还没拿到画面，等预览起来再试");
            return;
        }
        final String key = sp == null ? "" : sp.getString("key", "");
        if (key.isEmpty()) {
            aiResult.setText("请先用「设置 Key」填入 DeepSeek API Key");
            return;
        }
        aiResult.setText("AI 分析中…");
        new Thread(() -> {
            try {
                android.graphics.YuvImage yi =
                        new android.graphics.YuvImage(yuv, ImageFormat.NV21, w, h, null);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                yi.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 70, bos);
                String b64 = android.util.Base64.encodeToString(bos.toByteArray(),
                        android.util.Base64.NO_WRAP);
                JSONObject body = new JSONObject();
                body.put("model", "deepseek-flash");
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text")
                        .put("text", "用中文简要描述这张照片里的主要物体、大致数量和相对位置，60 字以内。"));
                content.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject()
                                .put("url", "data:image/jpeg;base64," + b64)
                                .put("detail", "low")));
                body.put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", content)));

                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "https://api.deepseek.com/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                int code = conn.getResponseCode();
                InputStream is = code >= 200 && code < 300
                        ? conn.getInputStream() : conn.getErrorStream();
                String resp = readStream(is);
                if (code != 200) {
                    final int c = code;
                    runOnUiThread(() -> aiResult.setText("API 返回错误 " + c));
                    return;
                }
                JSONObject json = new JSONObject(resp);
                String text = json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
                runOnUiThread(() -> aiResult.setText(text.trim()));
            } catch (Exception e) {
                runOnUiThread(() -> aiResult.setText("请求失败：" + e.getClass().getSimpleName()));
            }
        }).start();
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private void showKeyDialog() {
        final EditText et = new EditText(this);
        et.setHint("sk-...");
        et.setText(sp == null ? "" : sp.getString("key", ""));
        new android.app.AlertDialog.Builder(this)
                .setTitle("DeepSeek API Key（只存在本机）")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    sp.edit().putString("key", et.getText().toString().trim()).apply();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runBenchmark() {
        final byte[] yuv = frameNv21;
        if (detector == null || !detector.ready()) {
            detStatus.setText("模型还没就绪");
            return;
        }
        if (yuv == null) {
            detStatus.setText("还没拿到画面");
            return;
        }
        final int w = frameW, h = frameH;
        detStatus.setText("测速中…");
        new Thread(() -> {
            int n = 20;
            detector.detect(yuv, w, h, 0.6f);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) detector.detect(yuv, w, h, 0.6f);
            long dt = (System.nanoTime() - t0) / 1_000_000L;
            final String s = String.format(Locale.CHINA,
                    "本机实测：%.1f ms/帧，约 %.0f fps（%d×%d int8，CPU 4 线程），%d 帧共 %dms",
                    dt / (double) n, n * 1000.0 / Math.max(1, dt),
                    detector.inputSize(), detector.inputSize(), n, dt);
            runOnUiThread(() -> detStatus.setText(s));
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 9) {
            readSignal(true);
            return;
        }
        if (requestCode == 7) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraIfPossible(tab == 2 ? detectPreview : preview);
            } else {
                cameraStatus.setText("未授予摄像头权限");
            }
        }
    }

    class CameraCtl {
        private CameraManager mgr;
        private String id;
        private CameraDevice device;
        private CameraCaptureSession session;
        private ImageReader jpegReader;
        private ImageReader yuvReader;
        private HandlerThread bgThread;
        private Handler bg;
        private boolean torch;
        private final java.util.concurrent.atomic.AtomicBoolean busy =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private final MultiFormatReader qr = new MultiFormatReader();

        void start(TextureView tv) {
            stop();
            try {
                mgr = (CameraManager) getSystemService(CAMERA_SERVICE);
                if (mgr == null) {
                    cameraStatus.setText("没有摄像头服务");
                    return;
                }
                for (String cid : mgr.getCameraIdList()) {
                    CameraCharacteristics ch = mgr.getCameraCharacteristics(cid);
                    Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                    if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) {
                        id = cid;
                        break;
                    }
                    if (id == null) id = cid;
                }
                if (id == null) {
                    cameraStatus.setText("没有找到摄像头");
                    return;
                }
                bgThread = new HandlerThread("camera");
                bgThread.start();
                bg = new Handler(bgThread.getLooper());

                CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                CameraChars cc = new CameraChars(ch);
                Size jpeg = cc.chooseSize();
                Size yuv = cc.chooseYuvSize();
                Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                int sensor = so == null ? 90 : so;
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                int degrees = 0;
                switch (getWindowManager().getDefaultDisplay().getRotation()) {
                    case Surface.ROTATION_90: degrees = 90; break;
                    case Surface.ROTATION_180: degrees = 180; break;
                    case Surface.ROTATION_270: degrees = 270; break;
                    default: degrees = 0; break;
                }
                int auto;
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    auto = (sensor + degrees) % 360;
                    auto = (360 - auto) % 360;
                } else {
                    auto = (sensor - degrees + 360) % 360;
                }
                camRotation = rotOverride >= 0 ? rotOverride : auto;

                jpegReader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(),
                        ImageFormat.JPEG, 2);
                jpegReader.setOnImageAvailableListener(r -> {
                    try {
                        saveImage(r.acquireNextImage());
                    } catch (Throwable ignored) {
                    }
                }, bg);

                yuvReader = ImageReader.newInstance(yuv.getWidth(), yuv.getHeight(),
                        ImageFormat.YUV_420_888, 3);
                yuvReader.setOnImageAvailableListener(r -> {
                    Image img = null;
                    try {
                        img = r.acquireNextImage();
                    } catch (Throwable ignored) {
                    }
                    if (img == null) return;
                    try {
                        if ((scanOn || colorOn || tab == 2) && busy.compareAndSet(false, true)) {
                            try {
                                handleYuv(img);
                            } finally {
                                busy.set(false);
                            }
                        }
                    } finally {
                        img.close();
                    }
                }, bg);

                cameraStatus.setText("正在打开摄像头…");
                mgr.openCamera(id, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice d) {
                        device = d;
                        try {
                            SurfaceTexture st = tv.getSurfaceTexture();
                            if (st == null) {
                                cameraStatus.setText("预览表面还没准备好");
                                return;
                            }
                            st.setDefaultBufferSize(yuv.getWidth(), yuv.getHeight());
                            List<android.view.Surface> surfaces = new ArrayList<>();
                            surfaces.add(new android.view.Surface(st));
                            surfaces.add(jpegReader.getSurface());
                            surfaces.add(yuvReader.getSurface());
                            d.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession ss) {
                                    session = ss;
                                    try {
                                        CaptureRequest.Builder b =
                                                d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        b.addTarget(surfaces.get(0));
                                        b.addTarget(surfaces.get(2));
                                        ss.setRepeatingRequest(b.build(), null, bg);
                                        configureTransform(tv, yuv);
                                        cameraStatus.setText("预览中 " + yuv.getWidth() + "×"
                                                + yuv.getHeight() + "　·　扫码/取色已就绪");
                                    } catch (Exception e) {
                                        cameraStatus.setText("预览失败：" + e.getMessage());
                                    }
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession ss) {
                                    cameraStatus.setText("预览配置失败");
                                }
                            }, bg);
                        } catch (Exception e) {
                            cameraStatus.setText("打开失败：" + e.getMessage());
                        }
                    }

                    @Override
                    public void onDisconnected(CameraDevice d) {
                        d.close();
                        device = null;
                    }

                    @Override
                    public void onError(CameraDevice d, int error) {
                        cameraStatus.setText("摄像头错误码 " + error);
                        d.close();
                        device = null;
                    }
                }, bg);
            } catch (Exception e) {
                cameraStatus.setText("摄像头不可用：" + e.getMessage());
            }
        }

        /** 同时处理扫码与取色（同一帧）。 */
        private void handleYuv(Image img) {
            int w = img.getWidth(), h = img.getHeight();
            byte[] data = new byte[w * h];
            try {
                Image.Plane p0 = img.getPlanes()[0];
                java.nio.ByteBuffer buf = p0.getBuffer();
                int rowStride = p0.getRowStride();
                byte[] row = new byte[w];
                for (int y = 0; y < h; y++) {
                    int pos = y * rowStride;
                    if (pos + w > buf.limit()) break;
                    buf.position(pos);
                    buf.get(row, 0, w);
                    System.arraycopy(row, 0, data, y * w, w);
                }
            } catch (Exception e) {
                return;
            }

            if (tab == 2) {
                long now = System.currentTimeMillis();
                if (now - lastDetectMs > 120) {
                    lastDetectMs = now;
                    byte[] nv = toNv21(img, data, w, h);
                    frameNv21 = nv;
                    frameW = w;
                    frameH = h;
                    if (localDetect && detector != null && detector.ready()) {
                        long t0 = System.nanoTime();
                        final List<Detector.Det> res = detector.detect(nv, w, h, 0.3f);
                        lastInferMs = (System.nanoTime() - t0) / 1_000_000L;
                        if (!res.isEmpty()) {
                            lastLocalHitMs = now;
                            lastDets = res;
                            lastDetsAt = now;
                            onlineDets = null;
                        }
                        final java.util.List<Detector.Det> online = onlineDets;
                        final java.util.List<Detector.Det> merged = new java.util.ArrayList<>();
                        if (!res.isEmpty()) {
                            merged.addAll(res);
                        } else if (lastDets != null && now - lastDetsAt < 700) {
                            merged.addAll(lastDets);
                        }
                        boolean onlineFresh = online != null
                                && System.currentTimeMillis() - onlineAtMs < 8000;
                        if (merged.isEmpty() && onlineFresh) merged.addAll(online);
                        runOnUiThread(() -> {
                            if (overlayView != null) overlayView.setDetections(toBoxes(merged), camRotation, w, h);
                            if (detList != null) detList.setText(describe(merged));
                            if (cloudView != null) cloudView.setObjects(buildClouds(merged, nv, w, h));
                        });
                        if (res.isEmpty() && now - lastLocalHitMs > 1200
                                && now - lastOnlineTryMs > 5000) {
                            lastOnlineTryMs = now;
                            requestOnline(nv, w, h);
                        }
                    }
                }
            }

            if (scanOn) {
                try {
                    PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h,
                            0, 0, w, h, false);
                    BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
                    qr.reset();
                    final Result r = qr.decodeWithState(bmp);
                    if (r != null && r.getText() != null) {
                        runOnUiThread(() -> {
                            if (scanResult != null) {
                                scanResult.setText("格式 " + r.getBarcodeFormat() + "\n" + r.getText());
                                scanResult.setTextColor(Md3.ON_SURFACE);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // 没扫到就继续下一帧
                }
            }

            if (colorOn) {
                try {
                    int cx = w / 2, cy = h / 2, half = Math.min(w, h) / 6;
                    long r = 0, g = 0, b = 0;
                    int n = 0;
                    Image.Plane u = img.getPlanes()[1];
                    Image.Plane v = img.getPlanes()[2];
                    for (int y = cy - half; y < cy + half; y += 4) {
                        for (int x = cx - half; x < cx + half; x += 4) {
                            int Y = data[y * w + x] & 0xff;
                            int U = u.getBuffer().get((y / 2) * u.getRowStride()
                                    + (x / 2) * u.getPixelStride()) & 0xff;
                            int V = v.getBuffer().get((y / 2) * v.getRowStride()
                                    + (x / 2) * v.getPixelStride()) & 0xff;
                            int c = Math.max(0, Y - 16), d = U - 128, e = V - 128;
                            r += Math.max(0, Math.min(255, (298 * c + 409 * e + 128) >> 8));
                            g += Math.max(0, Math.min(255, (298 * c - 100 * d - 208 * e + 128) >> 8));
                            b += Math.max(0, Math.min(255, (298 * c + 516 * d + 128) >> 8));
                            n++;
                        }
                    }
                    if (n > 0) {
                        final int fr = (int) (r / n), fg = (int) (g / n), fb = (int) (b / n);
                        final String hex = String.format("#%02X%02X%02X", fr, fg, fb);
                        runOnUiThread(() -> {
                            if (colorHex != null) colorHex.setText(hex);
                            if (colorSwatch != null) {
                                colorSwatch.setBackground(
                                        Md3.rounded(MainActivity.this,
                                                android.graphics.Color.rgb(fr, fg, fb), 12));
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        }

        void capture() {
            if (device == null || bg == null || jpegReader == null) {
                cameraStatus.setText("摄像头还没就绪");
                return;
            }
            try {
                CaptureRequest.Builder b = device.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(jpegReader.getSurface());
                session.capture(b.build(), null, bg);
                cameraStatus.setText("已拍照，正在保存…");
            } catch (Exception e) {
                cameraStatus.setText("拍照失败：" + e.getMessage());
            }
        }

        void toggleTorch(TextView btn) {
            try {
                if (mgr == null || id == null) return;
                torch = !torch;
                mgr.setTorchMode(id, torch);
                btn.setText(torch ? "关手电筒" : "手电筒");
            } catch (Exception e) {
                cameraStatus.setText("闪光灯不可用");
            }
        }

        /** 竖屏 + 后摄：把横向缓冲区旋转成竖直并铺满预览。 */
        private void configureTransform(final TextureView tv, Size buf) {
            tv.addOnLayoutChangeListener((v, l, t, r, b2, ol, ot, or, ob) ->
                    applyTransform(tv, buf, 0));
            applyTransform(tv, buf, 0);
        }

        private void applyTransform(final TextureView tv, final Size buf, final int attempt) {
            tv.post(() -> {
                try {
                    int vw = tv.getWidth(), vh = tv.getHeight();
                    if ((vw == 0 || vh == 0) && attempt < 20) {
                        tv.postDelayed(() -> applyTransform(tv, buf, attempt + 1), 40);
                        return;
                    }
                    if (vw == 0 || vh == 0) return;
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    float cx = vw / 2f, cy = vh / 2f;
                    m.postRotate(camRotation, cx, cy);
                    float scale = Math.max(vw / (float) buf.getHeight(), vh / (float) buf.getWidth());
                    m.postScale(scale, scale, cx, cy);
                    tv.setTransform(m);
                } catch (Throwable ignored) {
                }
            });
        }

        void stop() {
            try {
                if (session != null) session.close();
                if (device != null) device.close();
                if (jpegReader != null) jpegReader.close();
                if (yuvReader != null) yuvReader.close();
                if (bgThread != null) bgThread.quitSafely();
            } catch (Exception ignored) {
            }
            session = null;
            device = null;
            jpegReader = null;
            yuvReader = null;
            bgThread = null;
        }
    }

    class CameraChars {
        final CameraCharacteristics ch;

        CameraChars(CameraCharacteristics c) {
            ch = c;
        }

        Size choosePreviewSize() {
            try {
                StreamConfigurationMap map = ch.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                if (sizes != null) {
                    for (Size s : sizes) {
                        if (s.getWidth() == 1280 && s.getHeight() == 720) return s;
                    }
                    return sizes[0];
                }
            } catch (Exception ignored) {
            }
            return new Size(1280, 720);
        }

        Size chooseYuvSize() {
            try {
                StreamConfigurationMap map = ch.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes != null) {
                    Size best = sizes[0];
                    int bestScore = Integer.MAX_VALUE;
                    for (Size s : sizes) {
                        int score = Math.abs(s.getWidth() - 640) + Math.abs(s.getHeight() - 480);
                        if (score < bestScore) {
                            bestScore = score;
                            best = s;
                        }
                    }
                    return best;
                }
            } catch (Exception ignored) {
            }
            return new Size(640, 480);
        }

        Size chooseSize() {
            try {
                StreamConfigurationMap map = ch.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                if (sizes != null) {
                    for (Size s : sizes) {
                        if (s.getWidth() == 1920 && s.getHeight() == 1080) return s;
                    }
                    return sizes[sizes.length / 2];
                }
            } catch (Exception ignored) {
            }
            return new Size(1280, 720);
        }
    }

    private byte[] toNv21(Image img, byte[] y, int w, int h) {
        byte[] out = new byte[w * h + w * h / 2];
        System.arraycopy(y, 0, out, 0, w * h);
        try {
            Image.Plane up = img.getPlanes()[1];
            Image.Plane vp = img.getPlanes()[2];
            java.nio.ByteBuffer ub = up.getBuffer();
            java.nio.ByteBuffer vb = vp.getBuffer();
            int urs = up.getRowStride(), ups = up.getPixelStride();
            int vrs = vp.getRowStride(), vps = vp.getPixelStride();
            int idx = w * h;
            for (int yy = 0; yy < h / 2; yy++) {
                for (int xx = 0; xx < w / 2; xx++) {
                    int u = ub.get(yy * urs + xx * ups) & 0xff;
                    int v = vb.get(yy * vrs + xx * vps) & 0xff;
                    out[idx++] = (byte) v;
                    out[idx++] = (byte) u;
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private String describe(List<Detector.Det> res) {
        StringBuilder head = new StringBuilder("推理 ").append(lastInferMs).append(" ms · ");
        if (res == null || res.isEmpty()) return head.append("未检测到明显目标").toString();
        head.append("发现 ").append(res.size()).append(" 个目标\n");
        for (Detector.Det d : res) {
            head.append("· ").append(d.label);
            if (d.online) head.append("（在线）");
            head.append("  ").append(Math.round(d.score * 100)).append("%  ")
                .append(position(d)).append('\n');
        }
        return head.toString();
    }

    private java.util.List<Views.DetBox> toBoxes(List<Detector.Det> list) {
        java.util.List<Views.DetBox> out = new java.util.ArrayList<>();
        if (list == null) return out;
        for (Detector.Det d : list) {
            out.add(new Views.DetBox(d.label, d.score, d.x1, d.y1, d.x2, d.y2, d.online));
        }
        return out;
    }

    private java.util.List<Views.PointCloudView.CloudObj> buildClouds(
            List<Detector.Det> list, byte[] nv, int w, int h) {
        java.util.List<Views.PointCloudView.CloudObj> out = new java.util.ArrayList<>();
        if (list == null || nv == null) return out;
        for (Detector.Det d : list) {
            Views.PointCloudView.CloudObj o = new Views.PointCloudView.CloudObj();
            o.label = d.label;
            o.online = d.online;
            // 把检测框旋转到与预览一致的方向，再算方位/尺寸
            float rx1 = d.x1, ry1 = d.y1, rx2 = d.x2, ry2 = d.y2;
            if (camRotation == 90) {
                rx1 = 1 - d.y2; ry1 = d.x1; rx2 = 1 - d.y1; ry2 = d.x2;
            } else if (camRotation == 270) {
                rx1 = d.y1; ry1 = 1 - d.x2; rx2 = d.y2; ry2 = 1 - d.x1;
            } else if (camRotation == 180) {
                rx1 = 1 - d.x2; ry1 = 1 - d.y2; rx2 = 1 - d.x1; ry2 = 1 - d.y1;
            }
            float cx = (rx1 + rx2) / 2f;
            o.bearing = (cx - 0.5f) * 60f;
            float bh = Math.max(0.05f, ry2 - ry1);
            float bw = Math.max(0.05f, rx2 - rx1);
            o.depth = Math.max(0f, Math.min(1f, 1f - bh * 1.5f));
            o.wWorld = Math.max(0.35f, Math.min(2.2f, 0.45f + bw * 2.2f));
            o.hWorld = Math.max(0.3f, Math.min(2.4f, o.wWorld * (bh / bw)));
            o.gw = 12;
            o.gh = 12;
            int[] cols = new int[o.gw * o.gh];
            for (int j = 0; j < o.gh; j++) {
                for (int i = 0; i < o.gw; i++) {
                    float fx = d.x1 + (i + 0.5f) / o.gw * (d.x2 - d.x1);
                    float fy = d.y1 + (j + 0.5f) / o.gh * (d.y2 - d.y1);
                    cols[j * o.gw + i] = sampleRgb(nv, w, h, fx, fy);
                }
            }
            o.colors = cols;
            out.add(o);
        }
        return out;
    }

    private int sampleRgb(byte[] nv, int w, int h, float fx, float fy) {
        try {
            int x = Math.max(0, Math.min(w - 1, (int) (fx * w)));
            int y = Math.max(0, Math.min(h - 1, (int) (fy * h)));
            int Y = nv[y * w + x] & 0xff;
            int uv = w * h + (y / 2) * w + (x / 2) * 2;
            int V = 0, U = 0;
            if (uv + 1 < nv.length) {
                V = nv[uv] & 0xff;
                U = nv[uv + 1] & 0xff;
            }
            int c = Math.max(0, Y - 16);
            int d = U - 128, e = V - 128;
            int r = Math.max(0, Math.min(255, (298 * c + 409 * e + 128) >> 8));
            int g = Math.max(0, Math.min(255, (298 * c - 100 * d - 208 * e + 128) >> 8));
            int b = Math.max(0, Math.min(255, (298 * c + 516 * d + 128) >> 8));
            if (r + g + b < 45) return 0;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 旋转开关：自动 / 0 / 90 / 180 / 270，保存到本机。 */
    private TextView rotationChip() {
        final TextView btn = Md3.chip(this, rotLabel(), false);
        btn.setOnClickListener(v -> {
            int[] steps = {-1, 0, 90, 180, 270};
            int cur = 0;
            for (int i = 0; i < steps.length; i++) if (steps[i] == rotOverride) cur = i;
            rotOverride = steps[(cur + 1) % steps.length];
            btn.setText(rotLabel());
            if (sp != null) sp.edit().putInt("rot", rotOverride).apply();
            if (camera != null) camera.stop();
            startCameraIfPossible(tab == 2 ? detectPreview : preview);
        });
        return btn;
    }

    /** 本地连续识别不到时，自动用在线视觉补充（不打断画面）。 */
    private void requestOnline(final byte[] nv, final int w, final int h) {
        final String key = sp == null ? "" : sp.getString("key", "");
        if (key.isEmpty()) {
            runOnUiThread(() -> {
                if (aiResult != null) aiResult.setText("本地没认出；填了 API Key 后会自动在线识别");
            });
            return;
        }
        runOnUiThread(() -> {
            if (aiResult != null) aiResult.setText("本地没认出 → 在线补充识别中…");
        });
        new Thread(() -> {
            try {
                android.graphics.YuvImage yi =
                        new android.graphics.YuvImage(nv, ImageFormat.NV21, w, h, null);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                yi.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 65, bos);
                String b64 = android.util.Base64.encodeToString(bos.toByteArray(),
                        android.util.Base64.NO_WRAP);
                JSONObject body = new JSONObject();
                body.put("model", "deepseek-flash");
                JSONArray content = new JSONArray();
                content.put(new JSONObject().put("type", "text").put("text",
                        "你是目标检测器。只输出 JSON，不要任何解释。格式："
                        + "{\"objects\":[{\"label\":\"键盘\",\"box\":[0.1,0.2,0.3,0.4],"
                        + "\"score\":0.9}]}。box 是归一化坐标 x1,y1,x2,y2（0~1），"
                        + "最多 5 个主要物体，label 一律用中文。"));
                content.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject()
                                .put("url", "data:image/jpeg;base64," + b64)
                                .put("detail", "low")));
                body.put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", content)));

                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "https://api.deepseek.com/chat/completions").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(45000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                int code = conn.getResponseCode();
                if (code != 200) {
                    runOnUiThread(() -> aiResult.setText("在线识别失败：HTTP " + code));
                    return;
                }
                String resp = readStream(conn.getInputStream());
                String text = new JSONObject(resp).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content");
                int a = text.indexOf('{');
                int b = text.lastIndexOf('}');
                if (a < 0 || b <= a) {
                    runOnUiThread(() -> aiResult.setText("在线识别返回格式异常"));
                    return;
                }
                JSONObject obj = new JSONObject(text.substring(a, b + 1));
                JSONArray arr = obj.optJSONArray("objects");
                final java.util.List<Detector.Det> ds = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length() && i < 6; i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        JSONArray bx = o.optJSONArray("box");
                        if (bx == null || bx.length() < 4) continue;
                        Detector.Det d = new Detector.Det(o.optString("label", "物体"),
                                (float) o.optDouble("score", 0.8),
                                (float) bx.optDouble(0, 0.2), (float) bx.optDouble(1, 0.2),
                                (float) bx.optDouble(2, 0.8), (float) bx.optDouble(3, 0.8));
                        d.online = true;
                        ds.add(d);
                    }
                }
                onlineDets = ds;
                onlineAtMs = System.currentTimeMillis();
                runOnUiThread(() -> {
                    if (overlayView != null) overlayView.setDetections(toBoxes(ds), camRotation, w, h);
                    if (cloudView != null) cloudView.setObjects(buildClouds(ds, nv, w, h));
                    if (detList != null) detList.setText(describe(ds));
                    if (aiResult != null) {
                        aiResult.setText(ds.isEmpty() ? "在线也没认出明确物体"
                                : "在线补充识别：" + ds.size() + " 个目标（标记·云）");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (aiResult != null) aiResult.setText("在线识别异常：" + e.getClass().getSimpleName());
                });
            }
        }).start();
    }

    private String position(Detector.Det d) {
        float cx = (d.x1 + d.x2) / 2f;
        String hz = cx < 0.33f ? "左侧" : (cx > 0.66f ? "右侧" : "中间");
        String vt = (d.y1 + d.y2) / 2f < 0.4f ? "远处" : "近处";
        return hz + "/" + vt;
    }

    private void saveImage(Image img) {
        if (img == null) return;
        try {
            String name = "SK_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                    .format(new Date()) + ".jpg";
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put("relative_path", "Pictures/传感器工具箱");
            }
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                java.nio.ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                os.write(bytes);
            }
            img.close();
            runOnUiThread(() -> cameraStatus.setText("已保存到相册 Pictures/传感器工具箱"));
        } catch (Exception e) {
            if (img != null) img.close();
            runOnUiThread(() -> cameraStatus.setText("保存失败：" + e.getMessage()));
        }
    }
}
