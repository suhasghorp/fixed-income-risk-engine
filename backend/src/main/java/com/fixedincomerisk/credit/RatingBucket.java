package com.fixedincomerisk.credit;

/** A (rating, sector) pair such as BBB Industrials, with its own Sector Factor. */
public record RatingBucket(String rating, String sector) {

    public String label() {
        return rating + " " + sector;
    }

    @Override
    public String toString() {
        return label();
    }
}
