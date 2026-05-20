#!/usr/bin/env node
/**
 * MCP server that exposes Tuya REST endpoints as tools.
 *
 * Communicates over stdio. Forwards calls to the running tuya-server (default
 * http://localhost:8082). Override with TUYA_SERVER_URL env var.
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';

const BASE = process.env.TUYA_SERVER_URL ?? 'http://localhost:8082';

async function call(method: string, path: string, body?: unknown): Promise<unknown> {
  const res = await fetch(BASE + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed: unknown;
  try { parsed = text ? JSON.parse(text) : null; } catch { parsed = text; }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText} from ${method} ${path}: ${text || '(empty body)'}`);
  }
  return parsed;
}

const TOOLS = [
  {
    name: 'tuya_list_devices',
    description:
      'List devices registered locally with the tuya-server. Returns id, name, gwId, ip, protocolVersion, connected status, and the dpsSchemaJson for each. Use this to find a device id before calling set/get.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'tuya_get_state',
    description:
      'Get the current DP (data point) values for one locally-registered device. Returns {dps: {"1": true, "2": 0, ...}, at: "ISO timestamp"}. DP "1" is typically the main power switch.',
    inputSchema: {
      type: 'object',
      properties: { deviceId: { type: 'number', description: 'numeric id of the registered device (from tuya_list_devices)' } },
      required: ['deviceId'],
      additionalProperties: false,
    },
  },
  {
    name: 'tuya_set_dp',
    description:
      'Set one or more data points on a device. DP keys are stringified integer indexes (e.g. "1"). Values are bool, number, or string per the device schema. Common: set DP "1" to true to turn on a switch.',
    inputSchema: {
      type: 'object',
      properties: {
        deviceId: { type: 'number' },
        dps: {
          type: 'object',
          description: 'map of DP index → value, e.g. {"1": true}',
          additionalProperties: true,
        },
      },
      required: ['deviceId', 'dps'],
      additionalProperties: false,
    },
  },
  {
    name: 'tuya_register_local',
    description:
      'Register a new device for LAN control. Requires the device gwId (Tuya device ID), 16-character localKey, IP address, and optional friendly name + JSON dpsSchema.',
    inputSchema: {
      type: 'object',
      properties: {
        name: { type: 'string' },
        gwId: { type: 'string' },
        localKey: { type: 'string', description: '16-character ASCII AES key' },
        ip: { type: 'string' },
        protocolVersion: { type: 'string', enum: ['3.3'], default: '3.3' },
        dpsSchemaJson: { type: 'string', description: 'optional JSON describing the DP layout' },
      },
      required: ['name', 'gwId', 'localKey', 'ip'],
      additionalProperties: false,
    },
  },
  {
    name: 'tuya_import_from_cloud',
    description:
      'Import a device from the Tuya cloud project: looks up its local_key automatically and registers it. Requires the deviceId, LAN ip, and optional name. Will fail with "No permissions" if the Tuya project does not have factory-infos API access.',
    inputSchema: {
      type: 'object',
      properties: {
        deviceId: { type: 'string' },
        ip: { type: 'string' },
        protocolVersion: { type: 'string', enum: ['3.3'], default: '3.3' },
        name: { type: 'string' },
      },
      required: ['deviceId', 'ip'],
      additionalProperties: false,
    },
  },
  {
    name: 'tuya_delete_device',
    description: 'Delete a locally-registered device by numeric id.',
    inputSchema: {
      type: 'object',
      properties: { deviceId: { type: 'number' } },
      required: ['deviceId'],
      additionalProperties: false,
    },
  },
  {
    name: 'tuya_list_cloud_devices',
    description:
      'List devices linked to the Tuya cloud project (via app account). Calls /v1.0/iot-01/associated-users/devices on Tuya. Will return code=28841001 No permissions if the project lacks the right API subscription.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'tuya_cloud_status',
    description:
      'Return whether the Tuya cloud integration is configured (access ID / secret saved and accepted).',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'tuya_test_cloud',
    description:
      'Verify the saved Tuya cloud credentials by requesting a fresh access token. Returns {ok: true} or {ok: false, error: "..."}.',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
];

const server = new Server(
  { name: 'tuya-mcp', version: '0.0.1' },
  { capabilities: { tools: {} } }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: TOOLS }));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const { name, arguments: rawArgs } = req.params;
  const args = (rawArgs ?? {}) as Record<string, unknown>;
  try {
    let result: unknown;
    switch (name) {
      case 'tuya_list_devices':
        result = await call('GET', '/api/devices');
        break;
      case 'tuya_get_state':
        result = await call('GET', `/api/devices/${args.deviceId}/state`);
        break;
      case 'tuya_set_dp':
        result = await call('POST', `/api/devices/${args.deviceId}/dps`, { dps: args.dps });
        break;
      case 'tuya_register_local':
        result = await call('POST', '/api/devices', {
          name: args.name, gwId: args.gwId, localKey: args.localKey,
          ip: args.ip, protocolVersion: args.protocolVersion ?? '3.3',
          source: 'LOCAL', dpsSchemaJson: args.dpsSchemaJson,
        });
        break;
      case 'tuya_import_from_cloud':
        result = await call('POST', '/api/devices/from-cloud', {
          deviceId: args.deviceId, ip: args.ip,
          protocolVersion: args.protocolVersion ?? '3.3',
          name: args.name,
        });
        break;
      case 'tuya_delete_device':
        await call('DELETE', `/api/devices/${args.deviceId}`);
        result = { deleted: args.deviceId };
        break;
      case 'tuya_list_cloud_devices':
        result = await call('GET', '/api/cloud/devices?pageSize=50');
        break;
      case 'tuya_cloud_status':
        result = await call('GET', '/api/cloud/status');
        break;
      case 'tuya_test_cloud':
        result = await call('POST', '/api/cloud/test', {});
        break;
      default:
        throw new Error(`Unknown tool: ${name}`);
    }
    return {
      content: [{ type: 'text', text: typeof result === 'string' ? result : JSON.stringify(result, null, 2) }],
    };
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : String(e);
    return {
      content: [{ type: 'text', text: `Error: ${message}` }],
      isError: true,
    };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
// stay alive
process.stdin.resume();
