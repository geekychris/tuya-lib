import { useEffect, useState } from 'react';
import { api, entryByCode } from '../api';
import type { DpsSchema } from '../api';

type Props = {
  schema: DpsSchema;
  values: Record<string, unknown>;
  pending: Record<string, boolean>;
  gwId: string;
  onChange: (index: string, value: unknown) => void;
  onChangeMany: (dps: Record<string, unknown>) => void | Promise<void>;
};

type StageDef = {
  speed: number;
  startHour: number;
  startMin: number;
  stopHour: number;
  stopMin: number;
};

type SchedulePreset = {
  id: string;
  label: string;
  description: string;
  /** Suggested target speed for the slider (controls prim_speed/clean_speed). */
  targetSpeed: number;
  stages: [StageDef, StageDef, StageDef];
};

/**
 * Schedule presets tuned for a 18,000-gallon residential pool in Florida.
 * Stage times are in the device's local timezone (typically your home's).
 * Targets 1.5–2× turnover/day during May–August chlorine demand.
 */
const PRESETS: SchedulePreset[] = [
  {
    id: 'fl-flat',
    label: 'FL flat rate · 3 stages',
    description:
      '8h mid-speed daytime, 3h higher-flow pre-sunset, 5h low overnight. ~2× turnover, ~$50/mo at typical FPL rates.',
    targetSpeed: 1800,
    stages: [
      { speed: 1800, startHour: 8,  startMin: 0, stopHour: 16, stopMin: 0 },
      { speed: 2400, startHour: 16, startMin: 0, stopHour: 19, stopMin: 0 },
      { speed: 1500, startHour: 23, startMin: 0, stopHour: 4,  stopMin: 0 },
    ],
  },
  {
    id: 'fl-tou',
    label: 'FL time-of-use · avoids 4–7 PM peak',
    description:
      'Heavy filtration before peak, mid-speed off-peak overnight, light morning. Best for FPL TOU customers.',
    targetSpeed: 1800,
    stages: [
      { speed: 2400, startHour: 11, startMin: 0, stopHour: 15, stopMin: 0 },
      { speed: 1800, startHour: 20, startMin: 0, stopHour: 2,  stopMin: 0 },
      { speed: 1500, startHour: 5,  startMin: 0, stopHour: 8,  stopMin: 0 },
    ],
  },
  {
    id: 'fl-storm',
    label: 'Post-storm / algae recovery (24h)',
    description:
      'Continuous 24-hour filtration at 1800 RPM split across 3 stages. Use for 2–3 days after heavy rain or shock.',
    targetSpeed: 1800,
    stages: [
      { speed: 1800, startHour: 0,  startMin: 0, stopHour: 8,  stopMin: 0 },
      { speed: 1800, startHour: 8,  startMin: 0, stopHour: 16, stopMin: 0 },
      { speed: 1800, startHour: 16, startMin: 0, stopHour: 23, stopMin: 59 },
    ],
  },
  {
    id: 'fl-simple',
    label: 'Simple — single stage, all day',
    description:
      'Stage 1 covers 00:00–23:59 at 1800 RPM; stages 2 & 3 disabled (zero-length windows). Easiest mental model.',
    targetSpeed: 1800,
    stages: [
      { speed: 1800, startHour: 0, startMin: 0, stopHour: 23, stopMin: 59 },
      { speed: 1800, startHour: 0, startMin: 0, stopHour: 0,  stopMin: 0 },
      { speed: 1800, startHour: 0, startMin: 0, stopHour: 0,  stopMin: 0 },
    ],
  },
];

/**
 * Compact dashboard for Lingxiao swimming pool pumps. Exposes:
 *  - Live status (RPM / Watts / Flow)
 *  - Three primary modes: Clean cycle (DP clean=true), Continuous (no cycle,
 *    motor follows prim_speed), and Off (pump=false).
 *  - A speed slider that writes both prim_speed and clean_speed so the motor
 *    follows the target in either mode.
 *  - A schedule editor for the 3 stage windows (stg_num/stg_speed/start+stop time).
 */
