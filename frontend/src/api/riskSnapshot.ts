/**
 * Mirrors the backend's RiskSnapshot. Prices are per 100 of face; values in USD. DV01 and CS01 are USD per
 * basis point on dirty value, positive when value rises as rates or spreads fall. Spreads are in bp.
 */
export interface RiskSnapshot {
  /** Sequence number of the last Risk Update this snapshot includes. */
  sequence: number;
  tick: number;
  /** Ticks still to go before the next Day Rollover. */
  ticksUntilDayRollover: number;
  session: SessionInfo;
  positions: PositionResult[];
  bookRisk: BookRisk;
  curve: CurveView;
  /** The latest lifecycle events, oldest first, at most MAX_RECENT_LIFECYCLE_EVENTS. */
  recentLifecycleEvents: LifecycleEvent[];
  telemetry: RepricingTelemetry;
  /** Each Treasury futures contract's market state. */
  futures: FuturesView[];
  /** The latest CTD Switches, oldest first, at most MAX_RECENT_CTD_SWITCHES. */
  recentCtdSwitches: CtdSwitchEvent[];
  credit: CreditView;
  /** Each interest rate swap's current floating period and its Fixing. */
  swaps: SwapView[];
}

/** A swap's floating leg as of the Valuation Date: the current coupon is known from its Fixing. */
export interface SwapView {
  instrumentId: string;
  description: string;
  direction: 'PAY_FIXED' | 'RECEIVE_FIXED';
  fixedRate: number;
  /** Null when the swap has not started or has matured; likewise the other current-period fields. */
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  currentFixing: number | null;
  nextResetDate: string | null;
}

/** What the risk engine sees of the credit market: never Latent Spreads. */
export interface CreditView {
  systemicBp: number;
  sectors: SectorLevel[];
  issuers: IssuerView[];
}

export interface SectorLevel {
  ratingBucket: string;
  levelBp: number;
}

/** An issuer's Mark and the evidence behind it. */
export interface IssuerView {
  issuerId: string;
  name: string;
  ratingBucket: string;
  markBp: number;
  lastPrintBp: number | null;
  lastPrintTick: number | null;
  lastQuoteBp: number | null;
  lastQuoteTick: number | null;
  /** The current Print intensity, including any burst after a Credit Event. */
  printsPerYear: number;
  /** The Rating Bucket before the latest Rating Migration, or null if none. */
  migratedFrom: string | null;
  migrationTick: number | null;
  /** True after a Rating Migration until the next Print or Quote: "downgraded, Mark stale". */
  markStale: boolean;
}

/** Mirrors RiskSnapshot.MAX_RECENT_CTD_SWITCHES on the backend. */
export const MAX_RECENT_CTD_SWITCHES = 20;

/** A Treasury futures contract's market state: its current Proxy Bond (standing in for the CTD) and Basis. */
export interface FuturesView {
  contract: string;
  description: string;
  proxyBondId: string;
  proxyBondDescription: string;
  conversionFactor: number;
  /** In price points per 100 face. */
  basis: number;
  ctdSwitchCount: number;
  lastCtdSwitchTick: number | null;
}

/** A CTD Switch: the contract's Proxy Bond was replaced and its Basis jumped. */
export interface CtdSwitchEvent {
  tick: number;
  contract: string;
  fromProxyBondId: string;
  toProxyBondId: string;
  /** In price points. */
  basisJump: number;
}

/** Selective repricing made visible: what the latest cycle repriced, and how stale the rest is. */
export interface RepricingTelemetry {
  instrumentsRepriced: number;
  instrumentsTotal: number;
  /** Per thresholded factor type, the largest move of any dependency since its Instrument was last priced. */
  maxStaleness: FactorStaleness[];
  /** Ticks merged into the latest cycle beyond the one it priced (latest-wins coalescing). */
  ticksCoalesced: number;
  /** Ticks coalesced since the session started. */
  totalTicksCoalesced: number;
}

