package com.lgvideosender;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Opens the webOS pointer-input socket and forwards relative pointer gestures. */
final class TvPointerClient {
    private static final String TAG = "LGPointer";
    private static final String IME_REQUEST_PREFIX = "phone_ime_";

    interface Callback {
        void onStatus(boolean connected, String message, String clientKey);
    }

    interface ImeCallback {
        void onResult(boolean success, String message);
    }

    private static final class PendingImeRequest {
        final ImeCallback callback;
        final String uri;
        final JSONObject payload;
        final String successMessage;
        volatile boolean dispatched;
        Runnable timeout;

        PendingImeRequest(String uri, JSONObject payload, ImeCallback callback, String successMessage) {
            this.callback = callback;
            this.uri = uri;
            this.payload = payload;
            this.successMessage = successMessage;
        }
    }

    private static final String POINTER_REQUEST_ID = "phone_pointer_socket";

    private final String host;
    private final String existingClientKey;
    private final Callback callback;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicBoolean imeChannelConnecting = new AtomicBoolean(false);
    private final AtomicInteger imeRequestCounter = new AtomicInteger();
    private final ConcurrentHashMap<String, PendingImeRequest> pendingImeRequests = new ConcurrentHashMap<>();
    private volatile boolean pairingPromptShown;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = () -> fail(pairingPromptShown
            ? "等待電視授權逾時。請按「連線觸控板」再試，並在電視上按允許。"
            : "電視沒有回應觸控板連線。請確認電視已開機後重試。");
    private volatile WebSocketClient ssapSocket;
    private volatile WebSocketClient pointerSocket;
    private volatile String clientKey;
    private volatile boolean connected;
    private volatile boolean ssapRegistered;

    TvPointerClient(String host, String existingClientKey, Callback callback) {
        this.host = host == null ? "" : host.trim();
        this.existingClientKey = existingClientKey == null ? "" : existingClientKey;
        this.clientKey = this.existingClientKey;
        this.callback = callback;
    }

