package com.fixedincomerisk.risk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Instrument's rates risk, one {@link CurveSensitivities} per currency. Each comes from bumping that
 * currency's curve alone with every other curve held fixed, so a euro basis point is never netted against
 * a dollar one: Basel's standardised approach defines the rates risk factors as a risk-free curve per
 * currency, and does not net across them without an explicit correlation.
 *
 * <p>The totals below do add the currencies together. They are a headline only, and everything that
 * reports them says so with {@link #TOTAL_LABEL}: a basis point of each curve, not a basis point of one.
 */
public record RatesSensitivities(Map<String, CurveSensitivities> byCurrency) {

    /** What a total across currencies must be called wherever it is shown. */
    public static final String TOTAL_LABEL = "all curves, 1bp each";

    /**
     * The parallel shift Gamma is measured over. One basis point of it is numerically invisible on a Book
     * this size; 25bp is a move a reader can picture and a number that prints.
     */
    public static final int GAMMA_SHIFT_BP = 25;

    /**
     * What Gamma must be called wherever it is shown. A number called "gamma" with no shift attached is
     * meaningless, and this Book also holds bonds with convexity, which is a different thing wearing a
     * similar name.
     */
    public static final String GAMMA_LABEL = "DV01 change for +" + GAMMA_SHIFT_BP + "bp";

    /** And a Gamma total across currencies carries both warnings at once. */
    public static final String GAMMA_TOTAL_LABEL = "all curves, +" + GAMMA_SHIFT_BP + "bp each";

    public RatesSensitivities {
        byCurrency = Collections.unmodifiableMap(new LinkedHashMap<>(byCurrency));
    }

    /** The currencies this risk covers, in market order. */
    public List<String> currencies() {
        return List.copyOf(byCurrency.keySet());
    }

    public CurveSensitivities in(String currency) {
        CurveSensitivities risk = byCurrency.get(currency);
        if (risk == null) {
            throw new IllegalArgumentException("No rates risk for currency " + currency + "; measured " + currencies());
        }
        return risk;
    }

    /** A Position's risk: this per-unit risk times its signed quantity, in every currency. */
    public RatesSensitivities scaledBy(double quantity) {
        Map<String, CurveSensitivities> scaled = new LinkedHashMap<>();
        byCurrency.forEach((currency, risk) -> scaled.put(currency, risk.scaledBy(quantity)));
        return new RatesSensitivities(scaled);
    }

    /** DV01 across every curve: {@value #TOTAL_LABEL}. */
    public double totalDv01() {
        return byCurrency.values().stream().mapToDouble(CurveSensitivities::dv01).sum() + 0.0;
    }

    /** Bucketed DV01 across every curve, in Pillar order: {@value #TOTAL_LABEL}. */
    public double[] totalBucketedDv01() {
        double[] total = null;
        for (CurveSensitivities risk : byCurrency.values()) {
            double[] buckets = risk.bucketedDv01();
            if (total == null) {
                total = new double[buckets.length];
            }
            for (int i = 0; i < buckets.length; i++) {
                total[i] += buckets[i];
            }
        }
        return total == null ? new double[0] : total;
    }

    /**
     * Gamma across every curve: {@value #GAMMA_TOTAL_LABEL}. It sums across Positions the way DV01 does,
     * but it is not a first derivative of anything — it is how far the first derivative itself moved.
     */
    public double totalGamma() {
        return byCurrency.values().stream().mapToDouble(CurveSensitivities::gamma).sum() + 0.0;
    }

    /** The sum of every bucket's absolute size, across every curve: how exposed the Instrument is at all. */
    public double totalAbsoluteBucketedDv01() {
        double total = 0;
        for (CurveSensitivities risk : byCurrency.values()) {
            for (double bucket : risk.bucketedDv01()) {
                total += Math.abs(bucket);
            }
        }
        return total;
    }
}
