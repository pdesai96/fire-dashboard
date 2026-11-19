# JARVIS: architecture and staged plan

Status, November 2026: **Stage 1 done**.
- `server/` is the jarvis-core backend, with 71 tests.
- The tablet can pair with the Pi and hold a text chat with spoken replies.
- Next up: Stage 2 (voice).

## 1. The existing project

This repo holds **one native Android app** written in Java for the Fire HD 10 (7th gen, Fire OS 5, Android 5.1, API 22). It has no web frontend, no React and no backend. Everything runs on the tablet.

| Area | What's there | Files |
|---|---|---|
| App shell | Full-screen activity: keeps the screen on, hides the system bars, dims at night, opens at boot | `DashboardActivity`, `BootReceiver` |
| Rendering | Custom Canvas drawing on a scalable grid (landscape 64×40, portrait 37.5×62), gradient "Follow the sky" backgrounds, translucent panels, vector weather icons | `Renderer`, `Painter`/`AndroidPainter`, `Palette`, `Icons` |
| Weather | Open-Meteo forecast (current, next 24h, 5 days, rain summary), cached | `WeatherClient`, `Forecast`, `Wx` |
| Location | Wi-Fi location, with an IP-based fallback | `Locator` |
| Manchester United | Next match, live score, last result, league position (ESPN's public JSON) | `United` |
| News | United headlines from ManUtd.com and BBC Sport RSS | `News` |
| Calendar | Outlook ICS link, with common repeat rules | `Agenda` |
| Alarms | AlarmManager alarm clock, full-screen ringing, snooze, spoken briefing | `Alarm*`, `Briefing`, `Speaker` |
| Remote control | Live config from `live/config.json`, one-tap self-updater, polling for Alexa-relay commands (relay not deployed yet) | `LiveConfig`, `Updater`, `Relay` |
| Networking | HTTP helper with modern root certificates bundled, because Fire OS 5's own list is out of date | `Net`, `res/raw/roots.pem` |
| Build | GitHub Actions builds a signed APK and publishes it as the `latest` release, along with `version.json` | `.github/workflows/build.yml` |

**What works vs. what's static.** All the cards above use live data. The only static parts are the defaults in `LiveConfig`. Nothing has an "intelligence" layer yet: no AI, no voice input, no memory and no integrations that need a sign-in.

**Not yet confirmed on the real device:** the v2 build has passed CI, the logic checks and rendering previews, but it hasn't been run on the tablet itself.

## 2. What needs to change

1. **Add a backend.** AI orchestration, API keys, memory, OAuth tokens and scheduled jobs can't live on the tablet. Keys would be extractable from the APK, and the device is too slow and too old.
2. **Turn the tablet into a "device layer".** It keeps doing what only it can do (screen, microphone, speaker, wake word, alarms) and talks to the backend for everything else.
3. **Put the assistant first on the home screen.** An assistant orb with listening, thinking and speaking states, plus a conversation strip, with the existing cards rearranged around it (Stage 2).
4. **Retire the Alexa-relay idea.** "Hey Jarvis" on the tablet replaces it, and the relay code stays dormant.

## 3. Recommended architecture

```
 Fire HD 10 (Fire OS 5)                    Raspberry Pi 3B (64-bit Pi OS, Docker)         Cloud
 ┌──────────────────────────┐   LAN HTTP   ┌───────────────────────────────────┐      ┌──────────────┐
 │ Native Android app       │◄────────────►│ jarvis-core (Node.js + TypeScript)│─────►│ OpenAI API   │
 │ - dashboard + orb UI     │  bearer      │ - agent loop, tool calling        │      │ Open-Meteo   │
 │ - wake word (Porcupine)  │  device      │ - memory (SQLite, user-controlled)│      │ ESPN, RSS    │
 │ - mic capture after wake │  token       │ - integrations (OAuth tokens)     │      │ Spotify, MS  │
 │ - plays TTS audio        │              │ - scheduler (briefings)           │      │ Graph, GitLab│
 │ - alarms, keep-awake     │              │ - audit log, admin console (web)  │      └──────────────┘
 └──────────────────────────┘              └───────────────────────────────────┘
                                           ▲ admin console in any browser on the LAN
```

**Why the tablet stays native and doesn't become a web/PWA app:**
- Fire OS 5 has no Google services, so there's no system speech recogniser. The browser Web Speech API isn't usable there.
- The microphone, audio playback and keeping the screen awake are dependable natively on Android 5.1.
- The existing dashboard already works natively. Rewriting it in React would throw away tested work, and its rendering speed on a 2017 WebView is unknown.
- React/TypeScript still has a home: the backend is TypeScript, and the admin console (memories, integrations, audit log, OAuth sign-ins) can grow into a React app served by the Pi. That's the right place for it, because OAuth sign-ins need a full, modern browser anyway.

**Backend choices:**
- **Node.js 22/24 + TypeScript, zero runtime dependencies.** Node runs `.ts` files directly (type stripping), stores data with the built-in `node:sqlite` and calls OpenAI with `fetch`. There are no native builds on the Pi and no supply-chain sprawl. TypeScript type-checking runs in CI.
- **SQLite on the Pi** for conversations, memories, audit and integration tokens: one file, easy to back up, private. The storage layer sits behind a small module, so moving to Postgres later is contained.
- **OpenAI Responses API** with function tools, behind a provider interface. The model is set by `OPENAI_MODEL` (default `gpt-6-luna`, the efficient tier) and checked at startup. A scripted provider makes tests deterministic and keeps the app runnable without a key.
- **Docker Compose** on 64-bit Raspberry Pi OS Lite. The image is just `node:24-slim` plus the source, so it builds quickly on the Pi.

**AI design:**
- **Persona:** calm, concise, dry wit, British English, spoken-friendly when the channel is voice. The assistant name is configurable.
- **Tools** carry a risk level. `read` tools run freely. `action` tools (reminders, music, email) need explicit user confirmation, done in code rather than by trusting the model.
- **External content** (news, email, web pages) is wrapped as untrusted data. The system prompt tells the model never to follow instructions found in it, and confirmation gates stop it triggering actions.
- **Conversation context** is short-term: recent turns per conversation. **Long-term memory** is separate and explicit (Stage 5): saved only when you ask, with sensitive items confirmed first, and viewable, editable and deletable in the console.

## 4. Stages (ordered by value)

| Stage | Delivers | Notes |
|---|---|---|
| **1. Foundation** (now) | jarvis-core with an agent loop, tool calling, conversation history, persona, audit log, admin console, device pairing, first tools (time, weather). The tablet pairs with the Pi and you can type to JARVIS, which speaks replies with the device voice. | Runs on the Pi via Docker. Tests and CI. |
| **2. Voice** | Mic button, then the "Hey Jarvis" wake word, with orb states (idle, listening, thinking, speaking), barge-in to interrupt and a follow-up window. New home screen with the assistant first. | See "Voice design" below. Picovoice is no longer an option. |
| **3. Information** | United correspondent (news, transfers, injuries, fixtures, table), world/UK/tech/AI news with source and opinion labels and links, weather Q&A with alerts, morning/evening briefings. | Uses RSS from reliable outlets and ESPN data. Briefings are generated on the Pi. |
| **4. Integrations** | Outlook/Google calendar (OAuth), reminders and tasks, email summaries (read-only first), Spotify control. | OAuth flows run in the admin console. Tokens stay on the Pi, encrypted. |
| **5. Memory** | Persistent categorised memories (personal, professional, projects, preferences), retrieval in answers, console view/edit/delete, "ask before saving sensitive". | SQLite with full-text search first. Embeddings only if needed. |
| **6. Automation** | Scheduled briefings, notifications with quiet hours, break reminders, GitLab pipelines, MRs and Playwright result summaries. | Every proactive behaviour is configurable. GitLab only with a token you supply, and only if your employer allows it. |

### Voice design (changed after research, November 2026)

- **Wake word:** Picovoice ended its free tier on 30 June 2026, and Porcupine won't run without a paid key, so it's out. Use **openWakeWord** instead. It's open-source, ships a pre-trained `hey_jarvis` model and runs on a single Raspberry Pi 3 core. It runs in a small sidecar container on the Pi. The tablet streams 16 kHz mic audio **over your home network to the Pi only** (about 32 KB/s), and nothing goes to the cloud until "Hey Jarvis" is detected. After that, only the request itself is sent for transcription.
- **Speech-to-text:** `gpt-transcribe` at $0.0045 per minute. The `gpt-4o-*-transcribe` models are deprecated.
- **Spoken replies:** OpenAI retires all of its `/v1/audio/speech` voices on 6 January 2027, and the named replacement (`gpt-realtime-2.1-mini`) works only over the Realtime API. Stage 2 will support two voices: the tablet's own Android voice (free, offline, robotic) and a premium OpenAI Realtime voice (natural, costs more; see costs below), switchable in settings.

## 5. What you'll need to buy

**Already owned:** Fire HD 10 (7th gen), Raspberry Pi 3B.

### Needed for Stage 1

| Item | Why | Cost |
|---|---|---|
| OpenAI API credit (platform.openai.com, separate from ChatGPT Plus) | The assistant's brain. Prepaid. | Minimum top-up $5. Credits expire after 1 year. Set a monthly limit and turn off auto-reload. |
| Official Raspberry Pi 5.1V 2.5A micro-USB power supply *(skip if you already have it)* | Phone chargers cause undervoltage on the Pi 3B: random crashes and corrupted SD cards. | £7.70 (The Pi Hut) |
| Official Raspberry Pi A2 microSD card, 32–64 GB *(skip if you have a good one)* | The OS, Docker and the JARVIS database live on it. Cheap cards fail early. | from about £10 (The Pi Hut) |

### Strongly recommended

| Item | Why | Approx. cost |
|---|---|---|
| Ethernet cable from the Pi to the router | The Pi 3B only has 2.4 GHz Wi-Fi. Wired is steadier for audio streaming in Stage 2. | £3–6 |
| Wi-Fi smart plug for the tablet charger (e.g. TP-Link Tapo P100) | An always-on tablet sitting at 100% charge risks battery swelling, and Fire OS has no charge limit. A schedule (e.g. 2 hours on, 4 hours off) keeps it cycling. | from about £8 |
| Pi 3B case with heatsinks | It will run 24/7. The Pi throttles when hot. | £5–10 |
| Tablet stand or wall mount for the Fire HD 10 | Desk or wall dashboard | £10–25 |

### Only if you want that feature

| Feature | What's needed | Cost |
|---|---|---|
| Spotify control (Stage 4) | **Spotify Premium.** Since February 2026, Spotify's developer mode requires the app owner to have Premium. Playback goes to an existing Connect device (Echo, phone, PC), or to the Pi itself with `raspotify` and a speaker plugged in. | £12.99/month (if you don't already have it) |
| Premium voice for replies (Stage 2) | Nothing to buy. It costs more API credit (see below). | about $6/month at 40 spoken replies/day |
| Better far-field listening | USB speakerphone or mic on the Pi, only if the tablet's mics struggle from across the room. Test first. | £20–60 |

