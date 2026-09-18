const price = new Intl.NumberFormat('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 4 });
const money = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });
const quantity = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0, signDisplay: 'exceptZero' });

export const formatPrice = (value: number) => price.format(value);
export const formatMoney = (value: number) => money.format(value);
export const formatQuantity = (value: number) => quantity.format(value);
const dv01 = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0, signDisplay: 'exceptZero' });

/** USD per basis point, signed. */
export const formatDv01 = (value: number) => dv01.format(value);
const spread = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** A spread in basis points, e.g. "95.3bp". */
export const formatBp = (value: number) => `${spread.format(value)}bp`;
export const formatPercent = (rate: number, digits = 3) => `${(rate * 100).toFixed(digits)}%`;

/** e.g. 93_600 → "1d 2h"; 1_800 → "30m". */
export function formatSimulatedDuration(totalSeconds: number): string {
  const days = Math.floor(totalSeconds / 86_400);
  const hours = Math.floor((totalSeconds % 86_400) / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const parts = [days ? `${days}d` : '', hours ? `${hours}h` : '', minutes ? `${minutes}m` : ''].filter(Boolean);
  return parts.length ? parts.join(' ') : '0m';
}
