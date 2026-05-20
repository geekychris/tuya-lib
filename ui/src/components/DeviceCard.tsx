import { useEffect, useState } from 'react';
import type { Device, DpsState } from '../api';
import { api, isPoolPump, parseDpsSchema } from '../api';
import { DpControls } from './DpControls';
import { PoolPumpControls } from './PoolPumpControls';

type Props = {
  device: Device;
  liveState?: DpsState;
  onDelete: () => void;
  onUpdated: (d: Device) => void;
};

export function DeviceCard({ device, liveState, onDelete, onUpdated }: Props) {
  const schema = parseDpsSchema(device.dpsSchemaJson);
  const poolPump = isPoolPump(schema);
  // In AUTO mode, v3.4/v3.5 devices effectively use cloud transport because the
  // library doesn't yet implement the session-key handshake those protocols need.
  const autoUsesCloud = device.protocolVersion === '3.5' || device.protocolVersion === '3.4';
  const transportIsCloud = device.preferredTransport === 'CLOUD'
      || (device.preferredTransport === 'AUTO' && autoUsesCloud);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [pending, setPending] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (liveState) setValues(liveState.dps);
  }, [liveState]);

  useEffect(() => {
    if (!device.connected) return;
    let cancelled = false;
    api.getState(device.id)
      .then((s) => { if (!cancelled) setValues(s.dps); })
      .catch((e) => { if (!cancelled) setError(String(e.message ?? e)); });
    return () => { cancelled = true; };
  }, [device.id, device.connected]);

  const updateDp = async (index: string, value: unknown) => {
    await updateDps({ [index]: value });
  };

  const updateDps = async (dps: Record<string, unknown>) => {
    setError(null);
    const keys = Object.keys(dps);
    setPending((p) => {
      const next = { ...p };
      for (const k of keys) next[k] = true;
      return next;
    });
    const prevSnap: Record<string, unknown> = {};
    keys.forEach((k) => { prevSnap[k] = values[k]; });
    setValues((v) => ({ ...v, ...dps }));
    try {
      const next = await api.setDps(device.id, dps);
      // Keep the user's requested values optimistically — Tuya's shadow takes
      // a few seconds to propagate, so the server may echo pre-change values.
      setValues({ ...next.dps, ...dps });
    } catch (e: any) {
      setValues((v) => ({ ...v, ...prevSnap }));
      setError(e.message ?? String(e));
    } finally {
      setPending((p) => {
        const next = { ...p };
        for (const k of keys) delete next[k];
        return next;
      });
    }
  };

  const refresh = async () => {
    setError(null);
    try {
      const fresh = await api.getState(device.id);
      setValues(fresh.dps);
    } catch (e: any) {
      setError(e.message ?? String(e));
    }
  };

  const changeTransport = async (t: 'AUTO' | 'LAN' | 'CLOUD') => {
    try {
      const updated = await api.setTransport(device.id, t);
      onUpdated(updated);
    } catch (e: any) {
      setError(e.message ?? String(e));
    }
  };

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div className="row">
          <span className={`dot ${device.connected ? 'online' : 'offline'}`} />
          <strong>{device.name}</strong>
          <span className={`tag ${transportIsCloud ? 'cloud' : ''}`}>
            {transportIsCloud ? 'via cloud' : 'LAN'} · v{device.protocolVersion}
          </span>
          {schema.pool_pump && <span className="tag">pool pump</span>}
        </div>
        <div className="row">
          <button className="btn secondary" onClick={refresh}>Refresh</button>
          <TransportSwitch value={device.preferredTransport} onChange={changeTransport} />
          <button className="btn danger" onClick={onDelete}>Delete</button>
        </div>
      </div>
      <div className="muted" style={{ marginTop: 4 }}>
        {device.ip ?? '(no ip)'} · {device.gwId.substring(0, 8)}…
      </div>
      {error && <div className="error">{error}</div>}
      <div style={{ marginTop: 12 }}>
        {poolPump && (
          <PoolPumpControls
            schema={schema}
            values={values}
            pending={pending}
            gwId={device.gwId}
            onChange={updateDp}
            onChangeMany={updateDps}
          />
        )}
        {!poolPump && (
          <DpControls
            schema={schema.entries}
            values={values}
            pending={pending}
            onChange={updateDp}
          />
        )}
        {poolPump && (
          <details style={{ marginTop: 14 }}>
            <summary className="muted" style={{ cursor: 'pointer', userSelect: 'none' }}>
              All data points ({schema.entries.length})
            </summary>
            <div style={{ marginTop: 8 }}>
              <DpControls
                schema={schema.entries}
                values={values}
                pending={pending}
                onChange={updateDp}
              />
            </div>
          </details>
        )}
        {schema.entries.length === 0 && Object.keys(values).length > 0 && (
          <pre style={{ background: '#0a0c10', padding: 12, borderRadius: 6, overflow: 'auto' }}>
            {JSON.stringify(values, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
}

function TransportSwitch({
  value, onChange,
}: { value: 'AUTO' | 'LAN' | 'CLOUD'; onChange: (v: 'AUTO' | 'LAN' | 'CLOUD') => void }) {
  return (
    <div className="seg">
      {(['AUTO', 'LAN', 'CLOUD'] as const).map((t) => (
        <button
          key={t}
          type="button"
          className={`seg-btn ${value === t ? 'active' : ''}`}
          onClick={() => value !== t && onChange(t)}
          title={t === 'AUTO' ? 'try LAN, fallback to cloud' : t === 'LAN' ? 'LAN only (will fail on v3.4/v3.5)' : 'cloud only'}
        >
          {t}
        </button>
      ))}
    </div>
  );
}
