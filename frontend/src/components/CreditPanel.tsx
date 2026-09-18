import type { CreditView } from '../api/riskSnapshot';
import { formatBp } from '../format';

const gap = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1, signDisplay: 'exceptZero' });

/** How long ago an observation was, in ticks. */
const ago = (tick: number | null, now: number) =>
  tick === null ? '' : tick === now ? 'this tick' : now - tick === 1 ? '1 tick ago' : `${now - tick} ticks ago`;

/**
 * The credit market as the risk engine sees it: the observable Systemic and Sector Factors, and each
 * issuer's Mark next to the evidence behind it. Latent Spreads are never shown, because the engine never sees them.
 */
export function CreditPanel({ credit, tick }: { credit: CreditView; tick: number }) {
  return (
    <section className="card">
      <header className="card-header">
        <h2>Credit Marks</h2>
        <p className="muted">
          Each issuer's Mark resets to every Print or Quote and is carried forward between them by the Systemic and Sector Factors
          (Matrix Pricing). Liquid names track their Prints closely; illiquid ones drift until the next Print. A Rating Migration is
          public at once, but the Credit Event's spread jump reaches the Mark only through later Prints, so a downgraded issuer is
          shown as Mark stale until then.
        </p>
      </header>
      <dl className="telemetry">
        <div>
          <dt>Systemic Factor</dt>
          <dd className="num">{formatBp(credit.systemicBp)}</dd>
        </div>
        {credit.sectors.map((s) => (
          <div key={s.ratingBucket}>
            <dt>{s.ratingBucket}</dt>
            <dd className="num">{formatBp(s.levelBp)}</dd>
          </div>
        ))}
      </dl>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Issuer</th>
              <th>Rating Bucket</th>
              <th className="num" title="Expected Prints per simulated day">
                Prints / day
              </th>
              <th className="num">Mark</th>
              <th className="num">Last Print</th>
              <th className="num" title="Mark minus last Print">
                Mark − Print
              </th>
              <th className="num">Last Quote</th>
            </tr>
          </thead>
          <tbody>
            {credit.issuers.map((i) => {
              const markGap = i.lastPrintBp === null ? null : i.markBp - i.lastPrintBp;
              return (
                <tr key={i.issuerId} className={i.lastPrintTick === tick ? 'repriced' : undefined}>
                  <td>
                    {i.name} <span className="mono muted">{i.issuerId}</span>
                  </td>
                  <td>
                    {i.ratingBucket}
                    {i.migratedFrom !== null && i.markStale && (
                      <span className="badge badge-stale" title={`Downgraded from ${i.migratedFrom} at tick ${i.migrationTick}`}>
                        ▼ Downgraded, Mark stale
                      </span>
                    )}
                    {i.migratedFrom !== null && !i.markStale && (
                      <span className="muted"> · from {i.migratedFrom} at tick {i.migrationTick?.toLocaleString('en-US')}</span>
                    )}
                  </td>
                  <td className="num">{(i.printsPerYear / 365).toFixed(1)}</td>
                  <td className="num">{formatBp(i.markBp)}</td>
                  <td className="num">
                    {i.lastPrintBp === null ? (
                      <span className="muted">none yet</span>
                    ) : (
                      <>
                        {formatBp(i.lastPrintBp)} <span className="muted">· {ago(i.lastPrintTick, tick)}</span>
                      </>
                    )}
                  </td>
                  <td className="num">{markGap === null ? '—' : `${gap.format(markGap)}bp`}</td>
                  <td className="num">
                    {i.lastQuoteBp === null ? (
                      <span className="muted">none yet</span>
                    ) : (
                      <>
                        {formatBp(i.lastQuoteBp)} <span className="muted">· {ago(i.lastQuoteTick, tick)}</span>
                      </>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}
