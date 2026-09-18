import type { RiskSnapshot, SessionInfo } from '../api/riskSnapshot';
import type { StreamStatus } from '../api/useRiskStream';
import { formatSimulatedDuration } from '../format';

const SOURCE_LABEL: Record<SessionInfo['curveSource'], string> = {
  LIVE: 'Live',
  CACHED: 'Cached',
  BUNDLED: 'Bundled',
};

const SOURCE_EXPLANATION: Record<SessionInfo['curveSource'], string> = {
  LIVE: 'Fetched from Treasury at startup.',
  CACHED: 'Live fetch failed; using the last curve fetched successfully.',
  BUNDLED: 'Live fetch failed and no cached curve was available; using the snapshot bundled with the app.',
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
        <dd>
          {session ? (
            <span className={`badge badge-${session.curveSource.toLowerCase()}`} title={SOURCE_EXPLANATION[session.curveSource]}>
              {SOURCE_LABEL[session.curveSource]}
            </span>
          ) : (
            '—'
          )}
        </dd>
      </div>
      <div>
        <dt>Curve date</dt>
        <dd>{session?.curveDate ?? '—'}</dd>
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
