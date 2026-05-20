# LX Pool Pump — Lingxiao Swimming Pool Pump

What we learned about this device from cloud shadow inspection and motor-on / motor-off diffing. Behavior notes are specific to the firmware on the unit at `gwId: ebdb751a96daf92311pbix` but the DP layout is shared with all Lingxiao pumps using product family `rbdhe569hfxrpcwk`.

## Identity

| Field | Value |
|---|---|
| Product | Swimming pool pump (LINGXIAO) |
| Product ID | `rbdhe569hfxrpcwk` |
| Tuya category | `qt` |
| Wire protocol | Tuya v3.5 |
| LAN control | Not yet supported (v3.5 session-key handshake unimplemented) |
| Cloud control | Working via Tuya OpenAPI `/v2.0/cloud/thing/{id}/shadow/properties[/issue]` |

## Data point catalog

DP IDs cluster in the `100-136` range. The cloud's `/model` endpoint reports access mode (`rw`, `ro`, `wr`), value type, and ranges; the table below comes directly from that.

### Writable (rw) — control points

| DP | Code | Type | Range / values | Purpose |
|---:|---|---|---|---|
| 101 | `pump` | bool | true/false | Main pump relay. Required-but-not-sufficient for the motor to spin. |
| 102 | `switch` | bool | true/false | Secondary switch. Function not fully characterised. |
| 105 | `stg_num` | enum | `1`, `2`, `3` | Active stage in the staged-schedule system. |
| 106 | `stg_speed` | value | 1–3450 | Stored speed for the currently-active stage. |
| 108 | `stg_rev` | value | 1–3 | Stage revision/version index. |
| 109 | `clean` | bool | true/false | **Clean mode trigger. Toggling true is the reliable way to start the motor.** Motor runs at `clean_speed`. |
| 110 | `prim_time` | value | 0–10 (min) | Priming duration. |
| 111 | `press` | bool | true/false | Pressure / prime mode. |
| 112 | `speed_press` | value | 1500–2900 | Speed used during pressure/prime cycle. |
| 114 | `stg_flow_l` | value | 0–999 | Flow target (low?) per stage. |
| 117 | `stg_start_hour` | value | 0–23 | Stage daily start hour. |
| 118 | `stg_start_min` | value | 0–59 | Stage start minute. |
| 119 | `stg_stop_hour` | value | 0–23 | Stage daily stop hour. |
| 120 | `stg_stop_min` | value | 0–59 | Stage stop minute. |
| 121 | `isspeed` | bool | true/false | "Manual-speed mode" flag. Flipped to true by Clean mode automatically. |
| 122 | `ismode` | bool | true/false | "Mode override" flag. Function not fully characterised. |
| 123 | `stg_flow` | value | 0–264 | Flow target per stage. |
| 124 | `prim_speed` | value | 1500–3450 | Speed used while priming. |
| 125 | `flow_unit` | enum | `1`, `2` | Display unit for flow. |
| 126 | `clear_time` | value | 0–60 (min) | Clean-cycle duration. |
| 128 | `stg_percent` | value | 10–100 | Stage completion percent. |
| 133 | `clean_speed` | value | **1500–3450** | **Speed used during Clean mode. Most useful "set the pump speed" knob.** |
| 136 | `set_speed` | value | **345–3450** | Target for manual-speed mode (which is gated by an unknown flag combination). |

### Read-only (ro) — sensors

| DP | Code | Type | Range | Notes |
|---:|---|---|---|---|
| 103 | `speed` | value | 0–3450 | Measured motor RPM. 0 when stopped. |
| 104 | `power` | value | 0–9999 | Current draw in watts. |
| 107 | `stg_info` | value | 0–999 | Stage info. |
| 113 | `flow` | value | 0–999 | Measured flow rate. |
| 115 | `run_time` | value | 0–99999 | Lifetime run-time accumulator. |
| 127 | `style` | enum | `1`/`2`/`3` | Operating mode the firmware reports itself in. Mirrors active configuration; `2` = staged, others not fully characterised. |
| 129 | `max_val` | value | 1–3450 | Max-speed capability indicator. |
| 130 | `prim_second` | value | 0–999 | Elapsed prime time. |
| 131 | `clean_second` | value | 0–9999 | Elapsed clean time. |
| 132 | `error_info` | string | maxlen 255 | Error message. Empty padded with spaces in normal operation. |

### Write-only (wr)

| DP | Code | Type | Range | Notes |
|---:|---|---|---|---|
| 116 | `init` | value | 0–1 | Initialise / reset. Not exercised. |

## Operating modes

### Clean mode — verified working

The single most reliable way to spin the motor is to set `clean = true`. The pump runs in two phases:

1. **Prime phase** — first `prim_time` minutes (default 10). The motor runs at the speed stored in **`prim_speed`** (DP 124, range 1500–3450, default 3450). The pump uses this to push air out and establish flow through the filter.
2. **Main clean phase** — after `prim_time` elapses, the motor drops to the speed stored in **`clean_speed`** (DP 133, range 1500–3450). This is the steady-state cleaning speed.

