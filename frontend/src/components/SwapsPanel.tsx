import type { PositionResult, SwapView } from '../api/riskSnapshot';
import { formatDv01, formatMoney, formatPercent, formatQuantity } from '../format';

interface Props {
  swaps: SwapView[];
  positions: PositionResult[];
}

/**
 * Interest rate swaps: value and DV01 per Position, where the risk sits on the curve (Bucketed DV01), and
 * the floating leg's known coupon, set by the current period's Fixing.
 */
export function SwapsPanel({ swaps, positions }: Props) {
  const swapsById = new Map(swaps.map((s) => [s.instrumentId, s]));
  const swapPositions = positions.filter((p) => swapsById.has(p.instrumentId));
  const pillars = swapPositions[0]?.bucketedDv01.map((b) => b.pillar) ?? [];

  return (
    <section className="card">
      <header className="card-header">
        <h2>Interest rate swaps</h2>
        <p className="muted">
          Single-curve valuation. The current floating period pays its recorded Fixing (known); later periods are projected off the
          curve. A new Fixing is recorded on each Day Rollover onto a reset date and never changes.
        </p>
      </header>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Position</th>
              <th>Swap</th>
              <th className="num">Notional</th>
              <th className="num">Value</th>
              <th className="num">DV01</th>
              <th>Current period</th>
              <th className="num">Fixing</th>
              <th>Next reset</th>
            </tr>
          </thead>
          <tbody>
            {swapPositions.map((p) => {
              const swap = swapsById.get(p.instrumentId)!;
              return (
                <tr key={p.positionId}>
                  <td>{p.positionId}</td>
                  <td>{swap.description}</td>
                  <td className="num">{formatQuantity(p.quantity)}</td>
                  <td className={`num ${p.value < 0 ? 'short' : ''}`}>{formatMoney(p.value)}</td>
                  <td className={`num ${p.dv01 < 0 ? 'short' : ''}`}>{formatDv01(p.dv01)}</td>
                  <td>{swap.currentPeriodStart ? `${swap.currentPeriodStart} → ${swap.currentPeriodEnd}` : '—'}</td>
                  <td className="num">{swap.currentFixing === null ? '—' : formatPercent(swap.currentFixing, 4)}</td>
                  <td>{swap.nextResetDate ?? '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div className="table-scroll">
        <table className="compact switch-log">
          <caption className="muted">Bucketed DV01 by Pillar</caption>
          <thead>
            <tr>
              <th>Position</th>
              {pillars.map((pillar) => (
                <th key={pillar} className="num">
                  {pillar}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {swapPositions.map((p) => (
              <tr key={p.positionId}>
                <td>{p.positionId}</td>
                {p.bucketedDv01.map((b) => (
                  <td key={b.pillar} className={`num ${b.dv01 < 0 ? 'short' : ''}`}>
                    {Math.abs(b.dv01) < 0.5 ? '—' : formatDv01(b.dv01)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
