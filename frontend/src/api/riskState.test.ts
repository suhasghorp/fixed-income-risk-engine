import { describe, expect, it } from 'vitest';
import { applyRiskMessage, initialRiskState, type RiskState } from './riskState';
import {
  type BookRisk,
  type CreditView,
  type FuturesView,
  type LifecycleEvent,
  MAX_RECENT_LIFECYCLE_EVENTS,
  type PositionResult,
  type RiskSnapshot,
  type RepricingTelemetry,
  type RiskUpdate,
  type SwapView,
} from './riskSnapshot';

const position = (positionId: string, dirtyPrice: number): PositionResult => ({
  positionId,
  instrumentId: `CUSIP-${positionId}`,
  instrumentType: 'TREASURY_BOND',
  description: `UST ${positionId}`,
  quantity: 1_000_000,
  notionalCurrency: 'USD',
  cleanPrice: dirtyPrice - 0.1,
  accruedInterest: 0.1,
  dirtyPrice,
  value: dirtyPrice * 10_000,
  dv01: dirtyPrice,
  bucketedDv01: [{ pillar: '2Y', years: 2, dv01: dirtyPrice }],
  ratesByCurrency: [{ currency: 'USD', dv01: dirtyPrice, bucketedDv01: [{ pillar: '2Y', years: 2, dv01: dirtyPrice }] }],
  cs01: 0,
  fxDelta: [],
  pointsDelta: [],
  ratingBucket: null,
  lastPricedTick: 0,
});

const credit = (markBp: number): CreditView => ({
  systemicBp: 60,
  sectors: [{ ratingBucket: 'A Industrials', levelBp: 25 }],
  issuers: [
    {
      issuerId: 'ACME',
      name: 'Acme Industries',
      ratingBucket: 'A Industrials',
      markBp,
      lastPrintBp: null,
      lastPrintTick: null,
      lastQuoteBp: null,
      lastQuoteTick: null,
      printsPerYear: 60,
      migratedFrom: null,
      migrationTick: null,
      markStale: false,
    },
  ],
});

const telemetry = (instrumentsRepriced: number, maxStaleness: number): RepricingTelemetry => ({
  instrumentsRepriced,
  instrumentsTotal: 2,
  maxStaleness: [{ factorType: 'PILLAR_ZERO_RATE', unit: 'bp', maxStaleness, threshold: 2 }],
  ticksCoalesced: 0,
  totalTicksCoalesced: 0,
});

const bookRisk = (dv01: number): BookRisk => ({
  value: dv01 * 10_000,
  dv01,
  bucketedDv01: [{ pillar: '2Y', years: 2, dv01 }],
  ratesByCurrency: [{ currency: 'USD', dv01, bucketedDv01: [{ pillar: '2Y', years: 2, dv01 }] }],
  cs01: 0,
  fxDeltaByCurrency: [{ currency: 'EUR', amount: 0 }],
  pointsDeltaByPair: [{ pair: 'USDKRW', amount: 0 }],
  byInstrumentType: [{ instrumentType: 'TREASURY_BOND', positionCount: 2, value: dv01 * 10_000, dv01, cs01: 0 }],
  byRatingBucket: [{ ratingBucket: 'A Industrials', positionCount: 0, value: 0, dv01: 0, cs01: 0 }],
});

const coupon = (tick: number, positionId = 'P01'): LifecycleEvent => ({
  tick,
  date: '2026-09-13',
  currency: 'USD',
  positionId,
  instrumentId: `CUSIP-${positionId}`,
  description: `UST ${positionId}`,
  kind: 'COUPON',
  amountPer100: 2,
  amount: 20_000,
});

const futuresView = (ctdSwitchCount: number, basis: number): FuturesView => ({
  contract: 'ZNZ6',
  description: 'ZN Dec26 10Y note future',
  proxyBondId: `ZNZ6-CTD${ctdSwitchCount + 1}`,
  proxyBondDescription: 'UST 4.125% 08/15/2033',
  conversionFactor: 0.9003,
  basis,
  ctdSwitchCount,
  lastCtdSwitchTick: ctdSwitchCount === 0 ? null : 2,
});

