package com.lgvideosender;

import android.os.Handler;
import android.os.Looper;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot SSAP client for TV volume commands. */
final class TvAudioClient extends WebSocketClient {
    interface Callback {
        void onComplete(boolean success, String message, String clientKey);
    }

    private final String existingClientKey;
    private final String command;
    private final Callback callback;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final String commandId = "phone_volume_" + System.currentTimeMillis();
    private volatile boolean waitingForPairing;
    private volatile boolean registrationSent;
    private volatile boolean commandSent;
    private volatile String activeClientKey;

    private final Runnable timeoutRunnable = () -> {
        finish(false, waitingForPairing
                ? "等待電視允許音量控制逾時；請在電視按允許後再試。"
                : "電視音量控制逾時，請確認電視已開機並連上同一個 Wi-Fi。", null);
        close();
    };

    TvAudioClient(String host, String existingClientKey, boolean volumeUp, Callback callback) throws Exception {
        super(new URI("ws://" + host + ":3000"));
        this.existingClientKey = existingClientKey == null ? "" : existingClientKey;
        this.command = volumeUp ? "ssap://audio/volumeUp" : "ssap://audio/volumeDown";
        this.callback = callback;
        setConnectionLostTimeout(20);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        timeoutHandler.postDelayed(timeoutRunnable, 45000);
        try {
            send(new JSONObject().put("type", "hello").put("id", "hello").toString());
        } catch (Exception error) {
            finish(false, "無法啟動電視音量控制協定。", null);
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
            JSONObject payload = packet.optJSONObject("payload");
            String key = payload == null ? "" : payload.optString("client-key", "");
            sendVolumeCommand(key.isEmpty() ? existingClientKey : key);
            return;
        }
        if ("response".equals(type) && "register_audio".equals(id)) {
            JSONObject payload = packet.optJSONObject("payload");
            if (payload == null) {
                finish(false, describeError("電視未接受音量控制授權", packet, payload), null);
                close();
                return;
            }
            String key = payload.optString("client-key", "");
            String pairingType = payload.optString("pairingType", "");
            if ("PROMPT".equalsIgnoreCase(pairingType) && existingClientKey.isEmpty() && key.isEmpty()) {
                waitingForPairing = true;
                return;
            }
            if (!payload.optBoolean("returnValue", true)) {
                finish(false, describeError("電視未接受音量控制授權", packet, payload), null);
                close();
                return;
            }
            if (!key.isEmpty() || !existingClientKey.isEmpty()) {
                sendVolumeCommand(key.isEmpty() ? existingClientKey : key);
            } else {
                waitingForPairing = true;
            }
            return;
        }
        if ("response".equals(type) && commandId.equals(id)) {
            JSONObject payload = packet.optJSONObject("payload");
            boolean success = payload != null && payload.optBoolean("returnValue", false);
            String messageText = success
                    ? ("ssap://audio/volumeUp".equals(command) ? "電視音量已調高。" : "電視音量已調低。")
                    : describeError("電視未接受音量指令", packet, payload);
            finish(success, messageText, activeClientKey);
            close();
            return;
        }
        if ("error".equals(type)) {
            finish(false, describeError(waitingForPairing ? "電視拒絕音量控制授權" : "電視拒絕音量指令", packet,
                    packet.optJSONObject("payload")), activeClientKey);
            close();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (!finished.get()) {
            finish(false, waitingForPairing
                    ? "音量控制連線中斷；如果電視出現授權提示，請先按允許再試。"
                    : "電視音量控制連線中斷，請重試。", activeClientKey);
        }
    }

    @Override
    public void onError(Exception error) {
        if (finished.get()) return;
        String detail = error == null || error.getMessage() == null ? "" : "（" + error.getMessage() + "）";
        finish(false, "連不到電視音量控制服務" + detail + "。請確認電視已開機並使用同一個 Wi-Fi。", activeClientKey);
    }

    private void sendRegistration() {
        registrationSent = true;
        try {
            JSONObject manifest = new JSONObject()
                    .put("manifestVersion", 1)
                    .put("appVersion", "1.0")
                    .put("permissions", new JSONArray().put("CONTROL_AUDIO"));
            JSONObject payload = new JSONObject()
                    .put("pairingType", "PROMPT")
                    .put("forcePairing", false)
                    .put("manifest", manifest);
            if (!existingClientKey.isEmpty()) payload.put("client-key", existingClientKey);
            send(new JSONObject().put("type", "register").put("id", "register_audio")
                    .put("payload", payload).toString());
        } catch (Exception error) {
            finish(false, "無法準備電視音量控制授權。", null);
            close();
        }
    }

    private void sendVolumeCommand(String key) {
        if (commandSent || finished.get()) return;
        if (key == null || key.isEmpty()) {
            waitingForPairing = true;
            return;
        }
        commandSent = true;
        waitingForPairing = false;
        activeClientKey = key;
        try {
            send(new JSONObject().put("type", "request").put("id", commandId)
                    .put("uri", command).put("payload", new JSONObject()).toString());
        } catch (Exception error) {
            finish(false, "無法傳送音量指令到電視。", key);
            close();
        }
    }

    private static String describeError(String prefix, JSONObject packet, JSONObject payload) {
        String detail = packet == null ? "" : packet.optString("error", "");
        if ((detail == null || detail.isEmpty()) && payload != null) {
            detail = payload.optString("errorText", payload.optString("error", ""));
        }
        return prefix + (detail == null || detail.isEmpty() ? "。" : "：" + detail);
    }

    private void finish(boolean success, String message, String key) {
        if (!finished.compareAndSet(false, true)) return;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        String savedKey = key == null || key.isEmpty() ? existingClientKey : key;
        callback.onComplete(success, message, savedKey.isEmpty() ? null : savedKey);
    }
}
