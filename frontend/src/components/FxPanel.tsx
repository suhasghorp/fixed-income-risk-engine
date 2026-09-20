import type { FxView, PositionResult } from '../api/riskSnapshot';
import { formatDv01, formatMoney, formatQuantity } from '../format';

interface Props {
  fx: FxView;
  positions: PositionResult[];
}

const DIRECTION_LABEL: Record<string, string> = {
  BUY_BASE: 'Buy',
  SELL_BASE: 'Sell',
};

/** Rates are quoted to four decimals for a pair near 1 and two for a pair near 1,000. */
const rate = (value: number) => value.toLocaleString('en-US', { minimumFractionDigits: value < 10 ? 4 : 2, maximumFractionDigits: value < 10 ? 4 : 2 });

/**
 * FX Forwards: the simulated market they price from, and each contract's terms against the rate it would
 * be struck at today. The two differ on purpose — the outright's forward is derived from two curves, the
 * NDF's is quoted as spot plus Forward Points, because there is no curve for the won to derive one from.
 */
export function FxPanel({ fx, positions }: Props) {
  const byInstrument = new Map(positions.map((p) => [p.instrumentId, p]));

  return (
    <section className="card">
      <header className="card-header">
        <h2>FX Forwards</h2>
        <p className="muted">
          The EUR/USD outright's forward is <em>derived</em> from the two curves and spot by covered interest parity. The USD/KRW
          NDF's is <em>quoted</em>: spot plus Forward Points, which are simulated and synthetic. One has DV01 in two curves and
          no points delta; the other has one curve and a points delta.
        </p>
      </header>
      <div className="table-scroll">
        <table className="compact">
          <caption className="muted">Market</caption>
          <thead>
            <tr>
              <th>Pair</th>
              <th>Risk currency</th>
              <th className="num">Spot</th>
              <th className="num">Forward Points</th>
            </tr>
          </thead>
          <tbody>
            {fx.pairs.map((p) => (
              <tr key={p.pair}>
                <td className="mono">{p.pair}</td>
                <td>{p.riskCurrency}</td>
                <td className="num">{rate(p.spot)}</td>
                <td className="num" title={p.points === null ? 'Deliverable: the forward comes from two curves' : 'pips'}>
                  {p.points === null ? '—' : `${p.points.toFixed(1)} pips`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Position</th>
              <th>Contract</th>
              <th className="num">Notional</th>
              <th className="num">Contract rate</th>
              <th className="num">Forward now</th>
              <th className="num">Value</th>
              <th className="num">FX Delta</th>
              <th className="num">Points delta</th>
              <th>Fixing date</th>
              <th className="num">FX Fixing</th>
              <th>Settles</th>
            </tr>
          </thead>
          <tbody>
            {fx.contracts.map((c) => {
              const position = byInstrument.get(c.instrumentId);
              const fxDelta = position?.fxDelta.find((d) => d.amount !== 0);
              const pointsDelta = position?.pointsDelta.find((d) => d.amount !== 0);
              return (
                <tr key={c.instrumentId}>
                  <td>{position?.positionId ?? '—'}</td>
                  <td>
                    {DIRECTION_LABEL[c.direction] ?? c.direction} <span className="mono">{c.pair}</span>
                    <span className="ccy-suffix">{c.kind}</span>
                  </td>
                  <td className="num" title={`${c.notionalCurrency} notional`}>
                    {position ? formatQuantity(position.quantity) : '—'}
                    <span className="ccy-suffix">{c.notionalCurrency}</span>
                  </td>
                  <td className="num">{rate(c.contractRate)}</td>
                  <td className="num" title={c.kind === 'OUTRIGHT' ? 'Derived from two curves' : 'Spot plus quoted points'}>
                    {rate(c.forwardRate)}
                  </td>
                  <td className={`num ${(position?.value ?? 0) < 0 ? 'short' : ''}`}>
                    {position ? formatMoney(position.value) : '—'}
                  </td>
                  <td className={`num ${(fxDelta?.amount ?? 0) < 0 ? 'short' : ''}`} title="Per 1% move in the risk currency">
                    {fxDelta ? formatDv01(fxDelta.amount) : '—'}
                    {fxDelta && <span className="ccy-suffix">{fxDelta.currency}</span>}
                  </td>
                  <td
                    className={`num ${(pointsDelta?.amount ?? 0) < 0 ? 'short' : ''}`}
                    title={c.kind === 'OUTRIGHT' ? 'A deliverable forward has no Forward Points' : 'Per pip, spot held fixed'}
                  >
                    {pointsDelta ? formatDv01(pointsDelta.amount) : '—'}
                  </td>
                  <td>{c.fixingDate ?? '—'}</td>
                  <td className="num" title={c.fxFixing === null ? 'Not yet fixed' : 'Recorded; it never changes'}>
                    {c.fxFixing === null ? '—' : rate(c.fxFixing)}
                  </td>
                  <td>{c.settlementDate}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
