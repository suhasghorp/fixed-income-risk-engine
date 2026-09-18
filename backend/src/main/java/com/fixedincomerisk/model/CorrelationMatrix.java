package com.fixedincomerisk.model;

import java.util.Arrays;
import java.util.Locale;

/**
 * The correlation between the three correlated drivers, in the order short rate, Systemic Factor, Basis.
 * Validated on construction: 3×3, unit diagonal, symmetric, entries in [−1, 1], and
 * positive definite, which is checked by computing its Cholesky factor once.
 */
public final class CorrelationMatrix {

    public static final int SHORT_RATE = 0;
    public static final int SYSTEMIC = 1;
    public static final int BASIS = 2;
    private static final int SIZE = 3;
    private static final double TOLERANCE = 1e-12;

    private final double[][] correlations;
    private final double[][] cholesky;

    public CorrelationMatrix(double[][] correlations) {
        if (correlations.length != SIZE || Arrays.stream(correlations).anyMatch(row -> row.length != SIZE)) {
            throw invalid("must be 3×3 (short rate, Systemic Factor, Basis)", correlations);
        }
        for (int i = 0; i < SIZE; i++) {
            if (Math.abs(correlations[i][i] - 1) > TOLERANCE) {
                throw invalid("must have 1 on the diagonal", correlations);
            }
            for (int j = 0; j < SIZE; j++) {
                if (!(Math.abs(correlations[i][j]) <= 1)) {
                    throw invalid("entries must be between −1 and 1", correlations);
                }
                if (Math.abs(correlations[i][j] - correlations[j][i]) > TOLERANCE) {
                    throw invalid("must be symmetric", correlations);
                }
            }
        }
        this.correlations = copy(correlations);
        this.cholesky = cholesky(correlations);
    }

    /** Independent drivers. */
    public static CorrelationMatrix identity() {
        return new CorrelationMatrix(new double[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}});
    }

    /** A matrix from its three off-diagonal correlations. */
    public static CorrelationMatrix of(double shortRateSystemic, double shortRateBasis, double systemicBasis) {
        return new CorrelationMatrix(new double[][] {
                {1, shortRateSystemic, shortRateBasis},
                {shortRateSystemic, 1, systemicBasis},
                {shortRateBasis, systemicBasis, 1}});
    }

    /** Parses rows separated by ';' and entries by ',', e.g. {@code 1,-0.3,0.1; -0.3,1,0; 0.1,0,1}. */
    public static CorrelationMatrix parse(String text) {
        try {
            double[][] rows = Arrays.stream(text.split(";"))
                    .map(row -> Arrays.stream(row.split(",")).map(String::trim).mapToDouble(Double::parseDouble).toArray())
                    .toArray(double[][]::new);
            return new CorrelationMatrix(rows);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Correlation matrix is not numeric: " + text, e);
        }
    }

    public double correlation(int i, int j) {
        return correlations[i][j];
    }

    /** Row {@code i} of the lower-triangular Cholesky factor L, with L·Lᵀ equal to this matrix. */
    double[] choleskyRow(int i) {
        return cholesky[i].clone();
    }

    private static double[][] cholesky(double[][] a) {
        double[][] l = new double[SIZE][SIZE];
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (sum <= TOLERANCE) {
                        throw invalid("must be positive definite", a);
                    }
                    l[i][i] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    private static double[][] copy(double[][] matrix) {
        return Arrays.stream(matrix).map(double[]::clone).toArray(double[][]::new);
    }

    private static IllegalArgumentException invalid(String problem, double[][] matrix) {
        return new IllegalArgumentException(String.format(Locale.ROOT,
                "Invalid correlation matrix (short rate, Systemic Factor, Basis): %s; got %s",
                problem, Arrays.deepToString(matrix)));
    }

    @Override
    public String toString() {
        return Arrays.deepToString(correlations);
    }
}
