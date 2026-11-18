# Fire Dashboard

A full-screen, always-on personal dashboard for an old Amazon Fire tablet (built for the Fire HD 10, 2017, Fire OS 5). It shows:

- **Clock and alarms.** Tap the clock to add an alarm. Alarms ring full-screen with Snooze and Stop, and after Stop the tablet reads a short morning briefing.
- **Weather for where you are.** It uses the tablet's Wi-Fi location, or a rough guess from the internet connection. Data comes from [Open-Meteo](https://open-meteo.com).
- **Manchester United.** The next match with a countdown, the live score during games, the last result and the league position (from ESPN's public data).
- **United news.** Headlines from ManUtd.com and BBC Sport. Tap a headline to read it.
- **Outlook calendar (optional).** Today's events, via your calendar's private ICS link.
- **Gradient backgrounds.** "Follow the sky" changes with the time of day. There are also Red Devils, Ocean, Aurora and more, and the screen dims at night.

## Install on the tablet (once)

1. On the tablet, open **Settings > Security & Privacy** and turn on **Apps from Unknown Sources**.
2. In Silk, open `https://github.com/pdesai96/fire-dashboard/releases/latest/download/fire-dashboard.apk`, then open the download and tap **Install**.

After that you don't need to download anything again:

- **Live changes:** colours, which cards show, news sources and similar settings live in [`live/config.json`](live/config.json). The tablet checks that file every hour.
- **New versions:** a yellow **Update ready** button appears at the top of the dashboard. Tap it, then tap **Install**.

## Use

- **Tap** anywhere to refresh. **Tap the clock** for alarms. **Tap a headline** to read it.
- **Long-press** for settings: location, background, alarms, briefing, Outlook calendar, clock and units, night dimming, start-on-boot, updates and the Alexa pairing code.

## Alexa

The tablet checks a small online mailbox (set in `live/config.json` under `relay.url`) for commands sent by a private Alexa skill, such as setting an alarm, reading the briefing or news, changing the background, or refreshing. Each command looks like `{"id": 12, "type": "alarm", "time": "07:00", "days": "weekdays", "label": "Work"}`.

## Build

GitHub Actions builds a signed APK on every push to `main` (pushes that only change `live/` are skipped) and publishes it, together with `version.json`, as the `latest` release. The signing key in `app/dashboard.keystore` is deliberately not secret. It only exists so that new builds install over old ones.
