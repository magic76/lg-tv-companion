package com.lgvideosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.Html;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.util.Log;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import androidx.core.view.WindowCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A standalone helper that detects direct MP4/HLS requests and sends a selected URL to the LG TV bridge. */
public final class MainActivity extends Activity {
    private static final Pattern MEDIA_URL = Pattern.compile("(?i)\\.(m3u8|mp4|m4v|mov|webm)(?:$|[?#])");
    private static final Pattern SHARED_URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<(?:video|source|meta|link)\\b[^>]*>");
    private static final Pattern HTML_ATTR = Pattern.compile("(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern QUALITY_HINT = Pattern.compile("(?i)(2160p|1440p|1080p|720p|480p|360p|240p|4k|uhd|\\d{3,4}\\s*[x×]\\s*\\d{3,4})");
    private static final Pattern VIDEO_DIMENSIONS = Pattern.compile("\"width\"\\s*:\\s*(\\d+).*?\"height\"\\s*:\\s*(\\d+)");
    private static final int BG = Color.rgb(16, 24, 32);
    private static final int PANEL = Color.rgb(27, 39, 50);
    private static final int TEXT = Color.rgb(242, 246, 249);
    private static final int MUTED = Color.rgb(167, 183, 194);
    private static final int ACCENT = Color.rgb(155, 225, 205);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Set<String> mediaUrls = new LinkedHashSet<>();
    private final Map<String, Map<String, String>> candidateHeaders = new HashMap<>();
    private final Map<String, TextView> candidateMetadataViews = new HashMap<>();
    private int candidateGeneration;
    private String pairCode;
    private PhoneBridgeServer bridgeServer;
    private volatile boolean bridgeReady;
    private volatile String bridgeStartupError;
    private int bridgePort = 8787;
    private volatile TvPointerClient pointerClient;
    private volatile WebView controllerWebView;
    private Dialog controllerDialog;
    private volatile String pendingControllerTvScanCallbackId = "";
    private PermissionRequest pendingWebPermission;

    private EditText urlInput;
    private LinearLayout homeRoot;
    private LinearLayout browserRoot;
    private TextView bridgeStatus;
    private TextView homePageStatus;
    private TextView pageStatus;
    private TextView playbackInfo;
    private TextView emptyCandidates;
    private LinearLayout candidateRows;
    private WebView webView;
    private String webUserAgent;
    private boolean pageLoaded;
    private final Runnable playbackInfoPoller = new Runnable() {
        @Override
        public void run() {
            if (webView == null || !pageLoaded) return;
            inspectPageVideoDimensions();
            mainHandler.postDelayed(this, 1800);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        initializePairingCode();
        startBridgeServer();
        buildUi();
        handleIncomingIntent(getIntent());
        refreshBridgeStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void buildUi() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(BG);

        ScrollView homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true);
        homeRoot = new LinearLayout(this);
        homeRoot.setOrientation(LinearLayout.VERTICAL);
        homeRoot.setPadding(dp(18), dp(16), dp(18), dp(24));
        homeScroll.addView(homeRoot, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.addView(homeScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("LG 電視 companion");
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        homeRoot.addView(title, matchWrap());

        TextView intro = new TextView(this);
        intro.setText("手機控制電視、開啟網站，或把影片送到電視。");
        intro.setTextColor(MUTED);
        intro.setTextSize(15);
        intro.setPadding(0, dp(5), 0, dp(18));
        homeRoot.addView(intro, matchWrap());

        bridgeStatus = new TextView(this);
        bridgeStatus.setText("手機橋接服務啟動中…");
        bridgeStatus.setTextColor(TEXT);
        bridgeStatus.setTextSize(14);
        bridgeStatus.setPadding(dp(14), dp(13), dp(14), dp(13));
        bridgeStatus.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.bottomMargin = dp(14);
        homeRoot.addView(bridgeStatus, statusParams);

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.CENTER_VERTICAL);
        Button controllerButton = new Button(this);
        controllerButton.setText("控制電視");
        controllerButton.setAllCaps(false);
        controllerButton.setMinHeight(dp(58));
        controllerButton.setTextColor(BG);
        controllerButton.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        controllerButton.setOnClickListener(view -> openPhoneController());
        LinearLayout.LayoutParams controllerParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        controllerParams.rightMargin = dp(8);
        quickActions.addView(controllerButton, controllerParams);
        Button scanTvButton = new Button(this);
        scanTvButton.setText("掃描電視 QR 配對");
        scanTvButton.setAllCaps(false);
        scanTvButton.setMinHeight(dp(58));
        scanTvButton.setTextColor(TEXT);
        scanTvButton.setBackgroundTintList(ColorStateList.valueOf(PANEL));
        scanTvButton.setOnClickListener(view -> scanTvQrCode());
        quickActions.addView(scanTvButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        homeRoot.addView(quickActions, matchWrap());

        TextView setupHint = new TextView(this);
        setupHint.setText("首次使用時，掃描電視「手機控制連線」畫面的 QR code。之後打開控制頁，按「連線電視」即可。手機和電視需使用同一個 Wi‑Fi。");
        setupHint.setTextColor(MUTED);
        setupHint.setTextSize(13);
        setupHint.setPadding(dp(2), dp(8), dp(2), dp(10));
        homeRoot.addView(setupHint, matchWrap());

        Button advancedSettingsToggle = new Button(this);
        advancedSettingsToggle.setText("進階設定　⌄");
        advancedSettingsToggle.setAllCaps(false);
        advancedSettingsToggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        advancedSettingsToggle.setMinHeight(dp(44));
        advancedSettingsToggle.setTextColor(MUTED);
        advancedSettingsToggle.setBackgroundTintList(ColorStateList.valueOf(BG));
        homeRoot.addView(advancedSettingsToggle, matchWrap());

        LinearLayout advancedSettings = new LinearLayout(this);
        advancedSettings.setOrientation(LinearLayout.VERTICAL);
        advancedSettings.setVisibility(View.GONE);
        advancedSettings.setPadding(dp(12), dp(4), dp(12), dp(10));
        TextView advancedHint = new TextView(this);
        advancedHint.setText("一般不需要手動設定。QR 無法使用或電視 IP 改變時，才手動輸入 IP。");
        advancedHint.setTextColor(MUTED);
        advancedHint.setTextSize(13);
        advancedSettings.addView(advancedHint, matchWrap());
        Button tvSettingsButton = new Button(this);
        tvSettingsButton.setText("手動輸入電視 IP");
        tvSettingsButton.setAllCaps(false);
        tvSettingsButton.setMinHeight(dp(48));
        tvSettingsButton.setTextColor(TEXT);
        tvSettingsButton.setBackgroundTintList(ColorStateList.valueOf(PANEL));
        tvSettingsButton.setOnClickListener(view -> openTvSettings());
        LinearLayout.LayoutParams manualIpParams = matchWrap();
        manualIpParams.topMargin = dp(6);
        advancedSettings.addView(tvSettingsButton, manualIpParams);
        homeRoot.addView(advancedSettings, matchWrap());
        advancedSettingsToggle.setOnClickListener(view -> {
            boolean show = advancedSettings.getVisibility() != View.VISIBLE;
            advancedSettings.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedSettingsToggle.setText(show ? "進階設定　⌃" : "進階設定　⌄");
        });

        TextView videoTitle = new TextView(this);
        videoTitle.setText("找影片並投放");
        videoTitle.setTextColor(TEXT);
        videoTitle.setTextSize(20);
        videoTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        homeRoot.addView(videoTitle, matchWrap());

        TextView videoHint = new TextView(this);
        videoHint.setText("貼上影片網頁，手機會開啟頁面並偵測可播放的來源。");
        videoHint.setTextColor(MUTED);
        videoHint.setTextSize(14);
        videoHint.setPadding(0, dp(5), 0, dp(12));
        homeRoot.addView(videoHint, matchWrap());

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(TEXT);
        urlInput.setHintTextColor(MUTED);
        urlInput.setHint("貼上影片網頁網址");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setMinHeight(dp(54));
        urlInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        homeRoot.addView(urlInput, matchWrap());

        Button openButton = new Button(this);
        openButton.setText("在手機開啟網頁");
        openButton.setAllCaps(false);
        openButton.setMinHeight(dp(56));
        openButton.setTextColor(BG);
        openButton.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        openButton.setOnClickListener(view -> openEnteredUrl());
        LinearLayout.LayoutParams openParams = matchWrap();
        openParams.topMargin = dp(8);
        homeRoot.addView(openButton, openParams);
        urlInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openEnteredUrl();
                return true;
            }
            return false;
        });

        homePageStatus = new TextView(this);
        homePageStatus.setText("網頁開啟後，按播放即可查看偵測到的影片來源。");
        homePageStatus.setTextColor(MUTED);
        homePageStatus.setTextSize(13);
        homePageStatus.setPadding(dp(2), dp(8), dp(2), 0);
        homeRoot.addView(homePageStatus, matchWrap());

        browserRoot = new LinearLayout(this);
        browserRoot.setOrientation(LinearLayout.VERTICAL);
        browserRoot.setPadding(dp(12), dp(8), dp(12), dp(8));
        browserRoot.setBackgroundColor(BG);
        browserRoot.setVisibility(View.GONE);

        LinearLayout browserHeader = new LinearLayout(this);
        browserHeader.setGravity(Gravity.CENTER_VERTICAL);
        Button backButton = new Button(this);
        backButton.setText("‹ 返回");
        backButton.setAllCaps(false);
        backButton.setTextColor(TEXT);
        backButton.setBackgroundTintList(ColorStateList.valueOf(PANEL));
        backButton.setOnClickListener(view -> showHomeScreen());
        browserHeader.addView(backButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        TextView browserTitle = new TextView(this);
        browserTitle.setText("影片網頁");
        browserTitle.setTextColor(TEXT);
        browserTitle.setTextSize(17);
        browserTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        browserTitle.setPadding(dp(12), 0, 0, 0);
        browserHeader.addView(browserTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        browserRoot.addView(browserHeader, matchWrap());

        pageStatus = new TextView(this);
        pageStatus.setTextColor(MUTED);
        pageStatus.setTextSize(12);
        pageStatus.setMaxLines(2);
        pageStatus.setPadding(dp(4), dp(3), dp(4), dp(3));
        browserRoot.addView(pageStatus, matchWrap());

        LinearLayout candidateHeader = new LinearLayout(this);
        candidateHeader.setOrientation(LinearLayout.HORIZONTAL);
        candidateHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView candidateTitle = new TextView(this);
        candidateTitle.setText("偵測到的影片來源");
        candidateTitle.setTextColor(TEXT);
        candidateTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        candidateTitle.setTextSize(15);
        candidateHeader.addView(candidateTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clearCandidates = new Button(this);
        clearCandidates.setText("清空");
        clearCandidates.setAllCaps(false);
        clearCandidates.setTextSize(12);
        clearCandidates.setMinHeight(dp(40));
        clearCandidates.setPadding(dp(10), 0, dp(10), 0);
        clearCandidates.setOnClickListener(view -> clearMediaCandidates());
        candidateHeader.addView(clearCandidates, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        browserRoot.addView(candidateHeader, matchWrap());

        playbackInfo = new TextView(this);
        playbackInfo.setText("網頁解析度：等待播放器回報");
        playbackInfo.setTextColor(MUTED);
        playbackInfo.setTextSize(12);
        playbackInfo.setPadding(dp(2), dp(2), dp(2), dp(5));
        browserRoot.addView(playbackInfo, matchWrap());

        ScrollView candidateScroll = new ScrollView(this);
        candidateScroll.setFillViewport(true);
        candidateScroll.setBackgroundColor(PANEL);
        candidateRows = new LinearLayout(this);
        candidateRows.setOrientation(LinearLayout.VERTICAL);
        candidateRows.setPadding(dp(8), dp(5), dp(8), dp(5));
        emptyCandidates = new TextView(this);
        emptyCandidates.setText("尚未找到串流。請在網頁中按播放。");
        emptyCandidates.setTextColor(MUTED);
        emptyCandidates.setTextSize(13);
        emptyCandidates.setPadding(dp(6), dp(8), dp(6), dp(8));
        candidateRows.addView(emptyCandidates, matchWrap());
        candidateScroll.addView(candidateRows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        browserRoot.addView(candidateScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webUserAgent = settings.getUserAgentString();
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if ("GET".equalsIgnoreCase(request.getMethod())) {
                    String candidate = request.getUrl().toString();
                    if (isMediaUrl(candidate)) addMediaCandidate(candidate, request.getRequestHeaders());
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageStatus.setText("目前頁面：" + url);
                pageLoaded = true;
                mainHandler.removeCallbacks(playbackInfoPoller);
                mainHandler.post(playbackInfoPoller);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) pageStatus.setText("網頁載入失敗：" + error.getDescription());
            }
        });
        browserRoot.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        screen.addView(browserRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(screen);
    }

    private void showHomeScreen() {
        if (homeRoot != null) homeRoot.setVisibility(View.VISIBLE);
        if (browserRoot != null) browserRoot.setVisibility(View.GONE);
    }

    private void showBrowserScreen() {
        if (homeRoot != null) homeRoot.setVisibility(View.GONE);
        if (browserRoot != null) browserRoot.setVisibility(View.VISIBLE);
        if (webView != null) webView.onResume();
    }

    private SharedPreferences tvPreferences() {
        return getSharedPreferences("lg_video_sender", MODE_PRIVATE);
    }

    private String configuredTvHost() {
        String host = tvPreferences().getString("tv_host", "").trim();
        return getLanAddresses().contains(host) ? "" : host;
    }

    private void initializePairingCode() {
        pairCode = tvPreferences().getString("pair_code", "");
        if (!pairCode.matches("\\d{6}")) {
            pairCode = String.valueOf(100000 + new SecureRandom().nextInt(900000));
            tvPreferences().edit().putString("pair_code", pairCode).apply();
        }
    }

    private void startBridgeServer() {
        try {
            bridgePort = findAvailableBridgePort();
            bridgeServer = new PhoneBridgeServer(pairCode, message -> mainHandler.post(() -> {
                if (message.startsWith("App 內橋接已啟動")) {
                    bridgeReady = true;
                    bridgeStartupError = null;
                } else {
                    bridgeStartupError = message;
                    bridgeReady = false;
                }
                if (bridgeStatus != null) {
                    if (bridgeReady) {
                        String host = configuredTvHost();
                        bridgeStatus.setText("手機橋接已啟動 · Port " + bridgePort
                                + (host.isEmpty() ? " · 尚未設定電視" : " · 電視 " + host));
                    } else {
                        bridgeStatus.setText(message);
                    }
                }
            }), bridgePort);
            bridgeServer.start();
        } catch (Exception error) {
            bridgeStartupError = "App 內橋接啟動失敗：" + error.getMessage();
            if (bridgeStatus != null) bridgeStatus.setText(bridgeStartupError);
        }
    }

    private int findAvailableBridgePort() throws Exception {
        for (int port = 8787; port <= 8899; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress("0.0.0.0", port));
                return port;
            } catch (Exception occupied) {}
        }
        throw new IllegalStateException("沒有可用的本機橋接連接埠。");
    }

    private JSONObject bridgeConfig() {
        JSONArrayBuilder addressList = new JSONArrayBuilder(getLanAddresses());
        JSONObject config = new JSONObject();
        try {
            config.put("port", bridgePort);
            config.put("pairCode", pairCode);
            config.put("tvConfigured", !configuredTvHost().isEmpty());
            config.put("addresses", addressList.json());
            config.put("phoneSocketUrl", "ws://localhost:" + bridgePort + "/ws");
        } catch (Exception ignored) {}
        return config;
    }

    private List<String> getLanAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> allInterfaces = interfaces == null
                    ? new ArrayList<>() : Collections.list(interfaces);
            Collections.sort(allInterfaces, (left, right) -> Integer.compare(
                    interfacePriority(left.getName()), interfacePriority(right.getName())));
            for (NetworkInterface networkInterface : allInterfaces) {
                if (interfacePriority(networkInterface.getName()) > 2) continue;
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    String value = address.getHostAddress();
                    if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()
                            && value.indexOf(':') < 0 && !addresses.contains(value)) addresses.add(value);
                }
            }
        } catch (Exception ignored) {}
        return addresses;
    }

    private int interfacePriority(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.contains("wlan") || lower.contains("wifi") || lower.startsWith("ap")) return 0;
        if (lower.startsWith("eth") || lower.startsWith("en")) return 1;
        if (lower.startsWith("usb")) return 2;
        return 3;
    }

    private void openPhoneController() {
        if (controllerDialog != null && controllerDialog.isShowing()) return;
        controllerWebView = new WebView(this);
        controllerWebView.setBackgroundColor(BG);
        WebSettings settings = controllerWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        controllerWebView.addJavascriptInterface(new ControllerJavascriptBridge(), "AndroidBridge");
        controllerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !"localhost".equalsIgnoreCase(request.getUrl().getHost());
            }
        });
        controllerWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    Uri origin = Uri.parse(request.getOrigin().toString());
                    if (controllerWebView == null || request.getResources().length != 1
                            || !PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(request.getResources()[0])
                            || !"localhost".equalsIgnoreCase(origin.getHost())) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingWebPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7412);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }
        });

        controllerDialog = new Dialog(this);
        controllerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        controllerDialog.setContentView(controllerWebView);
        controllerDialog.setOnDismissListener(ignored -> {
            if (pendingWebPermission != null) {
                try { pendingWebPermission.deny(); } catch (Exception ignoredPermission) {}
                pendingWebPermission = null;
            }
            if (controllerWebView != null) {
                controllerWebView.stopLoading();
                controllerWebView.destroy();
                controllerWebView = null;
            }
            TvPointerClient activePointer = pointerClient;
            pointerClient = null;
            if (activePointer != null) activePointer.close();
            controllerDialog = null;
        });
        Window dialogWindow = controllerDialog.getWindow();
        if (dialogWindow != null) WindowCompat.setDecorFitsSystemWindows(dialogWindow, true);
        controllerDialog.show();
        Window window = controllerDialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.getDecorView().setBackgroundColor(BG);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams windowParams = window.getAttributes();
            windowParams.dimAmount = 0.35f;
            window.setAttributes(windowParams);
        }
        try (InputStream input = getAssets().open("phone.html")) {
            String html = readBody(input);
            controllerWebView.loadDataWithBaseURL("http://localhost/", html, "text/html", "UTF-8", null);
        } catch (Exception error) {
            if (controllerWebView != null) controllerWebView.loadDataWithBaseURL("http://localhost/",
                    "<meta charset=\"utf-8\"><p>無法載入內建手機控制頁：" + error.getMessage() + "</p>",
                    "text/html", "UTF-8", null);
        }
    }

    private final class ControllerJavascriptBridge {
        @JavascriptInterface
        public String getConfig() {
            return bridgeConfig().toString();
        }

        @JavascriptInterface
        public void launchTvApp(String payloadText, String callbackId) {
            if (controllerWebView == null) {
                postControllerLaunchResult(callbackId, false, "控制頁來源無效，請重新開啟手機控制頁。");
                return;
            }
            JSONObject payload;
            try { payload = new JSONObject(payloadText == null ? "{}" : payloadText); }
            catch (Exception invalid) {
                postControllerLaunchResult(callbackId, false, "啟動資料格式錯誤。");
                return;
            }
            launchToTv(payload, (success, message) -> postControllerLaunchResult(callbackId, success, message));
        }

        @JavascriptInterface
        public void scanAndConnectTv(String callbackId) {
            if (controllerWebView == null) {
                postControllerLaunchResult(callbackId, false, "控制頁已關閉，請重新開啟後再掃描。");
                return;
            }
            pendingControllerTvScanCallbackId = callbackId == null ? "" : callbackId;
            mainHandler.post(() -> {
                if (controllerWebView == null) {
                    completeControllerTvScan(false, "控制頁已關閉，請重新開啟後再掃描。");
                    return;
                }
                scanTvQrCode();
            });
        }

        @JavascriptInterface
        public void connectPointer() {
            try { beginPointerConnection(); }
            catch (Throwable error) {
                Log.e("LGPointer", "connectPointer invocation failed", error);
                String detail = TextUtils.isEmpty(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage();
                postControllerPointerStatus(false, "觸控板初始化例外（" + detail + "）。請重試。");
            }
        }

        private void beginPointerConnection() {
            if (controllerWebView == null) {
                postControllerPointerStatus(false, "控制頁已關閉，請重新開啟手機控制頁。");
                return;
            }
            String host = configuredTvHost();
            if (host.isEmpty()) {
                postControllerPointerStatus(false, "請先掃描電視 QR code 或設定電視 IP。");
                return;
            }
            TvPointerClient current = pointerClient;
            if (current != null && current.isConnected()) {
                postControllerPointerStatus(true, "觸控板已連線。單指滑動移動游標，點一下確認，雙指滑動捲動。");
                return;
            }
            if (current != null) current.close();
            String clientKey = tvPreferences().getString("tv_client_key", "");
            TvPointerClient next = new TvPointerClient(host, clientKey, (connected, message, returnedKey) -> {
                if (!TextUtils.isEmpty(returnedKey)) tvPreferences().edit().putString("tv_client_key", returnedKey).apply();
                mainHandler.post(() -> postControllerPointerStatus(connected, message));
            });
            pointerClient = next;
            try {
                Thread pointerThread = new Thread(next::connect, "lg-tv-pointer-connect");
                pointerThread.setDaemon(true);
                pointerThread.start();
            } catch (Exception error) {
                pointerClient = null;
                next.close();
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                postControllerPointerStatus(false, "無法啟動電視觸控板連線（" + detail + "），請重試。");
            }
        }

        @JavascriptInterface
        public void pointerMove(double dx, double dy) {
            TvPointerClient current = pointerClient;
            if (current != null) current.move(dx, dy);
        }

        @JavascriptInterface
        public void pointerScroll(double dx, double dy) {
            TvPointerClient current = pointerClient;
            if (current != null) current.scroll(dx, dy);
        }

        @JavascriptInterface
        public void pointerClick() {
            TvPointerClient current = pointerClient;
            if (current != null) current.click();
        }

        @JavascriptInterface
        public void pointerButton(String name) {
            TvPointerClient current = pointerClient;
            if (current != null) current.button(name);
        }

        @JavascriptInterface
        public void insertTvText(String text, String callbackId) {
            TvPointerClient current = pointerClient;
            if (current == null) {
                postControllerImeStatus(callbackId, false, "請先連線觸控板。");
                return;
            }
            current.insertText(text, (success, message) -> postControllerImeStatus(callbackId, success, message));
        }

        @JavascriptInterface
        public void sendTvEnter(String callbackId) {
            TvPointerClient current = pointerClient;
            if (current == null) {
                postControllerImeStatus(callbackId, false, "請先連線觸控板。");
                return;
            }
            current.sendEnter((success, message) -> postControllerImeStatus(callbackId, success, message));
        }
    }

    private void postControllerPointerStatus(boolean connected, String message) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativePointerStatus && window.onNativePointerStatus(" + connected + ","
                    + JSONObject.quote(message == null ? "" : message) + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private void postControllerImeStatus(String callbackId, boolean success, String message) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeImeStatus && window.onNativeImeStatus("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + ","
                    + success + "," + JSONObject.quote(message == null ? "" : message) + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private void postControllerLaunchResult(String callbackId, boolean success, String message) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeLaunchResult && window.onNativeLaunchResult("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + ","
                    + success + "," + JSONObject.quote(message == null ? "" : message) + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private void openTvSettings() {
        EditText hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setHint("例如 192.168.x.x");
        hostInput.setText(configuredTvHost());
        hostInput.setSelection(hostInput.getText().length());
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setPadding(padding, dp(8), padding, 0);
        content.addView(hostInput, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("手動設定電視 IP")
                .setMessage("只有無法掃描 QR code 或電視 IP 改變時才需要設定。一般使用請掃描電視「手機控制連線」畫面的 QR code。手機和電視要連同一個 Wi-Fi。")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("儲存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String host = hostInput.getText().toString().trim();
            if (!host.matches("(?i)^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")) {
                hostInput.setError("請輸入電視 IP 或主機名稱");
                return;
            }
            String oldHost = tvPreferences().getString("tv_host", "");
            SharedPreferences.Editor editor = tvPreferences().edit().putString("tv_host", host);
            if (!host.equalsIgnoreCase(oldHost)) editor.remove("tv_client_key");
            editor.apply();
            dialog.dismiss();
            bridgeStatus.setText("電視已設定：" + host + " · 準備啟動並連線");
        }));
        dialog.show();
    }

    private void scanTvQrCode() {
        IntentIntegrator scanner = new IntentIntegrator(this);
        scanner.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        scanner.setPrompt("掃描電視「手機控制連線」畫面上的 QR code");
        scanner.setBeepEnabled(false);
        scanner.setOrientationLocked(true);
        scanner.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String contents = result.getContents();
            if (contents != null) handleTvPairQr(contents);
            else {
                if (bridgeStatus != null) bridgeStatus.setText("掃描已取消。電視設定沒有變更。");
                completeControllerTvScan(false, "掃描已取消，電視設定沒有變更。");
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleTvPairQr(String contents) {
        String host = null;
        try {
            Uri pairing = Uri.parse(contents.trim());
            if (pairing.isHierarchical()
                    && "lgcompanion".equalsIgnoreCase(pairing.getScheme())
                    && "pair".equalsIgnoreCase(pairing.getHost())
                    && "1".equals(pairing.getQueryParameter("v"))) {
                host = pairing.getQueryParameter("host");
            }
        } catch (Exception ignored) {}
        if (!isValidTvIpv4(host)) {
            completeControllerTvScan(false, "無法辨識這個 QR code，請掃描電視 App 的配對 QR code。");
            new AlertDialog.Builder(this)
                    .setTitle("這不是支援的電視 QR code")
                    .setMessage("請在 LG 電視 companion 的「手機控制連線」畫面重新查詢並掃描 QR code。")
                    .setPositiveButton("好", null)
                    .show();
            return;
        }

        final String tvHost = host;
        String oldHost = tvPreferences().getString("tv_host", "");
        SharedPreferences.Editor editor = tvPreferences().edit().putString("tv_host", tvHost);
        if (!tvHost.equalsIgnoreCase(oldHost)) editor.remove("tv_client_key");
        editor.apply();
        if (bridgeStatus != null) bridgeStatus.setText("已掃描到電視 " + tvHost + "，正在連線…");

        launchToTv(new JSONObject(), (success, message) -> {
            if (bridgeStatus != null) bridgeStatus.setText(success
                    ? "電視已連線 · " + tvHost : "已記住電視 " + tvHost + " · " + message);
            completeControllerTvScan(success, success
                    ? "已啟動電視 App，正在等待連線…" : message);
            if (success) {
                openPhoneController();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("已讀取電視 QR code")
                        .setMessage(message + "\n\n電視 IP 已儲存；確認手機和電視連上同一個 Wi‑Fi 後，可以再按「控制電視」。")
                        .setPositiveButton("好", null)
                        .show();
            }
        });
    }

    private void completeControllerTvScan(boolean success, String message) {
        String callbackId = pendingControllerTvScanCallbackId;
        pendingControllerTvScanCallbackId = "";
        if (!TextUtils.isEmpty(callbackId)) postControllerLaunchResult(callbackId, success, message);
    }

    private boolean isValidTvIpv4(String value) {
        if (value == null) return false;
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        try {
            int[] octets = new int[4];
            for (int index = 0; index < parts.length; index++) {
                if (!parts[index].matches("\\d{1,3}")) return false;
                octets[index] = Integer.parseInt(parts[index]);
                if (octets[index] < 0 || octets[index] > 255) return false;
            }
            return octets[0] != 0 && octets[0] != 127 && octets[0] < 224;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private interface TvLaunchCallback {
        void onResult(boolean success, String message);
    }

    private void launchToTv(JSONObject input, TvLaunchCallback callback) {
        network.execute(() -> {
            try {
                String host = configuredTvHost();
                if (host.isEmpty()) throw new IllegalStateException("請先設定電視 IP，再按「控制電視」。");
                List<String> addresses = getLanAddresses();
                if (addresses.isEmpty()) throw new IllegalStateException("手機沒有可用的 Wi-Fi 區域網路位址。");

                JSONObject params = new JSONObject()
                        .put("source", "phone-controller")
                        .put("controllerUrl", "ws://" + addresses.get(0) + ":" + bridgePort + "/ws")
                        .put("controllerCode", pairCode);
                String mediaPageUrl = input.optString("mediaPageUrl", "").trim();
                String mediaUrl = input.optString("mediaUrl", "").trim();
                String requestedUrl = input.optString("url", "").trim();
                if (!mediaPageUrl.isEmpty()) {
                    JSONObject resolved = resolveMediaPage(mediaPageUrl);
                    params.put("mediaUrl", resolved.getString("mediaUrl"));
                    params.put("mediaTitle", resolved.optString("title", ""));
                } else if (!mediaUrl.isEmpty()) {
                    String normalized = normalizeHttpUrl(mediaUrl, 2048);
                    if (normalized == null || !MEDIA_URL.matcher(normalized).find()) {
                        throw new IllegalStateException("串流網址必須是有效的 MP4、HLS 或其他直接影片連結。");
                    }
                    params.put("mediaUrl", normalized);
                }
                if (!requestedUrl.isEmpty()) {
                    String normalized = normalizeHttpUrl(requestedUrl, 2048);
                    if (normalized == null) throw new IllegalStateException("網址必須是有效的 http 或 https 網址。");
                    params.put("url", normalized);
                }

                String clientKey = tvPreferences().getString("tv_client_key", "");
                TvSsapClient client = new TvSsapClient(host, clientKey, params,
                        (success, message, returnedKey) -> {
                            if (!TextUtils.isEmpty(returnedKey)) tvPreferences().edit()
                                    .putString("tv_client_key", returnedKey).apply();
                            mainHandler.post(() -> callback.onResult(success, message));
                        });
                boolean connected = client.connectBlocking(10, TimeUnit.SECONDS);
                if (!connected) {
                    client.close();
                    mainHandler.post(() -> callback.onResult(false,
                            "連不到電視。請確認 IP 正確、電視已開機，而且手機與電視在同一個 Wi-Fi。"));
                }
            } catch (Exception error) {
                String message = TextUtils.isEmpty(error.getMessage()) ? "電視啟動失敗。" : error.getMessage();
                mainHandler.post(() -> callback.onResult(false, message));
            }
        });
    }

    private static String normalizeHttpUrl(String value, int maxLength) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty() || raw.length() > maxLength
                || (raw.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && !raw.matches("(?i)^https?://.*"))) return null;
        if (!raw.matches("(?i)^https?://.*")) {
            if (!raw.matches("(?i)^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?(?:[/?#][^\\s]*)?$")) return null;
            raw = "https://" + raw;
        }
        try {
            URI uri = new URI(raw);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return uri.toASCIIString();
        } catch (Exception ignored) { return null; }
    }

    private JSONObject resolveMediaPage(String value) throws Exception {
        String pageUrl = normalizeHttpUrl(value, 2048);
        if (pageUrl == null) throw new IllegalStateException("請輸入有效的 http 或 https 網址。");
        if (MEDIA_URL.matcher(pageUrl).find()) return new JSONObject().put("mediaUrl", pageUrl).put("title", "");

        String current = pageUrl;
        String html = null;
        for (int redirect = 0; redirect <= 5; redirect++) {
            URI currentUri = new URI(current);
            assertPublicHttpHost(currentUri);
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/121.0 Mobile Safari/537.36");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirect == 5) throw new IllegalStateException("網頁重新導向次數過多。");
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (status < 200 || status >= 300) throw new IllegalStateException("網頁回應 HTTP " + status + "。");
                String contentType = String.valueOf(connection.getContentType()).toLowerCase(Locale.ROOT);
                if (!contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) {
                    throw new IllegalStateException("這個網址沒有回傳可解析的 HTML 網頁。");
                }
                int declaredLength = connection.getContentLength();
                if (declaredLength > 2 * 1024 * 1024) throw new IllegalStateException("網頁太大，無法安全解析。");
                html = readLimited(connection.getInputStream(), 2 * 1024 * 1024);
                break;
            } finally {
                connection.disconnect();
            }
        }
        if (html == null) throw new IllegalStateException("無法讀取網頁。");
        JSONObject parsed = parsePublicVideoHtml(html, current);
        if (parsed == null) throw new IllegalStateException("找不到網頁公開宣告的 MP4 或 HLS 來源。這個頁面可能需要網站播放器、登入或 DRM。");
        assertPublicHttpHost(new URI(parsed.getString("mediaUrl")));
        return parsed;
    }

    private String readLimited(InputStream input, int maxBytes) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > maxBytes) throw new IllegalStateException("網頁太大，無法安全解析。");
                bytes.write(buffer, 0, count);
            }
            return bytes.toString("UTF-8");
        }
    }

    private void assertPublicHttpHost(URI uri) throws Exception {
        String host = uri.getHost();
        if (host == null || host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            throw new IllegalStateException("只支援公開網路上的 http 或 https 網頁。");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new IllegalStateException("網頁主機沒有可用的公開網路位址。");
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress() || isCarrierOrDocumentationIpv4(address.getAddress())) {
                throw new IllegalStateException("不能從內部網路位址解析影片網頁。");
            }
        }
    }

    private boolean isCarrierOrDocumentationIpv4(byte[] bytes) {
        if (bytes.length != 4) return false;
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        int c = bytes[2] & 255;
        return (a == 100 && b >= 64 && b <= 127) || (a == 198 && (b == 18 || b == 19 || b == 51))
                || (a == 192 && b == 0) || (a == 203 && b == 0 && c == 113);
    }

    private JSONObject parsePublicVideoHtml(String html, String baseUrl) throws Exception {
        List<String> candidates = new ArrayList<>();
        String title = "";
        Matcher titleMatcher = Pattern.compile("(?is)<title\\b[^>]*>([\\s\\S]*?)</title\\s*>").matcher(html);
        if (titleMatcher.find()) title = htmlText(titleMatcher.group(1)).replaceAll("\\s+", " ").trim();
        Matcher tags = HTML_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            Map<String, String> attrs = parseAttributes(tag);
            String tagName = tag.replaceFirst("(?is)^<", "").split("[\\s>/]")[0].toLowerCase(Locale.ROOT);
            String value = attrs.get("src");
            if (value == null) value = attrs.get("data-src");
            if (value == null) value = attrs.get("data-video-src");
            if (value == null) value = attrs.get("href");
            String type = attrs.getOrDefault("type", attrs.getOrDefault("content-type", ""));
            if ("meta".equals(tagName)) {
                String key = attrs.getOrDefault("property", attrs.getOrDefault("name", attrs.getOrDefault("itemprop", ""))).toLowerCase(Locale.ROOT);
                if (key.equals("og:title") || key.equals("twitter:title")) {
                    if (!attrs.getOrDefault("content", "").isEmpty()) title = htmlText(attrs.get("content"));
                }
                if (key.startsWith("og:video") || key.equals("video:content_url") || key.equals("twitter:player:stream") || key.equals("contenturl")) {
                    value = attrs.get("content");
                    type = attrs.getOrDefault("type", attrs.getOrDefault("content-type", ""));
                } else value = null;
            } else if ("link".equals(tagName)) {
                String rel = attrs.getOrDefault("rel", "").toLowerCase(Locale.ROOT);
                boolean videoPreload = rel.contains("preload") && "video".equalsIgnoreCase(attrs.get("as"));
                boolean videoAlternate = rel.contains("alternate") && (type.toLowerCase(Locale.ROOT).contains("video/") || type.toLowerCase(Locale.ROOT).contains("mpegurl"));
                if (!videoPreload && !videoAlternate) value = null;
            }
            addResolvedCandidate(candidates, value, type, baseUrl);
        }
        Matcher jsonLd = Pattern.compile("(?is)\\\"(?:contentUrl|url)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(html);
        while (jsonLd.find()) addResolvedCandidate(candidates, jsonLd.group(1).replace("\\/", "/").replace("\\u0026", "&"), "", baseUrl);
        if (candidates.isEmpty()) return null;
        candidates.sort((left, right) -> Integer.compare(mediaScore(right), mediaScore(left)));
        return new JSONObject().put("mediaUrl", candidates.get(0)).put("title", title.length() > 160 ? title.substring(0, 160) : title);
    }

    private Map<String, String> parseAttributes(String tag) {
        Map<String, String> attrs = new HashMap<>();
        Matcher matcher = HTML_ATTR.matcher(tag);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            attrs.put(matcher.group(1).toLowerCase(Locale.ROOT), htmlText(value));
        }
        return attrs;
    }

    private void addResolvedCandidate(List<String> candidates, String rawValue, String mimeType, String baseUrl) {
        if (rawValue == null || rawValue.trim().isEmpty() || rawValue.trim().matches("(?i)^(data|blob):.*")) return;
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (!MEDIA_URL.matcher(rawValue).find() && !(mime.contains("video/") || mime.contains("mpegurl"))) return;
        try {
            String url = new URL(new URL(baseUrl), htmlText(rawValue.trim())).toString();
            if ((url.startsWith("http://") || url.startsWith("https://")) && !candidates.contains(url)) candidates.add(url);
        } catch (Exception ignored) {}
    }

    private int mediaScore(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.mp4(?:$|[?#]).*")) return 4;
        if (lower.matches(".*\\.m3u8(?:$|[?#]).*")) return 3;
        if (lower.matches(".*\\.(?:m4v|mov)(?:$|[?#]).*")) return 2;
        return 1;
    }

    private String htmlText(String value) {
        if (value == null) return "";
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
    }

    private static final class JSONArrayBuilder {
        private final org.json.JSONArray array = new org.json.JSONArray();
        JSONArrayBuilder(List<String> values) { for (String value : values) array.put(value); }
        org.json.JSONArray json() { return array; }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7412 && pendingWebPermission != null) {
            PermissionRequest request = pendingWebPermission;
            pendingWebPermission = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && controllerWebView != null) {
                request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else request.deny();
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (TextUtils.isEmpty(shared) && intent.getData() != null) shared = intent.getData().toString();
        if (TextUtils.isEmpty(shared) && Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) shared = stream.toString();
        }
        String url = firstHttpUrl(shared);
        if (url != null && urlInput != null) {
            urlInput.setText(url);
            loadPage(url);
        }
    }

    private String firstHttpUrl(String value) {
        if (value == null) return null;
        Matcher matcher = SHARED_URL.matcher(value);
        if (!matcher.find()) return null;
        String url = matcher.group();
        while (!url.isEmpty() && ".,;:!?)]}".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private void openEnteredUrl() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            homePageStatus.setText("請先貼上影片網頁網址。");
            return;
        }
        if (!url.matches("(?i)^https?://.+")) url = "https://" + url;
        Uri parsed = Uri.parse(url);
        if (!"http".equalsIgnoreCase(parsed.getScheme()) && !"https".equalsIgnoreCase(parsed.getScheme())) {
            homePageStatus.setText("網址必須使用 http 或 https。");
            return;
        }
        urlInput.setText(url);
        homePageStatus.setText("正在開啟影片網頁…");
        loadPage(url);
    }

    private void loadPage(String url) {
        synchronized (mediaUrls) {
            mediaUrls.clear();
            candidateGeneration++;
        }
        synchronized (candidateHeaders) {
            candidateHeaders.clear();
        }
        candidateMetadataViews.clear();
        candidateRows.removeAllViews();
        candidateRows.addView(emptyCandidates, matchWrap());
        emptyCandidates.setText("尚未找到串流。請在網頁中按播放。");
        pageStatus.setText("正在開啟網頁…");
        playbackInfo.setText("網頁解析度：等待播放器回報");
        pageLoaded = false;
        mainHandler.removeCallbacks(playbackInfoPoller);
        showBrowserScreen();
        webView.loadUrl(url);
    }

    private void clearMediaCandidates() {
        synchronized (mediaUrls) {
            mediaUrls.clear();
            candidateGeneration++;
        }
        synchronized (candidateHeaders) {
            candidateHeaders.clear();
        }
        candidateMetadataViews.clear();
        candidateRows.removeAllViews();
        candidateRows.addView(emptyCandidates, matchWrap());
        emptyCandidates.setText("清單已清空。播放網頁中的影片可重新偵測。");
        pageStatus.setText("已清空影片來源清單。");
    }

    private boolean isMediaUrl(String value) {
        if (value == null || !(value.startsWith("http://") || value.startsWith("https://"))) return false;
        return MEDIA_URL.matcher(value).find();
    }

    private void addMediaCandidate(String value, Map<String, String> requestHeaders) {
        final int generation;
        synchronized (mediaUrls) {
            if (!mediaUrls.add(value)) return;
            generation = candidateGeneration;
        }
        Map<String, String> headers = new HashMap<>();
        if (requestHeaders != null) {
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                String name = header.getKey();
                if (name != null && (name.equalsIgnoreCase("Referer") || name.equalsIgnoreCase("Origin")
                        || name.equalsIgnoreCase("User-Agent") || name.equalsIgnoreCase("Accept")
                        || name.equalsIgnoreCase("Accept-Language"))) {
                    headers.put(name, header.getValue());
                }
            }
        }
        if (webUserAgent != null && !containsHeader(headers, "User-Agent")) headers.put("User-Agent", webUserAgent);
        String cookie = CookieManager.getInstance().getCookie(value);
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        synchronized (candidateHeaders) {
            candidateHeaders.put(value, headers);
        }
        mainHandler.post(() -> {
            synchronized (mediaUrls) {
                if (generation != candidateGeneration || !mediaUrls.contains(value)) return;
            }
            if (candidateRows.getChildCount() == 1 && candidateRows.getChildAt(0) == emptyCandidates) {
                candidateRows.removeAllViews();
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(Gravity.CENTER_VERTICAL);
            TextView summary = new TextView(this);
            summary.setText(candidateSummary(value));
            summary.setTextColor(TEXT);
            summary.setTextSize(12);
            summary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            summary.setSingleLine(true);
            summary.setEllipsize(TextUtils.TruncateAt.END);
            details.addView(summary, matchWrap());
            TextView source = new TextView(this);
            source.setText(candidateSource(value));
            source.setTextColor(MUTED);
            source.setTextSize(10);
            source.setSingleLine(true);
            source.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            details.addView(source, matchWrap());
            TextView metadata = new TextView(this);
            metadata.setText("時長／尺寸：點「預覽」讀取");
            metadata.setTextColor(MUTED);
            metadata.setTextSize(9);
            metadata.setSingleLine(true);
            metadata.setEllipsize(TextUtils.TruncateAt.END);
            details.addView(metadata, matchWrap());
            candidateMetadataViews.put(value, metadata);
            row.addView(details, new LinearLayout.LayoutParams(0, dp(58), 1f));
            Button preview = new Button(this);
            preview.setText("預覽");
            preview.setTextSize(11);
            preview.setMinWidth(dp(56));
            preview.setPadding(dp(2), 0, dp(2), 0);
            preview.setOnClickListener(view -> previewCandidate(value));
            row.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            Button send = new Button(this);
            send.setText("送電視");
            send.setTextSize(11);
            send.setMinWidth(dp(70));
            send.setPadding(dp(2), 0, dp(2), 0);
            send.setOnClickListener(view -> sendToTv(value));
            row.addView(send, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            candidateRows.addView(row, 0, matchWrap());
        });
    }

    private boolean containsHeader(Map<String, String> headers, String key) {
        for (String name : headers.keySet()) if (name.equalsIgnoreCase(key)) return true;
        return false;
    }

    private void previewCandidate(String mediaUrl) {
        Dialog dialog = new Dialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        content.setBackgroundColor(BG);

        TextView heading = new TextView(this);
        heading.setText("手機串流預覽");
        heading.setTextColor(TEXT);
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(heading, matchWrap());

        TextView info = new TextView(this);
        info.setText("正在讀取時長與影片尺寸…");
        info.setTextColor(MUTED);
        info.setTextSize(13);
        info.setPadding(0, dp(5), 0, dp(8));
        content.addView(info, matchWrap());

        AspectRatioFrame videoFrame = new AspectRatioFrame(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        TextureView video = new TextureView(this);
        videoFrame.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams videoFrameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        videoFrameParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(videoFrame, videoFrameParams);

        Button playPause = new Button(this);
        playPause.setText("等待影片…");
        playPause.setEnabled(false);
        content.addView(playPause, matchWrap());

        TextView note = new TextView(this);
        note.setText("若無法預覽，來源可能需要網站驗證、特殊標頭或 DRM。");
        note.setTextColor(MUTED);
        note.setTextSize(11);
        note.setPadding(0, dp(6), 0, 0);
        content.addView(note, matchWrap());

        dialog.setContentView(content);
        dialog.setTitle("串流預覽");
        MediaPlayer player = new MediaPlayer();
        Surface[] outputSurface = new Surface[1];
        boolean[] prepared = {false};
        boolean[] released = {false};
        boolean[] firstVideoFrame = {false};
        int[] videoSize = {0, 0};
        Map<String, String> headers;
        synchronized (candidateHeaders) {
            headers = new HashMap<>(candidateHeaders.containsKey(mediaUrl)
                    ? candidateHeaders.get(mediaUrl) : new HashMap<>());
        }
        TextView rowInfo = candidateMetadataViews.get(mediaUrl);

        Runnable showNoVideoTrack = () -> {
            String message = "沒有可解碼的影像軌，可能只有聲音或手機不支援影片編碼；已停止預覽聲音。仍可嘗試送到電視。";
            info.setText(message);
            if (rowInfo != null) rowInfo.setText("沒有手機可解碼的影像軌");
            playPause.setText("沒有畫面");
            playPause.setEnabled(false);
        };

        Runnable updateVideoDetails = () -> {
            if (!prepared[0] || released[0]) return;
            int width = videoSize[0];
            int height = videoSize[1];
            if (width <= 0 || height <= 0) return;
            videoFrame.setAspectRatio(width, height);
            String details = "長度：" + formatDuration(player.getDuration())
                    + "　畫面：" + width + " × " + height;
            info.setText(details);
            if (rowInfo != null) rowInfo.setText(details);
        };

        dialog.setOnDismissListener(ignored -> {
            released[0] = true;
            try { player.release(); } catch (Exception ignoredRelease) {}
            if (outputSurface[0] != null) {
                try { outputSurface[0].release(); } catch (Exception ignoredRelease) {}
                outputSurface[0] = null;
            }
            if (webView != null) webView.onResume();
        });
        playPause.setOnClickListener(view -> {
            if (!prepared[0] || released[0]) return;
            try {
                if (player.isPlaying()) {
                    player.pause();
                    playPause.setText("繼續播放");
                } else {
                    player.start();
                    playPause.setText("暫停");
                }
            } catch (IllegalStateException ignoredState) {
                showNoVideoTrack.run();
            }
        });

        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                if (released[0]) return;
                outputSurface[0] = new Surface(surfaceTexture);
                try {
                    player.setSurface(outputSurface[0]);
                    player.setOnVideoSizeChangedListener((mediaPlayer, videoWidth, videoHeight) -> {
                        videoSize[0] = videoWidth;
                        videoSize[1] = videoHeight;
                        updateVideoDetails.run();
                    });
                    player.setOnPreparedListener(mediaPlayer -> {
                        if (released[0]) return;
                        prepared[0] = true;
                        videoSize[0] = mediaPlayer.getVideoWidth();
                        videoSize[1] = mediaPlayer.getVideoHeight();
                        if (videoSize[0] <= 0 || videoSize[1] <= 0) {
                            showNoVideoTrack.run();
                            return;
                        }
                        updateVideoDetails.run();
                        playPause.setEnabled(true);
                        playPause.setText("暫停");
                        mediaPlayer.start();
                        mainHandler.postDelayed(() -> {
                            if (released[0] || !prepared[0] || firstVideoFrame[0]) return;
                            try { mediaPlayer.pause(); } catch (IllegalStateException ignoredState) {}
                            info.setText("已讀到影片尺寸，但手機沒有解出畫面；已停止播放聲音。這個來源仍可嘗試送到電視。");
                            if (rowInfo != null) rowInfo.setText("有影片軌，但手機沒有預覽畫面");
                            playPause.setText("手機無預覽畫面");
                            playPause.setEnabled(false);
                        }, 5000);
                    });
                    player.setOnErrorListener((mediaPlayer, what, extra) -> {
                        info.setText("手機無法解碼這段影片；可能需要網站驗證、DRM，或影片編碼不相容。仍可嘗試送到電視。 ");
                        if (rowInfo != null) rowInfo.setText("手機解碼失敗；可試電視播放");
                        playPause.setText("預覽失敗");
                        playPause.setEnabled(false);
                        return true;
                    });
                    player.setDataSource(MainActivity.this, Uri.parse(mediaUrl), headers);
                    player.prepareAsync();
                } catch (Exception error) {
                    info.setText("手機無法開啟這個串流；可能需要網站驗證、特殊標頭或 DRM。仍可嘗試送到電視。");
                    if (rowInfo != null) rowInfo.setText("手機預覽無法開啟；可試電視播放");
                    playPause.setEnabled(false);
                }
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (outputSurface[0] != null) {
                    try { player.setSurface(null); } catch (Exception ignoredSurface) {}
                    try { outputSurface[0].release(); } catch (Exception ignoredRelease) {}
                    outputSurface[0] = null;
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                firstVideoFrame[0] = true;
            }
        });

        webView.onPause();
        dialog.show();
    }

    private static final class AspectRatioFrame extends FrameLayout {
        private float aspectRatio = 16f / 9f;

        AspectRatioFrame(Activity activity) { super(activity); }

        void setAspectRatio(int width, int height) {
            if (width > 0 && height > 0) {
                aspectRatio = (float) width / (float) height;
                requestLayout();
            }
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
            int availableHeight = MeasureSpec.getSize(heightMeasureSpec);
            int measuredWidth = availableWidth;
            int measuredHeight = Math.round(measuredWidth / aspectRatio);
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED
                    && measuredHeight > availableHeight) {
                measuredHeight = availableHeight;
                measuredWidth = Math.round(measuredHeight * aspectRatio);
            }
            int childWidth = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY);
            int childHeight = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY);
            super.onMeasure(childWidth, childHeight);
            setMeasuredDimension(measuredWidth, measuredHeight);
        }
    }

    private String formatDuration(int milliseconds) {
        if (milliseconds <= 0) return "未知／可能是直播";
        long totalSeconds = milliseconds / 1000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private String candidateSummary(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String format;
        if (lower.matches(".*\\.m3u8(?:$|[?#]).*")) format = "HLS／m3u8";
        else if (lower.matches(".*\\.mp4(?:$|[?#]).*")) format = "MP4";
        else if (lower.matches(".*\\.webm(?:$|[?#]).*")) format = "WebM";
        else if (lower.matches(".*\\.m4v(?:$|[?#]).*")) format = "M4V";
        else format = "MOV";
        Matcher quality = QUALITY_HINT.matcher(value);
        if (quality.find()) return format + " · 網址標示 " + quality.group().replaceAll("\\s*[x×]\\s*", "×");
        return format + " · 解析度未標示";
    }

    private String candidateSource(String value) {
        Uri uri = Uri.parse(value);
        String host = uri.getHost() == null ? "未知網域" : uri.getHost();
        String path = uri.getLastPathSegment();
        if (TextUtils.isEmpty(path)) return host;
        return host + " · " + path;
    }

    private void inspectPageVideoDimensions() {
        if (webView == null) return;
        String script = "(function(){var v=Array.prototype.find.call(document.querySelectorAll('video'),function(x){return x.videoWidth>0&&x.videoHeight>0;});return v?{width:v.videoWidth,height:v.videoHeight}:null;})()";
        webView.evaluateJavascript(script, value -> {
            if (value == null || "null".equals(value)) return;
            Matcher dimensions = VIDEO_DIMENSIONS.matcher(value);
            if (dimensions.find()) {
                playbackInfo.setText("網頁目前解碼：" + dimensions.group(1) + " × " + dimensions.group(2));
            }
        });
    }

    private void refreshBridgeStatus() {
        String host = configuredTvHost();
        String state = bridgeStartupError != null ? bridgeStartupError : bridgeReady
                ? "手機橋接已啟動 · Port " + bridgePort : "手機橋接服務啟動中…";
        bridgeStatus.setText(state + (host.isEmpty() ? " · 尚未設定電視" : " · 電視 " + host));
    }

    private void sendToTv(String mediaUrl) {
        pageStatus.setText("正在把串流網址傳給電視…");
        JSONObject payload = new JSONObject();
        try { payload.put("mediaUrl", mediaUrl); }
        catch (Exception error) {
            pageStatus.setText("無法建立影片啟動資料。");
            return;
        }
        launchToTv(payload, (success, message) -> pageStatus.setText(success ? message : "傳送失敗：" + message));
    }

    private String readBody(InputStream input) throws Exception {
        if (input == null) return "{}";
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (browserRoot != null && browserRoot.getVisibility() == View.VISIBLE) {
            if (webView != null && webView.canGoBack()) webView.goBack();
            else showHomeScreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(playbackInfoPoller);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageLoaded) mainHandler.post(playbackInfoPoller);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(playbackInfoPoller);
        if (controllerDialog != null && controllerDialog.isShowing()) controllerDialog.dismiss();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        if (bridgeServer != null) bridgeServer.stopBridge();
        TvPointerClient activePointer = pointerClient;
        pointerClient = null;
        if (activePointer != null) activePointer.close();
        network.shutdownNow();
        super.onDestroy();
    }
}
