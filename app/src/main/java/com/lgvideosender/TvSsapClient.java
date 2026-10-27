package com.lgvideosender;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small SSAP client used only to pair with the configured TV and launch this TV app. */
final class TvSsapClient extends WebSocketClient {
    interface Callback {
        void onComplete(boolean success, String message, String clientKey);
    }

    private final JSONObject launchParams;
    private final String existingClientKey;
    private final Callback callback;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private boolean registrationSent;
    private boolean waitingForPairing;
    private boolean launchSent;
    private final String launchId = "phone_launch_" + System.currentTimeMillis();
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutRunnable = () -> {
        if (waitingForPairing) finish(false, "等待電視允許連線逾時。請在電視按允許後再試。", null);
        else finish(false, "等待電視啟動 App 逾時。請確認電視已開機並重試。", null);
        close();
    };

    TvSsapClient(String host, String existingClientKey, JSONObject launchParams, Callback callback) throws Exception {
        super(new URI("ws://" + host + ":3000"));
        this.existingClientKey = existingClientKey == null ? "" : existingClientKey;
        this.launchParams = launchParams;
        this.callback = callback;
        setConnectionLostTimeout(15);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        try {
            timeoutHandler.postDelayed(timeoutRunnable, 45000);
            send(new JSONObject().put("type", "hello").put("id", "hello").toString());
        } catch (Exception error) {
            finish(false, "已連上電視，但初始化控制協定失敗。", null);
            close();
        }
    }

    @Override
    public void onMessage(String message) {
        JSONObject packet;
        try { packet = new JSONObject(message); }
        catch (Exception ignored) { return; }

        String type = packet.optString("type", "");
        String id = packet.optString("id", "");
        if ("hello".equals(type) || ("response".equals(type) && "hello".equals(id))) {
            if (!registrationSent) sendRegistration();
            return;
        }
        if ("registered".equals(type)) {
            String key = packet.optJSONObject("payload") == null ? ""
                    : packet.optJSONObject("payload").optString("client-key", "");
            if (!key.isEmpty()) finishPairAndLaunch(key);
            else if (!existingClientKey.isEmpty()) sendLaunch(existingClientKey);
            return;
        }
        if ("response".equals(type) && "register_0".equals(id)) {
            JSONObject payload = packet.optJSONObject("payload");
            String pairingType = payload == null ? "" : payload.optString("pairingType", "");
            String key = payload == null ? "" : payload.optString("client-key", "");
            if ("PROMPT".equalsIgnoreCase(pairingType) && existingClientKey.isEmpty()) {
                waitingForPairing = true;
                return;
            }
            if (!key.isEmpty()) finishPairAndLaunch(key);
            else if (!existingClientKey.isEmpty()) sendLaunch(existingClientKey);
            else {
                waitingForPairing = true;
            }
            return;
        }
        if ("error".equals(type)) {
            String detail = packet.optString("error", "");
            String messageText = waitingForPairing
                    ? "電視拒絕配對。請在電視允許手機控制後重試。"
                    : "電視沒有接受啟動指令" + (detail.isEmpty() ? "。" : "：" + detail);
            finish(false, messageText, null);
            close();
            return;
        }
        if ("response".equals(type) && launchId.equals(id)) {
            JSONObject payload = packet.optJSONObject("payload");
            boolean accepted = payload != null && payload.optBoolean("returnValue", true);
            if (accepted) finish(true, "已在電視開啟網頁助理。", currentClientKey());
            else {
                String detail = payload == null ? "" : payload.optString("errorText", "");
                finish(false, detail.isEmpty() ? "電視沒有接受啟動指令。" : "電視啟動失敗：" + detail, currentClientKey());
            }
            close();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (!finished.get()) {
            finish(false, waitingForPairing
                    ? "尚未完成配對。請確認電視是否出現允許視窗，按允許後再試。"
                    : "與電視的連線中斷。請確認電視已開機並連上同一個 Wi-Fi。", null);
        }
    }

    @Override
    public void onError(Exception error) {
        if (!finished.get()) {
            String detail = error == null || error.getMessage() == null ? "" : "（" + error.getMessage() + "）";
            finish(false, "連不到電視。請確認 IP、電視電源和同一個 Wi-Fi" + detail + "。", null);
        }
    }

    private void sendRegistration() {
        registrationSent = true;
        try {
            JSONObject manifest = new JSONObject()
                    .put("manifestVersion", 1)
                    .put("appVersion", "1.1")
                    .put("permissions", new JSONArray().put("LAUNCH").put("LAUNCH_WEBAPP").put("APP_TO_APP"));
            JSONObject payload = new JSONObject()
                    .put("pairingType", "PROMPT")
                    .put("forcePairing", false)
                    .put("manifest", manifest);
            if (!existingClientKey.isEmpty()) payload.put("client-key", existingClientKey);
            send(new JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString());
        } catch (Exception error) {
            finish(false, "無法準備電視配對資料。", null);
            close();
        }
    }

    private void finishPairAndLaunch(String key) {
        if (key == null || key.isEmpty()) key = existingClientKey;
        if (key.isEmpty()) {
            waitingForPairing = true;
            return;
        }
        sendLaunch(key);
    }

    private void sendLaunch(String key) {
        if (launchSent) return;
        launchSent = true;
        try {
            JSONObject payload = new JSONObject()
                    .put("id", "com.example.hellotv")
                    .put("params", launchParams);
            JSONObject request = new JSONObject()
                    .put("type", "request")
                    .put("id", launchId)
                    .put("uri", "ssap://com.webos.applicationManager/launch")
                    .put("payload", payload);
            send(request.toString());
            activeClientKey = key;
        } catch (Exception error) {
            finish(false, "無法建立電視 App 啟動指令。", null);
            close();
        }
    }

    private String activeClientKey;

    private String currentClientKey() {
        return activeClientKey == null || activeClientKey.isEmpty() ? existingClientKey : activeClientKey;
    }

    private void finish(boolean success, String message, String key) {
        if (!finished.compareAndSet(false, true)) return;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        String savedKey = key == null || key.isEmpty() ? currentClientKey() : key;
        callback.onComplete(success, message, savedKey.isEmpty() ? null : savedKey);
    }
}
