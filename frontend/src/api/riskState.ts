import { MAX_RECENT_CTD_SWITCHES, MAX_RECENT_LIFECYCLE_EVENTS, type RiskSnapshot, type RiskUpdate } from './riskSnapshot';

/**
 * Client-side risk state, kept in step with the server by applying a Risk Snapshot and then each
 * Risk Update in sequence.
 *
 * - `awaiting-snapshot`: nothing to apply updates to yet.
 * - `in-sync`: the snapshot reflects every Risk Update received.
 * - `resync-required`: a sequence gap was detected; the last good snapshot is kept for display, and
 *   updates are ignored until a fresh Risk Snapshot arrives.
 */
export interface RiskState {
  snapshot: RiskSnapshot | null;
  sync: 'awaiting-snapshot' | 'in-sync' | 'resync-required';
}

export type RiskStreamMessage =
  | { type: 'risk-snapshot'; snapshot: RiskSnapshot }
  | { type: 'risk-update'; update: RiskUpdate };

export const initialRiskState: RiskState = { snapshot: null, sync: 'awaiting-snapshot' };

export function applyRiskMessage(state: RiskState, message: RiskStreamMessage): RiskState {
  if (message.type === 'risk-snapshot') {
    return { snapshot: message.snapshot, sync: 'in-sync' };
  }

  const { update } = message;
  const { snapshot } = state;
  if (snapshot === null || state.sync !== 'in-sync') {
    return state;
  }
  if (update.sequence <= snapshot.sequence) {
    return state; // stale or duplicate
  }
  if (update.sequence !== snapshot.sequence + 1) {
    return { ...state, sync: 'resync-required' };
  }
  return { snapshot: applyUpdate(snapshot, update), sync: 'in-sync' };
}

function applyUpdate(snapshot: RiskSnapshot, update: RiskUpdate): RiskSnapshot {
  const repriced = new Map(update.positions.map((p) => [p.positionId, p]));
  const known = new Set(snapshot.positions.map((p) => p.positionId));
  const positions = [
    ...snapshot.positions.map((p) => repriced.get(p.positionId) ?? p),
    ...update.positions.filter((p) => !known.has(p.positionId)),
  ];

  return {
    sequence: update.sequence,
    tick: update.tick,
    ticksUntilDayRollover: update.ticksUntilDayRollover,
    session: update.valuationDate === null ? snapshot.session : { ...snapshot.session, valuationDate: update.valuationDate },
    positions,
    bookRisk: update.bookRisk ?? snapshot.bookRisk,
    curve: update.curve === null ? snapshot.curve : { ...snapshot.curve, pillars: update.curve.pillars, points: update.curve.points },
    recentLifecycleEvents: appendRecent(snapshot.recentLifecycleEvents, update.lifecycleEvents, MAX_RECENT_LIFECYCLE_EVENTS),
    telemetry: update.telemetry,
    futures: update.futures,
    recentCtdSwitches: appendRecent(snapshot.recentCtdSwitches, update.ctdSwitches, MAX_RECENT_CTD_SWITCHES),
    credit: update.credit,
    swaps: update.swaps ?? snapshot.swaps,
  };
}

/** Appends new events to the recent ones, keeping only the latest `max`, as the backend does. */
function appendRecent<T>(recent: T[], added: T[], max: number): T[] {
  return added.length === 0 ? recent : [...recent, ...added].slice(-max);
}
