# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Java library for talking to [Tuya](https://www.tuya.com/) smart devices on the local LAN (modelled on Node.js [tuyapi](https://github.com/codetheweb/tuyapi)) and over Tuya's Cloud OpenAPI, plus a Spring Boot reference server and a Vite/React UI for managing and controlling registered devices.

Motivating device: a Tuya-based pool pump (smart relay) — but the library is generic and works with any Tuya device that supports the LAN protocol (v3.1 / v3.3 / v3.4 / v3.5).

## Stack & versions

- **Library** (`library/`): pure Java 21, no Spring deps. Maven module published as `com.hitorro:tuya-library:0.0.1-SNAPSHOT`.
- **Server** (`server/`): Spring Boot 3.4.x, Java 21, depends on `library/`. H2 in file mode at `./data/tuya`, Spring Data JPA, Flyway migrations.
- **UI** (`ui/`): Vite + React + TypeScript. Live state over WebSocket from the server.
- **Group**: `com.hitorro`. Java 21 from Homebrew `openjdk@21`. Maven 3.9+.

## Repository layout

```
tuya_lib/
├── CLAUDE.md
├── README.md
├── pom.xml                                  # parent pom (multi-module)
├── local.env.example                        # template for local.env (gitignored)
├── library/                                 # pure Java library
│   ├── pom.xml
│   └── src/main/java/com/hitorro/tuya/
│       ├── protocol/                        # frame codec, CRC32, command codes
│       ├── crypto/                          # AES-ECB (v3.3), GCM (v3.5) helpers
│       ├── client/                          # TuyaDevice (TCP), discovery (UDP)
│       ├── cloud/                           # TuyaCloudClient (OpenAPI REST)
│       └── model/                           # DeviceConfig, DpsState, ProtocolVersion
│   └── src/test/...                         # JUnit 5 codec tests
└── server/                                  # Spring Boot reference server
│   ├── pom.xml
│   └── src/main/java/com/hitorro/tuya/server/
│       ├── TuyaServerApplication.java
│       ├── api/                             # REST + WebSocket controllers
│       ├── device/                          # DeviceManager, DeviceEntity, repo
│       └── config/
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/V1__init.sql
└── ui/                                      # Vite + React + TS
    ├── package.json
    └── src/
```

## Local config & secrets

Secrets in **`local.env`** at the repo root (gitignored). Template in `local.env.example`. Variables:

- `TUYA_CLOUD_ACCESS_ID` / `TUYA_CLOUD_ACCESS_SECRET` — for the cloud client (from iot.tuya.com → Cloud → Projects). Leave blank if you only use the LAN path.
- `TUYA_CLOUD_REGION` — one of `us`, `eu`, `cn`, `in`. Default `us`.
- `DB_URL` — JDBC URL for H2 (default `jdbc:h2:file:./data/tuya`).

Device-level secrets (gwId, localKey, IP) live in the H2 database, not in env vars — they're registered through the UI / REST API.

`application.yml` references env vars as `${TUYA_CLOUD_ACCESS_ID:}` etc. Never hard-code keys; never paste a localKey into a committed file (including CLAUDE.md or comments).

## Build & run

```bash
# Library tests (no device required — codec uses fixed vectors)
mvn -pl library test

# Server (uses system mvn)
set -a && source local.env && set +a       # export env vars if you have cloud creds
mvn -pl server spring-boot:run -am          # -am builds dependencies (library) first

# UI
cd ui
npm install
npm run dev          # http://localhost:5173, proxies /api and /ws to :8080
npm run build
```

## Protocol notes (v3.3)

Source of truth: `library/src/main/java/com/hitorro/tuya/protocol/`. Key facts:

- TCP port 6668. UDP discovery on 6666 (v3.1 plaintext) and 6667 (v3.3+ AES with key `md5("yGAdlopoPVldABfn")`).
- Frame: prefix `0x000055AA` · seq(4) · cmd(4) · payloadLength(4) · payload · CRC32(4) · suffix `0x0000AA55`. `payloadLength` covers everything after itself, i.e. payload + CRC + suffix.
- CRC32 scope: bytes from prefix through end-of-payload.
- v3.3 payloads are AES-128-ECB encrypted with the 16-byte ASCII `localKey` as the key. PKCS#7 padding.
- For SET (`CONTROL=7`), the encrypted JSON is prepended with the literal ASCII `"3.3"` followed by 12 zero bytes (15 bytes total header) before being placed in the frame. For GET (`DP_QUERY=10`), the version header is **omitted**.
- Responses from the device contain a 4-byte return code immediately before the payload. On decode, strip the 15-byte `"3.3"` header if present.
- Heartbeat (`HEART_BEAT=9`) has an empty payload; expected reply is the same command with empty payload.
- Newer protocols: v3.4 uses HMAC-SHA256 instead of CRC and negotiates a session key on connect; v3.5 uses AES-GCM. Stubbed for later.

## Architecture notes

### Library layering

The library has zero framework dependencies — no Spring, no Lombok, no Jackson (JSON via a minimal `JsonCodec` wrapper to keep it swappable). Server module brings Jackson via Spring Boot.

`TuyaDevice` owns a single long-lived `SocketChannel` to the device. Each request gets a monotonically increasing sequence number; replies are matched back via a `Map<Integer, CompletableFuture<TuyaMessage>>`. A scheduled executor sends a heartbeat every 10s. On socket failure, reconnect with exponential backoff (1s, 2s, 4s, capped at 30s).

### Server / device lifecycle

`DeviceManager` is a singleton (`@Component`) that holds one `TuyaDevice` per registered device. On app startup it reads devices from the DB and opens connections; on `POST /api/devices` it persists and connects. State changes (push from device or polled `get()`) are broadcast over WebSocket `/ws/devices` as `{deviceId, dps, ts}` JSON messages.

### Persistence

H2 file mode (`jdbc:h2:file:./data/tuya`) via Spring Data JPA + Hibernate. Schema is owned by Flyway migrations under `server/src/main/resources/db/migration/`. `spring.jpa.hibernate.ddl-auto=validate` so Hibernate cross-checks entities against the migrated schema at startup.

### UI

Vite dev server on `:5173` with proxy entries for `/api` → `localhost:8080` and `/ws` → `ws://localhost:8080`. Pages: device list with live status badges; add-device form (gwId, localKey, IP, protocolVersion, name, optional JSON dps-schema); per-device control panel rendering DP controls from the schema (toggle / slider / select).

## Conventions

- Package root: `com.hitorro.tuya` (library) and `com.hitorro.tuya.server` (server).
- Tests use JUnit 5 + AssertJ (already on Spring Boot's test starter; library declares them explicitly).
- Maven test commands: `mvn -pl library test -Dtest=FrameCodecTest`, single method `-Dtest=FrameCodecTest#decodesDpQueryResponse`.
- Never log local keys or device IDs at INFO; use DEBUG and mask first/last 4 chars.
