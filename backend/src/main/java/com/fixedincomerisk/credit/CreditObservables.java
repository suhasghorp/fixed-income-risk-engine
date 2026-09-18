package com.fixedincomerisk.credit;

import java.util.Map;

/**
 * What the risk engine may see of the credit market: the Systemic Factor and Sector Factors (observable,
 * like credit indices) and each issuer's public rating. Levels are decimal spreads.
 */
public record CreditObservables(double systemic, Map<RatingBucket, Double> sectors, Map<String, RatingBucket> ratings) {

    public CreditObservables {
        sectors = Map.copyOf(sectors);
        ratings = Map.copyOf(ratings);
    }

    public RatingBucket rating(String issuerId) {
        RatingBucket bucket = ratings.get(issuerId);
        if (bucket == null) {
            throw new IllegalArgumentException("Unknown issuer " + issuerId);
        }
        return bucket;
    }

    public double sector(RatingBucket bucket) {
        Double level = sectors.get(bucket);
        if (level == null) {
            throw new IllegalArgumentException("Unknown Rating Bucket " + bucket);
        }
        return level;
    }
}
