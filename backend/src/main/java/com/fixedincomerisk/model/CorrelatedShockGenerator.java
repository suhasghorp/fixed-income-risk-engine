package com.fixedincomerisk.model;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Produces each tick's correlated standard normal shocks for the short rate, the Systemic Factor and the
 * futures Basis, as Z = L·ε from independent draws ε of the single seeded source and
 * the Cholesky factor L computed once. Each futures contract gets its own Basis shock from the Basis row of
 * L with its own independent draw, so every contract's Basis has the configured correlations with the
 * short rate and the Systemic Factor, and the contracts are correlated with each other only through them.
 */
public final class CorrelatedShockGenerator {

    private final double[] shortRateRow;
    private final double[] systemicRow;
    private final double[] basisRow;

    public CorrelatedShockGenerator(CorrelationMatrix correlations) {
        this.shortRateRow = correlations.choleskyRow(CorrelationMatrix.SHORT_RATE);
        this.systemicRow = correlations.choleskyRow(CorrelationMatrix.SYSTEMIC);
        this.basisRow = correlations.choleskyRow(CorrelationMatrix.BASIS);
    }

    /** Draws the next tick's shocks, in a fixed order: short rate, Systemic, then one per futures contract. */
    public Shocks next(RandomGenerator random, int futuresContracts) {
        double e0 = random.nextGaussian();
        double e1 = random.nextGaussian();
        double shortRate = shortRateRow[0] * e0;
        double systemic = systemicRow[0] * e0 + systemicRow[1] * e1;
        List<Double> basis = new ArrayList<>(futuresContracts);
        for (int i = 0; i < futuresContracts; i++) {
            basis.add(basisRow[0] * e0 + basisRow[1] * e1 + basisRow[2] * random.nextGaussian());
        }
        return new Shocks(shortRate, systemic, basis);
    }

    /**
     * One tick's correlated standard normal shocks.
     *
     * @param basis one per futures contract, in contract order
     */
    public record Shocks(double shortRate, double systemic, List<Double> basis) {

        public Shocks {
            basis = List.copyOf(basis);
        }
    }
}