const snapshot = (sequence: number): RiskSnapshot => ({
  sequence,
  tick: sequence,
  ticksUntilDayRollover: 24 - (sequence % 24),
  session: {
    curveSource: 'BUNDLED',
    curveDate: '2026-09-11',
    curves: [
      { currency: 'USD', source: 'BUNDLED', date: '2026-09-11', quotes: 'PAR_YIELD' },
      { currency: 'EUR', source: 'BUNDLED', date: '2026-09-17', quotes: 'ZERO_RATE' },
    ],
    valuationDate: '2026-09-11',
    seed: 42,
    simulatedSecondsPerTick: 3600,
    ticksPerDay: 24,
    stopAtTick: null,
  },
  fx: { pairs: [], contracts: [] },
  positions: [position('P01', 99), position('P02', 98)],
  bookRisk: bookRisk(197),
  curve: {
    pillars: [{ label: '2Y', years: 2, zeroRate: 0.045 }],
    points: [{ label: null, years: 2, zeroRate: 0.045 }],
    parInputs: [{ tenor: '2Y', years: 2, parYield: 0.046 }],
  },
  recentLifecycleEvents: [],
  telemetry: telemetry(2, 0),
  futures: [futuresView(0, -0.2)],
  recentCtdSwitches: [],
  credit: credit(95),
  swaps: [swapView(0.043)],
});

function swapView(currentFixing: number): SwapView {
  return {
    instrumentId: 'IRS-5Y-PAY',
    description: 'Pay 3.950% fixed vs USD-3M to 07/15/2031',
    direction: 'PAY_FIXED',
    fixedRate: 0.0395,
    currentPeriodStart: '2026-07-15',
    currentPeriodEnd: '2026-10-15',
    currentFixing,
    nextResetDate: '2026-10-15',
  };
}

const update = (sequence: number, overrides: Partial<RiskUpdate> = {}): RiskUpdate => ({
  sequence,
  tick: sequence,
  ticksUntilDayRollover: 24 - (sequence % 24),
  valuationDate: null,
  fx: null,
  positions: [position('P01', 99 + sequence / 100)],
  bookRisk: bookRisk(197 + sequence / 100),
  curve: {
    pillars: [{ label: '2Y', years: 2, zeroRate: 0.045 + sequence / 10_000 }],
    points: [{ label: null, years: 2, zeroRate: 0.045 + sequence / 10_000 }],
  },
  lifecycleEvents: [],
  telemetry: telemetry(1, sequence / 10),
  futures: [futuresView(0, -0.2 + sequence / 1000)],
  ctdSwitches: [],
  credit: credit(95 + sequence / 10),
  swaps: null,
  ...overrides,
});

const withSnapshot = (sequence: number): RiskState =>
  applyRiskMessage(initialRiskState, { type: 'risk-snapshot', snapshot: snapshot(sequence) });

