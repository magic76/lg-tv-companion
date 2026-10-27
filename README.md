# LG Video Sender

LG Video Sender is the Android companion for a custom LG webOS TV app. The phone runs the local WebSocket bridge and bundled control page; the TV app connects to the phone over Wi-Fi. The Android app does not need a separate Termux server.

此專案是 LG 電視自製 webOS app 的 Android companion。手機 App 內建區網 WebSocket bridge 和控制頁，電視端 App 會透過 Wi-Fi 連回手機；不需要另外在 Termux 啟動伺服器。

## Features / 功能

- Pair the TV by scanning its QR code, with manual TV IP entry as a fallback.
- Send text and microphone audio to Gemini Live and display its replies.
- Open a URL on the TV, manage the TV app's website bookmarks, and send supported video streams.
- Use the phone as a TV touchpad, send Back/Home buttons, and type into a focused TV text field.
- 手機掃描電視 QR code 配對，也可手動輸入電視 IP。
- 透過 Gemini Live 傳送文字或手機麥克風音訊，並顯示 AI 回覆。
- 在電視開啟網址、管理網站收藏，並傳送支援的影片串流。
- 用手機觸控板控制電視游標、送出返回／首頁，以及輸入文字。

## Requirements / 使用條件

- Android 7.0 or later (minSdk 24).
- An LG TV with the matching custom webOS companion app installed. The TV-side app is a separate project and is not included in this repository.
- Put the phone and TV on the same Wi-Fi network. Keep the Android companion app open while using the bridge.
- Android 7.0 以上。
- LG 電視需另外安裝相容的自製 webOS companion app；電視端專案不包含在此 repo。
- 手機和電視需在同一個 Wi-Fi。使用 bridge 時請讓 Android companion app 保持開啟。

## Pair and use / 配對與操作

1. Install the APK on the Android phone.
2. On the TV, open **手機控制連線** and show its QR code.
3. In LG Video Sender, choose **掃描電視 QR 並連線** and scan the TV. If scanning is unavailable, enter the TV's current IP in settings.
4. Accept the control permission prompt on the TV. Text input may request an additional permission.
5. To type, open **遙控器 → 手機鍵盤輸入**. First focus a search box or other editable field on the TV, then type on the phone and tap **傳送文字**. **Enter** is sent separately.

1. 在 Android 手機安裝 APK。
2. 電視開啟 **手機控制連線**，顯示 QR code。
3. 在 LG Video Sender 選 **掃描電視 QR 並連線**。若無法掃描，可在設定輸入電視目前的 IP。
4. 電視出現控制權限提示時按允許；文字輸入可能會另外詢問授權。
5. 要打字時開啟 **遙控器 → 手機鍵盤輸入**。先讓電視上的搜尋框或其他文字欄位取得焦點，再於手機輸入並按 **傳送文字**；Enter 需另外按。

## APK download / APK 下載

Current sideloadable APK: [LGVideoSender-v1.4.13.apk](releases/LGVideoSender-v1.4.13.apk). This is a debug-signed APK for direct installation, not a Play Store release.
SHA-256: `173ce71389dee35a231527106ba02a2a0bd01564052bb5dd14d59041f3d5ce29` (also listed in [`releases/SHA256SUMS.txt`](releases/SHA256SUMS.txt)).

目前版本：[LGVideoSender-v1.4.13.apk](releases/LGVideoSender-v1.4.13.apk)。這是供自行安裝的 debug 簽名 APK，不是 Play Store 發行版。

## Build / 編譯

Requirements: JDK 17 or later, Android SDK Platform 36, and Gradle 8.13.

```sh
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

需求：JDK 17 以上、Android SDK Platform 36、Gradle 8.13。執行上方指令後，APK 會產生於 `app/build/outputs/apk/debug/app-debug.apk`。

## Limitations / 限制

The video resolver can only pass through supported direct streams such as MP4/HLS when the page exposes them to its WebView. DRM, login-only playback, blob URLs, and streams requiring special cookies or headers may not work. Some TV apps do not expose a text field to the webOS input service.

影片解析只適用於頁面能讓 WebView 取得的 MP4/HLS 等來源。DRM、僅限登入的播放器、blob URL，或需要特殊 cookie／header 的串流可能無法播放。部分電視 App 不會把文字欄位提供給 webOS 輸入服務。
