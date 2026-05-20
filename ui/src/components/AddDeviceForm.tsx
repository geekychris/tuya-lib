import { useState } from 'react';
import { api } from '../api';
import type { Device } from '../api';

type Props = {
  onCreated: (device: Device) => void;
  onCancel: () => void;
};

const POOL_PUMP_SCHEMA = JSON.stringify(
  {
    pool_pump: true,
    entries: [
      { index: '1', label: 'Power', kind: 'bool' },
      { index: '2', label: 'Countdown (s)', kind: 'number', min: 0, max: 86400, unit: 's' },
      // Many Tuya pool-pump relays are simple on/off; extend here per your device.
    ],
  },
  null,
  2
);

export function AddDeviceForm({ onCreated, onCancel }: Props) {
  const [name, setName] = useState('');
  const [gwId, setGwId] = useState('');
  const [localKey, setLocalKey] = useState('');
  const [ip, setIp] = useState('');
  const [protocolVersion, setProtocolVersion] = useState('3.3');
  const [source, setSource] = useState<'LOCAL' | 'CLOUD'>('LOCAL');
  const [dpsSchemaJson, setDpsSchemaJson] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const created = await api.createDevice({
        name, gwId, localKey, ip, protocolVersion, source, dpsSchemaJson: dpsSchemaJson || undefined,
      });
      onCreated(created);
    } catch (ex: any) {
      setError(ex.message ?? String(ex));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card" onSubmit={submit}>
      <h3 style={{ marginTop: 0 }}>Register a device</h3>
      <div className="grid">
        <div>
          <label>Friendly name</label>
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div>
          <label>Source</label>
          <select value={source} onChange={(e) => setSource(e.target.value as 'LOCAL' | 'CLOUD')}>
            <option value="LOCAL">Local LAN</option>
            <option value="CLOUD">Tuya Cloud</option>
          </select>
        </div>
        <div>
          <label>Device ID (gwId)</label>
          <input value={gwId} onChange={(e) => setGwId(e.target.value)} required />
        </div>
        <div>
          <label>Protocol version</label>
          <select value={protocolVersion} onChange={(e) => setProtocolVersion(e.target.value)}>
            <option value="3.3">3.3</option>
            <option value="3.1" disabled>3.1 (not yet implemented)</option>
            <option value="3.4" disabled>3.4 (not yet implemented)</option>
            <option value="3.5" disabled>3.5 (not yet implemented)</option>
          </select>
        </div>
        {source === 'LOCAL' && (
          <>
            <div>
              <label>Local key (16 chars)</label>
              <input value={localKey} onChange={(e) => setLocalKey(e.target.value)} required={source === 'LOCAL'} />
            </div>
            <div>
              <label>IP address</label>
              <input value={ip} onChange={(e) => setIp(e.target.value)} required={source === 'LOCAL'} />
            </div>
          </>
        )}
      </div>
      <div style={{ marginTop: 12 }}>
        <label>
          DP schema JSON (optional){' '}
          <button
            type="button"
            className="btn secondary"
            style={{ padding: '2px 8px', marginLeft: 8, fontSize: '0.85em' }}
            onClick={() => setDpsSchemaJson(POOL_PUMP_SCHEMA)}
          >
            Load pool pump template
          </button>
        </label>
        <textarea
          value={dpsSchemaJson}
          onChange={(e) => setDpsSchemaJson(e.target.value)}
          rows={8}
          placeholder='{"entries": [{"index":"1","label":"Power","kind":"bool"}]}'
          style={{ fontFamily: 'ui-monospace, monospace' }}
        />
      </div>
      {error && <div className="error">{error}</div>}
      <div className="row" style={{ marginTop: 12, justifyContent: 'flex-end' }}>
        <button type="button" className="btn secondary" onClick={onCancel}>Cancel</button>
        <button type="submit" className="btn" disabled={busy}>{busy ? 'Saving…' : 'Add device'}</button>
      </div>
    </form>
  );
}
