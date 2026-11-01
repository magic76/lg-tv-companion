package com.lgvideosender;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LAN-only reverse proxy that adds legacy CSS fallbacks to Gimyai pages for webOS 4.x. */
final class GimyaiCompatProxyServer {
    interface Listener {
        void onProxyStatus(String message);
    }

    private static final String UPSTREAM = "https://gimyai.tw";
    private static final String TV_USER_AGENT = "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/53.0.2785.34 Safari/537.36 WebAppManager";
    private static final Pattern GIMYAI_ORIGIN = Pattern.compile(
            "(?i)(?:https?:)?//(?:www\\.)?gimyai\\.tw(?=[:/?#\\\"'\\s]|$)");
    private static final int MAX_TEXT_RESPONSE = 10 * 1024 * 1024;
    private static final String LEGACY_CSS = "\n"
            + "/* webOS 4.x / Chromium 53 layout fallbacks for Gimyai */\n"
            + ":root{--gutter:32px!important;--maxw:1480px!important}\n"
            + "html,body{min-width:100%;}\n"
            + ".container{max-width:1480px!important;margin-left:auto!important;margin-right:auto!important;"
            + "padding-left:32px!important;padding-right:32px!important}\n"
            + ".site-header{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}\n"
            + ".grid{display:flex!important;flex-wrap:wrap!important;gap:0!important;margin-left:-9px!important;"
            + "margin-right:-9px!important}\n"
            + ".grid>.poster{display:block!important;box-sizing:border-box!important;width:calc(16.6667% - 18px)!important;"
            + "max-width:calc(16.6667% - 18px)!important;flex:0 0 calc(16.6667% - 18px)!important;"
            + "margin:0 9px 24px!important}\n"
            + ".detail{gap:0!important}.detail__poster{margin-right:32px!important}\n"
            + ".detail__meta{display:flex!important;flex-wrap:wrap!important;gap:0!important}\n"
            + ".detail__meta>div{box-sizing:border-box!important;flex:0 0 calc(50% - 16px)!important;"
            + "margin:0 16px 12px 0!important}\n"
            + ".detail__meta>.full{grid-column:auto!important;flex-basis:100%!important}\n"
            + ".eps{display:flex!important;flex-wrap:wrap!important;gap:0!important;margin:-4px!important}\n"
            + ".eps>*{box-sizing:border-box!important;flex:0 0 auto!important;min-width:60px!important;"
            + "margin:4px!important}\n"
            + ".sources.route-grid{display:flex!important;flex-wrap:wrap!important;gap:0!important}\n"
            + ".sources.route-grid>*{flex:0 0 160px!important;margin:4px!important}\n"
            + ".rank,.top-cols{display:flex!important;flex-wrap:wrap!important;gap:0!important}\n"
            + ".rank>*{box-sizing:border-box!important;flex:1 1 21%!important;margin:10px!important}\n"
            + ".top-cols>*{box-sizing:border-box!important;flex:1 1 44%!important;margin:8px!important}\n"
            + ".member{display:flex!important;flex-wrap:wrap!important;gap:0!important}\n"
            + ".member>*:first-child{flex:0 0 248px!important;margin:0 28px 18px 0!important}\n"
            + ".member>*:last-child{flex:1 1 60%!important;min-width:0!important}\n"
            + ".search-m{display:flex!important;flex-direction:column!important}\n"
            + ".searchbar button,.icon-btn,.header-search__back,.header-search__go,.to-top,.rail__btn,"
            + ".comment-box__avatar,.comment__avatar,.settings-avatar .avatar,.install-banner__close,"
            + ".header-avatar--default,.profile-avatar--default,.profile-avatar__edit,.cmt__avatar,"
            + ".creply__avatar,.poster-menu-btn{display:flex!important;align-items:center!important;"
            + "justify-content:center!important}\n"
            + ".poster__thumb img{top:0!important;right:0!important;bottom:0!important;left:0!important}\n"
            + ".player{display:block!important;width:100%!important;height:56.25vw!important;max-height:80vh!important;"
            + "min-height:320px!important;aspect-ratio:auto!important}\n"
            + ".player .player-frame{top:0!important;right:0!important;bottom:0!important;left:0!important}\n"
            + "@media(max-width:900px){.grid>.poster{width:calc(33.3333% - 18px)!important;"
            + "max-width:calc(33.3333% - 18px)!important;flex-basis:calc(33.3333% - 18px)!important}}\n";

