package com.lgvideosender;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** In-app replacement for the old Termux phone-controller WebSocket bridge. */
final class PhoneBridgeServer extends WebSocketServer {
    interface Listener {
        void onBridgeStatus(String message);
    }

    private final String pairingCode;
    private final Listener listener;
    private final int port;
    private final Object peersLock = new Object();
    private volatile WebSocket phonePeer;
    private volatile WebSocket tvPeer;
    private boolean phoneAudioActive;

    PhoneBridgeServer(String pairingCode, Listener listener, int port) {
        super(new InetSocketAddress("0.0.0.0", port));
        this.pairingCode = pairingCode;
        this.listener = listener;
        this.port = port;
        setConnectionLostTimeout(30);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        String descriptor = handshake.getResourceDescriptor();
        String role = queryValue(descriptor, "role");
        String pin = queryValue(descriptor, "pin");
        if (!"/ws".equals(pathOf(descriptor)) || !("phone".equals(role) || "tv".equals(role))
                || !pairingCode.equals(pin)) {
            connection.close(1008, "Invalid role or pairing code");
            return;
        }

        synchronized (peersLock) {
            WebSocket previous = "phone".equals(role) ? phonePeer : tvPeer;
            if (previous != null && previous != connection) previous.close(4001, "Replaced by a new connection");
            if ("phone".equals(role)) phonePeer = connection;
            else tvPeer = connection;
        }
        sendJson(connection, json("type", "ready", "role", role));
        sendState();
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        String role = roleOf(connection);
        if (role == null) return;
        JSONObject packet;
        try {
            packet = new JSONObject(message);
        } catch (Exception invalid) {
            return;
        }

        if ("phone".equals(role)) {
            String type = packet.optString("type", "");
            String id = packet.optString("id", "").trim();
            if ("send_text".equals(type)) {
                String text = packet.optString("text", "").trim();
                if (id.isEmpty() || text.isEmpty() || text.length() > 2000) {
                    sendJson(connection, ack(id, false, "訊息是空的或太長。"));
                } else if (!isOpen(tvPeer)) {
                    sendJson(connection, ack(id, false, "電視 App 尚未連線。"));
                } else {
                    sendJson(tvPeer, json("type", "send_text", "id", id, "text", text));
                }
                return;
            }
            if ("add_site".equals(type)) {
                String name = packet.optString("name", "").trim();
                String url = normalizeSiteUrl(packet.optString("url", ""));
                if (id.isEmpty() || name.isEmpty() || name.length() > 40 || url == null) {
                    sendJson(connection, ack(id, false, "請輸入網站名稱和有效的 http 或 https 網址。"));
                } else if (!isOpen(tvPeer)) {
                    sendJson(connection, ack(id, false, "電視 App 尚未連線。"));
                } else {
                    sendJson(tvPeer, json("type", "add_site", "id", id, "name", name, "url", url));
                }
                return;
            }
            if ("audio_start".equals(type)) {
                synchronized (peersLock) {
                    if (phoneAudioActive) {
                        sendJson(connection, json("type", "audio_error", "message", "已有一段語音正在傳送。"));
                    } else if (!isOpen(tvPeer)) {
                        sendJson(connection, json("type", "audio_error", "message", "電視 App 尚未連線。"));
                    } else {
                        phoneAudioActive = true;
                        sendJson(tvPeer, json("type", "audio_start"));
                    }
                }
                return;
            }
            if ("audio_end".equals(type)) {
                synchronized (peersLock) {
                    if (phoneAudioActive && isOpen(tvPeer)) sendJson(tvPeer, json("type", "audio_end"));
                    phoneAudioActive = false;
                }
            }
            return;
        }

        String type = packet.optString("type", "");
        if ("ack".equals(type)) {
            String id = packet.optString("id", "");
            String messageText = packet.optString("message", "");
            sendJson(phonePeer, json("type", "ack", "id", id.substring(0, Math.min(80, id.length())),
                    "accepted", packet.optBoolean("accepted", false),
                    "message", messageText.substring(0, Math.min(200, messageText.length()))));
        } else if ("assistant_reply".equals(type)) {
            String text = packet.optString("text", "");
            sendJson(phonePeer, json("type", "assistant_reply", "text", text.substring(0, Math.min(4000, text.length()))));
        } else if ("audio_ready".equals(type) || "audio_error".equals(type) || "audio_done".equals(type)) {
            if ("audio_error".equals(type) || "audio_done".equals(type)) {
                synchronized (peersLock) { phoneAudioActive = false; }
            }
            String messageText = packet.optString("message", "");
            sendJson(phonePeer, json("type", type, "message", messageText.substring(0, Math.min(200, messageText.length()))));
        }
    }