export function PoolPumpControls({ schema, values, gwId, onChange, onChangeMany }: Props) {
  const cleanDp = entryByCode(schema, 'clean')?.index ?? '109';
  const cleanSpeedDp = entryByCode(schema, 'clean_speed')?.index ?? '133';
  const primSpeedDp = entryByCode(schema, 'prim_speed')?.index ?? '124';
  const speedDp = entryByCode(schema, 'speed')?.index ?? '103';
  const powerDp = entryByCode(schema, 'power')?.index ?? '104';
  const flowDp = entryByCode(schema, 'flow')?.index ?? '113';
  const pumpDp = entryByCode(schema, 'pump')?.index ?? '101';
  const primTimeDp = entryByCode(schema, 'prim_time')?.index ?? '110';
  const primSecondDp = entryByCode(schema, 'prim_second')?.index ?? '130';
  const stgNumDp = entryByCode(schema, 'stg_num')?.index ?? '105';
  const stgSpeedDp = entryByCode(schema, 'stg_speed')?.index ?? '106';
  const stgStartHourDp = entryByCode(schema, 'stg_start_hour')?.index ?? '117';
  const stgStartMinDp = entryByCode(schema, 'stg_start_min')?.index ?? '118';
  const stgStopHourDp = entryByCode(schema, 'stg_stop_hour')?.index ?? '119';
  const stgStopMinDp = entryByCode(schema, 'stg_stop_min')?.index ?? '120';
  const isspeedDp = entryByCode(schema, 'isspeed')?.index ?? '121';
  const ismodeDp = entryByCode(schema, 'ismode')?.index ?? '122';

  const cleanOn = values[cleanDp] === true;
  const motorOn = asNumber(values[speedDp]) > 0;
  const pumpEnabled = values[pumpDp] === true;

  const rpm = asNumber(values[speedDp]);
  const watts = asNumber(values[powerDp]);
  const flow = asNumber(values[flowDp]);
  const cleanSpeedSchema = entryByCode(schema, 'clean_speed');
  const primSpeedSchema = entryByCode(schema, 'prim_speed');
  const speedMin = Math.max(cleanSpeedSchema?.min ?? 1500, primSpeedSchema?.min ?? 1500);
  const speedMax = Math.min(cleanSpeedSchema?.max ?? 3450, primSpeedSchema?.max ?? 3450);
  const targetSpeed = asNumber(values[primSpeedDp]) || asNumber(values[cleanSpeedDp]) || speedMin;
  const primTimeMin = asNumber(values[primTimeDp]);
  const primElapsedSec = asNumber(values[primSecondDp]);
  const inPrimePhase = cleanOn && primElapsedSec < primTimeMin * 60;

  // Heuristic for mode display: motor running with clean=true → "clean cycle";
  // motor running with clean=false → "continuous"; otherwise off.
  const mode: 'clean' | 'continuous' | 'off' =
      cleanOn ? 'clean' :
      (pumpEnabled && motorOn) ? 'continuous' :
      'off';

  const startClean = () => onChangeMany({ [pumpDp]: true, [cleanDp]: true });
  const startContinuous = () => onChangeMany({
    [pumpDp]: true,
    [cleanDp]: false,
    [isspeedDp]: false,
    [ismodeDp]: false,
    // Make stage 1 a 24/7 window with the target speed so staged mode keeps the motor
    // spinning at the slider value continuously.
    [stgNumDp]: '1',
    [stgStartHourDp]: 0,
    [stgStartMinDp]: 0,
    [stgStopHourDp]: 23,
    [stgStopMinDp]: 59,
    [stgSpeedDp]: targetSpeed,
    [primSpeedDp]: targetSpeed,
    [cleanSpeedDp]: targetSpeed,
  });
  const stop = () => onChangeMany({ [cleanDp]: false, [pumpDp]: false });

  return (
    <div className="pool-pump">
      <div className={`pool-status ${motorOn ? 'on' : 'off'}`}>
        <div className="pool-status-line">
          <span className={`pool-pulse ${motorOn ? 'on' : 'off'}`} />
          <strong>{motorOn ? `Running (${mode})` : (cleanOn ? 'Clean engaged, motor not yet spinning' : 'Stopped')}</strong>
        </div>
        <div className="pool-stats">
          <Stat label="RPM" value={rpm} />
          <Stat label="Watts" value={watts} unit="W" />
          <Stat label="Flow" value={flow} />
        </div>
      </div>

      <div className="pool-mode-row">
        <ModeBtn active={mode === 'clean'} onClick={startClean}>
          ▶ Clean cycle
          <small>10 min prime, then clean_speed</small>
        </ModeBtn>
        <ModeBtn active={mode === 'continuous'} onClick={startContinuous}>
          ⟲ Continuous
          <small>runs forever at target speed</small>
        </ModeBtn>
        <ModeBtn active={mode === 'off'} onClick={stop} variant="stop">
          ⏹ Off
          <small>stop motor</small>
        </ModeBtn>
      </div>

      <div className="pool-speed">
        <label>
          Target speed (RPM) — {speedMin}–{speedMax}
          {motorOn && (
            <span className="muted" style={{ marginLeft: 8, fontWeight: 'normal' }}>
              {inPrimePhase
                ? `prime phase (${formatSeconds(primTimeMin * 60 - primElapsedSec)} remaining)`
                : (mode === 'continuous' ? 'continuous' : 'clean phase')}
            </span>
          )}
        </label>
        <CleanSpeedControl
          value={targetSpeed}
          min={speedMin}
          max={speedMax}
          onCommit={(v) => onChangeMany({ [primSpeedDp]: v, [cleanSpeedDp]: v })}
        />
      </div>

      <details className="pool-schedule" style={{ marginTop: 16 }}>
        <summary>Schedule (3 stages)</summary>
        <UnifiedSchedule
          gwId={gwId}
          values={values}
          onChangeMany={onChangeMany}
          stgNumDp={stgNumDp}
          stgSpeedDp={stgSpeedDp}
          stgStartHourDp={stgStartHourDp}
          stgStartMinDp={stgStartMinDp}
          stgStopHourDp={stgStopHourDp}
          stgStopMinDp={stgStopMinDp}
          primSpeedDp={primSpeedDp}
          cleanSpeedDp={cleanSpeedDp}
          cleanDp={cleanDp}
        />
      </details>

      <div className="muted pool-hint">
        <strong>Continuous</strong> uses the device's staged-schedule system with
        stage 1 covering 00:00–23:59 at your target speed. <strong>Clean cycle</strong>
        runs a 10-minute prime at max, then drops to clean_speed for cleaning.
        The schedule below is a 3-window daily timer.
      </div>
    </div>
  );
}

