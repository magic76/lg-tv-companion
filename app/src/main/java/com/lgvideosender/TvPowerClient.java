package com.lgvideosender;

import android.os.Handler;
import android.os.Looper;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot SSAP client that reads TV power state and can turn an active TV off. */
final class TvPowerClient extends WebSocketClient {
    interface Callback {
        void onComplete(boolean success, String message, String clientKey,
                        boolean stateKnown, boolean isOn, boolean shouldWake);
    }

    private static final String POWER_STATE_URI = "ssap://com.webos.service.tvpower/power/getPowerState";
    private static final String POWER_OFF_URI = "ssap://system/turnOff";

    private final String existingClientKey;
    private final boolean toggle;
    private final Callback callback;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final String stateRequestId = "phone_power_state_" + System.currentTimeMillis();
    private final String offRequestId = "phone_power_off_" + System.currentTimeMillis();
    private volatile boolean registrationSent;
    private volatile boolean waitingForPairing;
    private volatile boolean powerOffSent;
    private volatile String activeClientKey;

    private final Runnable timeoutRunnable = () -> {
        if (powerOffSent) {
            finish(true, "已送出關機指令，電視狀態會再確認。", activeClientKey, true, false, false);
        } else {
            finish(false, waitingForPairing
                    ? "請在電視允許電源控制後再試。"
                    : "讀取電視電源狀態逾時，請確認電視和手機在同一個 Wi-Fi。",
                    activeClientKey, false, false, false);
        }
        close();
    };

