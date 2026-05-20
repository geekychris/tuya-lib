export type Device = {
  id: number;
  name: string;
  gwId: string;
  localKey?: string;
  ip?: string;
  protocolVersion: string;
  source: 'LOCAL' | 'CLOUD';
  preferredTransport: 'AUTO' | 'LAN' | 'CLOUD';
  dpsSchemaJson?: string;
  connected: boolean;
};

export type DpsState = {
  dps: Record<string, unknown>;
  at: string;
};

export type DpSchemaEntry = {
  index: string;          // "1"
  label: string;          // "Power"
  kind: 'bool' | 'number' | 'enum' | 'string';
  code?: string;          // Tuya friendly code, e.g. "pump", "clean_speed"
  unit?: string;          // for number
  options?: string[];     // for enum
  min?: number;
  max?: number;
};

export type DpsSchema = {
  entries: DpSchemaEntry[];
  pool_pump?: boolean;    // hint to render the pool-pump card layout
};

async function http<T>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  listDevices: () => http<Device[]>('GET', '/api/devices'),
  createDevice: (d: Partial<Device>) => http<Device>('POST', '/api/devices', d),
  updateDevice: (id: number, d: Partial<Device>) => http<Device>('PUT', `/api/devices/${id}`, d),
  deleteDevice: (id: number) => http<void>('DELETE', `/api/devices/${id}`),
  getState: (id: number) => http<DpsState>('GET', `/api/devices/${id}/state`),
  setDps: (id: number, dps: Record<string, unknown>) =>
    http<DpsState>('POST', `/api/devices/${id}/dps`, { dps }),
  importFromCloud: (params: { deviceId: string; ip: string; protocolVersion?: string; name?: string; localKey?: string }) =>
    http<Device>('POST', '/api/devices/from-cloud', params),
  setTransport: (id: number, transport: 'AUTO' | 'LAN' | 'CLOUD') =>
    http<Device>('POST', `/api/devices/${id}/transport`, { transport }),
  cloudStatus: () => http<CloudStatus>('GET', '/api/cloud/status'),
  getCloudConfig: () => http<CloudConfig>('GET', '/api/cloud/config'),
  saveCloudConfig: (cfg: { accessId: string; accessSecret?: string; region: string }) =>
    http<CloudConfig>('PUT', '/api/cloud/config', cfg),
  clearCloudConfig: () => http<{ enabled: boolean }>('DELETE', '/api/cloud/config'),
  testCloudConfig: (cfg?: { accessId: string; accessSecret?: string; region: string }) =>
    http<{ ok: boolean; error?: string }>('POST', '/api/cloud/test', cfg ?? {}),
  listCloudDevices: () => http<{ devices?: CloudDevice[]; total?: number } | CloudDevice[]>(
    'GET', '/api/cloud/devices?pageSize=50'),
  getCloudDevice: (gwId: string) =>
    http<{ time_zone?: string; lat?: string; lon?: string; [key: string]: unknown }>(
      'GET', `/api/cloud/devices/${gwId}`),
};

export type CloudDevice = {
  id: string;
  name?: string;
  ip?: string;
  local_key?: string;
  online?: boolean;
  category?: string;
  product_name?: string;
  product_id?: string;
  sub?: boolean;
  time_zone?: string;
};

export type CloudStatus = {
  enabled: boolean;
  accessId: string;
  region: string;
};

export type CloudConfig = {
  accessId: string;
  region: string;
  hasSecret: boolean;
  enabled: boolean;
};

export function parseDpsSchema(schemaJson?: string): DpsSchema {
  if (!schemaJson) return { entries: [] };
  try {
    return JSON.parse(schemaJson) as DpsSchema;
  } catch {
    return { entries: [] };
  }
}

/** Heuristic: detect Lingxiao-style pool pumps by the presence of their distinctive DP codes. */
export function isPoolPump(schema: DpsSchema): boolean {
  if (schema.pool_pump === true) return true;
  const codes = new Set(schema.entries.map((e) => (e as any).code as string));
  return codes.has('pump') && codes.has('clean') && codes.has('clean_speed') && codes.has('speed');
}

/** Pull a DP entry by its `code`. Returns undefined if not in schema. */
export function entryByCode(schema: DpsSchema, code: string): DpSchemaEntry | undefined {
  return schema.entries.find((e) => (e as any).code === code);
}
