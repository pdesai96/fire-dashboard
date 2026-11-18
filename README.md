# Fire Dashboard

A full-screen, always-on dashboard app for an old Amazon Fire tablet: a big clock, the date, the weather now, the next 24 hours, the next 5 days, and sunrise and sunset. It uses a light look by day and a dark, dimmed look at night.

Weather comes from [Open-Meteo](https://open-meteo.com), which is free for non-commercial use and needs no account or API key.

Built for the Fire HD 10 (2017, Fire OS 5). It works on any Fire or Android tablet running Android 5.1 or later.

## Install on the tablet

1. On the tablet, open **Settings > Security & Privacy** and turn on **Apps from Unknown Sources**.
2. In the Silk browser, open this repo's **Releases** page and download `fire-dashboard.apk`:
   `https://github.com/pdesai96/fire-dashboard/releases/latest/download/fire-dashboard.apk`
3. Open the download, then tap **Install**.
4. Open **Dashboard** from the home screen.

## Use

- **Tap** anywhere to refresh the weather.
- **Long-press** to open the settings. From there you can set the location (search by town, or type `lat, lon`), add a note for the footer, switch between a 12- and 24-hour clock, change units, choose the look (auto, light or dark), dim the screen at night, set the hourly spacing and how often it updates, and choose whether it opens when the tablet starts.
- The screen stays on while the app is open, so keep the tablet plugged in.

## Build

GitHub Actions builds the APK on every push to `main` and publishes it as the `latest` release. To build it yourself, run `gradle assembleRelease` (Gradle 8.14, JDK 17 and the Android SDK).

The signing key in `app/dashboard.keystore` is deliberately not secret. It only exists so that new builds install over old ones.