The current elapsed prime time is reported in `prim_second` (read-only seconds, 0–999).

When `clean` transitions false → true the pump also automatically:
- Forces `pump = true` (closes main relay) if not already
- Sets `isspeed = true`
- Switches the active stage to `1` (`stg_num = "1"`, `stg_speed` follows whatever stage 1 has stored, observed 1690 during testing)

**Live retune**: writing `prim_speed` while the motor is in the prime phase changes the motor RPM within a few seconds (observed 3450 → 2408 → 2340 over ~12 s with power dropping from 1850 W to 675 W). Writing `clean_speed` during the prime phase only affects the speed *after* the prime phase completes — to retune the running motor, write `prim_speed` (or both).

Stopping: set `clean = false` and the motor spins down.

### Staged / scheduled mode — default

When `clean = false` and no manual flags are engaged, the pump runs on a stage schedule:

- Three stages (`stg_num` = `1`, `2`, or `3`).
- Each stage has stored `stg_speed`, `stg_flow`, and a daily window defined by `stg_start_hour:stg_start_min` and `stg_stop_hour:stg_stop_min`.
- During the active window, motor runs at the stage's stored `stg_speed`. Outside the window, motor is off.
- The current installation has stage 1 running 10:00–20:00 at speed 1690, stage 2 running 8:00–10:00 at speed 45.

To switch stages without Clean mode: set `stg_num`. Editing `stg_speed` while a stage is active retunes its speed (not yet verified for all stages).

### Manual speed mode — partial

`set_speed` (DP 136) is writable in the range 345–3450, but writing it alone does not start the motor. Setting `isspeed = true` and `ismode = true` in combination with `set_speed` did not start the motor either; observed `style` remained at `"2"` (staged). The exact flag combination the official app uses for "free manual speed" is not yet captured. To pin it down, start the pump from the Smart Life app in manual-speed mode and diff `/v2.0/cloud/thing/{id}/shadow/properties` against a stopped baseline.

### Pressure / prime mode — not verified

`press` (DP 111, bool) plus `speed_press` (1500–2900) and `prim_speed` (1500–3450) suggest a prime-and-pressurise routine, likely with a `prim_time`-bounded ramp. Not exercised.

## Empirical motor-start fingerprint

Diff between a motor-off baseline and the motor running at 3450 RPM after pressing "Clean" in the Smart Life app:

| DP | Code | Off | On | Direction |
|---:|---|---|---|---|
| 109 | `clean` | false | **true** | rw, user toggled |
| 121 | `isspeed` | false | **true** | rw, app set as part of Clean |
| 105 | `stg_num` | `"2"` | `"1"` | rw, app set as part of Clean |
| 106 | `stg_speed` | 45 | 1690 | rw, follows `stg_num` change |
| 117 | `stg_start_hour` | 10 | 8 | rw, follows stage switch |
| 119 | `stg_stop_hour` | 20 | 10 | rw, follows stage switch |
| 128 | `stg_percent` | 33 | 48 | ro, sensor |
| 129 | `max_val` | 134 | 3450 | ro, capability widened |
| 130 | `prim_second` | 0 | 575 | ro, prime timer |
| 103 | `speed` | 0 | 3450 | ro, motor RPM |
| 104 | `power` | 0 | 1871 | ro, watts |
| 113 | `flow` | 0 | 110 | ro, GPM |

The single user-driven trigger is **DP 109 `clean = true`**.

## Controlling via this server

### REST

```bash
# Start the pump (Clean mode at clean_speed)
curl -X POST http://localhost:8082/api/devices/{id}/dps \
     -H 'Content-Type: application/json' \
     -d '{"dps":{"109":true}}'

# Stop
curl -X POST http://localhost:8082/api/devices/{id}/dps \
     -H 'Content-Type: application/json' \
     -d '{"dps":{"109":false}}'

# Change running speed (1500-3450)
curl -X POST http://localhost:8082/api/devices/{id}/dps \
     -H 'Content-Type: application/json' \
     -d '{"dps":{"133":2400}}'

# Snapshot current state
curl http://localhost:8082/api/devices/{id}/state
```

### MCP tools

The same operations are available to Claude Code via the `tuya` MCP server:

- `tuya_set_dp` with `{"deviceId": <id>, "dps": {"109": true}}` — start
- `tuya_set_dp` with `{"deviceId": <id>, "dps": {"109": false}}` — stop
- `tuya_set_dp` with `{"deviceId": <id>, "dps": {"133": 2400}}` — set speed
- `tuya_get_state` — read

### UI

The device card auto-detects pool pumps (by the presence of `pump`, `clean`, `clean_speed`, `speed` codes in the schema) and renders a compact dashboard at the top of the card: live RPM / Watts / Flow readouts, a single Start/Stop button bound to `clean`, and a **Target speed** slider that writes both `prim_speed` and `clean_speed` simultaneously so the motor follows the slider immediately (during prime) AND stays at that speed for the main clean phase. The slider label also shows which phase the pump is in and seconds remaining in prime. The full 35-DP list lives below in a collapsible "All data points" section, where prim/clean speed can be set independently if desired.