    private final int port;
    private final Listener listener;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    GimyaiCompatProxyServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(false);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", port), 16);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "gimyai-compat-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
        if (listener != null) listener.onProxyStatus("Gimyai 相容代理已啟動");
    }

    void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(12000);
                client.setTcpNoDelay(true);
                workers.execute(() -> handle(client));
            } catch (IOException error) {
                if (running && listener != null) listener.onProxyStatus("Gimyai 相容代理連線中斷：" + error.getMessage());
                return;
            }
        }
    }

    private void handle(Socket client) {
        HttpURLConnection upstream = null;
        Socket socket = client;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() > 8192) return;
            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length < 2) {
                writeError(socket.getOutputStream(), 400, "Bad Request");
                return;
            }
            String method = requestParts[0].toUpperCase(Locale.US);
            if (!("GET".equals(method) || "HEAD".equals(method))) {
                drainHeaders(reader);
                writeError(socket.getOutputStream(), 405, "Only GET and HEAD are supported");
                return;
            }

            String localHost = "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.length() > 16384) continue;
                int colon = line.indexOf(':');
                if (colon > 0 && "host".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    localHost = sanitizeHost(line.substring(colon + 1).trim());
                }
            }
            if (localHost.isEmpty()) {
                writeError(socket.getOutputStream(), 400, "Missing host");
                return;
            }

            URI requestUri = new URI(requestParts[1]);
            String path = requestUri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (path.startsWith("//") || path.contains("\\\\")) {
                writeError(socket.getOutputStream(), 400, "Invalid path");
                return;
            }
            if ("/template/gimyai/js/mt11.js".equals(path)) {
                byte[] blockedLoader = "/* Gimyai compatibility mode: blocked third-party ad loader. */".getBytes(StandardCharsets.UTF_8);
                writeHeaders(socket.getOutputStream(), 200, "application/javascript; charset=UTF-8", blockedLoader.length, false);
                if (!"HEAD".equals(method)) socket.getOutputStream().write(blockedLoader);
                socket.getOutputStream().flush();
                return;
            }
            String target = UPSTREAM + path + (requestUri.getRawQuery() == null ? "" : "?" + requestUri.getRawQuery());
            upstream = (HttpURLConnection) new URL(target).openConnection();
            upstream.setInstanceFollowRedirects(true);
            upstream.setConnectTimeout(12000);
            upstream.setReadTimeout(18000);
            upstream.setRequestMethod(method);
            upstream.setRequestProperty("User-Agent", TV_USER_AGENT);
            upstream.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,text/css,*/*;q=0.8");
            upstream.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.7");
            upstream.setRequestProperty("Accept-Encoding", "identity");
            upstream.setRequestProperty("Referer", UPSTREAM + "/");

            int status = upstream.getResponseCode();
            String contentType = upstream.getContentType();
            if (contentType == null || contentType.trim().isEmpty()) contentType = "application/octet-stream";
            InputStream body = status >= 400 ? upstream.getErrorStream() : upstream.getInputStream();
            OutputStream output = socket.getOutputStream();

            if (isTextForRewrite(contentType)) {
                byte[] original = body == null ? new byte[0] : readLimited(body, MAX_TEXT_RESPONSE);
                String text = new String(original, StandardCharsets.UTF_8);
                String localOrigin = "http://" + localHost;
                text = rewriteGimyaiOrigin(text, localOrigin);
                if (contentType.toLowerCase(Locale.US).contains("text/html")) {
                    text = stripKnownAdLoaders(text);
                    text = injectPopupGuard(text);
                }
                if (contentType.toLowerCase(Locale.US).contains("text/css")) text += LEGACY_CSS;
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                writeHeaders(output, status, contentTypeWithUtf8(contentType), bytes.length, false);
                if (!"HEAD".equals(method)) output.write(bytes);
            } else {
                long length = upstream.getContentLengthLong();
                boolean chunked = length < 0;
                writeHeaders(output, status, contentType, length, chunked);
                if (!"HEAD".equals(method) && body != null) {
                    copyBody(body, output, chunked);
                }
            }
            output.flush();
        } catch (Exception error) {
            android.util.Log.e("GimyaiCompatProxy", "Request failed", error);
            try { writeError(client.getOutputStream(), 502, "Gimyai proxy error: " + safeMessage(error)); }
            catch (Exception ignored) {}
        } finally {
            if (upstream != null) upstream.disconnect();
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private static boolean isTextForRewrite(String contentType) {
        String lower = contentType.toLowerCase(Locale.US);
        return lower.contains("text/html") || lower.contains("text/css") || lower.contains("javascript")
                || lower.contains("application/json") || lower.contains("text/plain");
    }

    private static String contentTypeWithUtf8(String contentType) {
        String lower = contentType.toLowerCase(Locale.US);
        if (lower.contains("charset=")) return contentType;
        return contentType + "; charset=UTF-8";
    }

    private static String rewriteGimyaiOrigin(String text, String localOrigin) {
        Matcher matcher = GIMYAI_ORIGIN.matcher(text);
        StringBuffer rewritten = new StringBuffer(text.length());
        while (matcher.find()) matcher.appendReplacement(rewritten, Matcher.quoteReplacement(localOrigin));
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String injectPopupGuard(String html) {
        String guard = "<script>(function(){"
                + "function blocked(v){var s=String(v||'');if(/^(intent|market):/i.test(s))return true;"
                + "try{var u=new URL(s,document.baseURI),h=u.hostname.toLowerCase();"
                + "return /(^|\\.)(llvpn\\.com|pyppo\\.com)$/.test(h)}catch(e){return false}}"
                + "var old=window.open;window.open=function(u){if(blocked(u))return null;"
                + "return old?old.apply(window,arguments):null};"
                + "document.addEventListener('click',function(e){var a=e.target;"
                + "while(a&&a!==document&&String(a.tagName).toLowerCase()!=='a')a=a.parentNode;"
                + "if(a&&blocked(a.href)){e.preventDefault();e.stopPropagation();"
                + "if(e.stopImmediatePropagation)e.stopImmediatePropagation()}},true);"
                + "document.addEventListener('submit',function(e){if(e.target&&blocked(e.target.action)){"
                + "e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation()}},true);"
                + "})();</script>";
        Matcher head = Pattern.compile("(?i)<head(?:\\s[^>]*)?>").matcher(html);
        if (head.find()) return html.substring(0, head.end()) + guard + html.substring(head.end());
        return guard + html;
    }

    private static String stripKnownAdLoaders(String html) {
        Matcher scripts = Pattern.compile("(?is)<script\\b[^>]*>.*?</script\\s*>").matcher(html);
        StringBuffer filtered = new StringBuffer(html.length());
        while (scripts.find()) {
            String script = scripts.group().toLowerCase(Locale.US);
            if (script.contains("mt11.js") || script.contains("llvpn.com") || script.contains("pyppo.com")) {
                scripts.appendReplacement(filtered, "");
            } else {
                scripts.appendReplacement(filtered, Matcher.quoteReplacement(scripts.group()));
            }
        }
        scripts.appendTail(filtered);
        return filtered.toString();
    }

    private static String sanitizeHost(String host) {
        if (host == null || host.length() > 128 || !host.matches("[A-Za-z0-9.\\-:\\[\\]]+")) return "";
        return host;
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IOException("text response too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void copyBody(InputStream input, OutputStream output, boolean chunked) throws IOException {
        try (InputStream stream = input) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (chunked) {
                    output.write(Integer.toHexString(count).getBytes(StandardCharsets.US_ASCII));
                    output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                output.write(buffer, 0, count);
                if (chunked) output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            if (chunked) output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void writeHeaders(OutputStream output, int status, String contentType, long length, boolean chunked) throws IOException {
        String reason = status == 200 ? "OK" : status == 301 ? "Moved Permanently" : status == 302 ? "Found"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 503 ? "Service Unavailable" : "Response";
        StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n");
        if (chunked) headers.append("Transfer-Encoding: chunked\r\n");
        else if (length >= 0) headers.append("Content-Length: ").append(length).append("\r\n");
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void writeError(OutputStream output, int status, String message) throws IOException {
        String escaped = message == null ? "Request failed" : message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        byte[] body = ("<!doctype html><meta charset=utf-8><title>Gimyai 相容模式</title>"
                + "<body style='font:20px sans-serif;padding:32px'>Gimyai 相容代理暫時無法載入：" + escaped + "</body>")
                .getBytes(StandardCharsets.UTF_8);
        String reason = status == 400 ? "Bad Request" : status == 405 ? "Method Not Allowed" : "Bad Gateway";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static void drainHeaders(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {}
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message.replace("\r", " ").replace("\n", " ");
    }
}
