import type { CtdSwitchEvent, FuturesView, PositionResult } from '../api/riskSnapshot';
import { formatPrice } from '../format';

/** A CTD Switch stays flagged for this many ticks, so it is noticeable at a 1s tick interval. */
const RECENT_SWITCH_TICKS = 10;

const basis = new Intl.NumberFormat('en-US', { minimumFractionDigits: 3, maximumFractionDigits: 3, signDisplay: 'exceptZero' });
const factor = new Intl.NumberFormat('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 4 });

interface Props {
  futures: FuturesView[];
  positions: PositionResult[];
  recentSwitches: CtdSwitchEvent[];
  tick: number;
}

export function FuturesPanel({ futures, positions, recentSwitches, tick }: Props) {
  const priceByContract = new Map(positions.map((p) => [p.instrumentId, p.dirtyPrice]));
  const newestFirst = [...recentSwitches].reverse();

  return (
    <section className="card">
      <header className="card-header">
        <h2>Treasury futures</h2>
        <p className="muted">
          Price = Proxy Bond clean price ÷ conversion factor + Basis. The Proxy Bond stands in for the cheapest-to-deliver; a CTD
          Switch replaces it and jumps the Basis. Futures are margined daily, so their Positions carry risk but no value.
        </p>
      </header>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Contract</th>
              <th className="num">Price</th>
              <th>Proxy Bond (CTD)</th>
              <th className="num">Conv. factor</th>
              <th className="num">Basis (pts)</th>
              <th className="num">CTD Switches</th>
            </tr>
          </thead>
          <tbody>
            {futures.map((f) => {
              const recentlySwitched = f.lastCtdSwitchTick !== null && tick - f.lastCtdSwitchTick < RECENT_SWITCH_TICKS;
              const price = priceByContract.get(f.contract);
              return (
                <tr key={f.contract}>
                  <td>
                    {f.description} <span className="mono muted">{f.contract}</span>
                  </td>
                  <td className="num">{price === undefined ? '—' : formatPrice(price)}</td>
                  <td>
                    {f.proxyBondDescription} <span className="mono muted">{f.proxyBondId}</span>
                    {recentlySwitched && (
                      <span className="badge badge-switch" title={`CTD Switch at tick ${f.lastCtdSwitchTick}`}>
                        ⇄ CTD Switch
                      </span>
                    )}
                  </td>
                  <td className="num">{factor.format(f.conversionFactor)}</td>
                  <td className="num">{basis.format(f.basis)}</td>
                  <td className="num">
                    {f.ctdSwitchCount}
                    {f.lastCtdSwitchTick !== null && <span className="muted"> · last tick {f.lastCtdSwitchTick.toLocaleString('en-US')}</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {newestFirst.length > 0 && (
        <div className="table-scroll">
          <table className="compact switch-log">
            <caption className="muted">Recent CTD Switches</caption>
            <thead>
              <tr>
                <th className="num">Tick</th>
                <th>Contract</th>
                <th>From</th>
                <th>To</th>
                <th className="num">Basis jump (pts)</th>
              </tr>
            </thead>
            <tbody>
              {newestFirst.map((s) => (
                <tr key={`${s.tick}-${s.contract}`}>
                  <td className="num">{s.tick.toLocaleString('en-US')}</td>
                  <td className="mono">{s.contract}</td>
                  <td className="mono">{s.fromProxyBondId}</td>
                  <td className="mono">{s.toProxyBondId}</td>
                  <td className="num">{basis.format(s.basisJump)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