### Continuous mode — verified working

You can run the motor at a chosen RPM indefinitely without the 10-minute
prime/clean cycle by using the device's staged-schedule system:

1. Set `clean = false`, `isspeed = false`, `ismode = false`.
2. Set `stg_num = "1"` and configure stage 1 to cover the whole day:
   `stg_start_hour = 0`, `stg_start_min = 0`, `stg_stop_hour = 23`, `stg_stop_min = 59`.
3. Set `prim_speed` to your target RPM. The motor actually follows `prim_speed`
   even in non-clean mode — observed setting `prim_speed = 1800` and motor
   dropped to exactly 1800 RPM within 8 seconds.
4. Setting `pump = true` (if it isn't already) closes the main relay.

`stg_speed` was observed to behave strangely when written directly: writes get
clamped to a much smaller value (max ~134 in one test, while the spec says
1–3450). It looks like that DP is interpreted as a flow target or a different
scale internally, not RPM. For exact RPM control, write **`prim_speed`** —
that's the universal motor-speed setpoint across both Clean and non-Clean modes.

The Continuous-mode button in the UI batches all of the above in a single
`/api/devices/{id}/dps` call.

### Schedule / staged system

Three stages (`stg_num` = `1`, `2`, or `3`). Each stage stores its own:

- `stg_speed` — speed/flow target for that stage
- `stg_start_hour`, `stg_start_min` — daily start time
- `stg_stop_hour`, `stg_stop_min` — daily stop time

**Time zone**: the device stores times in its own local timezone. For US
devices linked through the Smart Life app this is whatever timezone the app
was registered in (visible on the cloud record as e.g. `"time_zone": "-04:00"`
= EDT / Florida).

The cloud shadow only ever exposes the **currently active** stage's values —
to read or write a different stage's settings you must first switch `stg_num`
to point at it. The UI schedule editor handles this orchestration: pick a
stage from the segmented control, edit the form, click Save. The trailing `●`
marks which stage is currently active on the device.

The UI also ships **schedule presets** (in the Schedule panel) for common
Florida-residential scenarios:

- *FL flat rate · 3 stages* — 1800/2400/1500 RPM staircase with overnight
  efficiency split. Targets ~2× daily turnover for an 18 k gallon pool.
- *FL time-of-use · avoids 4–7 PM peak* — shifts the high-speed cycle to
  11 AM–3 PM so it lands outside FPL's TOU peak window.
- *Post-storm / algae recovery (24h)* — three contiguous 8-hour stages all at
  1800 RPM for continuous filtration after heavy rain or shock treatment.
- *Simple — single stage, all day* — one 24-hour stage at 1800 RPM, stages 2
  and 3 disabled with zero-length windows.

Clicking a preset writes all three stages sequentially (one batched
shadow update per stage), restores `stg_num` to stage 1, and sets the
slider's target speed to match.

Caveats:
- Switching `stg_num` during operation actually changes which stage is active
  on the pump. There's no read-only "preview" path through the API.
- `stg_speed` units are unclear (see Continuous mode note above). Treat the
  schedule as a daily on-off timer; use the slider for fine RPM control.
- **The scheduler is edge-triggered.** The pump only changes state when the
  clock crosses a `stg_start_hour:stg_start_min` or `stg_stop_hour:stg_stop_min`
  boundary. Saving a schedule mid-window does NOT retroactively start the motor.
  Workaround: briefly pulse `clean = true` then `clean = false`. This starts
  the motor at `prim_speed` and hands control back to the staged scheduler with
  the motor already spinning. The UI's Apply button does this automatically
  when the current device-local time falls inside any saved window.
- **`stg_num` writes are NOT atomic with co-batched stage-field writes.** When
  a single shadow update contains both `stg_num = N` AND `stg_speed`/`stg_start_*`/
  `stg_stop_*`, the device applies the stage fields to the *previous* active
  stage first, then switches `stg_num` to N. Symptom: stage N's data appears
  on stage N-1. Workaround: send `stg_num` in its own request, wait ~1.5 s for
  the device to commit the switch, then send the stage's fields in a second
  request. The schedule editor does this automatically (one Apply click → six
  HTTP requests, ~8 s total for all three stages).

## Open questions / future work

- **Manual speed mode**: capture the exact app-issued flag combination via shadow diff.
- **`press` / pressure mode**: verify trigger sequence and physical effect.
- **`switch` (DP 102)**: characterise vs. `pump` (DP 101).
- **`ismode` (DP 122)**: appears in the schema but its effect alone is null. Likely a second-level mode flag the app sets in concert with others.
- **`init` (DP 116)**: try and observe whether it resets schedules.
- **v3.5 LAN protocol**: implement the session-key handshake (HMAC-SHA256 on `localKey` + `clientRandom`/`deviceRandom`) and AES-GCM frame encoding so that latency drops from ~150–300 ms cloud round-trip to a few ms LAN. Would also let the pump be controlled without internet access.
