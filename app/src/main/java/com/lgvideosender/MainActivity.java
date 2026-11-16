package com.lgvideosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.MotionEvent;
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
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InterfaceAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int REQUEST_TV_APP_PACKAGE = 7413;
    private static final int PANEL = Color.rgb(27, 39, 50);
    private static final int TEXT = Color.rgb(242, 246, 249);
    private static final int MUTED = Color.rgb(167, 183, 194);
    private static final int ACCENT = Color.rgb(155, 225, 205);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService tvAppInstallerExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> mediaUrls = new LinkedHashSet<>();
    private final Map<String, Map<String, String>> candidateHeaders = new HashMap<>();
    private final Map<String, TextView> candidateMetadataViews = new HashMap<>();
    private int candidateGeneration;
    private String pairCode;
    private PhoneBridgeServer bridgeServer;
    private GimyaiCompatProxyServer gimyaiCompatProxyServer;
    private GimyaiCompatProxyServer yahooCompatProxyServer;
    private volatile boolean bridgeReady;
    private volatile String bridgeStartupError;
    private volatile boolean gimyaiProxyReady;
    private volatile boolean yahooProxyReady;
    private int bridgePort = 8787;
    private int gimyaiProxyPort;
    private int yahooProxyPort;
    private volatile TvPointerClient pointerClient;
    private volatile TvAudioClient tvAudioClient;
    private final AtomicBoolean tvVolumeRequestPending = new AtomicBoolean(false);
    private volatile TvPowerClient tvPowerStateClient;
    private volatile TvPowerClient tvPowerToggleClient;
    private final AtomicBoolean tvPowerStateCheckPending = new AtomicBoolean(false);
    private final AtomicBoolean tvPowerTogglePending = new AtomicBoolean(false);
    private volatile WebView controllerWebView;
    private volatile String pendingControllerTvScanCallbackId = "";
    private volatile String pendingTvAppPackageCallbackId = "";
    private volatile boolean tvAppInstallerBusy;
    private File selectedTvAppPackageFile;
    private TvAppInstaller.PackageInfo selectedTvAppPackageInfo;
    private PermissionRequest pendingWebPermission;

    private FrameLayout controllerRoot;
    private String pendingSharedUrl = "";
    private volatile boolean controllerPageLoaded;
    private FrameLayout browserRoot;
    private FrameLayout browserWebContainer;
    private TextView pageStatus;
    private TextView playbackInfo;
    private TextView emptyCandidates;
    private LinearLayout candidateSheet;
    private LinearLayout candidateRows;
    private LinearLayout candidateEmptyPanel;
    private ScrollView candidateScroll;
    private TextView candidateTitle;
    private TextView candidateHint;
    private TextView candidateToggle;
    private TextView clearCandidatesButton;
    private TextView undoCandidatesButton;
    private TextView sendSelectedButton;
    private FrameLayout.LayoutParams candidateSheetLayoutParams;
    private String selectedMediaUrl = "";
    private String clearedSelectedMediaUrl = "";
    private final List<String> clearedMediaUrls = new ArrayList<>();
    private final Map<String, Map<String, String>> clearedCandidateHeaders = new HashMap<>();
    private boolean candidateSheetExpanded;
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
        CompanionKeepAliveService.start(this);
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

        browserRoot = new FrameLayout(this);
        browserRoot.setBackgroundColor(BG);
        browserRoot.setVisibility(View.GONE);
        screen.addView(browserRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildMediaBrowserUi();

        setContentView(screen);
        applySystemBarInsets(screen);
    }

    private void buildMediaBrowserUi() {
        LinearLayout browserContent = new LinearLayout(this);
        browserContent.setOrientation(LinearLayout.VERTICAL);
        browserContent.setPadding(dp(12), dp(8), dp(12), 0);
        browserRoot.addView(browserContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout browserHeader = new LinearLayout(this);
        browserHeader.setGravity(Gravity.CENTER_VERTICAL);
        Button backButton = new Button(this);
        backButton.setText("‹ 返回控制");
        backButton.setAllCaps(false);
        backButton.setTextColor(TEXT);
        backButton.setBackground(makeRoundedDrawable(PANEL, dp(14), 0));
        backButton.setOnClickListener(view -> showControllerScreen());
        browserHeader.addView(backButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        TextView browserTitle = new TextView(this);
        browserTitle.setText("網頁找影片");
        browserTitle.setTextColor(TEXT);
        browserTitle.setTextSize(17);
        browserTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        browserTitle.setPadding(dp(12), 0, 0, 0);
        browserHeader.addView(browserTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        browserContent.addView(browserHeader, matchWrap());

        pageStatus = new TextView(this);
        pageStatus.setText("準備開啟網頁…");
        pageStatus.setTextColor(MUTED);
        pageStatus.setTextSize(12);
        pageStatus.setSingleLine(true);
        pageStatus.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pageStatus.setPadding(dp(4), dp(4), dp(4), dp(1));
        browserContent.addView(pageStatus, matchWrap());

        playbackInfo = new TextView(this);
        playbackInfo.setText("在網頁播放器按播放，自動偵測影片來源");
        playbackInfo.setTextColor(MUTED);
        playbackInfo.setTextSize(11);
        playbackInfo.setSingleLine(true);
        playbackInfo.setEllipsize(TextUtils.TruncateAt.END);
        playbackInfo.setPadding(dp(4), dp(1), dp(4), dp(7));
        browserContent.addView(playbackInfo, matchWrap());

        FrameLayout browserBody = new FrameLayout(this);
        browserContent.addView(browserBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
                    pageStatus.setText("已略過廣告跳轉，仍停留在原頁面");
                    return true;
                }
                if (!isIntentUrl(url)) return false;
                String fallback = resolveIntentFallback(url);
                if (fallback == null) {
                    pageStatus.setText("這個連結沒有可開啟的網頁備用網址");
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
                pageStatus.setText("已開啟 · " + readableHost(url));
                pageLoaded = true;
                mainHandler.removeCallbacks(playbackInfoPoller);
                mainHandler.post(playbackInfoPoller);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageStatus.setText("網頁載入失敗 · " + error.getDescription());
                    playbackInfo.setText("請確認網路後重新載入網頁");
                }
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
        browserBody.addView(browserWebContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildCandidateSheet();
    }

    private void buildCandidateSheet() {
        candidateSheet = new LinearLayout(this);
        candidateSheet.setOrientation(LinearLayout.VERTICAL);
        candidateSheet.setPadding(dp(14), dp(7), dp(14), dp(10));
        candidateSheet.setBackground(makeRoundedDrawable(PANEL, dp(20), 0));
        candidateSheet.setElevation(dp(10));
        candidateSheetLayoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110), Gravity.BOTTOM);
        candidateSheetLayoutParams.leftMargin = dp(4);
        candidateSheetLayoutParams.rightMargin = dp(4);
        candidateSheetLayoutParams.bottomMargin = dp(4);
        browserRoot.addView(candidateSheet, candidateSheetLayoutParams);

        View dragHandle = new View(this);
        dragHandle.setBackground(makeRoundedDrawable(Color.rgb(105, 125, 137), dp(3), 0));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(38), dp(5));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(6);
        candidateSheet.addView(dragHandle, handleParams);
        installSheetGesture(dragHandle);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        candidateTitle = new TextView(this);
        candidateTitle.setText("影片來源 · 0");
        candidateTitle.setTextColor(TEXT);
        candidateTitle.setTextSize(15);
        candidateTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(candidateTitle, new LinearLayout.LayoutParams(0, dp(36), 1f));

        clearCandidatesButton = sheetAction("清空", () -> clearMediaCandidates());
        undoCandidatesButton = sheetAction("復原", () -> undoClearMediaCandidates());
        candidateToggle = sheetAction("⌃", () -> toggleCandidateSheet());
        candidateToggle.setTextSize(20);
        candidateToggle.setContentDescription("展開影片來源清單");
        heading.addView(clearCandidatesButton, wrapParams());
        heading.addView(undoCandidatesButton, wrapParams());
        heading.addView(candidateToggle, new LinearLayout.LayoutParams(dp(42), dp(40)));
        candidateSheet.addView(heading, matchWrap());
        installSheetGesture(heading);

        candidateHint = new TextView(this);
        candidateHint.setText("請在網頁播放器按播放，自動偵測影片來源");
        candidateHint.setTextColor(MUTED);
        candidateHint.setTextSize(12);
        candidateHint.setSingleLine(true);
        candidateHint.setEllipsize(TextUtils.TruncateAt.END);
        candidateHint.setPadding(dp(1), 0, dp(1), dp(5));
        candidateSheet.addView(candidateHint, matchWrap());

        candidateEmptyPanel = new LinearLayout(this);
        candidateEmptyPanel.setOrientation(LinearLayout.VERTICAL);
        candidateEmptyPanel.setGravity(Gravity.CENTER_VERTICAL);
        candidateEmptyPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        candidateEmptyPanel.setBackground(makeRoundedDrawable(Color.rgb(21, 31, 41), dp(14), 0));
        emptyCandidates = new TextView(this);
        emptyCandidates.setText("在網頁播放器按播放，手機會自動尋找影片來源。\n有些網站採用 DRM、blob 串流或登入驗證，可能無法直接偵測。");
        emptyCandidates.setTextColor(MUTED);
        emptyCandidates.setTextSize(12);
        emptyCandidates.setLineSpacing(dp(3), 1f);
        candidateEmptyPanel.addView(emptyCandidates, matchWrap());
        TextView reloadPage = sheetAction("重新載入此頁", () -> reloadCurrentPage());
        reloadPage.setGravity(Gravity.CENTER);
        reloadPage.setBackground(makeRoundedDrawable(Color.rgb(42, 59, 70), dp(12), 0));
        LinearLayout.LayoutParams reloadParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        reloadParams.topMargin = dp(5);
        candidateEmptyPanel.addView(reloadPage, reloadParams);
        candidateSheet.addView(candidateEmptyPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        candidateEmptyPanel.setVisibility(View.GONE);

        candidateScroll = new ScrollView(this);
        candidateScroll.setFillViewport(false);
        candidateScroll.setClipToPadding(false);
        candidateRows = new LinearLayout(this);
        candidateRows.setOrientation(LinearLayout.VERTICAL);
        candidateRows.setPadding(0, dp(3), 0, dp(7));
        candidateScroll.addView(candidateRows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        candidateSheet.addView(candidateScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        candidateScroll.setVisibility(View.GONE);

        sendSelectedButton = new TextView(this);
        sendSelectedButton.setText("投放到電視");
        sendSelectedButton.setTextColor(Color.rgb(12, 33, 34));
        sendSelectedButton.setTextSize(15);
        sendSelectedButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sendSelectedButton.setGravity(Gravity.CENTER);
        sendSelectedButton.setBackground(makeRoundedDrawable(ACCENT, dp(14), 0));
        sendSelectedButton.setOnClickListener(view -> {
            if (!TextUtils.isEmpty(selectedMediaUrl)) sendToTv(selectedMediaUrl);
        });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        sendParams.topMargin = dp(6);
        candidateSheet.addView(sendSelectedButton, sendParams);
        updateCandidateSheet();
    }

    private void installSheetGesture(View target) {
        final float[] downY = {0};
        target.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downY[0] = event.getRawY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float delta = event.getRawY() - downY[0];
                if (delta < -dp(28)) setCandidateSheetExpanded(true);
                else if (delta > dp(28)) setCandidateSheetExpanded(false);
                else toggleCandidateSheet();
                return true;
            }
            return true;
        });
    }

    private TextView sheetAction(String label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setBackground(makeRoundedDrawable(Color.rgb(42, 59, 70), dp(11), 0));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
    }

    private GradientDrawable makeRoundedDrawable(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void toggleCandidateSheet() {
        setCandidateSheetExpanded(!candidateSheetExpanded);
    }

    private void setCandidateSheetExpanded(boolean expanded) {
        candidateSheetExpanded = expanded;
        updateCandidateSheet();
    }

    private void updateCandidateSheet() {
        if (candidateSheet == null) return;
        int count;
        synchronized (mediaUrls) { count = mediaUrls.size(); }
        boolean hasCandidates = count > 0;
        candidateTitle.setText("影片來源 · " + count);
        clearCandidatesButton.setVisibility(hasCandidates ? View.VISIBLE : View.GONE);
        undoCandidatesButton.setVisibility(clearedMediaUrls.isEmpty() ? View.GONE : View.VISIBLE);
        candidateToggle.setText(candidateSheetExpanded ? "⌄" : "⌃");
        candidateToggle.setContentDescription(candidateSheetExpanded ? "收合影片來源清單" : "展開影片來源清單");
        candidateHint.setText(hasCandidates ? selectedCandidateHint()
                : (clearedMediaUrls.isEmpty() ? "請在網頁播放器按播放，自動偵測影片來源"
                : "來源清單已清空，可按「復原」還原"));
        candidateScroll.setVisibility(candidateSheetExpanded && hasCandidates ? View.VISIBLE : View.GONE);
        candidateEmptyPanel.setVisibility(candidateSheetExpanded && !hasCandidates ? View.VISIBLE : View.GONE);
        sendSelectedButton.setVisibility(hasCandidates ? View.VISIBLE : View.GONE);

        int height;
        if (candidateSheetExpanded) {
            int desired = Math.min(dp(560), Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f));
            int available = browserRoot.getHeight() > dp(120) ? browserRoot.getHeight() - dp(12) : desired;
            height = Math.max(dp(220), Math.min(desired, available));
        } else {
            height = hasCandidates ? dp(146) : dp(102);
        }
        candidateSheetLayoutParams.height = height;
        candidateSheet.requestLayout();
    }

    private String selectedCandidateHint() {
        String selected = selectedMediaUrl;
        if (TextUtils.isEmpty(selected)) return "點開清單，選擇想預覽或投放的來源";
        return "已選取 · " + candidateSummary(selected) + " · " + candidateSource(selected);
    }

    private String readableHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return TextUtils.isEmpty(host) ? url : host;
        } catch (Exception ignored) {
            return url;
        }
    }

    private void reloadCurrentPage() {
        String current = webView == null ? null : webView.getUrl();
        if (TextUtils.isEmpty(current) || !(current.startsWith("http://") || current.startsWith("https://"))) {
            pageStatus.setText("目前頁面沒有可重新載入的網址");
            return;
        }
        loadPage(current);
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
            gimyaiProxyPort = bridgePort + 1;
            yahooProxyPort = bridgePort + 2;
            gimyaiCompatProxyServer = new GimyaiCompatProxyServer(gimyaiProxyPort, message -> {
                if (message.startsWith("Gimyai 相容代理已啟動")) gimyaiProxyReady = true;
            });
            try {
                gimyaiCompatProxyServer.start();
                gimyaiProxyReady = true;
            } catch (Exception proxyError) {
                gimyaiProxyReady = false;
                gimyaiCompatProxyServer = null;
            }
            yahooCompatProxyServer = new GimyaiCompatProxyServer(yahooProxyPort, true, message -> {
                if (message.startsWith("Yahoo 相容代理已啟動")) yahooProxyReady = true;
            });
            try {
                yahooCompatProxyServer.start();
                yahooProxyReady = true;
            } catch (Exception proxyError) {
                yahooProxyReady = false;
                yahooCompatProxyServer = null;
            }
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
        for (int port = 8787; port <= 8896; port++) {
            boolean available = true;
            for (int offset = 0; offset <= 2; offset++) {
                try (ServerSocket socket = new ServerSocket()) {
                    socket.setReuseAddress(false);
                    socket.bind(new InetSocketAddress("0.0.0.0", port + offset));
                } catch (Exception occupied) {
                    available = false;
                    break;
                }
            }
            if (available) return port;
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
            config.put("gimyaiProxyReady", gimyaiProxyReady);
            config.put("gimyaiProxyPort", gimyaiProxyPort);
            config.put("yahooProxyReady", yahooProxyReady);
            config.put("yahooProxyPort", yahooProxyPort);
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
        public void selectTvAppPackage(String callbackId) {
            mainHandler.post(() -> {
                if (tvAppInstallerBusy) {
                    postControllerTvAppPackageResult(callbackId, false, "目前正在安裝，完成後再選套件。", null);
                    return;
                }
                pendingTvAppPackageCallbackId = callbackId == null ? "" : callbackId;
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("*/*");
                picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.debian.binary-package", "application/x-deb", "application/octet-stream"
                });
                picker.putExtra(Intent.EXTRA_TITLE, "選擇 webOS .ipk 套件");
                try {
                    startActivityForResult(picker, REQUEST_TV_APP_PACKAGE);
                } catch (Exception error) {
                    pendingTvAppPackageCallbackId = "";
                    postControllerTvAppPackageResult(callbackId, false, "無法開啟檔案選擇器。", null);
                }
            });
        }

        @JavascriptInterface
        public void loadBundledTvAppPackage(String callbackId) {
            mainHandler.post(() -> {
                if (tvAppInstallerBusy) {
                    postControllerTvAppPackageResult(callbackId, false, "目前正在準備或安裝套件，請稍後再試。", null);
                    return;
                }
                pendingTvAppPackageCallbackId = callbackId == null ? "" : callbackId;
                tvAppInstallerBusy = true;
                postControllerTvAppPackageProgress(pendingTvAppPackageCallbackId, "正在載入 app 內附的 webOS 套件…");
                tvAppInstallerExecutor.execute(() -> prepareBundledTvAppPackage(pendingTvAppPackageCallbackId));
            });
        }

        @JavascriptInterface
        public void resetTvAppHostKey(String callbackId) {
            mainHandler.post(() -> {
                if (tvAppInstallerBusy) {
                    postControllerTvAppTrustReset(callbackId, false, "安裝進行中，暫時無法重設指紋。");
                    return;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("重設電視 SSH 指紋？")
                        .setMessage("這會刪除手機已信任的電視 SSH 指紋。下次安裝時會再次顯示指紋確認。只在更換電視或重設 Developer Mode 後使用。")
                        .setNegativeButton("取消", (dialog, which) ->
                                postControllerTvAppTrustReset(callbackId, false, "沒有變更電視 SSH 指紋。"))
                        .setPositiveButton("重設指紋", (dialog, which) -> {
                            File knownHosts = new File(getFilesDir(), "webos-tv-known-hosts");
                            boolean reset = !knownHosts.exists() || knownHosts.delete();
                            postControllerTvAppTrustReset(callbackId, reset,
                                    reset ? "已重設；下次連線會重新要求確認 SSH 指紋。" : "無法刪除已儲存的 SSH 指紋。");
                        })
                        .show();
            });
        }

        @JavascriptInterface
        public void installSelectedTvApp(String passphrase, String callbackId) {
            mainHandler.post(() -> {
                if (tvAppInstallerBusy) {
                    postControllerTvAppInstallStatus(callbackId, true, false, "已有安裝工作進行中。");
                    return;
                }
                String host = configuredTvHost();
                if (host.isEmpty()) {
                    postControllerTvAppInstallStatus(callbackId, true, false, "請先設定電視 IP，再安裝套件。");
                    return;
                }
                if (selectedTvAppPackageFile == null || selectedTvAppPackageInfo == null
                        || !selectedTvAppPackageFile.isFile()) {
                    postControllerTvAppInstallStatus(callbackId, true, false, "請先選擇有效的 .ipk 套件。");
                    return;
                }
                String enteredPassphrase = passphrase == null ? "" : passphrase.trim();
                if (enteredPassphrase.isEmpty()) {
                    postControllerTvAppInstallStatus(callbackId, true, false, "請輸入電視 Developer Mode 顯示的 passphrase。");
                    return;
                }
                final File packageFile = selectedTvAppPackageFile;
                final TvAppInstaller.PackageInfo packageInfo = selectedTvAppPackageInfo;
                final String installHost = host;
                final String installPassphrase = enteredPassphrase;
                String version = packageInfo.version.isEmpty() ? "" : "\n版本：" + packageInfo.version;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("確認安裝電視 App")
                        .setMessage("名稱：" + packageInfo.title + "\nApp ID：" + packageInfo.id + version
                                + "\n\n目標電視：" + host + "\n\n確認後會安裝套件，不會自動開啟或切換電視畫面。")
                        .setNegativeButton("取消", (dialog, which) ->
                                postControllerTvAppInstallStatus(callbackId, true, false, "已取消安裝，電視沒有變更。"))
                        .setPositiveButton("確認安裝", (dialog, which) -> {
                            tvAppInstallerBusy = true;
                            tvAppInstallerExecutor.execute(() -> {
                                try {
                                    TvAppInstaller.install(MainActivity.this, installHost, installPassphrase,
                                            packageFile, packageInfo,
                                            message -> postControllerTvAppInstallStatus(callbackId, false, false, message));
                                    String message = "安裝完成：" + packageInfo.title + " · " + packageInfo.id
                                            + (packageInfo.version.isEmpty() ? "" : " · v" + packageInfo.version)
                                            + "。電視畫面沒有切換。";
                                    postControllerTvAppInstallStatus(callbackId, true, true, message);
                                } catch (Exception error) {
                                    String message = TextUtils.isEmpty(error.getMessage())
                                            ? "安裝失敗，請確認 Developer Mode 與電視連線。" : error.getMessage();
                                    postControllerTvAppInstallStatus(callbackId, true, false, message);
                                } finally {
                                    tvAppInstallerBusy = false;
                                }
                            });
                        })
                        .show();
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
                if (!normalized.equalsIgnoreCase(oldHost)) {
                    editor.remove("tv_client_key").remove("tv_audio_client_key")
                            .remove("tv_power_client_key").remove("tv_mac");
                }
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
        public void getTvPowerState(String callbackId) {
            mainHandler.post(() -> {
                String host = configuredTvHost();
                if (host.isEmpty()) {
                    postControllerTvPowerState(callbackId, false, false);
                    return;
                }
                if (tvPowerTogglePending.get()) {
                    postControllerTvPowerState(callbackId, false, false);
                    return;
                }
                if (!tvPowerStateCheckPending.compareAndSet(false, true)) {
                    postControllerTvPowerState(callbackId, false, false);
                    return;
                }
                String clientKey = tvPreferences().getString("tv_power_client_key", "");
                if (clientKey.isEmpty()) {
                    network.execute(() -> {
                        boolean reachable = isTvSsapReachable(host);
                        tvPowerStateCheckPending.set(false);
                        postControllerTvPowerState(callbackId, true, reachable);
                    });
                    return;
                }
                startTvPowerClient(host, clientKey, false, callbackId);
            });
        }

        @JavascriptInterface
        public void toggleTvPower(String callbackId) {
            mainHandler.post(() -> {
                String host = configuredTvHost();
                if (host.isEmpty()) {
                    postControllerTvPowerToggleResult(callbackId, false,
                            "請先掃描電視 QR code 或設定電視 IP。", false, false);
                    return;
                }
                if (!tvPowerTogglePending.compareAndSet(false, true)) {
                    postControllerTvPowerToggleResult(callbackId, false,
                            "電視電源操作仍在進行，請稍候。", false, false);
                    return;
                }
                network.execute(() -> {
                    if (!isTvSsapReachable(host)) {
                        sendWakeOnLan(tvPreferences().getString("tv_mac", ""), callbackId);
                        return;
                    }
                    String clientKey = tvPreferences().getString("tv_power_client_key", "");
                    startTvPowerClient(host, clientKey, true, callbackId);
                });
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
                postControllerPointerStatus(true, "遙控器已連線。可在方向鍵與觸控板分頁切換；觸控板右側上下捲動、底部左右捲動。");
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

        @JavascriptInterface
        public void changeTvVolume(String direction, String callbackId) {
            String normalized = direction == null ? "" : direction.trim().toLowerCase(Locale.ROOT);
            if (!"up".equals(normalized) && !"down".equals(normalized)) {
                postControllerVolumeStatus(callbackId, false, "音量方向無效，請重試。");
                return;
            }
            String host = configuredTvHost();
            if (host.isEmpty()) {
                postControllerVolumeStatus(callbackId, false, "請先掃描電視 QR code 或設定電視 IP。");
                return;
            }
            if (!tvVolumeRequestPending.compareAndSet(false, true)) {
                postControllerVolumeStatus(callbackId, false, "上一個音量指令仍在處理，請稍候再按。");
                return;
            }

            String clientKey = tvPreferences().getString("tv_audio_client_key", "");
            final TvAudioClient[] holder = new TvAudioClient[1];
            try {
                TvAudioClient next = new TvAudioClient(host, clientKey, "up".equals(normalized),
                        (success, message, returnedKey) -> {
                            if (!TextUtils.isEmpty(returnedKey)) {
                                tvPreferences().edit().putString("tv_audio_client_key", returnedKey).apply();
                            }
                            if (tvAudioClient == holder[0]) tvAudioClient = null;
                            tvVolumeRequestPending.set(false);
                            postControllerVolumeStatus(callbackId, success, message);
                        });
                holder[0] = next;
                tvAudioClient = next;
                Thread volumeThread = new Thread(next::connect, "lg-tv-volume-connect");
                volumeThread.setDaemon(true);
                volumeThread.start();
            } catch (Exception error) {
                tvVolumeRequestPending.set(false);
                String detail = TextUtils.isEmpty(error.getMessage())
                        ? error.getClass().getSimpleName() : error.getMessage();
                postControllerVolumeStatus(callbackId, false, "無法準備電視音量連線（" + detail + "）。");
            }
        }

        @JavascriptInterface
        public void disconnectTvVolume() {
            stopTvAudioClient();
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

    private void stopTvAudioClient() {
        TvAudioClient activeAudio = tvAudioClient;
        tvAudioClient = null;
        if (activeAudio != null) activeAudio.close();
        tvVolumeRequestPending.set(false);
    }

    private void postControllerVolumeStatus(String callbackId, boolean success, String message) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeVolumeStatus && window.onNativeVolumeStatus("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + ","
                    + success + "," + JSONObject.quote(message == null ? "" : message) + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private void postControllerTvPowerState(String callbackId, boolean known, boolean isOn) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeTvPowerState && window.onNativeTvPowerState("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + ","
                    + known + "," + isOn + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private void postControllerTvPowerToggleResult(String callbackId, boolean success, String message,
                                                  boolean stateKnown, boolean isOn) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeTvPowerToggleResult && window.onNativeTvPowerToggleResult("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + ","
                    + success + "," + JSONObject.quote(message == null ? "" : message) + ","
                    + stateKnown + "," + isOn + ");";
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

    private void postControllerTvAppPackageResult(String callbackId, boolean success, String message,
                                                 TvAppInstaller.PackageInfo packageInfo) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String packageJson = "null";
            if (packageInfo != null) {
                try { packageJson = packageInfo.toJson().toString(); }
                catch (Exception ignored) {}
            }
            String script = "window.onNativeTvAppPackageResult && window.onNativeTvAppPackageResult("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + "," + success + ","
                    + JSONObject.quote(message == null ? "" : message) + "," + packageJson + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private void postControllerTvAppPackageProgress(String callbackId, String message) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeTvAppPackageProgress && window.onNativeTvAppPackageProgress("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + ","
                    + JSONObject.quote(message == null ? "" : message) + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private void postControllerTvAppTrustReset(String callbackId, boolean success, String message) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeTvAppTrustReset && window.onNativeTvAppTrustReset("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + "," + success + ","
                    + JSONObject.quote(message == null ? "" : message) + ");";
            controllerWebView.evaluateJavascript(script, null);
        });
    }

    private void postControllerTvAppInstallStatus(String callbackId, boolean finished, boolean success,
                                                   String message) {
        mainHandler.post(() -> {
            if (controllerWebView == null) return;
            String script = "window.onNativeTvAppInstallStatus && window.onNativeTvAppInstallStatus("
                    + JSONObject.quote(callbackId == null ? "" : callbackId) + "," + finished + ","
                    + success + "," + JSONObject.quote(message == null ? "" : message) + ");";
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

    private boolean isTvSsapReachable(String host) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 3000), 1200);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startTvPowerClient(String host, String clientKey, boolean toggle, String callbackId) {
        final TvPowerClient[] holder = new TvPowerClient[1];
        try {
            TvPowerClient next = new TvPowerClient(host, clientKey, toggle,
                    (success, message, returnedKey, stateKnown, isOn, shouldWake) -> {
                        if (!TextUtils.isEmpty(returnedKey)) {
                            tvPreferences().edit().putString("tv_power_client_key", returnedKey).apply();
                        }
                        if (toggle && tvPowerToggleClient == holder[0]) tvPowerToggleClient = null;
                        if (!toggle && tvPowerStateClient == holder[0]) tvPowerStateClient = null;
                        if (toggle && shouldWake) {
                            sendWakeOnLan(tvPreferences().getString("tv_mac", ""), callbackId);
                            return;
                        }
                        if (toggle) {
                            tvPowerTogglePending.set(false);
                            postControllerTvPowerToggleResult(callbackId, success, message, stateKnown, isOn);
                            return;
                        }
                        tvPowerStateCheckPending.set(false);
                        if (tvPowerTogglePending.get()) {
                            postControllerTvPowerState(callbackId, false, false);
                            return;
                        }
                        if (success && stateKnown) {
                            postControllerTvPowerState(callbackId, true, isOn);
                        } else {
                            network.execute(() -> postControllerTvPowerState(callbackId, true,
                                    isTvSsapReachable(host)));
                        }
                    });
            holder[0] = next;
            if (toggle) tvPowerToggleClient = next;
            else tvPowerStateClient = next;
            Thread powerThread = new Thread(next::connect, "lg-tv-power-control");
            powerThread.setDaemon(true);
            powerThread.start();
        } catch (Exception error) {
            if (toggle && tvPowerToggleClient == holder[0]) tvPowerToggleClient = null;
            if (!toggle && tvPowerStateClient == holder[0]) tvPowerStateClient = null;
            if (toggle) {
                tvPowerTogglePending.set(false);
                String detail = TextUtils.isEmpty(error.getMessage())
                        ? error.getClass().getSimpleName() : error.getMessage();
                postControllerTvPowerToggleResult(callbackId, false,
                        "無法準備電視電源控制（" + detail + "）。", false, false);
            } else {
                tvPowerStateCheckPending.set(false);
                network.execute(() -> postControllerTvPowerState(callbackId, true,
                        isTvSsapReachable(host)));
            }
        }
    }

    private void sendWakeOnLan(String rawMac, String callbackId) {
        String mac = normalizeMac(rawMac);
        if (mac.isEmpty()) {
            tvPowerTogglePending.set(false);
            postControllerTvPowerToggleResult(callbackId, false,
                    "未設定電視 MAC，無法送出喚醒訊號。請掃描電視 QR 或填入 MAC。", false, false);
            return;
        }
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
                    tvPowerTogglePending.set(false);
                    String message = "已送出 Wi-Fi 喚醒封包；請確認電視已開啟 Mobile TV On。";
                    setControllerStatus(message);
                    Toast.makeText(this, "已送出電視喚醒訊號", Toast.LENGTH_SHORT).show();
                    postControllerTvPowerToggleResult(callbackId, true, message, true, false);
                });
            } catch (Exception error) {
                String message = TextUtils.isEmpty(error.getMessage())
                        ? "Wi-Fi 喚醒訊號送出失敗。" : error.getMessage();
                mainHandler.post(() -> {
                    tvPowerTogglePending.set(false);
                    setControllerStatus(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    postControllerTvPowerToggleResult(callbackId, false, message, false, false);
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
        if (requestCode == REQUEST_TV_APP_PACKAGE) {
            handleSelectedTvAppPackage(resultCode, data);
            return;
        }
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

    private void handleSelectedTvAppPackage(int resultCode, Intent data) {
        String callbackId = pendingTvAppPackageCallbackId;
        pendingTvAppPackageCallbackId = "";
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            postControllerTvAppPackageResult(callbackId, false, "沒有選擇套件。", null);
            return;
        }
        Uri packageUri = data.getData();
        tvAppInstallerBusy = true;
        postControllerTvAppPackageProgress(callbackId, "正在讀取並檢查 .ipk 套件…");
        tvAppInstallerExecutor.execute(() -> {
            File tempFile = null;
            try {
                File folder = new File(getCacheDir(), "tv-app-installer");
                if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("無法建立套件暫存資料夾。");
                tempFile = new File(folder, "selected-app.tmp");
                File readyFile = new File(folder, "selected-app.ipk");
                long total = 0;
                try (InputStream input = getContentResolver().openInputStream(packageUri);
                     FileOutputStream output = new FileOutputStream(tempFile, false)) {
                    if (input == null) throw new java.io.IOException("無法讀取選取的套件檔案。");
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 512L * 1024L * 1024L) throw new java.io.IOException("套件超過 512 MB 上限。");
                        output.write(buffer, 0, count);
                    }
                }
                TvAppInstaller.PackageInfo packageInfo = TvAppInstaller.inspect(tempFile);
                if (readyFile.exists() && !readyFile.delete()) throw new java.io.IOException("無法替換先前選取的套件。");
                if (!tempFile.renameTo(readyFile)) throw new java.io.IOException("無法保存已檢查的套件。");
                selectedTvAppPackageFile = readyFile;
                selectedTvAppPackageInfo = packageInfo;
                tvAppInstallerBusy = false;
                postControllerTvAppPackageResult(callbackId, true, "套件資訊已讀取。", packageInfo);
            } catch (Exception error) {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                tvAppInstallerBusy = false;
                String message = TextUtils.isEmpty(error.getMessage())
                        ? "無法讀取這個 .ipk 套件。" : error.getMessage();
                postControllerTvAppPackageResult(callbackId, false, message, null);
            }
        });
    }

    private void prepareBundledTvAppPackage(String callbackId) {
        File tempFile = null;
        try {
            File folder = new File(getCacheDir(), "tv-app-installer");
            if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("無法建立套件暫存資料夾。");
            tempFile = new File(folder, "bundled-app.tmp");
            File readyFile = new File(folder, "bundled-app.ipk");
            long total = 0;
            try (InputStream input = getAssets().open("tv-app.ipk");
                 FileOutputStream output = new FileOutputStream(tempFile, false)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 512L * 1024L * 1024L) throw new java.io.IOException("內附套件超過 512 MB 上限。");
                    output.write(buffer, 0, count);
                }
            }
            TvAppInstaller.PackageInfo packageInfo = TvAppInstaller.inspect(tempFile);
            if (readyFile.exists() && !readyFile.delete()) throw new java.io.IOException("無法替換內附套件暫存檔。");
            if (!tempFile.renameTo(readyFile)) throw new java.io.IOException("無法保存內附套件。");
            selectedTvAppPackageFile = readyFile;
            selectedTvAppPackageInfo = packageInfo;
            tvAppInstallerBusy = false;
            postControllerTvAppPackageResult(callbackId, true, "已載入 app 內附的電視 App。", packageInfo);
        } catch (Exception error) {
            if (tempFile != null && tempFile.exists()) tempFile.delete();
            tvAppInstallerBusy = false;
            String message = TextUtils.isEmpty(error.getMessage())
                    ? "無法讀取 app 內附的電視 App。" : error.getMessage();
            postControllerTvAppPackageResult(callbackId, false, message, null);
        }
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
        if (!tvHost.equalsIgnoreCase(oldHost)) {
                    editor.remove("tv_client_key").remove("tv_audio_client_key")
                            .remove("tv_power_client_key").remove("tv_mac");
        }
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
                if (gimyaiProxyReady) {
                    params.put("gimyaiProxyUrl", "http://" + addresses.get(0) + ":" + gimyaiProxyPort + "/");
                }
                if (yahooProxyReady) {
                    params.put("yahooProxyUrl", "http://" + addresses.get(0) + ":" + yahooProxyPort + "/");
                }
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
        clearedMediaUrls.clear();
        clearedCandidateHeaders.clear();
        clearedSelectedMediaUrl = "";
        selectedMediaUrl = "";
        candidateSheetExpanded = false;
        candidateMetadataViews.clear();
        candidateRows.removeAllViews();
        pageStatus.setText("正在開啟網頁…");
        playbackInfo.setText("在網頁播放器按播放，自動偵測影片來源");
        pageLoaded = false;
        mainHandler.removeCallbacks(playbackInfoPoller);
        updateCandidateSheet();
        showBrowserScreen();
        webView.loadUrl(url);
    }

    private void clearMediaCandidates() {
        synchronized (mediaUrls) {
            if (mediaUrls.isEmpty()) return;
            clearedMediaUrls.clear();
            clearedMediaUrls.addAll(mediaUrls);
            candidateGeneration++;
            mediaUrls.clear();
        }
        synchronized (candidateHeaders) {
            clearedCandidateHeaders.clear();
            for (Map.Entry<String, Map<String, String>> entry : candidateHeaders.entrySet()) {
                clearedCandidateHeaders.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
            candidateHeaders.clear();
        }
        clearedSelectedMediaUrl = selectedMediaUrl;
        selectedMediaUrl = "";
        candidateMetadataViews.clear();
        candidateRows.removeAllViews();
        if (pageStatus != null) pageStatus.setText("來源清單已清空，可按「復原」還原");
        updateCandidateSheet();
    }

    private void undoClearMediaCandidates() {
        if (clearedMediaUrls.isEmpty()) return;
        List<String> currentUrls;
        synchronized (mediaUrls) {
            currentUrls = new ArrayList<>(mediaUrls);
            LinkedHashSet<String> restored = new LinkedHashSet<>(clearedMediaUrls);
            restored.addAll(currentUrls);
            mediaUrls.clear();
            mediaUrls.addAll(restored);
            candidateGeneration++;
        }
        synchronized (candidateHeaders) {
            Map<String, Map<String, String>> currentHeaders = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> entry : candidateHeaders.entrySet()) {
                currentHeaders.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
            candidateHeaders.clear();
            for (Map.Entry<String, Map<String, String>> entry : clearedCandidateHeaders.entrySet()) {
                candidateHeaders.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
            candidateHeaders.putAll(currentHeaders);
        }
        boolean previousSelectionRestored;
        synchronized (mediaUrls) {
            previousSelectionRestored = !TextUtils.isEmpty(clearedSelectedMediaUrl)
                    && mediaUrls.contains(clearedSelectedMediaUrl);
        }
        selectedMediaUrl = previousSelectionRestored ? clearedSelectedMediaUrl
                : (currentUrls.isEmpty() ? "" : currentUrls.get(currentUrls.size() - 1));
        clearedMediaUrls.clear();
        clearedCandidateHeaders.clear();
        clearedSelectedMediaUrl = "";
        renderCandidateCards();
        if (pageStatus != null) pageStatus.setText("已復原影片來源清單");
    }

    private void renderCandidateCards() {
        candidateMetadataViews.clear();
        candidateRows.removeAllViews();
        List<String> ordered;
        synchronized (mediaUrls) { ordered = new ArrayList<>(mediaUrls); }
        for (int index = ordered.size() - 1; index >= 0; index--) {
            String value = ordered.get(index);
            candidateRows.addView(createCandidateCard(value));
        }
        updateCandidateSheet();
    }

    private View createCandidateCard(String value) {
        boolean selected = value.equals(selectedMediaUrl);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(9));
        card.setBackground(makeRoundedDrawable(selected ? Color.rgb(35, 58, 63) : Color.rgb(21, 31, 41),
                dp(14), selected ? ACCENT : 0));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> selectCandidate(value));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView summary = new TextView(this);
        summary.setText(candidateSummary(value));
        summary.setTextColor(TEXT);
        summary.setTextSize(14);
        summary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summary.setSingleLine(true);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView selectionBadge = new TextView(this);
        selectionBadge.setText(selected ? "已選取" : "選擇來源");
        selectionBadge.setTextColor(selected ? ACCENT : MUTED);
        selectionBadge.setTextSize(11);
        titleRow.addView(selectionBadge, wrapParams());
        card.addView(titleRow, matchWrap());

        TextView source = new TextView(this);
        source.setText(candidateSource(value));
        source.setTextColor(MUTED);
        source.setTextSize(12);
        source.setSingleLine(true);
        source.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceParams.topMargin = dp(3);
        card.addView(source, sourceParams);

        TextView metadata = new TextView(this);
        metadata.setText("片長未知 · 尺寸未知 · 點預覽讀取");
        metadata.setTextColor(MUTED);
        metadata.setTextSize(11);
        metadata.setSingleLine(true);
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metadataParams.topMargin = dp(2);
        card.addView(metadata, metadataParams);
        candidateMetadataViews.put(value, metadata);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView preview = sheetAction("預覽", () -> previewCandidate(value));
        preview.setBackground(makeRoundedDrawable(Color.rgb(42, 59, 70), dp(11), 0));
        LinearLayout.LayoutParams previewParams = wrapParams();
        previewParams.topMargin = dp(7);
        actions.addView(preview, previewParams);
        card.addView(actions, matchWrap());

        card.setTag(value);
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.bottomMargin = dp(7);
        card.setLayoutParams(cardParams);
        return card;
    }

    private void selectCandidate(String value) {
        selectedMediaUrl = value;
        renderCandidateCards();
    }

    private void addCandidateCard(String value) {
        candidateRows.addView(createCandidateCard(value), 0);
        updateCandidateSheet();
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
            if (TextUtils.isEmpty(selectedMediaUrl)) selectedMediaUrl = value;
            addCandidateCard(value);
            String currentUrl = webView == null ? "" : webView.getUrl();
            pageStatus.setText("已開啟 " + readableHost(currentUrl) + " · 找到 " + mediaCandidateCount() + " 個來源");
        });
    }

    private int mediaCandidateCount() {
        synchronized (mediaUrls) { return mediaUrls.size(); }
    }

    private boolean containsHeader(Map<String, String> headers, String key) {
        for (String name : headers.keySet()) if (name.equalsIgnoreCase(key)) return true;
        return false;
    }

    private void previewCandidate(String mediaUrl) {
        selectedMediaUrl = mediaUrl;
        renderCandidateCards();
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
        return format + " · 畫質未標示";
    }

    private String candidateSource(String value) {
        Uri uri = Uri.parse(value);
        String host = uri.getHost() == null ? "未知網站" : uri.getHost();
        return "來源網站 · " + host;
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
        if (TextUtils.isEmpty(mediaUrl)) {
            pageStatus.setText("請先選擇影片來源");
            return;
        }
        pageStatus.setText("正在傳送影片來源到電視…");
        JSONObject payload = new JSONObject();
        try { payload.put("mediaUrl", mediaUrl); }
        catch (Exception error) {
            pageStatus.setText("無法準備影片播放資料");
            return;
        }
        launchToTv(payload, (success, message) -> pageStatus.setText(success
                ? "已傳送給電視 · " + message : "投放失敗 · " + message));
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
            if (candidateSheetExpanded) {
                setCandidateSheetExpanded(false);
                return;
            }
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
        stopTvAudioClient();
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
        if (gimyaiCompatProxyServer != null) gimyaiCompatProxyServer.stop();
        if (yahooCompatProxyServer != null) yahooCompatProxyServer.stop();
        stopService(new Intent(this, CompanionKeepAliveService.class));
        TvPointerClient activePointer = pointerClient;
        pointerClient = null;
        if (activePointer != null) activePointer.close();
        TvPowerClient activePowerStateClient = tvPowerStateClient;
        tvPowerStateClient = null;
        if (activePowerStateClient != null) activePowerStateClient.close();
        TvPowerClient activePowerToggleClient = tvPowerToggleClient;
        tvPowerToggleClient = null;
        if (activePowerToggleClient != null) activePowerToggleClient.close();
        stopTvAudioClient();
        network.shutdownNow();
        tvAppInstallerExecutor.shutdownNow();
        super.onDestroy();
    }
}
