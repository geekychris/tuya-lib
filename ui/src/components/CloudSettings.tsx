import { useEffect, useState } from 'react';
import { api } from '../api';
import type { CloudConfig } from '../api';

type Props = {
  onClose: () => void;
  onSaved: (cfg: CloudConfig) => void;
};

const REGIONS = [
  { value: 'us', label: 'US (openapi.tuyaus.com)' },
  { value: 'eu', label: 'EU (openapi.tuyaeu.com)' },
  { value: 'cn', label: 'CN (openapi.tuyacn.com)' },
  { value: 'in', label: 'IN (openapi.tuyain.com)' },
];

export function CloudSettings({ onClose, onSaved }: Props) {
  const [loading, setLoading] = useState(true);
  const [accessId, setAccessId] = useState('');
  const [accessSecret, setAccessSecret] = useState('');
  const [region, setRegion] = useState('us');
  const [hasSecret, setHasSecret] = useState(false);
  const [enabled, setEnabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<string | null>(null);

  useEffect(() => {
    api.getCloudConfig()
      .then((c) => {
        setAccessId(c.accessId);
        setRegion(c.region);
        setHasSecret(c.hasSecret);
        setEnabled(c.enabled);
      })
      .catch((e) => setError(e.message ?? String(e)))
      .finally(() => setLoading(false));
  }, []);

  const save = async () => {
    setError(null);
    setTestResult(null);
    setBusy(true);
    try {
      const saved = await api.saveCloudConfig({ accessId, accessSecret, region });
      setHasSecret(saved.hasSecret);
      setEnabled(saved.enabled);
      setAccessSecret('');
      onSaved(saved);
    } catch (e: any) {
      setError(e.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    setError(null);
    setTestResult(null);
    setBusy(true);
    try {
      // Test the form values, not what's saved — so the user can validate before persisting.
      const result = await api.testCloudConfig({ accessId, accessSecret, region });
      if (result.ok) setTestResult('✓ Credentials valid (token acquired)');
      else setTestResult(`✗ ${result.error ?? 'Unknown error'}`);
    } catch (e: any) {
      setTestResult(`✗ ${e.message ?? String(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const clear = async () => {
    if (!confirm('Clear cloud credentials? You can re-enter them later.')) return;
    setError(null);
    setBusy(true);
    try {
      await api.clearCloudConfig();
      setAccessId('');
      setAccessSecret('');
      setHasSecret(false);
      setEnabled(false);
      setRegion('us');
      onSaved({ accessId: '', region: 'us', hasSecret: false, enabled: false });
    } catch (e: any) {
      setError(e.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h2 style={{ margin: 0 }}>Tuya Cloud settings</h2>
          <button className="btn secondary" onClick={onClose}>Close</button>
        </div>
        <p className="muted" style={{ marginTop: 4 }}>
          From iot.tuya.com → Cloud → Projects → your project → Overview tab.
          Leaving these blank disables cloud passthrough; local LAN devices still work.
        </p>

        {loading ? (
          <div className="muted">Loading…</div>
        ) : (
          <>
            <div className="row" style={{ marginTop: 8 }}>
              <span className={`dot ${enabled ? 'online' : 'offline'}`} />
              <span className="muted">
                Currently {enabled ? 'configured & active' : 'not configured'}
              </span>
            </div>

            <label>Access ID</label>
            <input value={accessId} onChange={(e) => setAccessId(e.target.value)} autoComplete="off" />

            <label>
              Access Secret{' '}
              {hasSecret && (
                <span className="muted" style={{ fontSize: '0.85em' }}>
                  (stored — leave blank to keep, fill in to replace)
                </span>
              )}
            </label>
            <input
              type="password"
              value={accessSecret}
              onChange={(e) => setAccessSecret(e.target.value)}
              autoComplete="off"
              placeholder={hasSecret ? '••••••••' : ''}
            />

            <label>Region (data center)</label>
            <select value={region} onChange={(e) => setRegion(e.target.value)}>
              {REGIONS.map((r) => (
                <option key={r.value} value={r.value}>{r.label}</option>
              ))}
            </select>

            {testResult && (
              <div className={testResult.startsWith('✓') ? 'success' : 'error'} style={{ marginTop: 12 }}>
                {testResult}
              </div>
            )}
            {error && <div className="error">{error}</div>}

            <div className="row" style={{ justifyContent: 'space-between', marginTop: 16 }}>
              <button className="btn secondary" onClick={clear} disabled={busy || !hasSecret}>
                Clear credentials
              </button>
              <div className="row">
                <button className="btn secondary" onClick={test} disabled={busy || !accessId}>
                  {busy ? 'Working…' : 'Test connection'}
                </button>
                <button className="btn" onClick={save} disabled={busy || !accessId}>
                  {busy ? 'Saving…' : 'Save'}
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
