package com.fixedincomerisk.credit;

/**
 * A fictional corporate issuer, with how liquid its bonds are: Prints and Quotes arrive as Poisson
 * processes, and each reveals the Latent Spread plus Gaussian noise.
 *
 * @param initialBucket          the Rating Bucket at session start
 * @param idiosyncraticMeanBp    the long-run mean of the issuer's Idiosyncratic Factor, in basis points;
 *                               the factor starts there
 * @param printsPerYear          Print intensity
 * @param printNoiseBp           standard deviation of a Print's noise, in basis points
 * @param quotesPerYear          Quote intensity
 * @param quoteNoiseBp           standard deviation of a Quote's noise, in basis points; wider than a Print's
 */
public record Issuer(
        String id,
        String name,
        RatingBucket initialBucket,
        double idiosyncraticMeanBp,
        double printsPerYear,
        double printNoiseBp,
        double quotesPerYear,
        double quoteNoiseBp) {

    public Issuer {
        if (printsPerYear < 0 || quotesPerYear < 0 || printNoiseBp < 0 || quoteNoiseBp < 0) {
            throw new IllegalArgumentException("Observation intensities and noise must be non-negative for " + id);
        }
    }
}