describe('applyRiskMessage', () => {
  it('ignores Risk Updates until a Risk Snapshot arrives', () => {
    const state = applyRiskMessage(initialRiskState, { type: 'risk-update', update: update(1) });

    expect(state).toEqual(initialRiskState);
  });

  it('applies consecutive Risk Updates on top of the snapshot', () => {
    let state = withSnapshot(5);
    state = applyRiskMessage(state, { type: 'risk-update', update: update(6) });
    state = applyRiskMessage(state, { type: 'risk-update', update: update(7) });

    expect(state.sync).toBe('in-sync');
    expect(state.snapshot?.sequence).toBe(7);
    expect(state.snapshot?.tick).toBe(7);
    expect(state.snapshot?.positions.map((p) => [p.positionId, p.dirtyPrice])).toEqual([
      ['P01', 99.07],
      ['P02', 98],
    ]);
    expect(state.snapshot?.curve.pillars[0].zeroRate).toBeCloseTo(0.0457);
    expect(state.snapshot?.curve.parInputs).toEqual(snapshot(5).curve.parInputs);
    expect(state.snapshot?.bookRisk.dv01).toBeCloseTo(197.07);
    expect(state.snapshot?.telemetry).toEqual(telemetry(1, 0.7));
    expect(state.snapshot?.credit).toEqual(credit(95.7));
  });

  it('keeps unchanged parts when an update omits the curve and Valuation Date', () => {
    const state = applyRiskMessage(withSnapshot(1), {
      type: 'risk-update',
      update: update(2, { curve: null, valuationDate: null, positions: [], bookRisk: null }),
    });

    expect(state.snapshot?.bookRisk).toEqual(snapshot(1).bookRisk);
    expect(state.snapshot?.curve).toEqual(snapshot(1).curve);
    expect(state.snapshot?.positions).toEqual(snapshot(1).positions);
    expect(state.snapshot?.sequence).toBe(2);
  });

  it('applies a new Valuation Date', () => {
    const state = applyRiskMessage(withSnapshot(1), {
      type: 'risk-update',
      update: update(2, { valuationDate: '2026-09-12' }),
    });

    expect(state.snapshot?.session.valuationDate).toBe('2026-09-12');
  });

  it('tracks the ticks until the next Day Rollover', () => {
    const state = applyRiskMessage(withSnapshot(1), { type: 'risk-update', update: update(2, { ticksUntilDayRollover: 22 }) });

    expect(state.snapshot?.ticksUntilDayRollover).toBe(22);
  });

  it('appends lifecycle events from a Day Rollover and keeps only the most recent', () => {
    let state = withSnapshot(1);
    state = applyRiskMessage(state, {
      type: 'risk-update',
      update: update(2, { valuationDate: '2026-09-13', lifecycleEvents: [coupon(2, 'P01'), coupon(2, 'P02')] }),
    });
    expect(state.snapshot?.recentLifecycleEvents.map((e) => e.positionId)).toEqual(['P01', 'P02']);

    for (let sequence = 3; sequence <= 12; sequence++) {
      state = applyRiskMessage(state, {
        type: 'risk-update',
        update: update(sequence, { lifecycleEvents: [coupon(sequence, 'A'), coupon(sequence, 'B')] }),
      });
    }

    const events = state.snapshot?.recentLifecycleEvents ?? [];
    expect(events).toHaveLength(MAX_RECENT_LIFECYCLE_EVENTS);
    expect(events[0].tick).toBe(3);
    expect(events.at(-1)?.tick).toBe(12);
  });

  it('replaces the futures state and appends CTD Switches', () => {
    const ctdSwitch = { tick: 2, contract: 'ZNZ6', fromProxyBondId: 'ZNZ6-CTD1', toProxyBondId: 'ZNZ6-CTD2', basisJump: 0.15 };
    const state = applyRiskMessage(withSnapshot(1), {
      type: 'risk-update',
      update: update(2, { futures: [futuresView(1, -0.05)], ctdSwitches: [ctdSwitch] }),
    });

    expect(state.snapshot?.futures).toEqual([futuresView(1, -0.05)]);
    expect(state.snapshot?.recentCtdSwitches).toEqual([ctdSwitch]);
  });

  it('keeps the swaps until a Day Rollover sends new ones', () => {
    let state = applyRiskMessage(withSnapshot(1), { type: 'risk-update', update: update(2) });
    expect(state.snapshot?.swaps).toEqual([swapView(0.043)]);

    state = applyRiskMessage(state, { type: 'risk-update', update: update(3, { valuationDate: '2026-10-15', swaps: [swapView(0.041)] }) });
    expect(state.snapshot?.swaps).toEqual([swapView(0.041)]);
  });

  it('ignores stale and duplicate Risk Updates', () => {
    const state = withSnapshot(10);

    expect(applyRiskMessage(state, { type: 'risk-update', update: update(10) })).toBe(state);
    expect(applyRiskMessage(state, { type: 'risk-update', update: update(3) })).toBe(state);
  });

  it('detects a sequence gap, keeps the last good snapshot, and requires a resync', () => {
    const state = applyRiskMessage(withSnapshot(4), { type: 'risk-update', update: update(6) });

    expect(state.sync).toBe('resync-required');
    expect(state.snapshot).toEqual(snapshot(4));
  });

  it('ignores further updates while a resync is pending, and recovers on a fresh snapshot', () => {
    let state = applyRiskMessage(withSnapshot(4), { type: 'risk-update', update: update(6) });
    state = applyRiskMessage(state, { type: 'risk-update', update: update(7) });
    expect(state.snapshot?.sequence).toBe(4);

    state = applyRiskMessage(state, { type: 'risk-snapshot', snapshot: snapshot(9) });
    state = applyRiskMessage(state, { type: 'risk-update', update: update(10) });

    expect(state.sync).toBe('in-sync');
    expect(state.snapshot?.sequence).toBe(10);
  });
});
