import type { CurveSourceInfo, CurveSourceKind, RiskSnapshot } from '../api/riskSnapshot';
import type { StreamStatus } from '../api/useRiskStream';
import { formatSimulatedDuration } from '../format';

const SOURCE_LABEL: Record<CurveSourceKind, string> = {
  LIVE: 'Live',
  CACHED: 'Cached',
  BUNDLED: 'Bundled',
};

const SOURCE_EXPLANATION: Record<CurveSourceKind, string> = {
  LIVE: 'Fetched from the publisher at startup.',
  CACHED: 'Live fetch failed; using the last curve fetched successfully.',
  BUNDLED: 'Live fetch failed and no cached curve was available; using the snapshot bundled with the app.',
};

const QUOTES_EXPLANATION: Record<CurveSourceInfo['quotes'], string> = {
  PAR_YIELD: 'Published as par yields, bootstrapped to a discount curve.',
  ZERO_RATE: 'Published as spot rates, already bootstrapped by the publisher.',
};

export function SessionPanel({ snapshot, status }: { snapshot: RiskSnapshot | null; status: StreamStatus }) {
  const session = snapshot?.session;

  return (
    <dl className="session">
      <div>
        <dt>Stream</dt>
        <dd>
          <span className={`dot dot-${status}`} aria-hidden="true" />
          {status}
        </dd>
      </div>
      <div>
        <dt>Tick</dt>
        <dd className="num">
          {snapshot ? snapshot.tick.toLocaleString('en-US') : '—'}
          {session?.stopAtTick != null && snapshot && snapshot.tick >= session.stopAtTick && (
            <span className="badge badge-stopped" title="risk.simulation.stop-at-tick froze the run here">
              Stopped
            </span>
          )}
        </dd>
      </div>
      <div>
        <dt>Simulated time</dt>
        <dd
          className="num"
          title={session ? `${formatSimulatedDuration(session.simulatedSecondsPerTick)} simulated per tick` : undefined}
        >
          {snapshot && session ? `+${formatSimulatedDuration(snapshot.tick * session.simulatedSecondsPerTick)}` : '—'}
        </dd>
      </div>
      <div>
        <dt>Curve Source</dt>
        <dd className="curve-sources">
          {session?.curves?.length
            ? session.curves.map((curve) => (
                <span key={curve.currency} className="curve-source">
                  <span className="ccy">{curve.currency}</span>
                  <span
                    className={`badge badge-${curve.source.toLowerCase()}`}
                    title={`${SOURCE_EXPLANATION[curve.source]} ${QUOTES_EXPLANATION[curve.quotes]}`}
                  >
                    {SOURCE_LABEL[curve.source]}
                  </span>
                  <span className="curve-date">{curve.date}</span>
                </span>
              ))
            : '—'}
        </dd>
      </div>
      <div>
        <dt>Valuation Date</dt>
        <dd>{session?.valuationDate ?? '—'}</dd>
      </div>
      <div>
        <dt>Next Day Rollover</dt>
        <dd
          className="num"
          title={session ? `The Valuation Date advances one day every ${session.ticksPerDay} ticks` : undefined}
        >
          {snapshot ? `in ${snapshot.ticksUntilDayRollover} ${snapshot.ticksUntilDayRollover === 1 ? 'tick' : 'ticks'}` : '—'}
        </dd>
      </div>
      <div>
        <dt>Seed</dt>
        <dd className="mono" title="Set risk.simulation.seed to this value to replay the run">
          {session?.seed ?? '—'}
        </dd>
      </div>
    </dl>
  );
}
