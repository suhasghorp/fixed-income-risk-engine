import type { BookRisk, PositionResult, RepricingTelemetry as Telemetry } from '../api/riskSnapshot';
import { formatDv01, formatMoney, formatPrice, formatQuantity } from '../format';
import { RepricingTelemetry } from './RepricingTelemetry';

interface Props {
  positions: PositionResult[];
  bookRisk: BookRisk;
  tick: number;
  telemetry: Telemetry;
}

export function PositionTable({ positions, bookRisk, tick, telemetry }: Props) {

  return (
    <section className="card">
      <header className="card-header">
        <h2>Book</h2>
        <p className="muted">
          Positions in the Book. Prices per 100 face; value is dirty, in USD. DV01 and CS01 are USD per 1bp fall in zero rates and in the issuer's Mark.
          Highlighted rows were repriced in the latest cycle; the rest are within their Materiality Thresholds. When
          repricing overruns a tick, the ticks it missed are coalesced and only the newest market is priced.
        </p>
        <RepricingTelemetry telemetry={telemetry} />
      </header>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Position</th>
              <th>Instrument</th>
              <th>Id</th>
              <th className="num">Quantity</th>
              <th className="num">Clean price</th>
              <th className="num">Accrued</th>
              <th className="num">Value</th>
              <th className="num">DV01</th>
              <th className="num">CS01</th>
              <th className="num">Last priced</th>
            </tr>
          </thead>
          <tbody>
            {positions.map((p) => (
              <tr key={p.positionId} className={p.lastPricedTick === tick ? 'repriced' : undefined}>
                <td>{p.positionId}</td>
                <td>{p.description}</td>
                <td className="mono">{p.instrumentId}</td>
                <td className={`num ${p.quantity < 0 ? 'short' : ''}`} title={`${p.notionalCurrency} notional`}>
                  {formatQuantity(p.quantity)}
                  <span className="ccy-suffix">{p.notionalCurrency}</span>
                </td>
                <td className="num">{formatPrice(p.cleanPrice)}</td>
                <td className="num">{formatPrice(p.accruedInterest)}</td>
                <td className={`num ${p.value < 0 ? 'short' : ''}`}>{formatMoney(p.value)}</td>
                <td className={`num ${p.dv01 < 0 ? 'short' : ''}`}>{formatDv01(p.dv01)}</td>
                <td className={`num ${p.cs01 < 0 ? 'short' : ''}`}>{p.cs01 === 0 ? '—' : formatDv01(p.cs01)}</td>
                <td className="num" title={`${tick - p.lastPricedTick} ticks ago`}>
                  {p.lastPricedTick === tick ? 'this tick' : `tick ${p.lastPricedTick.toLocaleString('en-US')}`}
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td colSpan={6}>Book total</td>
              <td className={`num ${bookRisk.value < 0 ? 'short' : ''}`}>{formatMoney(bookRisk.value)}</td>
              <td className={`num ${bookRisk.dv01 < 0 ? 'short' : ''}`}>{formatDv01(bookRisk.dv01)}</td>
              <td className={`num ${bookRisk.cs01 < 0 ? 'short' : ''}`}>{formatDv01(bookRisk.cs01)}</td>
              <td />
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  );
}