    TvPowerClient(String host, String existingClientKey, boolean toggle, Callback callback) throws Exception {
        super(new URI("ws://" + host + ":3000"));
        this.existingClientKey = existingClientKey == null ? "" : existingClientKey;
        this.toggle = toggle;
        this.callback = callback;
        setConnectionLostTimeout(15);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        timeoutHandler.postDelayed(timeoutRunnable, 30000);
        try {
            send(new JSONObject().put("type", "hello").put("id", "hello").toString());
        } catch (Exception error) {
            finish(false, "無法啟動電視電源控制協定。", null, false, false, false);
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
            if (!key.isEmpty()) activeClientKey = key;
            requestPowerState();
            return;
        }
        if ("response".equals(type) && "register_power".equals(id)) {
            handleRegistration(packet.optJSONObject("payload"));
            return;
        }
        if ("response".equals(type) && stateRequestId.equals(id)) {
            handlePowerState(packet.optJSONObject("payload"));
            return;
        }
        if ("response".equals(type) && offRequestId.equals(id)) {
            JSONObject payload = packet.optJSONObject("payload");
            boolean accepted = payload != null && payload.optBoolean("returnValue", true);
            String detail = payload == null ? "" : payload.optString("errorText", "");
            finish(accepted, accepted ? "已送出電視關機指令。"
                            : (detail.isEmpty() ? "電視沒有接受關機指令。" : "電視關機失敗：" + detail),
                    activeClientKey, true, !accepted, false);
            close();
            return;
        }
        if ("error".equals(type)) {
            String detail = packet.optString("error", "");
            if (powerOffSent) {
                finish(false, detail.isEmpty() ? "電視沒有接受關機指令。" : "電視拒絕關機指令：" + detail,
                        activeClientKey, true, true, false);
            } else {
                finish(false, waitingForPairing
                        ? "電視尚未授權電源控制；請在電視允許後再試。"
                        : (detail.isEmpty() ? "電視拒絕電源控制。" : "電視拒絕電源控制：" + detail),
                        activeClientKey, false, false, false);
            }
            close();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (finished.get()) return;
        if (powerOffSent) {
            finish(true, "已送出關機指令，電視連線已中斷；狀態會再確認。", activeClientKey,
                    true, false, false);
        } else {
            finish(false, waitingForPairing
                    ? "等待電視允許電源控制逾時。"
                    : "讀取電視電源狀態時連線中斷。",
                    activeClientKey, false, false, false);
        }
    }

    @Override
    public void onError(Exception error) {
        if (finished.get()) return;
        if (powerOffSent) {
            finish(true, "已送出關機指令，電視狀態會再確認。", activeClientKey, true, false, false);
        } else {
            String detail = error == null || error.getMessage() == null ? "" : "（" + error.getMessage() + "）";
            finish(false, "讀取電視電源狀態失敗" + detail + "。", activeClientKey, false, false, false);
        }
    }

    private void sendRegistration() {
        registrationSent = true;
        try {
            JSONObject manifest = new JSONObject()
                    .put("manifestVersion", 1)
                    .put("appVersion", "1.0")
                    .put("permissions", new JSONArray().put("CONTROL_POWER"));
            JSONObject payload = new JSONObject()
                    .put("pairingType", "PROMPT")
                    .put("forcePairing", false)
                    .put("manifest", manifest);
            if (!existingClientKey.isEmpty()) payload.put("client-key", existingClientKey);
            send(new JSONObject().put("type", "register").put("id", "register_power")
                    .put("payload", payload).toString());
        } catch (Exception error) {
            finish(false, "無法準備電視電源控制授權。", null, false, false, false);
            close();
        }
    }

    private void handleRegistration(JSONObject payload) {
        if (payload == null) {
            finish(false, "電視沒有回覆電源控制授權。", null, false, false, false);
            close();
            return;
        }
        String key = payload.optString("client-key", "");
        if (!key.isEmpty()) activeClientKey = key;
        String pairingType = payload.optString("pairingType", "");
        if ("PROMPT".equalsIgnoreCase(pairingType)
                && existingClientKey.isEmpty() && activeClientKey == null) {
            waitingForPairing = true;
            return;
        }
        if (!payload.optBoolean("returnValue", true)) {
            finish(false, "電視尚未授權電源控制，請在電視允許後重試。",
                    activeClientKey, false, false, false);
            close();
            return;
        }
        if (activeClientKey == null || activeClientKey.isEmpty()) activeClientKey = existingClientKey;
        if (activeClientKey == null || activeClientKey.isEmpty()) {
            waitingForPairing = true;
            return;
        }
        requestPowerState();
    }

    private void requestPowerState() {
        if (finished.get()) return;
        if (activeClientKey == null || activeClientKey.isEmpty()) activeClientKey = existingClientKey;
        if (activeClientKey == null || activeClientKey.isEmpty()) {
            waitingForPairing = true;
            return;
        }
        waitingForPairing = false;
        try {
            send(new JSONObject().put("type", "request").put("id", stateRequestId)
                    .put("uri", POWER_STATE_URI).put("payload", new JSONObject()).toString());
        } catch (Exception error) {
            finish(false, "無法讀取電視電源狀態。", activeClientKey, false, false, false);
            close();
        }
    }

    private void handlePowerState(JSONObject payload) {
        if (payload == null || !payload.optBoolean("returnValue", true)) {
            String detail = payload == null ? "" : payload.optString("errorText", "");
            finish(false, detail.isEmpty() ? "電視無法回報電源狀態。" : "電視無法回報電源狀態：" + detail,
                    activeClientKey, false, false, false);
            close();
            return;
        }
        String state = payload.optString("state", "").trim();
        if (state.isEmpty()) {
            finish(false, "電視回報的電源狀態無法辨識。", activeClientKey, false, false, false);
            close();
            return;
        }
        String processing = payload.optString("processing", "").trim();
        boolean isOn = "Active".equalsIgnoreCase(state) || "Screen Off".equalsIgnoreCase(state);
        if (!processing.isEmpty()) {
            finish(true, "電視正在切換電源，請稍候。", activeClientKey, true, isOn, false);
            close();
            return;
        }
        if (!toggle) {
            finish(true, isOn ? "電視已開機。" : "電視待機中。", activeClientKey, true, isOn, false);
            close();
            return;
        }
        if (isOn) {
            sendPowerOff();
        } else {
            finish(true, "電視目前待機，將送出 Wi-Fi 喚醒訊號。", activeClientKey, true, false, true);
            close();
        }
    }

    private void sendPowerOff() {
        if (finished.get()) return;
        powerOffSent = true;
        try {
            send(new JSONObject().put("type", "request").put("id", offRequestId)
                    .put("uri", POWER_OFF_URI).put("payload", new JSONObject()).toString());
        } catch (Exception error) {
            finish(false, "無法傳送電視關機指令。", activeClientKey, true, true, false);
            close();
        }
    }

    private void finish(boolean success, String message, String clientKey,
                        boolean stateKnown, boolean isOn, boolean shouldWake) {
        if (!finished.compareAndSet(false, true)) return;
        timeoutHandler.removeCallbacks(timeoutRunnable);
        String savedKey = clientKey == null || clientKey.isEmpty() ? existingClientKey : clientKey;
        callback.onComplete(success, message, savedKey.isEmpty() ? null : savedKey,
                stateKnown, isOn, shouldWake);
    }
}
