import {
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Scatter,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { CurveView } from '../api/riskSnapshot';
import { formatPercent } from '../format';

const TICKS = [0, 2, 5, 10, 20, 30];
const GRID_STEP_BP = 50;

/**
 * Rate axis snapped to 50bp gridlines with one gridline of headroom, so the axis only moves when the
 * curve crosses a gridline rather than on every tick. Works in whole basis points to avoid float drift.
 */
function rateAxis(rates: number[]): { domain: [number, number]; ticks: number[] } {
  const lowBp = Math.floor((Math.min(...rates) * 10_000) / GRID_STEP_BP) * GRID_STEP_BP - GRID_STEP_BP;
  const highBp = Math.ceil((Math.max(...rates) * 10_000) / GRID_STEP_BP) * GRID_STEP_BP + GRID_STEP_BP;
  const ticks: number[] = [];
  for (let bp = lowBp; bp <= highBp; bp += GRID_STEP_BP) {
    ticks.push(bp / 10_000);
  }
  return { domain: [lowBp / 10_000, highBp / 10_000], ticks };
}

export function CurveChart({ curve }: { curve: CurveView }) {
  const zeroCurve = curve.points.map((p) => ({ years: p.years, zero: p.zeroRate }));
  const parYields = curve.parInputs.map((p) => ({ years: p.years, par: p.parYield, tenor: p.tenor }));
  const axis = rateAxis([...zeroCurve.map((p) => p.zero), ...parYields.map((p) => p.par)]);

  return (
    <section className="card">
      <header className="card-header">
        <h2>USD Treasury curve</h2>
        <p className="muted">Continuously compounded zero rates from the calibrated model, with the published par yields.</p>
      </header>
      <div className="chart">
        <ResponsiveContainer width="100%" height={300}>
          <ComposedChart margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis
              dataKey="years"
              type="number"
              domain={[0, 30]}
              ticks={TICKS}
              tickFormatter={(y) => `${y}Y`}
              stroke="var(--muted)"
            />
            <YAxis
              type="number"
              domain={axis.domain}
              ticks={axis.ticks}
              tickFormatter={(r) => formatPercent(r, 2)}
              stroke="var(--muted)"
              width={64}
            />
            <Tooltip
              formatter={(value) => formatPercent(Number(value))}
              labelFormatter={(years) => `${Number(years).toFixed(2)}Y`}
              contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)' }}
            />
            <Legend />
            <Line data={zeroCurve} dataKey="zero" name="Zero rate" stroke="var(--accent)" strokeWidth={2} dot={false} isAnimationActive={false} />
            <Scatter data={parYields} dataKey="par" name="Par yield (input)" fill="var(--secondary)" isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <ul className="pillars">
        {curve.pillars.map((p) => (
          <li key={p.label}>
            <span className="muted">{p.label}</span>
            <span className="num">{formatPercent(p.zeroRate)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
