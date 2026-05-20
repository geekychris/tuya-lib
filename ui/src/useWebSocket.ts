import { useEffect, useRef } from 'react';

type Message = { deviceId: number; dps: Record<string, unknown>; at: string };

export function useDeviceStateSocket(onMessage: (msg: Message) => void) {
  const handlerRef = useRef(onMessage);
  handlerRef.current = onMessage;

  useEffect(() => {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${proto}//${window.location.host}/ws/devices`;
    let ws: WebSocket | null = null;
    let stopped = false;
    let backoffMs = 1000;

    const connect = () => {
      if (stopped) return;
      ws = new WebSocket(url);
      ws.onmessage = (e) => {
        try {
          handlerRef.current(JSON.parse(e.data));
        } catch { /* ignore non-JSON */ }
      };
      ws.onopen = () => { backoffMs = 1000; };
      ws.onclose = () => {
        if (stopped) return;
        setTimeout(connect, backoffMs);
        backoffMs = Math.min(backoffMs * 2, 15000);
      };
      ws.onerror = () => { ws?.close(); };
    };

    connect();
    return () => {
      stopped = true;
      ws?.close();
    };
  }, []);
}
