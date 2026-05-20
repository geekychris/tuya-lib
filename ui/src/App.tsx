import { useEffect, useMemo, useState } from 'react';
import { api } from './api';
import type { Device, DpsState } from './api';
import { DeviceCard } from './components/DeviceCard';
import { AddDeviceForm } from './components/AddDeviceForm';
import { CloudSettings } from './components/CloudSettings';
import { CloudDevices } from './components/CloudDevices';
import { useDeviceStateSocket } from './useWebSocket';

export default function App() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [liveStates, setLiveStates] = useState<Record<number, DpsState>>({});
  const [adding, setAdding] = useState(false);
  const [showCloudSettings, setShowCloudSettings] = useState(false);
  const [cloudEnabled, setCloudEnabled] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);

  useDeviceStateSocket((msg) => {
    setLiveStates((prev) => ({
      ...prev,
      [msg.deviceId]: { dps: msg.dps, at: msg.at },
    }));
  });

  const refresh = () => {
    api.listDevices()
      .then(setDevices)
      .catch((e) => setError(e.message ?? String(e)));
  };

  useEffect(() => {
    refresh();
    api.cloudStatus().then((s) => setCloudEnabled(s.enabled)).catch(() => setCloudEnabled(false));
  }, []);

  const registeredGwIds = useMemo(() => new Set(devices.map((d) => d.gwId)), [devices]);

  const onDelete = async (id: number) => {
    if (!confirm('Delete this device?')) return;
    try {
      await api.deleteDevice(id);
      setDevices((ds) => ds.filter((d) => d.id !== id));
    } catch (e: any) {
      setError(e.message ?? String(e));
    }
  };

  return (
    <div className="app">
      <div className="header">
        <h1>Tuya Devices</h1>
        <div className="row">
          <button className="link-button" onClick={() => setShowCloudSettings(true)}>
            Cloud: {cloudEnabled === null ? '…' : cloudEnabled ? 'configured' : 'disabled'}
          </button>
          {!adding && (
            <button className="btn" onClick={() => setAdding(true)}>+ Add device</button>
          )}
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      {adding && (
        <AddDeviceForm
          onCreated={(d) => { setDevices((prev) => [...prev, d]); setAdding(false); }}
          onCancel={() => setAdding(false)}
        />
      )}

      {devices.length === 0 && !adding && (
        <div className="card muted">
          No devices yet. Click <strong>+ Add device</strong> to register one.
          You'll need the device's gwId, 16-char localKey, and LAN IP — get those
          from your Tuya IoT Platform project.
        </div>
      )}

      {devices.map((d) => (
        <DeviceCard
          key={d.id}
          device={d}
          liveState={liveStates[d.id]}
          onDelete={() => onDelete(d.id)}
          onUpdated={(updated) =>
            setDevices((prev) => prev.map((dd) => (dd.id === updated.id ? updated : dd)))
          }
        />
      ))}

      {cloudEnabled && (
        <CloudDevices
          registeredGwIds={registeredGwIds}
          onImported={(d) => setDevices((prev) => [...prev, d])}
        />
      )}

      {showCloudSettings && (
        <CloudSettings
          onClose={() => setShowCloudSettings(false)}
          onSaved={(cfg) => setCloudEnabled(cfg.enabled)}
        />
      )}
    </div>
  );
}