    void connect() {
        try {
            if (host.isEmpty()) throw new IllegalArgumentException("請先設定電視 IP。");
            WebSocketClient client = new WebSocketClient(new URI("ws://" + host + ":3000")) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    sendStatus(false, "已連上電視，正在準備觸控板…", null);
                    try {
                        sendPacket(new JSONObject().put("type", "hello").put("id", "hello").toString());
                    } catch (Exception error) {
                        fail("無法啟動電視控制協定。");
                    }
                }

                @Override
                public void onMessage(String message) {
                    handleSsapMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    WebSocketClient input = pointerSocket;
                    Log.d(TAG, "SSAP socket closed code=" + code + " remote=" + remote
                            + " pointerSocketPrepared=" + (input != null) + " pointerConnected=" + connected);
                    if (ssapSocket == this) {
                        ssapSocket = null;
                        ssapRegistered = false;
                        imeChannelConnecting.set(false);
                    }
                    if (!closed.get() && input != null) {
                        failPendingImeRequests("電視文字輸入通道已中斷，請重新連線觸控板。");
                    }
                    if (!closed.get() && !failed.get() && input == null) {
                        String detail = reason == null || reason.trim().isEmpty() ? "" : "（" + reason + "）";
                        fail("電視控制連線中斷（代碼 " + code + "）" + detail + "。請重新連線。");
                    }
                    // webOS uses this SSAP socket only to obtain the pointer socket URL.
                    // Pointer input has its own WebSocket and can outlive the SSAP socket.
                }

                @Override
                public void onError(Exception error) {
                    if (ssapSocket == this) {
                        ssapSocket = null;
                        ssapRegistered = false;
                        imeChannelConnecting.set(false);
                    }
                    if (!closed.get()) {
                        if (pointerSocket != null) {
                            Log.w(TAG, "SSAP socket error after pointer socket was prepared", error);
                            failPendingImeRequests("電視文字輸入通道已中斷，請重新連線觸控板。");
                            return;
                        }
                        Log.e(TAG, "SSAP socket error before pointer socket was prepared", error);
                        String detail = error == null || error.getMessage() == null ? "" : "（" + error.getMessage() + "）";
                        fail("連不到電視觸控板" + detail + "。確認電視已開機且與手機在同一個 Wi-Fi。");
                    }
                }

                private void sendPacket(String value) {
                    if (isOpen()) send(value);
                }
            };
            client.setConnectionLostTimeout(20);
            ssapSocket = client;
            timeoutHandler.postDelayed(timeoutRunnable, 30000);
            client.connect();
        } catch (Exception error) {
            sendStatus(false, error.getMessage() == null ? "無法連線電視觸控板。" : error.getMessage(), null);
        }
    }

    boolean isConnected() {
        WebSocketClient socket = pointerSocket;
        return connected && socket != null && socket.isOpen();
    }

    void move(double dx, double dy) {
        if (!isConnected()) return;
        int x = boundedDelta(dx);
        int y = boundedDelta(dy);
        if (x == 0 && y == 0) return;
        sendPointerFrame("type:move\ndx:" + x + "\ndy:" + y + "\ndown:0\n\n");
    }

    void scroll(double dx, double dy) {
        if (!isConnected()) return;
        int x = boundedDelta(dx);
        int y = boundedDelta(dy);
        if (x == 0 && y == 0) return;
        sendPointerFrame("type:scroll\ndx:" + x + "\ndy:" + y + "\n\n");
    }

    void click() {
        sendPointerFrame("type:click\n\n");
    }

    void button(String name) {
        if (name == null || !name.matches("[A-Z0-9_]{1,24}")) return;
        sendPointerFrame("type:button\nname:" + name + "\n\n");
    }

    void insertText(String text, ImeCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            notifyIme(callback, false, "請先輸入文字。");
            return;
        }
        if (text.length() > 1000) {
            notifyIme(callback, false, "一次最多傳送 1000 個字元。");
            return;
        }
        try {
            JSONObject payload = new JSONObject().put("text", text).put("replace", false);
            sendImeRequest("ssap://com.webos.service.ime/insertText", payload, "文字已傳送到電視。", callback);
        } catch (Exception error) {
            notifyIme(callback, false, "無法準備電視文字輸入。");
        }
    }

    void sendEnter(ImeCallback callback) {
        try {
            sendImeRequest("ssap://com.webos.service.ime/sendEnterKey", new JSONObject(), "已送出 Enter。", callback);
        } catch (Exception error) {
            notifyIme(callback, false, "無法準備 Enter 指令。");
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        imeChannelConnecting.set(false);
        connected = false;
        WebSocketClient pointer = pointerSocket;
        WebSocketClient ssap = ssapSocket;
        pointerSocket = null;
        ssapSocket = null;
        failPendingImeRequests("電視控制連線已關閉。");
        if (pointer != null) {
            try { pointer.close(); } catch (RuntimeException ignored) {}
        }
        if (ssap != null) {
            try { ssap.close(); } catch (RuntimeException ignored) {}
        }
    }

    private void handleSsapMessage(String message) {
        JSONObject packet;
        try { packet = new JSONObject(message); }
        catch (Exception ignored) { return; }

        String type = packet.optString("type", "");
        String id = packet.optString("id", "");
        Log.d(TAG, "SSAP message type=" + type + " id=" + id);
        if ("hello".equals(type) || ("response".equals(type) && "hello".equals(id))) {
            register();
            return;
        }
        if ("registered".equals(type)) {
            ssapRegistered = true;
            imeChannelConnecting.set(false);
            JSONObject payload = packet.optJSONObject("payload");
            if (payload != null) {
                String returnedKey = payload.optString("client-key", "");
                if (!returnedKey.isEmpty()) clientKey = returnedKey;
            }
            if (isConnected()) {
                sendStatus(true, "觸控板已連線。單指滑動移動游標，點一下確認，雙指滑動捲動。", clientKey);
                dispatchPendingImeRequests();
            } else {
                requestPointerSocket();
            }
            return;
        }
        if ("response".equals(type) && "register_pointer".equals(id)) {
            JSONObject payload = packet.optJSONObject("payload");
            if (payload != null) {
                String pairingType = payload.optString("pairingType", "");
                Log.d(TAG, "Registration response returnValue=" + payload.optBoolean("returnValue", false)
                        + " subscribed=" + payload.optBoolean("subscribed", false)
                        + " pairingType=" + pairingType);
                String returnedKey = payload.optString("client-key", "");
                if (!returnedKey.isEmpty()) clientKey = returnedKey;
                if ("PROMPT".equalsIgnoreCase(pairingType)) {
                    pairingPromptShown = true;
                    sendStatus(isConnected(), "請在電視允許手機使用滑鼠、鍵盤和文字輸入控制。", clientKey);
                    return;
                }
                if (payload.optBoolean("returnValue", false) && payload.optBoolean("subscribed", false)) {
                    ssapRegistered = true;
                    if (isConnected()) {
                        imeChannelConnecting.set(false);
                        dispatchPendingImeRequests();
                    } else {
                        requestPointerSocket();
                    }
                    return;
                }
                if (isConnected()) {
                    imeChannelConnecting.set(false);
                    String detail = payload.optString("errorText", payload.optString("error", ""));
                    failPendingImeRequests("電視未授權文字輸入" + (detail.isEmpty() ? "，請確認電視上的授權視窗。" : "：" + detail));
                    return;
                }
            }
            requestPointerSocket();
            return;
        }
        if ("response".equals(type) && POINTER_REQUEST_ID.equals(id)) {
            JSONObject payload = packet.optJSONObject("payload");
            Log.d(TAG, "Pointer socket response returnValue=" + (payload != null && payload.optBoolean("returnValue", false))
                    + " hasSocketPath=" + (payload != null && !payload.optString("socketPath", "").isEmpty()));
            if (payload == null || !payload.optBoolean("returnValue", false)) {
                String detail = payload == null ? "" : payload.optString("errorText", payload.optString("error", ""));
                String lowered = detail.toLowerCase(Locale.ROOT);
                String reason = lowered.contains("permission") || lowered.contains("401")
                        ? "電視尚未授權滑鼠、鍵盤和文字輸入控制。請確認電視上的授權視窗；若沒有出現，重新配對後再試。"
                        : "電視沒有提供觸控板連線" + (detail.isEmpty() ? "。" : "：" + detail);
                fail(reason);
                return;
            }
            openPointerSocket(payload.optString("socketPath", ""));
            return;
        }
        if ("response".equals(type) || "error".equals(type)) {
            PendingImeRequest pending = pendingImeRequests.remove(id);
            if (pending != null) {
                if (pending.timeout != null) timeoutHandler.removeCallbacks(pending.timeout);
                JSONObject payload = packet.optJSONObject("payload");
                boolean success = "response".equals(type) && payload != null && payload.optBoolean("returnValue", false);
                String detail = packet.optString("error", "");
                if (payload != null && detail.isEmpty()) {
                    detail = payload.optString("errorText", payload.optString("error", ""));
                }
                String resultMessage = success ? pending.successMessage
                        : "電視未接受文字輸入" + (detail.isEmpty() ? "。請確認電視上有已選取的文字欄位。" : "：" + detail);
                notifyIme(pending.callback, success, resultMessage);
                return;
            }
        }
        if ("error".equals(type)) {
            JSONObject payload = packet.optJSONObject("payload");
            String detail = packet.optString("error", "");
            if (payload != null && detail.isEmpty()) detail = payload.optString("errorText", payload.optString("error", ""));
            String lowered = detail.toLowerCase(Locale.ROOT);
            if (isConnected()) {
                imeChannelConnecting.set(false);
                failPendingImeRequests("電視拒絕文字輸入" + (detail.isEmpty() ? "。請確認文字輸入授權。" : "：" + detail));
                return;
            }
            if (pairingPromptShown || lowered.contains("permission") || lowered.contains("401")) {
                fail("電視尚未授權滑鼠、鍵盤和文字輸入控制。請在電視允許授權；若沒出現授權視窗，重新配對後再試。");
            } else {
                fail("電視拒絕觸控板連線" + (detail.isEmpty() ? "。" : "：" + detail));
            }
        }
    }

    private void register() {
        try {
            JSONArray permissions = new JSONArray()
                    .put("CONTROL_MOUSE_AND_KEYBOARD")
                    .put("CONTROL_INPUT_TEXT");
            JSONObject manifest = new JSONObject()
                    .put("manifestVersion", 1)
                    .put("appVersion", "1.0")
                    .put("permissions", permissions);
            JSONObject payload = new JSONObject()
                    .put("pairingType", "PROMPT")
                    .put("forcePairing", false)
                    .put("manifest", manifest);
            if (!existingClientKey.isEmpty()) payload.put("client-key", existingClientKey);
            sendSsap(new JSONObject().put("type", "register").put("id", "register_pointer")
                    .put("payload", payload).toString());
            if (!isConnected()) sendStatus(false, "正在向電視請求滑鼠和文字輸入權限…", null);
        } catch (Exception error) {
            fail("無法準備觸控板配對資料。");
        }
    }

    private void requestPointerSocket() {
        try {
            sendStatus(false, "正在開啟電視游標連線…", clientKey);
            sendSsap(new JSONObject().put("type", "request")
                    .put("id", POINTER_REQUEST_ID)
                    .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
                    .put("payload", new JSONObject()).toString());
        } catch (Exception error) {
            fail("無法向電視要求游標連線。");
        }
    }

    private void openPointerSocket(String socketPath) {
        try {
            URI uri = new URI(socketPath);
            String scheme = uri.getScheme();
            String path = uri.getPath();
            boolean webOs4PointerSocket = "ws".equalsIgnoreCase(scheme)
                    && uri.getPort() == 3000
                    && path != null
                    && path.matches("/resources/[A-Fa-f0-9]{40}/netinput\\.pointer\\.sock");
            boolean legacyPointerSocket = "wss".equalsIgnoreCase(scheme)
                    && uri.getPort() == 3001
                    && "/netinput.pointer.sock".equals(path);
            if (uri.getHost() == null || !host.equalsIgnoreCase(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(webOs4PointerSocket || legacyPointerSocket)) {
                fail("電視回傳的游標連線位址無效，請重新連線。");
                return;
            }
            WebSocketClient pointer = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected = true;
                    Log.d(TAG, "Pointer input socket opened");
                    timeoutHandler.removeCallbacks(timeoutRunnable);
                    sendStatus(true, "觸控板已連線。單指滑動移動游標，點一下確認，雙指滑動捲動。", clientKey);
                }

                @Override
                public void onMessage(String message) {}

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "Pointer input socket closed code=" + code + " remote=" + remote);
                    if (!closed.get() && !failed.get()) handleSocketClosed("電視游標連線已中斷。按「連線觸控板」重新連線。");
                }

                @Override
                public void onError(Exception error) {
                    if (!closed.get()) {
                        Log.e(TAG, "Pointer input socket error", error);
                        String detail = error == null || error.getMessage() == null ? "" : "（" + error.getMessage() + "）";
                        fail("電視游標連線失敗" + detail + "。請重新連線；若電視顯示授權視窗，請按允許。");
                    }
                }
            };
            if ("wss".equalsIgnoreCase(uri.getScheme())) pointer.setSocketFactory(trustWebOsSocketFactory());
            pointer.setConnectionLostTimeout(20);
            pointerSocket = pointer;
            pointer.connect();
        } catch (Exception error) {
            fail("無法開啟電視游標連線。");
        }
    }

    private static SSLSocketFactory trustWebOsSocketFactory() throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        return context.getSocketFactory();
    }

    private void sendSsap(String message) {
        WebSocketClient socket = ssapSocket;
        if (socket == null || !socket.isOpen()) {
            fail("與電視的控制連線已中斷，請重新連線。");
            return;
        }
        socket.send(message);
    }

    private void sendPointerFrame(String message) {
        WebSocketClient socket = pointerSocket;
        if (socket != null && socket.isOpen()) {
            try { socket.send(message); }
            catch (Exception ignored) {}
        }
    }

    private void sendImeRequest(String uri, JSONObject payload, String successMessage, ImeCallback callback) {
        if (!isConnected()) {
            notifyIme(callback, false, "請先連線觸控板，再輸入電視文字。");
            return;
        }
        String id = IME_REQUEST_PREFIX + imeRequestCounter.incrementAndGet();
        PendingImeRequest pending = new PendingImeRequest(uri, payload, callback, successMessage);
        pending.timeout = () -> {
            if (pendingImeRequests.remove(id, pending)) {
                notifyIme(callback, false, "電視文字輸入逾時；請確認文字欄位已取得焦點，並在電視允許文字輸入控制。");
                if (!ssapRegistered && imeChannelConnecting.compareAndSet(true, false)) {
                    WebSocketClient channel = ssapSocket;
                    if (channel != null && channel.isOpen()) channel.close();
                }
            }
        };
        pendingImeRequests.put(id, pending);
        timeoutHandler.postDelayed(pending.timeout, 30000);
        WebSocketClient socket = ssapSocket;
        if (socket != null && socket.isOpen() && ssapRegistered) {
            dispatchPendingImeRequests();
        } else {
            connectImeChannel();
        }
    }

    private void connectImeChannel() {
        if (closed.get() || failed.get()) {
            failPendingImeRequests("電視控制連線已關閉，請重新連線觸控板。");
            return;
        }
        if (!isConnected()) {
            failPendingImeRequests("電視游標連線已中斷，請重新連線觸控板。");
            return;
        }
        WebSocketClient existing = ssapSocket;
        if (existing != null && existing.isOpen() && ssapRegistered) {
            dispatchPendingImeRequests();
            return;
        }
        if (!imeChannelConnecting.compareAndSet(false, true)) return;
        ssapRegistered = false;
        try {
            if (existing != null && existing.isOpen()) existing.close();
            WebSocketClient client = new WebSocketClient(new URI("ws://" + host + ":3000")) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.d(TAG, "Reopened SSAP channel for TV text input");
                    try {
                        send(new JSONObject().put("type", "hello").put("id", "hello").toString());
                    } catch (Exception error) {
                        imeChannelConnecting.set(false);
                        failPendingImeRequests("無法重新開啟電視文字輸入通道。");
                    }
                }

                @Override
                public void onMessage(String message) {
                    handleSsapMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (ssapSocket == this) {
                        ssapSocket = null;
                        ssapRegistered = false;
                        imeChannelConnecting.set(false);
                    }
                    Log.d(TAG, "Reopened SSAP channel closed code=" + code + " remote=" + remote);
                    if (!closed.get() && !pendingImeRequests.isEmpty()) {
                        failPendingImeRequests("電視文字輸入通道已中斷，請重試。");
                    }
                }

                @Override
                public void onError(Exception error) {
                    if (ssapSocket == this) {
                        ssapSocket = null;
                        ssapRegistered = false;
                        imeChannelConnecting.set(false);
                    }
                    if (!closed.get()) {
                        Log.e(TAG, "Failed to reopen SSAP channel for TV text input", error);
                        failPendingImeRequests("無法重新連接電視文字輸入通道；請確認電視仍在線上。");
                    }
                }
            };
            client.setConnectionLostTimeout(20);
            ssapSocket = client;
            client.connect();
        } catch (Exception error) {
            imeChannelConnecting.set(false);
            failPendingImeRequests("無法重新開啟電視文字輸入通道。");
        }
    }

    private void dispatchPendingImeRequests() {
        WebSocketClient socket = ssapSocket;
        if (socket == null || !socket.isOpen()) return;
        for (String id : pendingImeRequests.keySet()) {
            PendingImeRequest pending = pendingImeRequests.get(id);
            if (pending == null || pending.dispatched) continue;
            pending.dispatched = true;
            try {
                socket.send(new JSONObject().put("type", "request").put("id", id)
                        .put("uri", pending.uri).put("payload", pending.payload).toString());
            } catch (Exception error) {
                pending.dispatched = false;
                if (pendingImeRequests.remove(id, pending)) {
                    if (pending.timeout != null) timeoutHandler.removeCallbacks(pending.timeout);
                    notifyIme(pending.callback, false, "無法傳送文字到電視。");
                }
            }
        }
    }

    private void failPendingImeRequests(String message) {
        for (String id : pendingImeRequests.keySet()) {
            PendingImeRequest pending = pendingImeRequests.remove(id);
            if (pending != null) {
                if (pending.timeout != null) timeoutHandler.removeCallbacks(pending.timeout);
                notifyIme(pending.callback, false, message);
            }
        }
    }

    private static void notifyIme(ImeCallback callback, boolean success, String message) {
        if (callback != null) callback.onResult(success, message);
    }

    private void handleSocketClosed(String message) {
        if (closed.get() || !failed.compareAndSet(false, true)) return;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        imeChannelConnecting.set(false);
        ssapRegistered = false;
        failPendingImeRequests("電視文字輸入連線已中斷，請重新連線。");
        connected = false;
        WebSocketClient pointer = pointerSocket;
        WebSocketClient ssap = ssapSocket;
        pointerSocket = null;
        ssapSocket = null;
        if (pointer != null && pointer.isOpen()) pointer.close();
        if (ssap != null && ssap.isOpen()) ssap.close();
        Log.w(TAG, message);
        sendStatus(false, message, clientKey);
    }

    private void fail(String message) {
        if (closed.get() || !failed.compareAndSet(false, true)) return;
        Log.e(TAG, "Pointer connection failed: " + message);
        timeoutHandler.removeCallbacks(timeoutRunnable);
        imeChannelConnecting.set(false);
        ssapRegistered = false;
        failPendingImeRequests("電視文字輸入連線已中斷，請重新連線。");
        connected = false;
        sendStatus(false, message, clientKey);
        WebSocketClient pointer = pointerSocket;
        WebSocketClient ssap = ssapSocket;
        pointerSocket = null;
        ssapSocket = null;
        if (pointer != null) pointer.close();
        if (ssap != null) ssap.close();
    }

    private void sendStatus(boolean success, String message, String key) {
        if (callback != null && !closed.get()) callback.onStatus(success, message, key);
    }

    private static int boundedDelta(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return (int) Math.round(Math.max(-1000, Math.min(1000, value)));
    }
}
