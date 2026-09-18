import { useRiskStream } from './api/useRiskStream';
import { BookRiskPanel } from './components/BookRiskPanel';
import { CreditPanel } from './components/CreditPanel';
import { CurveChart } from './components/CurveChart';
import { FuturesPanel } from './components/FuturesPanel';
import { LifecycleEvents } from './components/LifecycleEvents';
import { PositionTable } from './components/PositionTable';
import { SwapsPanel } from './components/SwapsPanel';
import { SessionPanel } from './components/SessionPanel';

export function App() {
  const { state, status } = useRiskStream();
  const { snapshot } = state;

  return (
    <div className="app">
      <header className="app-header">
        <h1>Fixed Income Risk Simulator</h1>
        <SessionPanel snapshot={snapshot} status={status} />
      </header>
      {snapshot ? (
        <main className="layout">
          <CurveChart curve={snapshot.curve} />
          <BookRiskPanel bookRisk={snapshot.bookRisk} />
          <PositionTable
            positions={snapshot.positions}
            bookRisk={snapshot.bookRisk}
            tick={snapshot.tick}
            telemetry={snapshot.telemetry}
          />
          {snapshot.credit.issuers.length > 0 && <CreditPanel credit={snapshot.credit} tick={snapshot.tick} />}
          {snapshot.swaps.length > 0 && <SwapsPanel swaps={snapshot.swaps} positions={snapshot.positions} />}
          {snapshot.futures.length > 0 && (
            <FuturesPanel
              futures={snapshot.futures}
              positions={snapshot.positions}
              recentSwitches={snapshot.recentCtdSwitches}
              tick={snapshot.tick}
            />
          )}
          <LifecycleEvents events={snapshot.recentLifecycleEvents} />
        </main>
      ) : (
        <p className="waiting">Waiting for the first Risk Snapshot from the backend…</p>
      )}
    </div>
  );
}
