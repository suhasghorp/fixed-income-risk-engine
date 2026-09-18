import type { RepricingTelemetry as Telemetry } from '../api/riskSnapshot';

const FACTOR_LABEL: Record<string, string> = {
  PILLAR_ZERO_RATE: 'Pillar zero rate',
  MARK: 'Mark',
  SYSTEMIC: 'Systemic Factor',
  SECTOR: 'Sector Factor',
  BASIS: 'Basis',
};

const twoDecimals = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const threeDecimals = new Intl.NumberFormat('en-US', { minimumFractionDigits: 3, maximumFractionDigits: 3 });

/** Basis is in price points with thresholds of a few hundredths, so it needs an extra decimal. */
const formatStaleness = (value: number, unit: string) =>
  `${(unit === 'pts' ? threeDecimals : twoDecimals).format(value)}${unit}`;

/** What selective repricing saved this tick, and what it costs in Staleness. */
export function RepricingTelemetry({ telemetry }: { telemetry: Telemetry }) {
  return (
    <dl className="telemetry">
      <div>
        <dt>Repriced this cycle</dt>
        <dd className="num">
          {telemetry.instrumentsRepriced} <span className="muted">/ {telemetry.instrumentsTotal} Instruments</span>
        </dd>
      </div>
      <div>
        <dt title="Ticks the simulation advanced while the last repricing cycle ran: merged, never priced individually">
          Ticks coalesced
        </dt>
        <dd className="num">
          {telemetry.ticksCoalesced} <span className="muted">this cycle · {telemetry.totalTicksCoalesced.toLocaleString('en-US')} total</span>
        </dd>
      </div>
      {telemetry.maxStaleness.map((s) => (
        <div key={s.factorType}>
          <dt title="Largest move of any dependency since its Instrument was last priced; never more than the Materiality Threshold">
            Max Staleness · {FACTOR_LABEL[s.factorType] ?? s.factorType}
          </dt>
          <dd className="num">
            {formatStaleness(s.maxStaleness, s.unit)} <span className="muted">of {s.threshold}{s.unit} threshold</span>
            <span className="staleness-bar" aria-hidden="true">
              <span style={{ width: `${Math.min(100, (s.maxStaleness / (s.threshold || 1)) * 100)}%` }} />
            </span>
          </dd>
        </div>
      ))}
    </dl>
  );
}
