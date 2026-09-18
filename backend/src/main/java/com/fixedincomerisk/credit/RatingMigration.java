package com.fixedincomerisk.credit;

/** A public change of an issuer's Rating Bucket, like a rating agency action. */
public record RatingMigration(String issuerId, RatingBucket from, RatingBucket to) {
}
