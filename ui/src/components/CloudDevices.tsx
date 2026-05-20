import { useEffect, useState } from 'react';
import { api } from '../api';
import type { CloudDevice, Device } from '../api';

type Props = {
  registeredGwIds: Set<string>;
  onImported: (device: Device) => void;
};

type Status =
  | { kind: 'loading' }
  | { kind: 'ok'; devices: CloudDevice[] }
  | { kind: 'permission'; raw: string }
  | { kind: 'error'; raw: string };

export function CloudDevices({ registeredGwIds, onImported }: Props) {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [showManual, setShowManual] = useState(false);

  const refresh = () => {
    setStatus({ kind: 'loading' });
    api.listCloudDevices()
      .then((res) => {
        const arr: CloudDevice[] = Array.isArray(res)
          ? res
          : (res?.devices ?? []);
        setStatus({ kind: 'ok', devices: arr });
      })
      .catch((e) => {
        const msg = String(e?.message ?? e);
        if (/code=28841001|No permissions|code=1108|code=99963001/.test(msg)) {
          setStatus({ kind: 'permission', raw: msg });
        } else {
          setStatus({ kind: 'error', raw: msg });
        }
      });
  };

  useEffect(refresh, []);

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>Devices from Tuya cloud</h3>
        <div className="row">
          <button className="btn secondary" onClick={refresh}>Refresh</button>
          <button className="btn secondary" onClick={() => setShowManual((v) => !v)}>
            {showManual ? 'Hide manual import' : 'Manual import'}
          </button>
        </div>
      </div>

      {status.kind === 'loading' && <div className="muted">Loading…</div>}

      {status.kind === 'permission' && (
        <div className="error" style={{ marginTop: 12 }}>
          <strong>Tuya project lacks API permissions:</strong> {status.raw}
          <p className="muted" style={{ marginTop: 8 }}>
            Your project subscription only includes basic IoT Core. To list devices via the
            cloud, go to <a href="https://iot.tuya.com" target="_blank" rel="noreferrer">iot.tuya.com</a>{' '}
            → Cloud → Development → your project → <em>Service API</em> tab → click
            <em> Go to Authorize</em>, and subscribe (free) to:
          </p>
          <ul className="muted">
            <li><strong>Authorization</strong></li>
            <li><strong>Device Status Notification</strong></li>
            <li><strong>Smart Home Basic Service</strong></li>
          </ul>
          <p className="muted">
            Or use manual import below if you already know a device's ID.
          </p>
        </div>
      )}

      {status.kind === 'error' && (
        <div className="error" style={{ marginTop: 12 }}>{status.raw}</div>
      )}

      {status.kind === 'ok' && status.devices.length === 0 && !showManual && (
        <div className="muted" style={{ marginTop: 12 }}>
          No devices linked to this Tuya project. Link your Tuya/Smart Life app account
          at iot.tuya.com → your project → Devices → Link App Account.
        </div>
      )}

      {status.kind === 'ok' && status.devices.length > 0 && (
        <CloudDeviceList
          devices={status.devices}
          registeredGwIds={registeredGwIds}
          onImported={onImported}
        />
      )}

      {showManual && <ManualImportForm onImported={onImported} />}
    </div>
  );
}

function CloudDeviceList({
  devices, registeredGwIds, onImported,
}: { devices: CloudDevice[]; registeredGwIds: Set<string>; onImported: (d: Device) => void }) {
  return (
    <div style={{ marginTop: 12 }}>
      {devices.map((d) => (
        <CloudDeviceRow
          key={d.id}
          device={d}
          alreadyRegistered={registeredGwIds.has(d.id)}
          onImported={onImported}
        />
      ))}
    </div>
  );
}

function CloudDeviceRow({
  device, alreadyRegistered, onImported,
}: { device: CloudDevice; alreadyRegistered: boolean; onImported: (d: Device) => void }) {
  const [importing, setImporting] = useState(false);
  // Don't pre-fill from device.ip — Tuya reports the public WAN IP there, not the LAN IP.
  const [ip, setIp] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);

  const doImport = async () => {
    setError(null);
    if (!ip) { setEditing(true); return; }
    setImporting(true);
    try {
      const created = await api.importFromCloud({
        deviceId: device.id,
        ip,
        protocolVersion: '3.3',
        name: device.name ?? device.id,
        localKey: device.local_key,  // we already have it from the cloud listing
      });
      onImported(created);
    } catch (e: any) {
      setError(e.message ?? String(e));
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="dp-row" style={{ alignItems: 'flex-start' }}>
      <div style={{ flex: 1 }}>
        <div>
          <span className={`dot ${device.online ? 'online' : 'offline'}`} />{' '}
          <strong>{device.name ?? device.id}</strong>
          {device.category && <span className="tag" style={{ marginLeft: 8 }}>{device.category}</span>}
          {alreadyRegistered && <span className="tag" style={{ marginLeft: 8 }}>registered</span>}
        </div>
        <div className="muted" style={{ fontSize: '0.85em' }}>
          {device.id}{device.product_name ? ` · ${device.product_name}` : ''}
          {device.ip ? ` · WAN ${device.ip}` : ''}
        </div>
        {editing && (
          <div className="row" style={{ marginTop: 6 }}>
            <input
              placeholder="LAN IP (e.g. 192.168.1.42)"
              value={ip}
              onChange={(e) => setIp(e.target.value)}
              style={{ width: 220 }}
              autoFocus
            />
          </div>
        )}
        {error && <div className="error" style={{ marginTop: 6 }}>{error}</div>}
      </div>
      {!alreadyRegistered && (
        <button
          className="btn"
          onClick={doImport}
          disabled={importing}
        >
          {importing ? 'Importing…' : editing ? 'Import' : 'Import…'}
        </button>
      )}
    </div>
  );
}

function ManualImportForm({ onImported }: { onImported: (d: Device) => void }) {
  const [deviceId, setDeviceId] = useState('');
  const [ip, setIp] = useState('');
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await api.importFromCloud({
        deviceId, ip, protocolVersion: '3.3', name: name || undefined,
      });
      onImported(created);
      setDeviceId(''); setIp(''); setName('');
    } catch (e: any) {
      setError(e.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} style={{ marginTop: 12, borderTop: '1px solid var(--border)', paddingTop: 12 }}>
      <h4 style={{ margin: '4px 0' }}>Manual import by device ID</h4>
      <p className="muted" style={{ marginTop: 0 }}>
        Use this if cloud listing is blocked. Server will fetch local_key via the
        cloud factory-infos endpoint (also needs API permissions).
      </p>
      <div className="grid">
        <div>
          <label>Device ID (gwId)</label>
          <input value={deviceId} onChange={(e) => setDeviceId(e.target.value)} required />
        </div>
        <div>
          <label>LAN IP</label>
          <input value={ip} onChange={(e) => setIp(e.target.value)} required />
        </div>
        <div>
          <label>Friendly name (optional)</label>
          <input value={name} onChange={(e) => setName(e.target.value)} />
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="row" style={{ marginTop: 8, justifyContent: 'flex-end' }}>
        <button className="btn" type="submit" disabled={busy}>{busy ? 'Importing…' : 'Import'}</button>
      </div>
    </form>
  );
}