export interface FactorStaleness {
  factorType: string;
  unit: string;
  /** Never more than the threshold. */
  maxStaleness: number;
  threshold: number;
}

/** Mirrors RiskSnapshot.MAX_RECENT_LIFECYCLE_EVENTS on the backend. */
export const MAX_RECENT_LIFECYCLE_EVENTS = 20;

export interface SessionInfo {
  curveSource: 'LIVE' | 'CACHED' | 'BUNDLED';
  curveDate: string;
  valuationDate: string;
  seed: number;
  simulatedSecondsPerTick: number;
  /** Ticks per simulated day, between Day Rollovers. */
  ticksPerDay: number;
  /** The Tick the simulation stops at, or null if it runs indefinitely. */
  stopAtTick: number | null;
}

/** A coupon or redemption paid to a Position, processed at a Day Rollover. */
export interface LifecycleEvent {
  /** The Tick whose Day Rollover processed it. */
  tick: number;
  date: string;
  positionId: string;
  instrumentId: string;
  description: string;
  kind: 'COUPON' | 'REDEMPTION' | 'FIXED_LEG' | 'FLOATING_LEG';
  amountPer100: number;
  /** Paid to the Position; negative when short. */
  amount: number;
}

export interface PositionResult {
  positionId: string;
  instrumentId: string;
  instrumentType: string;
  description: string;
  quantity: number;
  cleanPrice: number;
  accruedInterest: number;
  dirtyPrice: number;
  value: number;
  dv01: number;
  bucketedDv01: BucketDv01[];
  cs01: number;
  /** The issuer's current Rating Bucket, for corporate bonds; otherwise null. */
  ratingBucket: string | null;
  /** The Tick this Position's Instrument was last repriced at. */
  lastPricedTick: number;
}

/** DV01 for a 1bp bump at one Pillar, fading to zero at the neighbouring Pillars. */
export interface BucketDv01 {
  pillar: string;
  years: number;
  dv01: number;
}

/** Book-level risk rolled up from Position contributions. */
export interface BookRisk {
  value: number;
  dv01: number;
  bucketedDv01: BucketDv01[];
  cs01: number;
  byInstrumentType: InstrumentTypeRisk[];
  /** Every Rating Bucket, by each issuer's current rating, including empty ones. */
  byRatingBucket: RatingBucketRisk[];
}

export interface InstrumentTypeRisk {
  instrumentType: string;
  positionCount: number;
  value: number;
  dv01: number;
  cs01: number;
}

export interface RatingBucketRisk {
  ratingBucket: string;
  positionCount: number;
  value: number;
  dv01: number;
  cs01: number;
}

export interface CurveView {
  pillars: CurvePoint[];
  points: CurvePoint[];
  parInputs: ParInput[];
}

export interface CurvePoint {
  label: string | null;
  years: number;
  zeroRate: number;
}

export interface ParInput {
  tenor: string;
  years: number;
  parYield: number;
}

/** Mirrors the backend's RiskUpdate: only what changed in one cycle. */
export interface RiskUpdate {
  sequence: number;
  tick: number;
  ticksUntilDayRollover: number;
  /** New Valuation Date, or null if unchanged. */
  valuationDate: string | null;
  /** Only the Position results repriced this cycle. */
  positions: PositionResult[];
  /** New Book rollups, or null if unchanged. */
  bookRisk: BookRisk | null;
  /** New curve points, or null if unchanged. */
  curve: { pillars: CurvePoint[]; points: CurvePoint[] } | null;
  /** Coupons and redemptions processed this cycle; only a Day Rollover has any. */
  lifecycleEvents: LifecycleEvent[];
  telemetry: RepricingTelemetry;
  /** Every futures contract's market state; the Basis moves every Tick. */
  futures: FuturesView[];
  /** CTD Switches that fired this cycle. */
  ctdSwitches: CtdSwitchEvent[];
  /** The credit market's observable state and Marks. */
  credit: CreditView;
  /** Every swap's current period and Fixing, on a Day Rollover; null if unchanged. */
  swaps: SwapView[] | null;
}