    @Override
    public void onMessage(WebSocket connection, ByteBuffer bytes) {
        synchronized (peersLock) {
            if (connection != phonePeer || !phoneAudioActive || !isOpen(tvPeer)) return;
            ByteBuffer copy = bytes.slice();
            byte[] data = new byte[copy.remaining()];
            copy.get(data);
            tvPeer.send(data);
        }
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        synchronized (peersLock) {
            if (connection == phonePeer) {
                if (phoneAudioActive && isOpen(tvPeer)) sendJson(tvPeer, json("type", "audio_end"));
                phoneAudioActive = false;
                phonePeer = null;
            } else if (connection == tvPeer) {
                phoneAudioActive = false;
                tvPeer = null;
            }
        }
        sendState();
    }

    @Override
    public void onError(WebSocket connection, Exception error) {
        if (connection == null && listener != null) {
            listener.onBridgeStatus("App 內橋接啟動失敗：" + (error == null ? "未知錯誤" : error.getMessage()));
        }
    }

    @Override
    public void onStart() {
        if (listener != null) listener.onBridgeStatus("App 內橋接已啟動 · " + port);
    }

    void stopBridge() {
        try { stop(1000); } catch (Exception ignored) {}
    }

    private String roleOf(WebSocket connection) {
        synchronized (peersLock) {
            if (connection == phonePeer) return "phone";
            if (connection == tvPeer) return "tv";
            return null;
        }
    }

    private void sendState() {
        JSONObject state;
        WebSocket phone;
        WebSocket tv;
        synchronized (peersLock) {
            phone = phonePeer;
            tv = tvPeer;
            state = json("type", "state", "phoneConnected", isOpen(phone), "tvConnected", isOpen(tv));
        }
        sendJson(phone, state);
        sendJson(tv, state);
    }

    private static JSONObject ack(String id, boolean accepted, String message) {
        return json("type", "ack", "id", id, "accepted", accepted, "message", message);
    }

    private static JSONObject json(Object... entries) {
        JSONObject result = new JSONObject();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            try { result.put(String.valueOf(entries[index]), entries[index + 1]); }
            catch (Exception ignored) {}
        }
        return result;
    }

    private static boolean isOpen(WebSocket peer) {
        return peer != null && peer.isOpen();
    }

    private static void sendJson(WebSocket peer, JSONObject packet) {
        if (isOpen(peer)) peer.send(packet.toString());
    }

    private static String pathOf(String descriptor) {
        try { return new URI(descriptor).getPath(); } catch (Exception ignored) { return ""; }
    }

    private static String queryValue(String descriptor, String wanted) {
        try {
            String query = new URI(descriptor).getRawQuery();
            if (query == null) return "";
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                String key = equals < 0 ? pair : pair.substring(0, equals);
                if (wanted.equals(URLDecoder.decode(key, "UTF-8"))) {
                    return URLDecoder.decode(equals < 0 ? "" : pair.substring(equals + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String normalizeSiteUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty() || raw.length() > 500) return null;
        if (!raw.matches("(?i)^https?://.+")) {
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
}