function ModeBtn({
  active, variant, onClick, children,
}: { active: boolean; variant?: 'stop'; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      className={`pool-mode-btn ${active ? 'active' : ''} ${variant ?? ''}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

/**
 * Unified schedule panel. Picks a preset to pre-fill all three stages, then
 * lets you tweak any stage's start/stop time and speed in place. A single
 * Apply button writes everything back to the device in one orchestrated
 * sequence (one batched shadow update per stage, plus a final restore of
 * stg_num to stage 1).
 */
function UnifiedSchedule({
  gwId, values, onChangeMany,
  stgNumDp, stgSpeedDp, stgStartHourDp, stgStartMinDp, stgStopHourDp, stgStopMinDp,
  primSpeedDp, cleanSpeedDp, cleanDp,
}: {
  gwId: string;
  values: Record<string, unknown>;
  onChangeMany: (dps: Record<string, unknown>) => void | Promise<void>;
  stgNumDp: string; stgSpeedDp: string;
  stgStartHourDp: string; stgStartMinDp: string;
  stgStopHourDp: string; stgStopMinDp: string;
  primSpeedDp: string; cleanSpeedDp: string; cleanDp: string;
}) {
  const activeStage = String(values[stgNumDp] ?? '1');
  const [timezone, setTimezone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [presetId, setPresetId] = useState<string | null>(null);
  const [targetSpeed, setTargetSpeed] = useState<number>(() => firstNonZero(
      asNumber(values[primSpeedDp]), asNumber(values[cleanSpeedDp]), 1800));
  // 3-element array of stage drafts. The currently-active stage initially
  // mirrors the live device values; the others use sensible defaults.
  const [stages, setStages] = useState<StageDef[]>(() => makeInitialStages(values, {
    stgSpeedDp, stgStartHourDp, stgStartMinDp, stgStopHourDp, stgStopMinDp,
  }, activeStage));

  useEffect(() => {
    api.getCloudDevice(gwId)
      .then((d) => setTimezone((d.time_zone as string) ?? null))
      .catch(() => setTimezone(null));
  }, [gwId]);

  // Keep the active stage's draft in sync with device values, but only while
  // no preset is loaded (otherwise we'd clobber the user's chosen preset).
  useEffect(() => {
    if (presetId !== null) return;
    const idx = Number(activeStage) - 1;
    if (idx < 0 || idx > 2) return;
    setStages((prev) => {
      const next = prev.slice() as StageDef[];
      next[idx] = {
        speed: asNumber(values[stgSpeedDp]) || next[idx].speed,
        startHour: asNumber(values[stgStartHourDp]),
        startMin: asNumber(values[stgStartMinDp]),
        stopHour: asNumber(values[stgStopHourDp]),
        stopMin: asNumber(values[stgStopMinDp]),
      };
      return next as StageDef[];
    });
  }, [activeStage, presetId, values, stgSpeedDp, stgStartHourDp, stgStartMinDp, stgStopHourDp, stgStopMinDp]);

  const loadPreset = (preset: SchedulePreset) => {
    setPresetId(preset.id);
    setStages(preset.stages.map((s) => ({ ...s })) as [StageDef, StageDef, StageDef]);
    setTargetSpeed(preset.targetSpeed);
    setMsg(`Loaded preset "${preset.label}" — review the stages and click Apply.`);
  };

  const updateStage = (i: number, patch: Partial<StageDef>) => {
    setStages((prev) => {
      const next = prev.slice() as StageDef[];
      next[i] = { ...next[i], ...patch };
      return next as StageDef[];
    });
  };

  const apply = async () => {
    setBusy(true);
    setMsg(null);
    try {
      await onChangeMany({ [primSpeedDp]: targetSpeed, [cleanSpeedDp]: targetSpeed });

      // The device applies batched property writes to the CURRENTLY-active
      // stage first, then switches stg_num. Sending {stg_num=2, stg_speed=X}
      // in one batch ends up writing X to stage 1 (the previous active stage)
      // and then flipping to stage 2 with stage 2's old values intact.
      //
      // Workaround: split each stage into two requests with a settling delay
      // between them. First switch stg_num, then write the stage's fields.
      const settle = () => new Promise((r) => setTimeout(r, 1500));
      for (let i = 0; i < stages.length; i++) {
        const stage = stages[i];
        setMsg(`Writing stage ${i + 1}…`);
        // 1) switch active stage and let the device commit it
        await onChangeMany({ [stgNumDp]: String(i + 1) });
        await settle();
        // 2) write that stage's fields (now applied to the right slot)
        await onChangeMany({
          [stgSpeedDp]: clamp(stage.speed, 0, 3450),
          [stgStartHourDp]: clamp(stage.startHour, 0, 23),
          [stgStartMinDp]: clamp(stage.startMin, 0, 59),
          [stgStopHourDp]: clamp(stage.stopHour, 0, 23),
          [stgStopMinDp]: clamp(stage.stopMin, 0, 59),
        });
        await new Promise((r) => setTimeout(r, 500));
      }
      // Leave stg_num on stage 1 so the editor reflects stage 1 by default.
      await onChangeMany({ [stgNumDp]: '1' });

      // The device's scheduler is edge-triggered: it only acts when the clock
      // crosses a stg_start_hour boundary. If the user saved a schedule mid-
      // window, the motor needs an explicit "kick" to start.
      const now = new Date();
      if (isInAnyWindow(now.getHours(), now.getMinutes(), stages)) {
        await onChangeMany({ [cleanDp]: true });
        await new Promise((r) => setTimeout(r, 3500));
        await onChangeMany({ [cleanDp]: false });
        setMsg('Applied schedule and started motor (current time is inside a scheduled window).');
      } else {
        setMsg('Applied schedule. Motor will start at the next stage start time.');
      }
      setPresetId(null);
    } catch (e: any) {
      setMsg(`Failed: ${e.message ?? String(e)}`);
    } finally {
      setBusy(false);
    }
  };

  /** True if hh:mm falls inside any of the stage windows (handles midnight wrap). */
  const isInAnyWindow = (h: number, m: number, stages: StageDef[]): boolean => {
    const now = h * 60 + m;
    for (const s of stages) {
      const start = s.startHour * 60 + s.startMin;
      const stop = s.stopHour * 60 + s.stopMin;
      if (start === stop) continue; // empty / disabled window
      if (start < stop) {
        if (now >= start && now < stop) return true;
      } else {
        // wraps midnight (e.g. 20:00-02:00)
        if (now >= start || now < stop) return true;
      }
    }
    return false;
  };

  const reset = () => {
    setStages(makeInitialStages(values, {
      stgSpeedDp, stgStartHourDp, stgStartMinDp, stgStopHourDp, stgStopMinDp,
    }, activeStage));
    setTargetSpeed(firstNonZero(asNumber(values[primSpeedDp]), asNumber(values[cleanSpeedDp]), 1800));
    setPresetId(null);
    setMsg(null);
  };

  return (
    <div className="pool-sched">
      <div className="pool-sched-header">
        <span className="muted" style={{ fontSize: '0.85em' }}>
          Times are device local{timezone ? ` (UTC${timezone})` : ''}. ● = active stage.
        </span>
      </div>

      <div className="pool-preset-grid">
        {PRESETS.map((p) => (
          <button
            key={p.id}
            type="button"
            className={`pool-preset-btn ${presetId === p.id ? 'selected' : ''}`}
            onClick={() => loadPreset(p)}
            disabled={busy}
          >
            <div className="pool-preset-label">{p.label}</div>
            <div className="pool-preset-stages">
              {p.stages.map((s, i) => {
                const start = `${pad(s.startHour)}:${pad(s.startMin)}`;
                const stop = `${pad(s.stopHour)}:${pad(s.stopMin)}`;
                const empty = s.startHour === s.stopHour && s.startMin === s.stopMin;
                return (
                  <span key={i} className={empty ? 'pool-preset-stage off' : 'pool-preset-stage'}>
                    {empty ? '— off —' : `${start}–${stop}@${s.speed}`}
                  </span>
                );
              })}
            </div>
            <div className="pool-preset-desc">{p.description}</div>
          </button>
        ))}
      </div>

      <div className="pool-stage-list">
        {stages.map((stage, i) => {
          const num = String(i + 1);
          const isActive = num === activeStage;
          const empty = stage.startHour === stage.stopHour && stage.startMin === stage.stopMin;
          return (
            <div key={i} className={`pool-stage-row ${isActive ? 'active' : ''}`}>
              <div className="pool-stage-label">
                Stage {num}{isActive ? ' ●' : ''}
                {empty && <span className="muted" style={{ marginLeft: 6, fontSize: '0.85em' }}>(disabled — empty window)</span>}
              </div>
              <div className="pool-stage-fields">
                <div>
                  <label>Start</label>
                  <TimeInput
                    hour={stage.startHour}
                    minute={stage.startMin}
                    onChange={(h, m) => updateStage(i, { startHour: h, startMin: m })}
                  />
                </div>
                <div>
                  <label>Stop</label>
                  <TimeInput
                    hour={stage.stopHour}
                    minute={stage.stopMin}
                    onChange={(h, m) => updateStage(i, { stopHour: h, stopMin: m })}
                  />
                </div>
                <div>
                  <label>Stage speed</label>
                  <input
                    type="number"
                    min={0}
                    max={3450}
                    value={stage.speed}
                    onChange={(e) => updateStage(i, { speed: Number(e.target.value) })}
                    style={{ width: 90 }}
                  />
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <div className="pool-sched-footer">
        <div className="pool-target">
          <label>Motor target RPM (writes prim_speed + clean_speed)</label>
          <input
            type="number"
            min={1500}
            max={3450}
            value={targetSpeed}
            onChange={(e) => setTargetSpeed(Number(e.target.value))}
            style={{ width: 100 }}
          />
          <span className="muted" style={{ fontSize: '0.8em', marginLeft: 8 }}>
            Use this for exact motor speed. The stage-speed field above is in the
            device's native unit and may be reinterpreted.
          </span>
        </div>
        <div className="row" style={{ gap: 10, justifyContent: 'flex-end' }}>
          {msg && <span className="muted" style={{ flex: 1, fontSize: '0.85em' }}>{msg}</span>}
          <button type="button" className="btn secondary" onClick={reset} disabled={busy}>
            Reset
          </button>
          <button type="button" className="btn" onClick={apply} disabled={busy}>
            {busy ? 'Applying…' : 'Apply schedule'}
          </button>
        </div>
        <p className="muted" style={{ fontSize: '0.78em', marginTop: 10, marginBottom: 0 }}>
          The pump's scheduler only acts when the clock crosses a stage's start
          time. If you save a schedule mid-window, Apply will briefly toggle
          Clean mode to start the motor immediately, then hand control back to
          the schedule.
        </p>
      </div>
    </div>
  );
}

function makeInitialStages(
  values: Record<string, unknown>,
  dps: { stgSpeedDp: string; stgStartHourDp: string; stgStartMinDp: string; stgStopHourDp: string; stgStopMinDp: string },
  activeStage: string,
): [StageDef, StageDef, StageDef] {
  const activeValues: StageDef = {
    speed: asNumber(values[dps.stgSpeedDp]),
    startHour: asNumber(values[dps.stgStartHourDp]),
    startMin: asNumber(values[dps.stgStartMinDp]),
    stopHour: asNumber(values[dps.stgStopHourDp]),
    stopMin: asNumber(values[dps.stgStopMinDp]),
  };
  const empty: StageDef = { speed: 0, startHour: 0, startMin: 0, stopHour: 0, stopMin: 0 };
  const out: StageDef[] = [empty, empty, empty];
  const idx = Number(activeStage) - 1;
  if (idx >= 0 && idx <= 2) out[idx] = activeValues;
  return out as [StageDef, StageDef, StageDef];
}

function firstNonZero(...vals: number[]): number {
  for (const v of vals) if (v) return v;
  return vals[vals.length - 1] ?? 0;
}

function pad(n: number): string {
  return n.toString().padStart(2, '0');
}

function TimeInput({
  hour, minute, onChange,
}: { hour: number; minute: number; onChange: (h: number, m: number) => void }) {
  const value = `${pad(clamp(hour, 0, 23))}:${pad(clamp(minute, 0, 59))}`;
  return (
    <input
      type="time"
      value={value}
      onChange={(e) => {
        const v = e.target.value;
        const [h, m] = v.split(':').map((s) => Number(s));
        onChange(clamp(h ?? 0, 0, 23), clamp(m ?? 0, 0, 59));
      }}
      style={{ width: 120 }}
    />
  );
}

function clamp(v: number, min: number, max: number): number {
  if (!Number.isFinite(v)) return min;
  return Math.min(max, Math.max(min, Math.round(v)));
}

function asNumber(v: unknown): number {
  if (typeof v === 'number') return v;
  if (typeof v === 'string') return Number(v) || 0;
  return 0;
}

function formatSeconds(s: number): string {
  if (s <= 0) return '0s';
  const m = Math.floor(s / 60);
  const r = s % 60;
  if (m === 0) return `${r}s`;
  return `${m}m${r > 0 ? ' ' + r + 's' : ''}`;
}

function Stat({ label, value, unit }: { label: string; value: number; unit?: string }) {
  return (
    <div className="pool-stat">
      <div className="pool-stat-val">{value}{unit ?? ''}</div>
      <div className="pool-stat-label">{label}</div>
    </div>
  );
}

function CleanSpeedControl({
  value, min, max, onCommit,
}: { value: number; min: number; max: number; onCommit: (v: number) => void }) {
  const [draft, setDraft] = useState<number>(value || min);
  const [active, setActive] = useState(false);

  useEffect(() => {
    if (!active) setDraft(value || min);
  }, [value, active, min]);

  const commit = () => {
    setActive(false);
    const clamped = Math.min(max, Math.max(min, Math.round(draft)));
    if (clamped !== value) onCommit(clamped);
  };

  return (
    <div className="row" style={{ gap: 12, alignItems: 'center' }}>
      <input
        type="range"
        min={min}
        max={max}
        step={10}
        value={draft}
        onChange={(e) => { setActive(true); setDraft(Number(e.target.value)); }}
        onMouseUp={commit}
        onTouchEnd={commit}
        onKeyUp={commit}
        style={{ flex: 1 }}
      />
      <input
        type="number"
        min={min}
        max={max}
        value={draft}
        onChange={(e) => { setActive(true); setDraft(Number(e.target.value)); }}
        onBlur={commit}
        onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur(); }}
        style={{ width: 90 }}
      />
    </div>
  );
}
