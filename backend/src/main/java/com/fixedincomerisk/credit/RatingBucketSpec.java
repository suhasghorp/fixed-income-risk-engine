package com.fixedincomerisk.credit;

/**
 * Reference data for a Rating Bucket.
 *
 * @param sectorLongRunMeanBp the long-run mean of the bucket's Sector Factor, in basis points
 */
public record RatingBucketSpec(RatingBucket bucket, double sectorLongRunMeanBp) {
}
