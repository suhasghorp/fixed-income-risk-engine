import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { SwaptionsPanel } from './SwaptionsPanel';
import { BookRiskPanel } from './BookRiskPanel';
import type { BookRisk, PositionResult, SwaptionView } from '../api/riskSnapshot';

/**
 * The two panels the swaptions put numbers on, rendered to static markup. Gamma and Vega are meaningless
 * without the shift and the factor they are measured over, so what is asserted here is mostly the
 * labelling — and that the Exercise column turns from a date into a decision at the Expiry.
 */

const view = (exercised: boolean | null, forwardRate: number | null): SwaptionView => ({
  instrumentId: 'SWPN-1Mx5Y-PAY', description: 'Payer 1Mx5Y swaption at 4.802%, expires 10/11/2026',
  direction: 'PAYER', surfacePoint: 'USD 1Mx5Y', strike: 0.048017, expiryDate: '2026-10-11',
  underlyingMaturityDate: '2031-10-11', normalVolBp: 95.1397, forwardRate, exercised,
});

const position: PositionResult = {
  positionId: 'P20', instrumentId: 'SWPN-1Mx5Y-PAY', instrumentType: 'SWAPTION', description: 'x',
  quantity: 1_000_000, notionalCurrency: 'USD', cleanPrice: 1, accruedInterest: 0, dirtyPrice: 1,
  value: 14_000, dv01: -4058.3381, bucketedDv01: [], ratesByCurrency: [], gamma: -4003.4059, cs01: 0,
  vega: [{ currency: 'USD', amount: 1164.1089 }], fxDelta: [], pointsDelta: [], ratingBucket: null,
  lastPricedTick: 24,
};

const bookRisk: BookRisk = {
  value: 33_661_279, dv01: 25_383.8273, bucketedDv01: [{ pillar: '2Y', years: 2, dv01: 1 }],
  ratesByCurrency: [{ currency: 'USD', dv01: 25_102, bucketedDv01: [{ pillar: '2Y', years: 2, dv01: 1 }], gamma: -6742.8611 },
                    { currency: 'EUR', dv01: 280, bucketedDv01: [{ pillar: '2Y', years: 2, dv01: 0 }], gamma: 0 }],
  gamma: -6742.8611, cs01: 100, vegaByCurrency: [{ currency: 'USD', amount: 7117.883 }],
  fxDeltaByCurrency: [], pointsDeltaByPair: [], byInstrumentType: [], byRatingBucket: [],
};

describe('the swaptions panel', () => {
  it('shows the strike against the forward, the quoted vol, and the Expiry until it is decided', () => {
    const pending = renderToStaticMarkup(<SwaptionsPanel swaptions={[view(null, 0.0470732)]} positions={[position]} />);
    // Out of the money by 9.44bp, from the payer's side: forward 4.7073% against a 4.8017% strike.
    expect(pending).toContain('4.8017%');
    expect(pending).toContain('4.7073%');
    expect(pending).toContain('Expires 2026-10-11');
    expect(pending).toContain('95.1bp');
    expect(pending).toContain('-9.44bp');

    // A lapsed option's swap never started, so there is eventually no forward left to quote: "—".
    const lapsed = renderToStaticMarkup(<SwaptionsPanel swaptions={[view(false, null)]} positions={[position]} />);
    expect(lapsed).toContain('Lapsed');
    expect(lapsed).toContain('—');
    expect(renderToStaticMarkup(<SwaptionsPanel swaptions={[view(true, 0.049)]} positions={[position]} />)).toContain('Exercised');
  });

  it('gives the Book risk panel Vega and Gamma, each labelled with what it measures', () => {
    const html = renderToStaticMarkup(<BookRiskPanel bookRisk={bookRisk} />);
    expect(html).toContain('Book Gamma');
    expect(html).toContain('all curves, +25bp each');
    expect(html).toContain('DV01 change for +25bp');
    expect(html).toContain('Book Vega');
  });
});
