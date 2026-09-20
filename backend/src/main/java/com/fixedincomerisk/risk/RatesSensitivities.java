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
