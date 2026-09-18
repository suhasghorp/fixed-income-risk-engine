package com.fixedincomerisk.credit;

import java.util.List;

/**
 * Credit Events: Poisson jumps in an issuer's Latent Spread that may trigger a downgrade to
 * a worse Rating Bucket, followed by a temporary burst of Prints. Time is in years; spreads in bp.
 *
 * @param intensityPerYear        Credit Events per issuer per year
 * @param jumpMeanBp              mean spread jump; random jumps are exponentially distributed (widening)
 * @param jumpDecayPerYear        how fast a jump decays away; slow, so a jump does not mean-revert at once
 * @param migrationProbability    probability that a Credit Event also migrates the issuer's rating
 * @param maxNotches              a migration moves down 1 to this many rating grades
 * @param printBurstMultiplier    the issuer's Print intensity multiplier just after a Credit Event
 * @param printBurstDecayPerYear  how fast the burst decays back to the normal intensity
 * @param scheduled               Credit Events that happen at fixed ticks, for rehearsing a demo and for tests
 */
public record CreditEventParameters(
        double intensityPerYear,
        double jumpMeanBp,
        double jumpDecayPerYear,
        double migrationProbability,
        int maxNotches,
        double printBurstMultiplier,
        double printBurstDecayPerYear,
        List<ScheduledCreditEvent> scheduled) {

    public CreditEventParameters {
        if (intensityPerYear < 0 || jumpMeanBp < 0 || jumpDecayPerYear < 0 || printBurstDecayPerYear < 0
                || migrationProbability < 0 || migrationProbability > 1 || maxNotches < 1 || printBurstMultiplier < 1) {
            throw new IllegalArgumentException("Invalid Credit Event parameters");
        }
        scheduled = List.copyOf(scheduled);
    }

    /** No Credit Events at all. */
    public static final CreditEventParameters NONE = new CreditEventParameters(0, 0, 0, 0, 1, 1, 0, List.of());

    /**
     * A Credit Event at a fixed tick.
     *
     * @param tick    the Tick it happens on
     * @param jumpBp  the Latent Spread jump
     * @param notches rating grades to move down; 0 for no Rating Migration
     */
    public record ScheduledCreditEvent(String issuerId, long tick, double jumpBp, int notches) {

        /** Parses {@code ISSUER@tick:jumpBp:notches}, e.g. {@code ACME@120:80:1}. */
        public static ScheduledCreditEvent parse(String spec) {
            String[] issuerAndRest = spec.trim().split("@");
            String[] parts = issuerAndRest.length == 2 ? issuerAndRest[1].split(":") : new String[0];
            if (parts.length != 3) {
                throw new IllegalArgumentException("Scheduled Credit Event must look like ACME@120:80:1, got " + spec);
            }
            return new ScheduledCreditEvent(issuerAndRest[0].trim(), Long.parseLong(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        }
    }
}
