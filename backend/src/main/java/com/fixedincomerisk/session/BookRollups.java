package com.fixedincomerisk.session;

import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.session.RiskSnapshot.BookRisk;
import com.fixedincomerisk.session.RiskSnapshot.BucketDv01;
import com.fixedincomerisk.session.RiskSnapshot.CurrencyAmount;
import com.fixedincomerisk.session.RiskSnapshot.CurrencyRates;
import com.fixedincomerisk.session.RiskSnapshot.InstrumentTypeRisk;
import com.fixedincomerisk.session.RiskSnapshot.PairAmount;
import com.fixedincomerisk.session.RiskSnapshot.PositionResult;
import com.fixedincomerisk.session.RiskSnapshot.RatingBucketRisk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rolls Position contributions up to the Book: simple sums, since value, DV01 and CS01 are linear at the
 * Book level, and group-bys for totals per Instrument type, per Rating Bucket and per currency.
 *
 * <p>FX Delta is summed <em>within</em> a currency and never across currencies, and the points delta
 * within a pair. There is deliberately no grand total of either: exposure to the euro and exposure to the
 * won are different risks, and a number that added them would mean nothing.
 */
final class BookRollups {

    private BookRollups() {
    }

    /**
     * @param currencies    every currency the session simulates, in market order; each gets a line, even
     *                      when the Book has no risk in it
     * @param ratingBuckets every Rating Bucket label, in reporting order; each gets a line, even when empty
     */
    static BookRisk rollUp(List<PositionResult> positions, List<Pillar> pillars, List<String> currencies,
                           List<String> ratingBuckets) {
        double value = 0;
        double dv01 = 0;
        double cs01 = 0;
        double[] buckets = new double[pillars.size()];
        Map<String, double[]> bucketsByCurrency = new LinkedHashMap<>();
        Map<String, Double> dv01ByCurrency = new LinkedHashMap<>();
        currencies.forEach(currency -> {
            bucketsByCurrency.put(currency, new double[pillars.size()]);
            dv01ByCurrency.put(currency, 0.0);
        });
        Map<String, Double> fxDeltaByCurrency = new LinkedHashMap<>();
        Map<String, Double> pointsDeltaByPair = new LinkedHashMap<>();
        Map<String, InstrumentTypeRisk> byType = new LinkedHashMap<>();
        Map<String, RatingBucketRisk> byRatingBucket = new LinkedHashMap<>();
        ratingBuckets.forEach(bucket -> byRatingBucket.put(bucket, new RatingBucketRisk(bucket, 0, 0, 0, 0)));
        for (PositionResult position : positions) {
            value += position.value();
            dv01 += position.dv01();
            cs01 += position.cs01();
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] += position.bucketedDv01().get(i).dv01();
            }
            for (CurrencyRates rates : position.ratesByCurrency()) {
                double[] currencyBuckets = bucketsByCurrency.get(rates.currency());
                if (currencyBuckets == null) {
                    throw new IllegalStateException("Position " + position.positionId() + " carries risk in "
                            + rates.currency() + ", which the session does not simulate: " + currencies);
                }
                dv01ByCurrency.merge(rates.currency(), rates.dv01(), Double::sum);
                for (int i = 0; i < currencyBuckets.length; i++) {
                    currencyBuckets[i] += rates.bucketedDv01().get(i).dv01();
                }
            }
            // FX Delta sums within a currency and never across them; points delta sums within a pair.
            position.fxDelta().forEach(delta ->
                    fxDeltaByCurrency.merge(delta.currency(), delta.amount(), Double::sum));
            position.pointsDelta().forEach(delta ->
                    pointsDeltaByPair.merge(delta.pair(), delta.amount(), Double::sum));
            byType.merge(position.instrumentType(),
                    new InstrumentTypeRisk(position.instrumentType(), 1, position.value(), position.dv01(), position.cs01()),
                    (a, b) -> new InstrumentTypeRisk(a.instrumentType(), a.positionCount() + b.positionCount(),
                            a.value() + b.value(), a.dv01() + b.dv01(), a.cs01() + b.cs01()));
            if (position.ratingBucket() != null) {
                byRatingBucket.merge(position.ratingBucket(),
                        new RatingBucketRisk(position.ratingBucket(), 1, position.value(), position.dv01(), position.cs01()),
                        (a, b) -> new RatingBucketRisk(a.ratingBucket(), a.positionCount() + b.positionCount(),
                                a.value() + b.value(), a.dv01() + b.dv01(), a.cs01() + b.cs01()));
            }
        }
        List<CurrencyRates> ratesByCurrency = currencies.stream()
                .map(currency -> new CurrencyRates(currency, dv01ByCurrency.get(currency),
                        bucketDv01s(pillars, bucketsByCurrency.get(currency))))
                .toList();
        List<CurrencyAmount> fxDelta = fxDeltaByCurrency.entrySet().stream()
                .map(entry -> new CurrencyAmount(entry.getKey(), entry.getValue()))
                .toList();
        List<PairAmount> pointsDelta = pointsDeltaByPair.entrySet().stream()
                .map(entry -> new PairAmount(entry.getKey(), entry.getValue()))
                .toList();
        return new BookRisk(value, dv01, bucketDv01s(pillars, buckets), ratesByCurrency, cs01,
                fxDelta, pointsDelta, new ArrayList<>(byType.values()), new ArrayList<>(byRatingBucket.values()));
    }

    static List<BucketDv01> bucketDv01s(List<Pillar> pillars, double[] dv01s) {
        List<BucketDv01> buckets = new ArrayList<>(pillars.size());
        for (int i = 0; i < pillars.size(); i++) {
            buckets.add(new BucketDv01(pillars.get(i).label(), pillars.get(i).years(), dv01s[i]));
        }
        return buckets;
    }
}
