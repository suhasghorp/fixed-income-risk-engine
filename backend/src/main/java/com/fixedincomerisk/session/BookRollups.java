package com.fixedincomerisk.session;

import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.session.RiskSnapshot.BookRisk;
import com.fixedincomerisk.session.RiskSnapshot.BucketDv01;
import com.fixedincomerisk.session.RiskSnapshot.InstrumentTypeRisk;
import com.fixedincomerisk.session.RiskSnapshot.PositionResult;
import com.fixedincomerisk.session.RiskSnapshot.RatingBucketRisk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rolls Position contributions up to the Book: simple sums, since value, DV01 and CS01 are linear at the
 * Book level, and group-bys for totals per Instrument type and per Rating Bucket.
 */
final class BookRollups {

    private BookRollups() {
    }

    /**
     * @param ratingBuckets every Rating Bucket label, in reporting order; each gets a line, even when empty
     */
    static BookRisk rollUp(List<PositionResult> positions, List<Pillar> pillars, List<String> ratingBuckets) {
        double value = 0;
        double dv01 = 0;
        double cs01 = 0;
        double[] buckets = new double[pillars.size()];
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
        return new BookRisk(value, dv01, bucketDv01s(pillars, buckets), cs01, new ArrayList<>(byType.values()),
                new ArrayList<>(byRatingBucket.values()));
    }

    static List<BucketDv01> bucketDv01s(List<Pillar> pillars, double[] dv01s) {
        List<BucketDv01> buckets = new ArrayList<>(pillars.size());
        for (int i = 0; i < pillars.size(); i++) {
            buckets.add(new BucketDv01(pillars.get(i).label(), pillars.get(i).years(), dv01s[i]));
        }
        return buckets;
    }
}
