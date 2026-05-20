# tuya_lib

A pure-Java library for talking to [Tuya](https://www.tuya.com/) smart devices over the local LAN (modelled on Node.js [tuyapi](https://github.com/codetheweb/tuyapi)) and over Tuya's Cloud OpenAPI, plus a Spring Boot reference server and a Vite/React UI for registering and controlling them.

The library is generic — any Tuya device that speaks the v3.3 LAN protocol works. A pool-pump DP schema template ships in the UI as a convenience.

## Layout

```
tuya_lib/
├── library/                pure-Java library (no framework deps)
├── server/                 Spring Boot 3.4.x reference server
└── ui/                     Vite + React + TypeScript
```

See [CLAUDE.md](./CLAUDE.md) for the protocol details and architecture notes.

## Build & run

```bash
# 1. Build & test the library
mvn -pl library test

# 2. Run the Spring Boot server
cp local.env.example local.env             # edit if you have cloud creds
set -a && source local.env && set +a
mvn -pl server spring-boot:run -am
# server listens on http://localhost:8082
# H2 DB lives at ./data/tuya
# WebSocket at ws://localhost:8082/ws/devices

# 3. Run the UI (separate terminal)
cd ui
npm install
npm run dev
# UI on http://localhost:5174, proxies /api and /ws to :8082
```

## Registering a device

You need three things per device. Click **+ Add device** in the UI and fill them in:

1. **Device ID (gwId)** — typically a 22-character hex string.
2. **Local key** — 16 ASCII characters; this is the AES key.
3. **LAN IP** — e.g. `192.168.1.42`. Static-DHCP-reserve it on your router.

### Getting the local key

The local key isn't broadcast and the device won't tell you. Easiest method:

1. Register a free developer account at https://iot.tuya.com.
2. Create a Cloud → Development → Project (any data center; pick the one closest to you).
3. Link your Smart Life / Tuya app account: project → Devices → Link App Account → scan the QR code from your app.
4. The project's device list now shows every device on that app account, with **Device ID** and **Local Key** columns. Copy them.

For Tuya Cloud API usage, the same project page gives you `Access ID` and `Access Secret` — paste those into `local.env` as `TUYA_CLOUD_ACCESS_ID` / `TUYA_CLOUD_ACCESS_SECRET`. Set `TUYA_CLOUD_REGION=us|eu|cn|in` to match your project's data center.

### DP schema (optional)

Each Tuya device exposes a numbered set of "Data Points" — DP `1` is usually the main power switch (bool), other indexes are model-specific. The UI renders generic toggles/sliders if you describe them via a JSON schema. Example for a typical pool-pump relay:

```json
{
  "pool_pump": true,
  "entries": [
    { "index": "1", "label": "Power", "kind": "bool" },
    { "index": "2", "label": "Countdown (s)", "kind": "number", "min": 0, "max": 86400, "unit": "s" }
  ]
}
```

Click **Load pool pump template** in the add-device form to start from this. Adjust to your specific model — DPs vary widely. Without a schema, the card just shows the raw JSON.

## MCP server (for Claude Code)

There's a Model Context Protocol server in `mcp/` that wraps the REST API so Claude Code (or any MCP client) can control devices with tools.

```bash
cd mcp
npm install
npm run build
```

The project ships with a `.mcp.json` at the repo root that registers the server with Claude Code project-scoped. When you start `claude` from this directory, you'll be prompted once to approve the server. Then these tools become available:

- `tuya_list_devices`, `tuya_get_state`, `tuya_set_dp` — local device control
- `tuya_register_local`, `tuya_import_from_cloud`, `tuya_delete_device` — manage registrations
- `tuya_list_cloud_devices`, `tuya_cloud_status`, `tuya_test_cloud` — cloud integration

Override the server URL via `TUYA_SERVER_URL` env var if it isn't on `localhost:8082`.

## What's implemented

- **Library**
  - v3.3 frame codec (encode/decode), AES-128-ECB, CRC32, version-header handling
  - `TuyaDevice` long-lived TCP client with heartbeat, sequence-correlated futures, async state push
  - `TuyaDiscovery` UDP listener on 6666 (plaintext) and 6667 (encrypted)
  - `TuyaCloudClient` thin HMAC-SHA256 signed REST client (US/EU/CN/IN regions)
- **Server**
  - REST: `GET/POST/PUT/DELETE /api/devices`, `GET /api/devices/{id}/state`, `POST /api/devices/{id}/dps`
  - Cloud passthrough: `/api/cloud/users/{userId}/devices`, `/api/cloud/devices/{id}/status`, `POST /api/cloud/devices/{id}/commands`
  - WebSocket `/ws/devices` broadcasting state changes
  - H2 file mode + Flyway + JPA
- **UI**
  - Device list with live online/state badges via WebSocket
  - Add/edit/delete forms
  - Generic DP controls (toggle / number / enum / string) driven by per-device schema
  - Pool-pump schema template

## What's not implemented yet

- v3.1, v3.4, v3.5 protocols (the codec is structured to add them; only v3.3 is wired up)
- Auto-discovery → click-to-register from the UI (the library finds devices, but the UI doesn't surface them yet)
- Device-online polling / reconnect backoff loop (single connect on startup; reconnect happens on the next request)
- Auth on the server REST API (intended for trusted LAN only)
