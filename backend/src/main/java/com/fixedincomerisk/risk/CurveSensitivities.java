package com.fixedincomerisk.risk;

import java.util.Arrays;

/**
 * Curve risk per unit of notional (or, once scaled, of a Position).
 *
 * @param dv01         change in value for a 1bp parallel fall in zero rates
 * @param bucketedDv01 Bucketed DV01 per Pillar, in Pillar order
 * @param gamma        how much that DV01 itself moves: the DV01 measured again with this curve shifted up
 *                     by {@value RatesSensitivities#GAMMA_SHIFT_BP} basis points, minus the DV01 above.
 *                     Never shown without its shift size — {@link RatesSensitivities#GAMMA_LABEL}
 */
public record CurveSensitivities(double dv01, double[] bucketedDv01, double gamma) {

    public CurveSensitivities {
        bucketedDv01 = bucketedDv01.clone();
    }

    /** Risk with no second-order measurement: every Instrument the engine had before it held an option. */
    public CurveSensitivities(double dv01, double[] bucketedDv01) {
        this(dv01, bucketedDv01, 0);
    }

    @Override
    public double[] bucketedDv01() {
        return bucketedDv01.clone();
    }

    /** A Position's risk: this per-unit risk times its signed quantity. */
    public CurveSensitivities scaledBy(double quantity) {
        return new CurveSensitivities(scale(dv01, quantity),
                Arrays.stream(bucketedDv01).map(b -> scale(b, quantity)).toArray(), scale(gamma, quantity));
    }

    /** Adding 0.0 turns the −0 of a short Position's empty bucket into 0. */
    private static double scale(double risk, double quantity) {
        return risk * quantity + 0.0;
    }

    public double bucketedSum() {
        return Arrays.stream(bucketedDv01).sum();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CurveSensitivities that
                && Double.compare(dv01, that.dv01) == 0
                && Double.compare(gamma, that.gamma) == 0
                && Arrays.equals(bucketedDv01, that.bucketedDv01);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Double.hashCode(dv01) + Arrays.hashCode(bucketedDv01)) + Double.hashCode(gamma);
    }

    @Override
    public String toString() {
        return "CurveSensitivities[dv01=" + dv01 + ", bucketedDv01=" + Arrays.toString(bucketedDv01)
                + ", gamma=" + gamma + "]";
    }
}
