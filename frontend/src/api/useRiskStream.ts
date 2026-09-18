import { useEffect, useReducer, useState } from 'react';
import type { RiskSnapshot, RiskUpdate } from './riskSnapshot';
import { applyRiskMessage, initialRiskState, type RiskState } from './riskState';

const STREAM_URL = '/api/risk/stream';
const RECONNECT_DELAY_MS = 2_000;

export type StreamStatus = 'connecting' | 'live' | 'resyncing' | 'disconnected';

/**
 * Subscribes to the risk stream and keeps client state in step with it. On a sequence gap it
 * requests a fresh Risk Snapshot by reconnecting (the server always opens with a snapshot).
 */
export function useRiskStream(): { state: RiskState; status: StreamStatus } {
  const [state, dispatch] = useReducer(applyRiskMessage, initialRiskState);
  const [connection, setConnection] = useState(0);
  const [status, setStatus] = useState<StreamStatus>('connecting');

  useEffect(() => {
    const source = new EventSource(STREAM_URL);
    source.addEventListener('risk-snapshot', (event) => {
      dispatch({ type: 'risk-snapshot', snapshot: JSON.parse((event as MessageEvent<string>).data) as RiskSnapshot });
      setStatus('live');
    });
    source.addEventListener('risk-update', (event) => {
      dispatch({ type: 'risk-update', update: JSON.parse((event as MessageEvent<string>).data) as RiskUpdate });
    });
    let retry: ReturnType<typeof setTimeout> | undefined;
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) {
        // The browser gives up for good on an HTTP error (e.g. a proxy 502 while the backend restarts),
        // so reconnect ourselves. The server opens every connection with a fresh Risk Snapshot.
        setStatus('disconnected');
        retry = setTimeout(() => setConnection((n) => n + 1), RECONNECT_DELAY_MS);
      } else {
        setStatus('connecting'); // EventSource is retrying on its own
      }
    };
    return () => {
      clearTimeout(retry);
      source.close();
    };
  }, [connection]);

  useEffect(() => {
    if (state.sync === 'resync-required') {
      setStatus('resyncing');
      setConnection((n) => n + 1);
    }
  }, [state.sync]);

  return { state, status };
}
