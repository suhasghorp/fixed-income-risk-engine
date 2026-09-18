import {
  Bar,
  BarChart,
  type BarShapeProps,
  CartesianGrid,
  Rectangle,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { BookRisk } from '../api/riskSnapshot';
import { formatDv01, formatMoney } from '../format';

const INSTRUMENT_TYPE_LABEL: Record<string, string> = {
  TREASURY_BOND: 'Treasuries',
  TREASURY_FUTURE: 'Treasury futures',
  CORPORATE_BOND: 'Corporates',
  INTEREST_RATE_SWAP: 'Swaps',
};

const GRID_STEP = 1_000;

/** Coloured by sign; only the data end is rounded, so the end on the zero line stays square. */
function DirectionalBar(props: BarShapeProps) {
  const negative = Number(props.value) < 0;
  return (
    <Rectangle {...props} fill={negative ? 'var(--negative)' : 'var(--accent)'} radius={negative ? [0, 0, 4, 4] : [4, 4, 0, 0]} />
  );
}

/**
 * Symmetric DV01 axis snapped to whole gridlines with headroom, so bars grow and shrink against a
 * steady scale instead of the axis rescaling every tick.
 */
function dv01Axis(values: number[]): [number, number] {
  const largest = Math.max(GRID_STEP, ...values.map(Math.abs));
  const step = 10 ** Math.floor(Math.log10(largest));
  const bound = Math.ceil((largest * 1.1) / step) * step;
  return [-bound, bound];
}

export function BookRiskPanel({ bookRisk }: { bookRisk: BookRisk }) {
  const buckets = bookRisk.bucketedDv01.map((b) => ({
    pillar: b.pillar,
    dv01: b.dv01,
  }));
  const domain = dv01Axis(buckets.map((b) => b.dv01));

  return (
    <section className="card">
      <header className="card-header">
        <h2>Book risk</h2>
        <p className="muted">
          USD per 1bp fall, on dirty value: DV01 in zero rates, CS01 in issuer Marks. Positive risk gains as rates or spreads fall.
        </p>
      </header>
      <div className="risk-grid">
        <div className="risk-summary">
          <div className="stats">
            <div className="stat">
              <span className="stat-label">Book DV01</span>
              <span className={`stat-value num ${bookRisk.dv01 < 0 ? 'short' : ''}`}>{formatDv01(bookRisk.dv01)}</span>
              <span className="muted">1bp parallel shift</span>
            </div>
            <div className="stat">
              <span className="stat-label">Book CS01</span>
              <span className={`stat-value num ${bookRisk.cs01 < 0 ? 'short' : ''}`}>{formatDv01(bookRisk.cs01)}</span>
              <span className="muted">1bp on every Mark</span>
            </div>
          </div>
          <table className="compact">
            <caption className="muted">By Instrument type</caption>
            <thead>
              <tr>
                <th>Type</th>
                <th className="num">Positions</th>
                <th className="num">Value</th>
                <th className="num">DV01</th>
                <th className="num">CS01</th>
              </tr>
            </thead>
            <tbody>
              {bookRisk.byInstrumentType.map((t) => (
                <tr key={t.instrumentType}>
                  <td>{INSTRUMENT_TYPE_LABEL[t.instrumentType] ?? t.instrumentType}</td>
                  <td className="num">{t.positionCount}</td>
                  <td className={`num ${t.value < 0 ? 'short' : ''}`}>{formatMoney(t.value)}</td>
                  <td className={`num ${t.dv01 < 0 ? 'short' : ''}`}>{formatDv01(t.dv01)}</td>
                  <td className={`num ${t.cs01 < 0 ? 'short' : ''}`}>{t.cs01 === 0 ? '—' : formatDv01(t.cs01)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <table className="compact">
            <caption className="muted">CS01 by Rating Bucket (current ratings)</caption>
            <thead>
              <tr>
                <th>Rating Bucket</th>
                <th className="num">Positions</th>
                <th className="num">Value</th>
                <th className="num">CS01</th>
              </tr>
            </thead>
            <tbody>
              {bookRisk.byRatingBucket.map((b) => (
                <tr key={b.ratingBucket} className={b.positionCount === 0 ? 'muted' : undefined}>
                  <td>{b.ratingBucket}</td>
                  <td className="num">{b.positionCount}</td>
                  <td className={`num ${b.value < 0 ? 'short' : ''}`}>{formatMoney(b.value)}</td>
                  <td className={`num ${b.cs01 < 0 ? 'short' : ''}`}>{formatDv01(b.cs01)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <figure className="bucketed">
          <figcaption className="muted">Bucketed DV01 by Pillar</figcaption>
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={buckets} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
              <CartesianGrid stroke="var(--grid)" vertical={false} />
              <XAxis dataKey="pillar" stroke="var(--muted)" tickLine={false} />
              <YAxis domain={domain} tickFormatter={(v) => formatDv01(Number(v))} stroke="var(--muted)" width={72} />
              <ReferenceLine y={0} stroke="var(--muted)" />
              <Tooltip
                cursor={{ fill: 'var(--grid)' }}
                formatter={(value) => [formatDv01(Number(value)), 'DV01']}
                contentStyle={{
                  background: 'var(--surface)',
                  border: '1px solid var(--border)',
                }}
              />
              <Bar dataKey="dv01" name="Bucketed DV01" maxBarSize={36} isAnimationActive={false} shape={DirectionalBar} />
            </BarChart>
          </ResponsiveContainer>
          <div className="table-scroll">
            <table className="compact bucket-table">
              <thead>
                <tr>
                  {buckets.map((b) => (
                    <th key={b.pillar} className="num">
                      {b.pillar}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                <tr>
                  {buckets.map((b) => (
                    <td key={b.pillar} className="num">
                      {formatDv01(b.dv01)}
                    </td>
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
        </figure>
      </div>
    </section>
  );
}
