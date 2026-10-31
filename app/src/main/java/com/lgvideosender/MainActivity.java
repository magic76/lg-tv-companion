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
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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
import android.widget.Toast;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.util.Log;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InterfaceAddress;
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
    private volatile String pendingControllerTvScanCallbackId = "";
    private PermissionRequest pendingWebPermission;

    private FrameLayout controllerRoot;
    private String pendingSharedUrl = "";
    private volatile boolean controllerPageLoaded;
    private LinearLayout browserRoot;
    private FrameLayout browserWebContainer;
    private TextView pageStatus;
    private TextView playbackInfo;
    private TextView emptyCandidates;
    private LinearLayout candidateRows;
    private WebView webView;
    private WebView mediaPopupWebView;
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
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        initializePairingCode();
        startBridgeServer();
        buildUi();
        openPhoneController();
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

        controllerRoot = new FrameLayout(this);
        controllerRoot.setBackgroundColor(BG);
        screen.addView(controllerRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        browserRoot = new LinearLayout(this);
        browserRoot.setOrientation(LinearLayout.VERTICAL);
        browserRoot.setPadding(dp(12), dp(8), dp(12), dp(8));
        browserRoot.setBackgroundColor(BG);
        browserRoot.setVisibility(View.GONE);
        screen.addView(browserRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildMediaBrowserUi();

        setContentView(screen);
        applySystemBarInsets(screen);
    }

    private void buildMediaBrowserUi() {
        LinearLayout browserHeader = new LinearLayout(this);
        browserHeader.setGravity(Gravity.CENTER_VERTICAL);
        Button backButton = new Button(this);
        backButton.setText("‹ 返回控制");
        backButton.setAllCaps(false);
        backButton.setTextColor(TEXT);
        backButton.setBackgroundTintList(ColorStateList.valueOf(PANEL));
        backButton.setOnClickListener(view -> showControllerScreen());
        browserHeader.addView(backButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        TextView browserTitle = new TextView(this);
        browserTitle.setText("手機影片來源偵測");
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
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        webUserAgent = settings.getUserAgentString();
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isPyppoUrl(url)) {
                    pageStatus.setText("已略過播放器的廣告跳轉，保留目前頁面。");
                    return true;
                }
                if (!isIntentUrl(url)) return false;
                String fallback = resolveIntentFallback(url);
                if (fallback == null) {
                    pageStatus.setText("這個 intent:// 連結沒有可用的 http 或 https 備用網址。");
                } else {
                    loadPage(fallback);
                }
                return true;
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
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (!isUserGesture || browserWebContainer == null || webView == null
                        || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;

                disposeMediaPopup(mediaPopupWebView);
                WebView popup = new WebView(MainActivity.this);
                WebSettings popupSettings = popup.getSettings();
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setDomStorageEnabled(true);
                popupSettings.setJavaScriptCanOpenWindowsAutomatically(true);
                popupSettings.setSupportMultipleWindows(true);
                popupSettings.setMediaPlaybackRequiresUserGesture(false);
                popupSettings.setUserAgentString(webUserAgent);
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popupView, WebResourceRequest request) {
                        return routeMediaPopupUrl(popupView, request.getUrl().toString());
                    }

                    @Override
                    @SuppressWarnings("deprecation")
                    public boolean shouldOverrideUrlLoading(WebView popupView, String url) {
                        return routeMediaPopupUrl(popupView, url);
                    }
                });
                mediaPopupWebView = popup;
                browserWebContainer.addView(popup, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                popup.setAlpha(0f);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
        browserWebContainer = new FrameLayout(this);
        browserWebContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        browserRoot.addView(browserWebContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private boolean routeMediaPopupUrl(WebView popup, String url) {
        if ("about:blank".equalsIgnoreCase(url)) return false;
        if (isPyppoUrl(url)) {
            disposeMediaPopup(popup);
            if (pageStatus != null) pageStatus.setText("已略過播放器的廣告跳轉，保留目前頁面。");
            return true;
        }
        String normalized = normalizeHttpUrl(url, 8192);
        if (normalized == null && isIntentUrl(url)) normalized = resolveIntentFallback(url);
        if (normalized == null) {
            disposeMediaPopup(popup);
            if (pageStatus != null) {
                pageStatus.setText(isIntentUrl(url)
                        ? "這個 intent:// 連結沒有可用的 http 或 https 備用網址。"
                        : "這個連結不是 http 或 https 網址，影片偵測頁無法開啟。");
            }
            return true;
        }
        disposeMediaPopup(popup);
        if (webView != null) loadPage(normalized);
        return true;
    }

    private boolean isIntentUrl(String url) {
        return url != null && url.regionMatches(true, 0, "intent:", 0, 7);
    }

    private boolean isPyppoUrl(String url) {
        if (url == null) return false;
        String candidate = normalizeHttpUrl(url, 8192);
        if (candidate == null && isIntentUrl(url)) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                Uri data = intent.getData();
                if (isPyppoHost(data == null ? null : data.getHost())) return true;
            } catch (Exception ignored) {}
            candidate = resolveIntentFallback(url);
        }
        return candidate != null && isPyppoHost(Uri.parse(candidate).getHost());
    }

    private boolean isPyppoHost(String host) {
        if (host == null) return false;
        String suffix = ".pyppo.com";
        return host.equalsIgnoreCase("pyppo.com")
                || (host.length() > suffix.length()
                && host.regionMatches(true, host.length() - suffix.length(), suffix, 0, suffix.length()));
    }

    private String resolveIntentFallback(String url) {
        if (!isIntentUrl(url)) return null;
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            String fallback = normalizeHttpUrl(intent.getStringExtra("browser_fallback_url"), 8192);
            if (fallback != null) return fallback;
            Uri data = intent.getData();
            String resolvedData = normalizeHttpUrl(data == null ? null : data.toString(), 8192);
            if (resolvedData != null) return resolvedData;
        } catch (Exception ignored) {}

        // Some sites emit a plain intent://host/path URL without Chrome's #Intent; metadata.
        if (url.regionMatches(true, 0, "intent://", 0, 9) && !url.contains("#Intent;")) {
            String httpsUrl = "https://" + url.substring(9).replace(" ", "%20");
            return normalizeHttpUrl(httpsUrl, 8192);
        }
        return null;
    }

    private void disposeMediaPopup(WebView popup) {
        if (popup == null) return;
        if (mediaPopupWebView == popup) mediaPopupWebView = null;
        popup.stopLoading();
        if (popup.getParent() instanceof ViewGroup) {
            ((ViewGroup) popup.getParent()).removeView(popup);
        }
        popup.destroy();
    }

    private void applySystemBarInsets(View content) {
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private void showControllerScreen() {
        if (controllerRoot != null) controllerRoot.setVisibility(View.VISIBLE);
        if (browserRoot != null) browserRoot.setVisibility(View.GONE);
    }

    private void showBrowserScreen() {
        if (controllerRoot != null) controllerRoot.setVisibility(View.GONE);
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
                    refreshBridgeStatus();
                } else {
                    bridgeStartupError = message;
                    bridgeReady = false;
                    setControllerStatus(message);
                }
            }), bridgePort);
            bridgeServer.start();
        } catch (Exception error) {
            bridgeStartupError = "App 內橋接啟動失敗：" + error.getMessage();
            setControllerStatus(bridgeStartupError);
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
            String host = configuredTvHost();
            config.put("tvConfigured", !host.isEmpty());
            config.put("tvHost", host);
            config.put("tvMac", tvPreferences().getString("tv_mac", ""));
            config.put("bridgeReady", bridgeReady);
            config.put("bridgeStatus", bridgeStartupError == null ? "" : bridgeStartupError);
            config.put("addresses", addressList.json());
            config.put("phoneSocketUrl", "ws://localhost:" + bridgePort + "/ws");
        } catch (Exception ignored) {}
        return config;
    }

    private String currentControllerStatus() {
        if (!TextUtils.isEmpty(bridgeStartupError)) return bridgeStartupError;
        String host = configuredTvHost();
        return (bridgeReady ? "手機控制服務已就緒" : "手機控制服務啟動中…")
                + (host.isEmpty() ? " · 尚未設定電視" : " · 電視 " + host);
    }

    private void setControllerStatus(String message) {
        WebView current = controllerWebView;
        if (current == null || TextUtils.isEmpty(message)) return;
        current.post(() -> {
            if (current != controllerWebView) return;
            current.evaluateJavascript("window.onNativeBridgeStatus && window.onNativeBridgeStatus("
                    + JSONObject.quote(message) + ");", null);
        });
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
        if (controllerWebView != null) {
            if (controllerRoot != null) controllerRoot.setVisibility(View.VISIBLE);
            return;
        }
        if (controllerRoot == null) return;

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

            @Override
            public void onPageFinished(WebView view, String url) {
                controllerPageLoaded = true;
                setControllerStatus(currentControllerStatus());
                if (!TextUtils.isEmpty(pendingSharedUrl)) {
                    String sharedUrl = pendingSharedUrl;
                    pendingSharedUrl = "";
                    view.evaluateJavascript("window.setSharedMediaUrl && window.setSharedMediaUrl("
                            + JSONObject.quote(sharedUrl) + ");", null);
                }
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
        controllerRoot.addView(controllerWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        controllerRoot.setVisibility(View.VISIBLE);

        try (InputStream input = getAssets().open("phone.html")) {
            String html = readBody(input);
            controllerWebView.loadDataWithBaseURL("http://localhost/", html, "text/html", "UTF-8", null);
        } catch (Exception error) {
            String message = error.getMessage() == null ? "未知錯誤" : error.getMessage();
            controllerWebView.loadDataWithBaseURL("http://localhost/",
                    "<meta charset=\"utf-8\"><p>無法載入手機控制頁：" + message + "</p>",
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
        public void openMediaScanner(String url, String callbackId) {
            String normalized = normalizeHttpUrl(url, 2048);
            if (normalized == null) {
                postControllerLaunchResult(callbackId, false, "請輸入有效的 http 或 https 網址。");
                return;
            }
            mainHandler.post(() -> {
                if (webView == null) {
                    postControllerLaunchResult(callbackId, false, "手機影片瀏覽器尚未準備好，請重試。");
                    return;
                }
                loadPage(normalized);
                postControllerLaunchResult(callbackId, true, "已在手機開啟影片來源偵測。");
            });
        }

        @JavascriptInterface
        public void saveTvHost(String host, String callbackId) {
            mainHandler.post(() -> {
                String normalized = host == null ? "" : host.trim();
                boolean valid = normalized.length() <= 253
                        && normalized.matches("(?i)^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")
                        && !normalized.contains("..");
                if (valid && normalized.matches("[0-9.]+")) valid = isValidTvIpv4(normalized);
                if (!valid) {
                    postControllerSettingsResult(callbackId, false, "請輸入有效的電視 IP 或主機名稱。");
                    return;
                }
                String oldHost = tvPreferences().getString("tv_host", "");
                SharedPreferences.Editor editor = tvPreferences().edit().putString("tv_host", normalized);
                if (!normalized.equalsIgnoreCase(oldHost)) editor.remove("tv_client_key").remove("tv_mac");
                editor.apply();
                setControllerStatus("電視位址已儲存：" + normalized);
                postControllerSettingsResult(callbackId, true, "電視位址已儲存。請確認手機和電視使用同一個 Wi-Fi。");
            });
        }

        @JavascriptInterface
        public void saveTvMac(String mac, String callbackId) {
            mainHandler.post(() -> {
                String normalized = normalizeMac(mac);
                if (normalized.isEmpty()) {
                    postControllerSettingsResult(callbackId, false, "請輸入有效的 MAC 位址。");
                    return;
                }
                tvPreferences().edit().putString("tv_mac", normalized).apply();
                postControllerSettingsResult(callbackId, true, "電視喚醒位址已儲存。");
            });
        }

        @JavascriptInterface
        public void wakeTv(String callbackId) {
            mainHandler.post(() -> {
                String mac = normalizeMac(tvPreferences().getString("tv_mac", ""));
                if (mac.isEmpty()) {
                    postControllerSettingsResult(callbackId, false, "先掃描電視 QR；如果 QR 沒有 MAC，請在下方填入電視 MAC。");
                    return;
                }
                sendWakeOnLan(mac, callbackId);
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

        @JavascriptInterface
        public void disconnectPointer() {
            stopPointerClient(null);
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

    private void stopPointerClient(String message) {
        TvPointerClient activePointer = pointerClient;
        pointerClient = null;
        if (activePointer != null) activePointer.close();
        if (!TextUtils.isEmpty(message)) postControllerPointerStatus(false, message);
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

    private void postControllerSettingsResult(String callbackId, boolean success, String message) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeSettingsResult && window.onNativeSettingsResult("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + ","
                    + success + "," + JSONObject.quote(message == null ? "" : message) + ","
                    + bridgeConfig().toString() + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private static String normalizeMac(String value) {
        if (value == null) return "";
        String mac = value.trim().replace('-', ':').toUpperCase(Locale.ROOT);
        if (!mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
                || "00:00:00:00:00:00".equals(mac)
                || "FF:FF:FF:FF:FF:FF".equals(mac)) return "";
        return mac;
    }

    private void rememberTvMacFromArp(String host) {
        try {
            String ip = InetAddress.getByName(host).getHostAddress();
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length >= 4 && ip.equals(fields[0])) {
                        String mac = normalizeMac(fields[3]);
                        if (!mac.isEmpty()) {
                            tvPreferences().edit().putString("tv_mac", mac).apply();
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Android may hide ARP entries; manual MAC entry remains available.
        }
    }

    private void sendWakeOnLan(String rawMac, String callbackId) {
        String mac = normalizeMac(rawMac);
        if (mac.isEmpty()) {
            postControllerSettingsResult(callbackId, false, "請先設定有效的電視 MAC 位址。");
            return;
        }
        setControllerStatus("正在送出 Wi-Fi 喚醒訊號…");
        network.execute(() -> {
            try {
                ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                Network wifiNetwork = null;
                if (connectivity != null) {
                    for (Network candidate : connectivity.getAllNetworks()) {
                        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(candidate);
                        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            wifiNetwork = candidate;
                            break;
                        }
                    }
                }
                if (wifiNetwork == null) throw new IllegalStateException("手機目前沒有連上 Wi-Fi。");

                Set<InetAddress> destinations = new LinkedHashSet<>();
                LinkProperties linkProperties = connectivity.getLinkProperties(wifiNetwork);
                if (linkProperties != null && !TextUtils.isEmpty(linkProperties.getInterfaceName())) {
                    NetworkInterface wifiInterface = NetworkInterface.getByName(linkProperties.getInterfaceName());
                    if (wifiInterface != null && wifiInterface.isUp()) {
                        for (InterfaceAddress interfaceAddress : wifiInterface.getInterfaceAddresses()) {
                            InetAddress broadcast = interfaceAddress.getBroadcast();
                            if (broadcast instanceof Inet4Address) destinations.add(broadcast);
                        }
                    }
                }
                destinations.add(InetAddress.getByName("255.255.255.255"));

                byte[] macBytes = new byte[6];
                String[] octets = mac.split(":");
                for (int index = 0; index < macBytes.length; index++) {
                    macBytes[index] = (byte) Integer.parseInt(octets[index], 16);
                }
                byte[] packet = new byte[6 + 16 * macBytes.length];
                for (int index = 0; index < 6; index++) packet[index] = (byte) 0xFF;
                for (int repeat = 0; repeat < 16; repeat++) {
                    System.arraycopy(macBytes, 0, packet, 6 + repeat * macBytes.length, macBytes.length);
                }

                int sent = 0;
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    wifiNetwork.bindSocket(socket);
                    for (InetAddress destination : destinations) {
                        for (int repeat = 0; repeat < 3; repeat++) {
                            socket.send(new DatagramPacket(packet, packet.length, destination, 9));
                            sent++;
                        }
                    }
                }
                if (sent == 0) throw new IllegalStateException("沒有可用的 Wi-Fi 廣播位址。");
                mainHandler.post(() -> {
                    String message = "已送出 Wi-Fi 喚醒封包。請確認電視已開啟 Mobile TV On。";
                    setControllerStatus(message);
                    Toast.makeText(this, "已送出電視喚醒訊號", Toast.LENGTH_SHORT).show();
                    postControllerSettingsResult(callbackId, true, message);
                });
            } catch (Exception error) {
                String message = TextUtils.isEmpty(error.getMessage())
                        ? "Wi-Fi 喚醒訊號送出失敗。" : error.getMessage();
                mainHandler.post(() -> {
                    setControllerStatus(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    postControllerSettingsResult(callbackId, false, message);
                });
            }
        });
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
                setControllerStatus("掃描已取消。電視設定沒有變更。");
                completeControllerTvScan(false, "掃描已取消，電視設定沒有變更。");
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleTvPairQr(String contents) {
        String host = null;
        String mac = "";
        try {
            Uri pairing = Uri.parse(contents.trim());
            String version = pairing.getQueryParameter("v");
            if (pairing.isHierarchical()
                    && "lgcompanion".equalsIgnoreCase(pairing.getScheme())
                    && "pair".equalsIgnoreCase(pairing.getHost())
                    && ("1".equals(version) || "2".equals(version))) {
                host = pairing.getQueryParameter("host");
                mac = normalizeMac(pairing.getQueryParameter("mac"));
            }
        } catch (Exception ignored) {}
        if (!isValidTvIpv4(host)) {
            String message = "無法辨識這個 QR code，請掃描電視 App 的配對 QR code。";
            setControllerStatus(message);
            completeControllerTvScan(false, message);
            return;
        }

        final String tvHost = host;
        final String tvMac = mac;
        String oldHost = tvPreferences().getString("tv_host", "");
        SharedPreferences.Editor editor = tvPreferences().edit().putString("tv_host", tvHost);
        if (!tvHost.equalsIgnoreCase(oldHost)) editor.remove("tv_client_key").remove("tv_mac");
        if (!tvMac.isEmpty()) editor.putString("tv_mac", tvMac);
        editor.apply();
        setControllerStatus("已掃描到電視 " + tvHost
                + (tvMac.isEmpty() ? "，正在連線…" : "（已記錄 MAC），正在連線…"));

        launchToTv(new JSONObject(), (success, message) -> {
            setControllerStatus(success ? "電視已連線 · " + tvHost : "已記住電視 " + tvHost + " · " + message);
            completeControllerTvScan(success, success
                    ? "已啟動電視 App，正在等待連線…" : message);
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
                            if (success) rememberTvMacFromArp(host);
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
        if (url != null) {
            pendingSharedUrl = url;
            if (controllerWebView != null && controllerPageLoaded) {
                controllerWebView.evaluateJavascript("window.setSharedMediaUrl && window.setSharedMediaUrl("
                        + JSONObject.quote(url) + ");", null);
                pendingSharedUrl = "";
            }
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
        setControllerStatus(currentControllerStatus());
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
            if (mediaPopupWebView != null) {
                disposeMediaPopup(mediaPopupWebView);
                return;
            }
            if (webView != null && webView.canGoBack()) webView.goBack();
            else showControllerScreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(playbackInfoPoller);
        stopPointerClient("手機 App 進入背景，觸控板已暫停；回到遙控器頁會自動重連。");
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageLoaded) mainHandler.post(playbackInfoPoller);
        WebView activeControllerWebView = controllerWebView;
        if (activeControllerWebView != null) {
            activeControllerWebView.post(() -> activeControllerWebView.evaluateJavascript(
                    "window.onNativeAppForegrounded && window.onNativeAppForegrounded();", null));
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(playbackInfoPoller);
        if (pendingWebPermission != null) {
            try { pendingWebPermission.deny(); } catch (Exception ignored) {}
            pendingWebPermission = null;
        }
        if (controllerWebView != null) {
            controllerWebView.stopLoading();
            controllerWebView.destroy();
            controllerWebView = null;
        }
        disposeMediaPopup(mediaPopupWebView);
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
