# jarvis-core

The backend for JARVIS. It runs on a Raspberry Pi 3B on your home network. The Fire tablet (and any browser) talks to it, and only it talks to OpenAI, so **the API key never leaves the Pi**.

- Node.js 22.18+ / 24 running TypeScript directly, with **no runtime dependencies** (`node:http`, `node:sqlite`, `fetch`).
- OpenAI **Responses API** with function tools. The model is configurable and checked at startup.
- SQLite database in `./data`: devices, short-term chat history, audit log, usage.
- Admin console at `http://<pi-address>:8787`.

## What you need

- Raspberry Pi 3B with the official 5.1 V / 2.5 A power supply and a good microSD card. Wired Ethernet is recommended.
- An OpenAI API account with credit (platform.openai.com; this is separate from ChatGPT Plus). The minimum top-up is $5. Typical use costs about $1–2 a month. See `docs/jarvis/PLAN.md` §5.

## Install on the Pi

1. **Flash the SD card** with Raspberry Pi Imager. Choose **Raspberry Pi OS Lite (64-bit)**, and in the settings set a hostname, user, Wi-Fi and **enable SSH**.
2. **Give the Pi a fixed address.** In your router, add a DHCP reservation for the Pi (e.g. `192.168.1.50`). The tablet (Android 5) can't look up `.local` names, so it needs the IP address.
3. **Install Docker** over SSH:
   ```sh
   ssh you@192.168.1.50
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker $USER && exit   # log back in afterwards
   ```
4. **Get the code and configure it:**
   ```sh
   git clone https://github.com/pdesai96/fire-dashboard.git
   cd fire-dashboard/server
   cp .env.example .env && nano .env       # set OPENAI_API_KEY, ADMIN_PASSWORD, HOME_*
   mkdir -p data
   docker compose up -d --build
   docker compose logs -f                  # look for "listening" and "model verified"
   ```
5. Open `http://192.168.1.50:8787` on your laptop and sign in. The **System** tab should show a green model status.

**OpenAI key tips:**
- Create a *project* key, and optionally a *restricted* one (Responses: write, Models: read).
- In the OpenAI dashboard, turn off auto-recharge and set a monthly limit. `MONTHLY_BUDGET_USD` is a second, local safety net.

### Pair the tablet

1. Console → **Devices** → **Create pairing code**. Or, on the Pi: `docker compose exec jarvis node src/cli.ts pair`.
2. Tablet → dashboard **Settings** → **Connect to JARVIS**. Enter `http://192.168.1.50:8787` and the 6-digit code.
3. Tap the **JARVIS** pill on the dashboard to talk to it.

Each device gets its own token, stored hashed on the Pi. Revoke a device under **Devices** at any time.

### Update

```sh
cd ~/fire-dashboard && git pull && cd server && docker compose up -d --build
```

### Without Docker

If you have Node 22.18 or newer: `cp .env.example .env`, edit it, then `npm start`. There's nothing to install.

## Configuration

Everything is set with environment variables in `.env`. `.env.example` documents each one. Key ones:

| Variable | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | – | Required for conversation. Server-side only. |
| `OPENAI_MODEL` | `gpt-6-luna` | Any Responses API model. Checked at startup. |
| `ASSISTANT_NAME` | `JARVIS` | Name and persona |
| `ADMIN_PASSWORD` | – | 10+ characters, or the console stays disabled |
| `HOME_NAME` / `HOME_LAT` / `HOME_LON` | – | Default weather location |
| `MONTHLY_BUDGET_USD` | `5` | Model calls stop once the month's estimated spend reaches this |
| `CONVERSATION_RETENTION_HOURS` | `24` | Short-term history is deleted after this |

## API (for the tablet)

| Endpoint | Auth | |
|---|---|---|
| `GET /api/health` | none | name, version, model and model status |
| `POST /api/pair` `{code, name}` | none (rate-limited) | → `{deviceId, token}` |
| `POST /api/chat` `{message, conversationId?, channel: "text"\|"voice"}` | device bearer or admin | → `{conversationId, reply, toolsUsed, pendingAction, resolvedAction, usage}` |
| `POST /api/actions/:id/confirm` · `/cancel` | owner | confirms a pending action from a button |
| `GET`/`DELETE /api/conversations/:id` | owner | view or delete history |
| `/api/admin/*` | admin session | pairing codes, devices, audit, usage, status |

## Security model

- **Secrets:** stay in `.env` on the Pi. They are never sent to the tablet or the browser and never written to the logs or the audit trail.
- **Devices:** a single-use, 10-minute pairing code is exchanged for a random 256-bit token, stored as a SHA-256 hash. Tokens can be revoked instantly.
- **Admin console:**
  - password (scrypt-compared), with sign-in limited to 5 attempts per 15 minutes per IP;
  - HttpOnly, `SameSite=Strict` session cookie lasting 12 hours, with an Origin check on every change;
  - JSON-only request bodies;
  - a strict Content-Security-Policy, and no `innerHTML` with data.
- **Consequential actions:**
  - Tools marked `action` never run when the model calls them. They create a *pending action*.
  - Only the user's own reply ("yes"/"no", checked by code, not by the model) or a Confirm button runs it.
  - Pending actions expire after 5 minutes.
- **Prompt-injection defence:**
  - Third-party data (weather now; news and email later) is wrapped as `untrusted_external_content` with an explicit notice.
  - The persona tells the model never to follow instructions inside that data.
  - Even if the model is fooled, the confirmation gate stops it acting.
- **Audit log:** sign-ins, pairing, revocations, tool calls, actions, model errors and budget blocks. View it under **Activity**, or run `node src/cli.ts audit 50`.
- **Privacy:**
  - Chat history is short-term only, deleted after `CONVERSATION_RETENTION_HOURS`.
  - Long-term memory (Stage 5) will be explicit and under your control, with nothing saved automatically.
- **Network:** keep port 8787 on your LAN. Don't forward it on the router. For remote access later, use a VPN such as Tailscale.

## Development

```sh
npm install        # only for the type-checker (typescript, @types/node)
npm test           # node:test with a scripted model; no network or API key needed
npm run typecheck
```

`src/llm/scripted.ts` is a deterministic stand-in for the model, so the agent loop, tools, confirmation gate, auth and HTTP layer are all tested offline. CI runs the tests on Node 22 and 24 (`.github/workflows/server.yml`).