### Not needed

- Picovoice licence: openWakeWord is free.
- A domain name or HTTPS certificate: Spotify accepts `http://127.0.0.1` loopback redirects, so the one-off sign-in runs through an SSH tunnel to the Pi. Microsoft allows `localhost`.
- An Alexa developer account or Neon: replaced by JARVIS's own voice.
- A newer Raspberry Pi: the 3B is enough, because all AI runs in the cloud.

### Estimated monthly running cost (assumes 40 exchanges per day)

| Part | Basis | Per month |
|---|---|---|
| Conversation (`gpt-6-luna`) | about 4k tokens in and 300 out per exchange, at $0.10/$0.50 per million tokens | about $0.70 |
| Two daily briefings (Stage 3) | about 20k tokens each | about $0.15 |
| Speech-to-text (`gpt-transcribe`) | 6 s per request, at $0.0045 per minute | about $0.55 |
| Premium voice replies (optional) | 12 s per reply via `gpt-realtime-2.1-mini` audio output at $20 per million tokens (assumes ~1,250 audio tokens per minute; to be measured) | about $6 |
| **Total** | | **about $1.50 with the tablet voice, about $7.50 with the premium voice** |

The backend records the token usage of every call and enforces `MONTHLY_BUDGET_USD`, so a runaway loop can't drain your credit.

