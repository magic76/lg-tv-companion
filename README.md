# LG TV Companion

適用於 LG webOS 電視的手機 companion 與電視 App，整合手機遙控、Gemini 對話、網站開啟和支援格式的影片投放。Android 與 webOS 兩端的原始碼和安裝檔都在這個 repo。

## 專案內容

| 目錄 | 用途 |
| --- | --- |
| `app/` | Android companion：QR 配對、觸控板、文字輸入、Gemini Live、網站控制與影片串流傳送 |
| `webos-tv/app/` | LG webOS 電視 App：網站與收藏管理、Gemini 對話、接收手機控制和支援的影片串流 |
| `releases/` | Android APK、webOS IPK 與 SHA-256 校驗檔 |

目前安裝檔版本分開管理：Android `1.4.13`，webOS TV `1.4.8`。

## 下載

- [Android companion APK v1.4.13](releases/LGTVCompanion-Android-v1.4.13.apk) — debug 簽名，供自行安裝，不是 Play Store 發行版。
- [LG webOS TV IPK v1.4.8](releases/com.example.hellotv_1.4.8_all.ipk)
- [SHA-256 校驗檔](releases/SHA256SUMS.txt)

## 初次設定

1. 在 LG 電視啟用 Developer Mode，使用 webOS TV CLI 安裝 IPK；安裝範例見 [`webos-tv/README.md`](webos-tv/README.md)。
2. Android 手機安裝 APK，手機和電視連到同一個 Wi-Fi。
3. 開啟電視 App 的「手機控制連線」並顯示 QR code；在 Android companion 掃描配對。電視出現控制授權提示時允許。
4. 在電視 App 設定 Gemini API 金鑰，即可使用 Gemini 對話。手機和電視不需要另外安裝或啟動 Termux bridge；使用手機控制時，Android companion 必須保持開啟。

## 功能與限制

- 手機可控制電視游標、輸入文字、開啟網址、編輯網站收藏，也能把支援的影片串流交給電視播放。
- Gemini Live 可在電視上對話；手機 companion 可傳送文字或手機麥克風音訊，電視畫面會顯示回覆。
- 影片投放適用於網頁能公開取得的 MP4/HLS 等串流。DRM、登入限制、blob URL 或依賴特殊 cookie/header 的影片可能無法播放。這不是手機全螢幕鏡像功能。
- 手機與電視需要在同一個區域網路。電視型號、webOS 版本和網站播放器可能影響相容性。
- Gemini 金鑰由使用者在電視 App 輸入並保存在電視 App 的本機儲存區；它不在 repo 內。用戶端金鑰可能被取出，請勿把含有個人金鑰的 App 或裝置資料分享出去。

## 從原始碼建置

### Android companion

需求：JDK 17 以上、Android SDK Platform 36、Gradle 8.13。

```sh
gradle assembleDebug
```

APK 會產生於 `app/build/outputs/apk/debug/app-debug.apk`。

### LG webOS TV App

先安裝 [webOS TV CLI](https://webostv.developer.lge.com/develop/tools/cli-installation)，然後：

```sh
cd webos-tv
mkdir -p dist
ares-package -o dist app
```

IPK 會輸出至 `webos-tv/dist/`。電視端建置、安裝指令和 Developer Mode 說明見 [`webos-tv/README.md`](webos-tv/README.md)。

---

## English

LG TV Companion is a two-part project for LG webOS TVs. The Android app provides QR pairing, touchpad and text input, Gemini Live controls, website management, and supported video stream handoff. The webOS app provides the TV interface and receives phone control over the local network.

Install the webOS IPK and Android APK, connect both devices to the same Wi-Fi, then pair by scanning the QR code shown in the TV app. The Android companion hosts the local bridge, so keep it open while using phone control. Gemini API keys are entered and stored locally on the TV; they are not included in this repository.
