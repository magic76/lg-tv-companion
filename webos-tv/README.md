# LG webOS TV App

This directory contains the webOS app source used for the v1.4.18 IPK in the repository's `releases/` directory. The packaged application files are in `app/`.

Version 1.4.18 routes Gimyai and `tw.yahoo.com` pages through the paired Android companion's site-specific compatibility proxies when available. Yahoo CSS cascade layers are expanded and legacy flex/grid fallbacks are added for Chromium 53; other sites continue to open directly in the LG browser. It also keeps the existing `fullMode: false` browser launch parameter.

## Build

Install the [webOS TV CLI](https://webostv.developer.lge.com/develop/tools/cli-installation), then run:

```sh
mkdir -p dist
ares-package -o dist app
```

The package is written to `dist/com.example.hellotv_1.4.18_all.ipk`.

## Install on a TV

Enable Developer Mode on the TV, configure it with `ares-setup-device`, then install and launch the package. Replace `TV_DEVICE` with the device name configured in the CLI:

```sh
ares-install -d TV_DEVICE ../releases/com.example.hellotv_1.4.18_all.ipk
ares-launch -d TV_DEVICE com.example.hellotv
```

Keep the Android companion app open while using phone control. It hosts the local WebSocket bridge; the TV app connects to it over Wi-Fi. Both devices must be on the same network.

The TV app uses its built-in webOS services for system integration. Gemini API keys can be entered in the app or sent from the Android companion, then stored locally on the TV; no personal key is included in the source or IPK. Phone-to-TV key transfer uses an unencrypted local WebSocket, so only use it on a trusted network.
