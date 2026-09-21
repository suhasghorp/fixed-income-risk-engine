import type { PositionResult, SwaptionView } from '../api/riskSnapshot';
import { formatBp, formatDv01, formatMoney, formatPercent, formatQuantity } from '../format';

interface Props {
  swaptions: SwaptionView[];
  positions: PositionResult[];
}

const DIRECTION_LABEL: Record<string, string> = {
  PAYER: 'Payer',
  RECEIVER: 'Receiver',
};

/** In the money when a payer's forward is above its strike, a receiver's below. */
function moneyness(swaption: SwaptionView): { label: string; positive: boolean } | null {
  if (swaption.forwardRate === null) {
    return null;
  }
  const bp = (swaption.forwardRate - swaption.strike) * 1e4 * (swaption.direction === 'PAYER' ? 1 : -1);
  return { label: `${bp >= 0 ? '+' : ''}${bp.toFixed(2)}bp`, positive: bp >= 0 };
}

/** Pending until the Expiry, then whatever was decided there — and it is never revisited. */
function decision(swaption: SwaptionView): { label: string; title: string } {
  if (swaption.exercised === null) {
    return { label: `Expires ${swaption.expiryDate}`, title: 'The Exercise Decision is taken on the Expiry' };
  }
  return swaption.exercised
    ? { label: 'Exercised', title: 'In the money at the Expiry: the Position holds the underlying swap now' }
    : { label: 'Lapsed', title: 'Out of the money at the Expiry: worth zero, and still in the Book' };
}

/**
 * Swaptions: the Book's only optionality, and the only Positions priced off a Risk Factor no curve can
 * produce. Each shows the Normal Volatility quoted at its own Surface Point — the surface is invented, and
 * these two points are all of it — the Forward Swap Rate against the strike, which is what the Exercise
 * Decision turns on, and the two risk numbers optionality brings with it. The decision is taken once, on
 * the Expiry, and never revisited; before then the row shows the date it is coming.
 */
export function SwaptionsPanel({ swaptions, positions }: Props) {
  const byInstrument = new Map(positions.map((p) => [p.instrumentId, p]));

  return (
    <section className="card">
      <header className="card-header">
        <h2>Swaptions</h2>
        <p className="muted">
          Priced with the Bachelier (normal) model off a <em>quoted</em> Normal Volatility, not off the simulator's own
          Hull-White σ. The surface is synthetic: two named points, no grid, no interpolation and no smile, so each option
          prices at its own point's vol whatever strike it carries. Vega is per 1bp of that vol; Gamma is the DV01 change
          for a +25bp parallel shift, which on these is nearly a whole DV01.
        </p>
      </header>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Position</th>
              <th>Swaption</th>
              <th className="num">Notional</th>
              <th>Surface Point</th>
              <th className="num">Normal Vol</th>
              <th className="num">Strike</th>
              <th className="num">Forward</th>
              <th className="num">Moneyness</th>
              <th className="num">Value</th>
              <th className="num">DV01</th>
              <th className="num">Gamma</th>
              <th className="num">Vega</th>
              <th>Underlying</th>
              <th>Exercise</th>
            </tr>
          </thead>
          <tbody>
            {swaptions.map((s) => {
              const position = byInstrument.get(s.instrumentId);
              const vega = position?.vega.find((v) => v.amount !== 0);
              const money = moneyness(s);
              const exercise = decision(s);
              return (
                <tr key={s.instrumentId}>
                  <td>{position?.positionId ?? '—'}</td>
                  <td>
                    {DIRECTION_LABEL[s.direction] ?? s.direction} <span className="mono">{s.surfacePoint.split(' ')[1]}</span>
                  </td>
                  <td className="num">{position ? formatQuantity(position.quantity) : '—'}</td>
                  <td className="mono">{s.surfacePoint}</td>
                  <td className="num" title="Quoted, not derived — and invented, not observed">
                    {formatBp(s.normalVolBp)}
                  </td>
                  <td className="num">{formatPercent(s.strike, 4)}</td>
                  <td className="num" title="The rate that would make the underlying swap worth zero">
                    {s.forwardRate === null ? '—' : formatPercent(s.forwardRate, 4)}
                  </td>
                  <td className={`num ${money && !money.positive ? 'short' : ''}`} title="Forward against strike, from this side">
                    {money?.label ?? '—'}
                  </td>
                  <td className={`num ${(position?.value ?? 0) < 0 ? 'short' : ''}`}>
                    {position ? formatMoney(position.value) : '—'}
                  </td>
                  <td className={`num ${(position?.dv01 ?? 0) < 0 ? 'short' : ''}`}>
                    {position ? formatDv01(position.dv01) : '—'}
                  </td>
                  <td className={`num ${(position?.gamma ?? 0) < 0 ? 'short' : ''}`} title="DV01 change for +25bp">
                    {position ? formatDv01(position.gamma) : '—'}
                  </td>
                  <td className={`num ${(vega?.amount ?? 0) < 0 ? 'short' : ''}`} title="Per 1bp rise in this point's Normal Volatility">
                    {vega ? formatDv01(vega.amount) : '—'}
                    {vega && <span className="ccy-suffix">{vega.currency}</span>}
                  </td>
                  <td title="The swap this option exercises into">{s.underlyingMaturityDate}</td>
                  <td title={exercise.title}>{exercise.label}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
