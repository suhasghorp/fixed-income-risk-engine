import type { LifecycleEvent } from '../api/riskSnapshot';
import { formatMoney, formatPrice } from '../format';

const KIND_LABEL: Record<LifecycleEvent['kind'], string> = {
  COUPON: 'Coupon',
  REDEMPTION: 'Redemption',
  FIXED_LEG: 'Swap fixed leg',
  FLOATING_LEG: 'Swap floating leg',
  FX_LEG: 'FX Forward leg',
  FX_SETTLEMENT: 'NDF settlement',
};

export function LifecycleEvents({ events }: { events: LifecycleEvent[] }) {
  const newestFirst = [...events].reverse();

  return (
    <section className="card">
      <header className="card-header">
        <h2>Lifecycle events</h2>
        <p className="muted">
          Coupons, redemptions and swap payments, processed only at Day Rollover. Amounts are paid to the Position in USD; negative when short.
        </p>
      </header>
      {newestFirst.length === 0 ? (
        <p className="muted">No coupons, redemptions or swap payments have been processed yet.</p>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th className="num">Tick</th>
                <th>Event</th>
                <th>Position</th>
                <th>Instrument</th>
                <th className="num">Per 100</th>
                <th className="num">Amount</th>
              </tr>
            </thead>
            <tbody>
              {newestFirst.map((e) => (
                <tr key={`${e.tick}-${e.positionId}-${e.kind}`}>
                  <td>{e.date}</td>
                  <td className="num">{e.tick.toLocaleString('en-US')}</td>
                  <td>{KIND_LABEL[e.kind]}</td>
                  <td>{e.positionId}</td>
                  <td>{e.description}</td>
                  <td className="num">{formatPrice(e.amountPer100)}</td>
                  <td className={`num ${e.amount < 0 ? 'short' : ''}`} title={`Paid in ${e.currency}`}>
                    {formatMoney(e.amount)}
                    <span className="ccy-suffix">{e.currency}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