## 6. Fire HD 10 (7th gen) compatibility

| Issue | Impact | Plan |
|---|---|---|
| Android 5.1 (API 22), no Google services | No system speech-to-text. Modern web APIs are unreliable. | Native audio capture → backend transcription. Device TTS as a fallback voice. |
| One app at a time can use the mic (before Android 10) | Alexa hands-free and "Hey Jarvis" will fight over the microphone. | Turn off Alexa hands-free when using the wake word, or use only the mic button. |
| Spotify's Android app needs Android 7.0+ | The tablet can't be a Spotify Connect speaker. | Control another device (Echo, phone, PC) through the Web API, or run raspotify on the Pi with speakers. Playback control needs Premium. |
| Old certificate store | HTTPS to modern sites fails. | Bundled roots (done). The LAN link to the Pi is HTTP with bearer tokens, and must not be exposed to the internet. |
| No mDNS (`.local`) name lookup in Android 5 | The tablet can't find `jarvis.local`. | Give the Pi a fixed IP (DHCP reservation) and enter that IP when pairing. |
| 2 GB RAM, 2017 CPU | Heavy UI or on-device models struggle. | Keep the tablet thin. All AI runs in the cloud, orchestrated by the Pi. |
| Raspberry Pi 3B (1 GB RAM) | Can't run LLMs. Builds are slow. | No local models. Zero-dependency Node service. Use the 64-bit OS. |

## 7. Security baseline

- API keys exist only in the Pi's `.env` file, never in the APK or the browser.
- The admin console uses a password with an HttpOnly, SameSite=Strict session cookie and Origin checks.
- Each device gets its own revocable token, stored hashed and issued through a short-lived pairing code.
- Every tool call and admin action is written to an audit log.
- Actions with consequences need confirmation, enforced server-side.
- External content is treated as data, not instructions.
- Integrations are opt-in, with tokens on the Pi that can be revoked.
