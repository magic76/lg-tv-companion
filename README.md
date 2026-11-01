# LG TV Companion

## 中文

LG TV Companion 由 Android 手機 companion 和 LG webOS 電視 App 組成，提供手機遙控、Gemini 對話、網站收藏、網址開啟，以及支援格式的影片投放。

### 專案內容

| 目錄 | 用途 |
| --- | --- |
| `app/` | Android companion：QR 配對、觸控板、文字輸入、Gemini Live、網站控制、影片串流傳送與電視 App 安裝 |
| `webos-tv/app/` | LG webOS 電視 App：網站與收藏管理、Gemini 對話、手機控制及支援的影片串流播放 |
| `releases/` | Android APK、webOS IPK 與 SHA-256 校驗檔 |

目前版本：Android companion `1.4.38`、webOS TV App `1.4.18`。Android APK 已內附 TV App IPK，可從手機安裝到電視。

### 下載

- [Android companion APK v1.4.38](releases/LGTVCompanion-Android-v1.4.38.apk) — 內含 webOS TV v1.4.18 IPK；debug 簽名，供自行安裝，不是 Play Store 發行版。
- [LG webOS TV IPK v1.4.18](releases/com.example.hellotv_1.4.18_all.ipk)
- [SHA-256 校驗檔](releases/SHA256SUMS.txt)

### 初次設定

1. 在電視啟用 Developer Mode 與 Key Server，確認工作階段有效，記下電視 IP 和畫面顯示的 passphrase。手機和電視需連上同一個 Wi-Fi。
2. 在 Android companion 設定電視 IP，展開「安裝電視 App」即可載入 APK 內附的 IPK。若選擇其他 `.ipk`，內附版本仍會保留，也可以隨時切回。
3. 安裝時輸入電視 Developer Mode 顯示的 passphrase，並確認第一次 SSH 連線顯示的主機指紋。安裝前會再次顯示套件名稱、App ID 和目標電視供確認。
4. 在電視 App 開啟「手機控制連線」並顯示 QR code，再用 Android companion 掃描。電視出現控制授權提示時允許。
5. 在電視 App 設定 Gemini API 金鑰，或從手機控制頁傳送到電視。手機與電視配對後，可在手機頁面按「連線電視」啟動電視 App 並連線。

電視 App 安裝使用 Developer Mode Key Server 取得 SSH 金鑰（連接埠 9991），再透過 SSH（連接埠 9922）傳送及安裝 IPK；這與手機控制頁的 QR 配對是不同連線，不需要先完成手機控制配對。安裝不會自動開啟 App 或切換電視畫面。

### 功能與限制

- 手機可調整電視音量、控制游標、輸入文字、開啟網址、管理網站收藏，也可將支援的影片串流交給電視播放。首次使用音量鍵時，需在電視允許音量控制。
- Android companion APK 內附 webOS TV IPK。安裝器會顯示電視回報的安裝進度；若兩分鐘內未取得完成回報，電視端仍可能在背景安裝，請先查看電視 App 清單再重試。
- 手機控制橋接服務、Gimyai 與 Yahoo 台灣相容代理都由 Android companion 提供，不需要另外安裝或啟動 Termux bridge。使用期間 Android 會顯示持續通知；請勿強制停止 companion。
- Gimyai 相容代理針對舊版 Chromium 的 CSS 排版問題提供備援，也會移除已知第三方廣告載入器與彈出跳轉。Yahoo 台灣相容代理會展開新版 CSS cascade layers，並補上舊版 Chromium 的 flex/grid 排版備援；這仍是實驗性相容處理，Yahoo 的新 JavaScript 功能可能無法完整運作。其他網站仍直接交由 LG 瀏覽器開啟。
- 切到「遙控器」分頁時會自動連線觸控板；離開分頁或 companion 進入背景時會暫停，回到遙控器分頁時再連線。短暫斷線後會逐步重試。
- Android companion 可送出 Wi-Fi Wake-on-LAN 封包。電視需接著電源，並開啟「一般 → Mobile TV On → Turn on via Wi-Fi」。能否喚醒仍取決於電視韌體和路由器設定，目前尚未在目標電視上實測。
- Gemini Live 可用文字或手機麥克風音訊對話，並開啟收藏網站或使用者提供的完整網址。手機也可將 Gemini API 金鑰傳送到電視保存。webOS TV v1.4.12 暫時停用 Google Search grounding。
- 影片投放適用於網頁可公開取得的 MP4/HLS 等串流。DRM、登入限制、blob URL，或需要特殊 cookie/header 的影片可能無法播放；這不是手機畫面鏡像。
- 手機和電視需在同一個區域網路。電視型號、webOS 版本和網站播放器都可能影響相容性。
- Gemini API 金鑰保存在電視 App 的本機儲存區，不會包含在 repo。手機傳送金鑰使用未加密的區網 WebSocket，請只在可信任的 Wi-Fi 使用，也不要分享含有個人金鑰的裝置資料。

### 從原始碼建置

#### Android companion

需求：JDK 17 以上、Android SDK Platform 36、Gradle 8.13。

```sh
gradle assembleDebug
```

APK 會產生於 `app/build/outputs/apk/debug/app-debug.apk`。

