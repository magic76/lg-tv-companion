package com.lgvideosender;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/** Installs an IPK over the same Developer Mode SSH/Luna path used by LG's webOS CLI. */
final class TvAppInstaller {
    private static final long MAX_PACKAGE_SIZE = 512L * 1024L * 1024L;
    private static final long INSTALL_TIMEOUT_MS = 120_000L;
    private static final int MAX_APPINFO_SIZE = 256 * 1024;
    private static final String REMOTE_DIR = "/media/developer/temp";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class PackageInfo {
        final String id;
        final String version;
        final String title;
        final long size;

        PackageInfo(String id, String version, String title, long size) {
            this.id = id;
            this.version = version;
            this.title = title;
            this.size = size;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("version", version)
                    .put("title", title)
                    .put("size", size);
        }
    }

    interface Progress {
        void update(String message);
    }

    private TvAppInstaller() {}

    static PackageInfo inspect(File ipk) throws Exception {
        if (ipk == null || !ipk.isFile() || ipk.length() < 8 || ipk.length() > MAX_PACKAGE_SIZE) {
            throw new IOException("套件檔案不存在、太大或格式不正確。");
        }
        try (InputStream input = new FileInputStream(ipk)) {
            byte[] magic = readExact(input, 8);
            if (!"!<arch>\n".equals(new String(magic, "US-ASCII"))) {
                throw new IOException("這不是有效的 webOS .ipk 套件。");
            }

            while (true) {
                byte[] header = readOptional(input, 60);
                if (header == null) break;
                if (header[58] != '`' || header[59] != '\n') {
                    throw new IOException("IPK 封裝格式無法辨識。");
                }
                String name = new String(header, 0, 16, "US-ASCII").trim();
                if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                long length = parseDecimal(header, 48, 10);
                if (length < 0 || length > ipk.length()) throw new IOException("IPK 封裝長度錯誤。");
                if ("data.tar.gz".equals(name)) {
                    try (InputStream entry = new LimitedInputStream(input, length);
                         GZIPInputStream gzip = new GZIPInputStream(entry)) {
                        JSONObject appInfo = findAppInfo(gzip);
                        if (appInfo == null) throw new IOException("套件內找不到 appinfo.json。");
                        String id = appInfo.optString("id", "").trim();
                        String version = appInfo.optString("version", "").trim();
                        String title = appInfo.optString("title", id).trim();
                        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                            throw new IOException("appinfo.json 缺少有效的 App ID。");
                        }
                        if (title.isEmpty()) title = id;
                        return new PackageInfo(id, version, title, ipk.length());
                    }
                }
                skipExact(input, length + (length & 1L));
            }
        }
        throw new IOException("IPK 套件中沒有 data.tar.gz。");
    }

    static String install(Context context, String host, String passphrase, File ipk,
                          PackageInfo packageInfo, Progress progress) throws Exception {
        if (host == null || host.trim().isEmpty()) throw new IOException("請先設定電視 IP。");
        if (passphrase == null || passphrase.trim().isEmpty()) {
            throw new IOException("請輸入 Developer Mode 畫面顯示的 passphrase。");
        }
        if (packageInfo == null || ipk == null || !ipk.isFile()) throw new IOException("請先選擇 .ipk 套件。");

        report(progress, "正在向電視 Developer Mode 取得 SSH 金鑰…");
        byte[] privateKey;
        try {
            privateKey = fetchPrivateKey(host.trim());
        } catch (java.net.ConnectException | java.net.SocketTimeoutException | java.net.UnknownHostException networkError) {
            throw new IOException("連不到電視 Developer Mode Key Server（9991）。請確認電視已開機、Key Server 已啟用，且手機和電視使用同一個 Wi-Fi。", networkError);
        }
        JSch jsch = new JSch();
        File knownHosts = new File(context.getFilesDir(), "webos-tv-known-hosts");
        if (!knownHosts.exists() && !knownHosts.createNewFile()) throw new IOException("無法建立電視 SSH 指紋資料。");
        jsch.setKnownHosts(knownHosts.getAbsolutePath());
        try {
            jsch.addIdentity("webos-tv", privateKey, null, passphrase.trim().getBytes("UTF-8"));
        } catch (Exception keyError) {
            java.util.Arrays.fill(privateKey, (byte) 0);
            throw new IOException("SSH 金鑰無法解鎖；請確認輸入的是電視 Developer Mode 顯示的 passphrase。", keyError);
        }

        Session session = null;
        ChannelSftp sftp = null;
        String remotePath = REMOTE_DIR + "/crew-companion-" + Long.toHexString(System.nanoTime()) + ".ipk";
        boolean uploaded = false;
        boolean preserveRemotePackage = false;
        try {
            session = jsch.getSession("prisoner", host.trim(), 9922);
            session.setConfig("StrictHostKeyChecking", "ask");
            session.setConfig("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1");
            session.setConfig("server_host_key", "rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp256,ssh-ed25519");
            session.setConfig("PubkeyAcceptedAlgorithms", "rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp256,ssh-ed25519");
            session.setUserInfo(new TvHostKeyPrompt(context));
            session.setTimeout(180000);
            session.connect(25000);

            report(progress, "已安全連上電視，正在傳送套件…");
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15000);
            try {
                sftp.stat(REMOTE_DIR);
            } catch (SftpException missing) {
                sftp.mkdir(REMOTE_DIR);
            }
            sftp.put(ipk.getAbsolutePath(), remotePath);
            uploaded = true;
            if (sftp.stat(remotePath).getSize() != ipk.length()) {
                throw new IOException("套件傳輸長度不符，已取消安裝。");
            }
            String localMd5 = digestFile(ipk, "MD5");
            String remoteMd5 = runCommand(session, "/usr/bin/md5sum " + shellQuote(remotePath), 20000).split("\\s+")[0];
            if (!localMd5.equalsIgnoreCase(remoteMd5)) throw new IOException("套件傳輸校驗失敗，已取消安裝。");

            report(progress, "套件已傳完，正在由電視安裝…");
            String status;
            try {
                status = installViaLuna(session, remotePath, progress);
            } catch (IOException installError) {
                preserveRemotePackage = installError.getMessage() != null
                        && installError.getMessage().contains("等待電視安裝結果逾時");
                throw installError;
            }
            try { sftp.rm(remotePath); } catch (Exception ignored) {}
            uploaded = false;
            return status;
        } catch (Exception error) {
            if (error.getMessage() != null && error.getMessage().contains("UnknownHostKey")) {
                throw new IOException("未能確認電視 SSH 指紋，連線已中止。", error);
            }
            if (error instanceof com.jcraft.jsch.JSchException) {
                String message = error.getMessage() == null ? "" : error.getMessage();
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("reject hostkey") || lower.contains("host key") || lower.contains("hostkey")) {
                    throw new IOException("電視 SSH 指紋未被信任或與先前記錄不同，連線已中止。", error);
                }
                if (lower.contains("auth fail") || lower.contains("userauth fail")) {
                    throw new IOException("SSH 驗證失敗。請確認 Developer Mode 的 Key Server 已開啟，並重新輸入 passphrase。", error);
                }
                if (lower.contains("connection refused") || lower.contains("connect timed out") || lower.contains("timeout")) {
                    throw new IOException("連不上電視 SSH（9922）。請確認電視 Developer Mode 開啟、Key Server 已啟用，且手機與電視在同一個 Wi-Fi。", error);
                }
                if (lower.contains("algorithm negotiation fail")) {
                    throw new IOException("電視 SSH 演算法協商失敗；此電視的 Developer Mode SSH 可能不相容。", error);
                }
            }
            if (error instanceof java.net.ConnectException || error instanceof java.net.SocketTimeoutException) {
                throw new IOException("連不上電視 SSH（9922）。請確認電視 Developer Mode 開啟、Key Server 已啟用，且手機與電視在同一個 Wi-Fi。", error);
            }
            throw error;
        } finally {
            if (uploaded && !preserveRemotePackage && sftp != null && sftp.isConnected()) {
                try { sftp.rm(remotePath); } catch (Exception ignored) {}
            }
            if (sftp != null) sftp.disconnect();
            if (session != null) session.disconnect();
            java.util.Arrays.fill(privateKey, (byte) 0);
        }
    }

    private static byte[] fetchPrivateKey(String host) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + host + ":9991/webos_rsa").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("無法從電視取得 Developer Mode SSH 金鑰（HTTP " + status
                        + "）。請在電視 Developer Mode 開啟 Key Server。");
            }
            int expected = connection.getContentLength();
            if (expected > 128 * 1024) throw new IOException("電視回傳的 SSH 金鑰大小不正確。");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 128 * 1024) throw new IOException("電視回傳的 SSH 金鑰大小不正確。");
                    output.write(buffer, 0, count);
                }
                if (total < 64) throw new IOException("電視回傳的 SSH 金鑰是空的；請確認 Key Server 已開啟。");
                return output.toByteArray();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String installViaLuna(Session session, String remotePath, Progress progress) throws Exception {
        JSONObject params = new JSONObject()
                .put("id", "com.ares.defaultName")
                .put("ipkUrl", remotePath)
                .put("subscribe", true);
        String command = "/usr/bin/luna-send-pub -i luna://com.webos.appInstallService/dev/install "
                + shellQuote(params.toString());
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setErrStream(errors);
        InputStream stdout = channel.getInputStream();
        channel.connect(15000);
        InstallState installState = new InstallState();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                int available = stdout.available();
                if (available > 0) {
                    int count = stdout.read(buffer, 0, Math.min(buffer.length, available));
                    if (count < 0) break;
                    for (int index = 0; index < count; index++) {
                        int value = buffer[index] & 0xff;
                        if (value == '\n') {
                            processInstallResponse(line.toString("UTF-8"), installState, progress);
                            line.reset();
                            if (installState.complete) return installState.lastState;
                        } else if (value != '\r') {
                            if (line.size() >= 1024 * 1024) throw new IOException("電視安裝回應資料過大。");
                            line.write(value);
                        }
                    }
                } else if (channel.isClosed()) {
                    if (line.size() > 0) processInstallResponse(line.toString("UTF-8"), installState, progress);
                    if (installState.complete) return installState.lastState;
                    break;
                } else {
                    Thread.sleep(50);
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("等待電視安裝結果逾時（2 分鐘）。電視可能仍在背景安裝；請先查看電視 App 清單，不要立即重送。");
            }
        } finally {
            channel.disconnect();
        }
        String errorText = errors.toString("UTF-8").trim();
        if (!installState.lastError.isEmpty()) throw new IOException("電視安裝失敗：" + installState.lastError);
        if (!errorText.isEmpty()) throw new IOException("電視安裝服務錯誤：" + errorText);
        if (installState.statusValue >= 0 || !installState.lastState.isEmpty()) {
            throw new IOException("電視尚未回報安裝完成（狀態 "
                    + (installState.statusValue >= 0 ? installState.statusValue : installState.lastState) + "）。");
        }
        throw new IOException("電視安裝服務沒有回報結果；請稍後查看電視 App 清單。");
    }

    private static final class InstallState {
        String lastState = "";
        String lastError = "";
        int statusValue = -1;
        boolean complete;
    }

    private static void processInstallResponse(String line, InstallState installState, Progress progress) throws Exception {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) return;
        final JSONObject response;
        try {
            response = new JSONObject(trimmed);
        } catch (org.json.JSONException ignored) {
            // luna-send may write diagnostic text alongside JSON.
            return;
        }

        JSONObject payload = response.optJSONObject("return");
        if (payload == null) payload = response;
        JSONObject details = payload.optJSONObject("details");
        if (details == null) details = response.optJSONObject("details");
        if (!response.optBoolean("returnValue", true)) {
            String error = response.optString("errorText", response.optString("errorMessage", ""));
            if (error.isEmpty() && details != null) error = details.optString("reason", "");
            installState.lastError = error.isEmpty() ? "未知錯誤" : error;
            throw new IOException("電視安裝服務拒絕套件：" + installState.lastError);
        }

        installState.statusValue = payload.optInt("statusValue", response.optInt("statusValue", installState.statusValue));
        installState.lastState = details == null ? payload.optString("state", "") : details.optString("state", "");
        if (installState.statusValue == 23 || installState.statusValue == 24
                || installState.lastState.matches("(?i).*FAILED.*")) {
            String reason = details == null ? "" : details.optString("reason", "");
            int errorCode = details == null ? 0 : details.optInt("errorCode", 0);
            if (reason.isEmpty()) reason = errorCode != 0 ? "錯誤代碼 " + errorCode : installState.lastState;
            if (reason.isEmpty()) reason = "安裝服務回報失敗";
            installState.lastError = reason;
            throw new IOException("電視安裝失敗：" + reason);
        }
        if (installState.statusValue == 22) throw new IOException("電視已取消這次安裝。");

        if (installState.statusValue == 30 || installState.lastState.matches("(?i).*installed.*")
                || installState.lastState.matches("(?i)^SUCCESS.*")) {
            installState.complete = true;
            return;
        }

        String progressMessage = installProgressMessage(installState.statusValue, installState.lastState);
        if (!progressMessage.isEmpty()) report(progress, progressMessage);
    }

    private static String installProgressMessage(int statusValue, String state) {
        switch (statusValue) {
            case 6: case 7: case 8: return "電視正在讀取套件…";
            case 10: return "電視已讀取套件，正在準備安裝…";
            case 11: case 12: case 13: return "電視正在安裝 App…";
            case 17: case 27: case 28: case 29: return "電視正在完成 App 安裝設定…";
            default:
                if (state != null && !state.isEmpty()) return "電視安裝中（" + state + "）…";
                return "";
        }
    }

    private static String runCommand(Session session, String command, int timeoutMs) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setErrStream(errors);
        InputStream stdout = channel.getInputStream();
        channel.connect(15000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            byte[] buffer = new byte[1024];
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                while (stdout.available() > 0) {
                    int count = stdout.read(buffer);
                    if (count < 0) break;
                    output.write(buffer, 0, count);
                }
                Thread.sleep(40);
            }
            while (stdout.available() > 0) output.write(buffer, 0, stdout.read(buffer));
            if (!channel.isClosed()) throw new IOException("電視檔案校驗逾時。");
            if (channel.getExitStatus() != 0) throw new IOException("電視無法校驗已傳送的套件。");
            String value = output.toString("UTF-8").trim();
            if (value.isEmpty()) throw new IOException("電視沒有回傳套件校驗結果。");
            return value;
        } finally {
            channel.disconnect();
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String digestFile(File file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static JSONObject findAppInfo(InputStream tar) throws Exception {
        while (true) {
            byte[] header = readOptional(tar, 512);
            if (header == null || isZeroBlock(header)) return null;
            String name = cString(header, 0, 100);
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = parseOctal(header, 124, 12);
            if (size < 0) throw new IOException("IPK 中的 tar 檔案長度錯誤。");
            if (name.endsWith("appinfo.json")) {
                if (size > MAX_APPINFO_SIZE) throw new IOException("appinfo.json 太大，已取消讀取。");
                byte[] body = readExact(tar, (int) size);
                return new JSONObject(new String(body, "UTF-8"));
            }
            skipExact(tar, size + ((512 - (size % 512)) % 512));
        }
    }

    private static String cString(byte[] bytes, int offset, int length) throws Exception {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, "UTF-8");
    }

    private static long parseOctal(byte[] bytes, int offset, int length) throws IOException {
        String value = new String(bytes, offset, length, "US-ASCII").replace("\u0000", "").trim();
        if (value.isEmpty()) return 0;
        try { return Long.parseLong(value, 8); }
        catch (NumberFormatException invalid) { throw new IOException("IPK tar 欄位格式錯誤。", invalid); }
    }

    private static long parseDecimal(byte[] bytes, int offset, int length) throws IOException {
        String value = new String(bytes, offset, length, "US-ASCII").trim();
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IOException("IPK 封裝欄位格式錯誤。", invalid); }
    }

    private static boolean isZeroBlock(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static byte[] readOptional(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int position = 0;
        while (position < length) {
            int count = input.read(result, position, length - position);
            if (count < 0) {
                if (position == 0) return null;
                throw new IOException("IPK 封裝突然結束。");
            }
            position += count;
        }
        return result;
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] result = readOptional(input, length);
        if (result == null) throw new IOException("IPK 封裝資料不完整。");
        return result;
    }

    private static void skipExact(InputStream input, long length) throws IOException {
        long remaining = length;
        byte[] scratch = new byte[8192];
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                int count = input.read(scratch, 0, (int) Math.min(scratch.length, remaining));
                if (count < 0) throw new IOException("IPK 封裝資料不完整。");
                skipped = count;
            }
            remaining -= skipped;
        }
    }

    private static void report(Progress progress, String message) {
        if (progress != null) progress.update(message);
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = in.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }

    private static final class TvHostKeyPrompt implements UserInfo {
        private final Context context;
        TvHostKeyPrompt(Context context) { this.context = context; }
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean promptPassword(String message) { return false; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public void showMessage(String message) { }
        @Override public boolean promptYesNo(String message) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean accepted = new AtomicBoolean(false);
            MAIN.post(() -> new AlertDialog.Builder(context)
                    .setTitle("確認電視 SSH 指紋")
                    .setMessage("第一次連線會顯示電視 SSH 指紋。只有在你正在設定的電視旁操作時才接受。\n\n" + message)
                    .setPositiveButton("信任並繼續", (dialog, which) -> { accepted.set(true); latch.countDown(); })
                    .setNegativeButton("取消", (dialog, which) -> latch.countDown())
                    .setOnCancelListener(dialog -> latch.countDown())
                    .show());
            try { return latch.await(120, TimeUnit.SECONDS) && accepted.get(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        }
    }
}