#### LG webOS TV App

先安裝 [webOS TV CLI](https://webostv.developer.lge.com/develop/tools/cli-installation)，然後：

```sh
cd webos-tv
mkdir -p dist
ares-package -o dist app
```

IPK 會輸出至 `webos-tv/dist/`。電視端建置與安裝說明見 [`webos-tv/README.md`](webos-tv/README.md)。

---

## English

LG TV Companion consists of an Android phone companion and an LG webOS TV app. It provides phone-based TV control, Gemini conversations, website bookmarks, URL launching, and handoff for supported video streams.

### Project contents

| Directory | Purpose |
| --- | --- |
| `app/` | Android companion: QR pairing, touchpad, text input, Gemini Live, website controls, video handoff, and TV app installation |
| `webos-tv/app/` | LG webOS TV app: website and bookmark management, Gemini conversations, phone control, and supported video playback |
| `releases/` | Android APKs, webOS IPKs, and SHA-256 checksums |

Current versions: Android companion `1.4.38` and webOS TV app `1.4.18`. The Android APK bundles the TV app IPK so it can be installed from the phone.

### Downloads

- [Android companion APK v1.4.38](releases/LGTVCompanion-Android-v1.4.38.apk) — includes webOS TV app v1.4.18; debug-signed for sideloading and not a Play Store release.
- [LG webOS TV app IPK v1.4.18](releases/com.example.hellotv_1.4.18_all.ipk)
- [SHA-256 checksums](releases/SHA256SUMS.txt)

### Getting started

1. Enable Developer Mode and Key Server on the TV. Make sure the Developer Mode session is active, then note the TV IP address and the passphrase displayed on the TV. Connect the phone and TV to the same Wi-Fi network.
2. Enter the TV IP in Android companion settings and expand **Install TV app** to load the IPK bundled in the APK. Choosing another `.ipk` keeps the bundled version available, and you can switch back at any time.
3. During installation, enter the passphrase shown in the TV's Developer Mode app and confirm the SSH host fingerprint on the first connection. Review the package name, app ID, and target TV in the confirmation dialog.
4. Open **Phone control** in the TV app and display its QR code. Scan it with Android companion, then accept the TV's control authorization prompt.
5. Set the Gemini API key in the TV app, or send it from the phone control page. After pairing, tap **Connect TV** on the phone page to launch and connect to the TV app.

TV app installation uses the Developer Mode Key Server to retrieve the SSH key (port 9991), then transfers and installs the IPK over SSH (port 9922). This is separate from QR pairing for phone control; phone control does not need to be paired first. Installation does not launch the app or switch the TV screen automatically.

### Features and limitations

- Use the phone to adjust TV volume, move the TV pointer, enter text, open URLs, manage website bookmarks, and send supported video streams to the TV. The first volume command requires permission on the TV.
- The Android APK includes the webOS TV app IPK. The installer displays installation progress reported by the TV. If no completion response arrives within two minutes, the TV may still be installing in the background; check the TV's app list before retrying.
- Android companion provides the phone-control bridge and the Gimyai and Yahoo Taiwan compatibility proxies. No separate Termux bridge is required. Android displays an ongoing notification while these services are active; do not force-stop the companion.
- The Gimyai proxy provides CSS layout fallbacks for older Chromium and removes known third-party ad loaders and popup redirects. The Yahoo Taiwan proxy unwraps modern CSS cascade layers and adds flex/grid layout fallbacks for older Chromium. This compatibility path is experimental; newer Yahoo JavaScript features may still fail. Other websites are opened by the LG browser.
- The touchpad connects when you enter the **Remote** tab, pauses when you leave the tab or the companion goes to the background, and reconnects when you return. It retries gradually after a brief disconnect.
- Android companion can send a Wi-Fi Wake-on-LAN packet. The TV must remain connected to power, and **General → Mobile TV On → Turn on via Wi-Fi** must be enabled. Wake support depends on the TV firmware and router configuration and has not been verified on the target TV.
- Gemini Live supports text and phone-microphone audio conversations and can open bookmarked sites or a full URL provided by the user. The phone can also send a Gemini API key to the TV for local storage. Google Search grounding is temporarily disabled in webOS TV v1.4.12.
- Video handoff works with publicly accessible streams such as MP4 and HLS. DRM, login requirements, blob URLs, or streams requiring special cookies or headers may not work. This is not phone screen mirroring.
- The phone and TV must be on the same local network. Compatibility may vary with the TV model, webOS version, and website player.
- Gemini API keys are stored locally by the TV app and are not included in this repository. Sending a key from the phone uses an unencrypted local-network WebSocket; use a trusted Wi-Fi network and do not share device data containing personal keys.

### Build from source

#### Android companion

Requirements: JDK 17 or later, Android SDK Platform 36, and Gradle 8.13.

```sh
gradle assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

#### LG webOS TV app

Install the [webOS TV CLI](https://webostv.developer.lge.com/develop/tools/cli-installation), then run:

```sh
cd webos-tv
mkdir -p dist
ares-package -o dist app
```

The IPK is written to `webos-tv/dist/`. See [`webos-tv/README.md`](webos-tv/README.md) for TV build and installation instructions.
