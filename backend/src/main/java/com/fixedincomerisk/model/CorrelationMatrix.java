package com.fixedincomerisk.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The correlation between the session's correlated drivers, each identified by name rather than by
 * position: {@code shortRate.USD}, {@code systemic}, {@code fxSpot.EURUSD}, {@code basis}, and so on.
 * The set is configuration, so a currency or a currency pair can be added without touching this class.
 *
 * <p>Validated on construction: square and the same size as the factor list, no duplicate names, unit
 * diagonal, symmetric, entries in [−1, 1], and positive definite, which is checked by computing the
 * Cholesky factor once.
 */
public final class CorrelationMatrix {

    private static final double TOLERANCE = 1e-12;

    private final List<String> factors;
    private final Map<String, Integer> indices;
    private final double[][] correlations;
    private final double[][] cholesky;

    public CorrelationMatrix(List<String> factors, double[][] correlations) {
        this.factors = List.copyOf(factors);
        this.indices = index(this.factors);
        int size = this.factors.size();
        if (correlations.length != size || Arrays.stream(correlations).anyMatch(row -> row.length != size)) {
            throw invalid(this.factors, "must be " + size + "×" + size + ", one row and column per factor",
                    correlations);
        }
        for (int i = 0; i < size; i++) {
            if (Math.abs(correlations[i][i] - 1) > TOLERANCE) {
                throw invalid(this.factors, "must have 1 on the diagonal", correlations);
            }
            for (int j = 0; j < size; j++) {
                if (!(Math.abs(correlations[i][j]) <= 1)) {
                    throw invalid(this.factors, "entries must be between −1 and 1", correlations);
                }
                if (Math.abs(correlations[i][j] - correlations[j][i]) > TOLERANCE) {
                    throw invalid(this.factors, "must be symmetric", correlations);
                }
            }
        }
        this.correlations = copy(correlations);
        this.cholesky = cholesky(this.factors, correlations);
    }

    /** Independent drivers, one per named factor. */
    public static CorrelationMatrix independent(List<String> factors) {
        double[][] identity = new double[factors.size()][factors.size()];
        for (int i = 0; i < factors.size(); i++) {
            identity[i][i] = 1;
        }
        return new CorrelationMatrix(factors, identity);
    }

    /**
     * Parses the factor names, separated by ',', and the matrix, rows separated by ';' and entries by ','
     * — e.g. {@code "shortRate.USD, systemic, basis"} and {@code "1,-0.3,0.1; -0.3,1,0; 0.1,0,1"}.
     */
    public static CorrelationMatrix parse(String factorNames, String matrixText) {
        List<String> names = Arrays.stream(factorNames.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList();
        if (names.isEmpty()) {
            throw new IllegalArgumentException("No correlated factors configured: " + factorNames);
        }
        try {
            double[][] rows = Arrays.stream(matrixText.split(";"))
                    .map(row -> Arrays.stream(row.split(",")).map(String::trim)
                            .mapToDouble(Double::parseDouble).toArray())
                    .toArray(double[][]::new);
            return new CorrelationMatrix(names, rows);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Correlation matrix is not numeric: " + matrixText, e);
        }
    }

    /** The factors in matrix order. */
    public List<String> factors() {
        return factors;
    }

    public int size() {
        return factors.size();
    }

    public boolean has(String factor) {
        return indices.containsKey(factor);
    }

    /** The factor's position, or a failure naming the offender and listing what is configured. */
    public int indexOf(String factor) {
        Integer index = indices.get(factor);
        if (index == null) {
            throw new IllegalArgumentException("Unknown correlated factor '" + factor
                    + "'; risk.correlation.factors names " + factors);
        }
        return index;
    }

    public double correlation(String a, String b) {
        return correlations[indexOf(a)][indexOf(b)];
    }

    /** Row for {@code factor} of the lower-triangular Cholesky factor L, with L·Lᵀ equal to this matrix. */
    double[] choleskyRow(String factor) {
        return cholesky[indexOf(factor)].clone();
    }

    private static Map<String, Integer> index(List<String> factors) {
        Map<String, Integer> indices = new LinkedHashMap<>();
        for (int i = 0; i < factors.size(); i++) {
            if (indices.put(factors.get(i), i) != null) {
                throw new IllegalArgumentException("Duplicate correlated factor '" + factors.get(i)
                        + "' in risk.correlation.factors " + factors);
            }
        }
        return indices;
    }

    private static double[][] cholesky(List<String> factors, double[][] a) {
        int size = factors.size();
        double[][] l = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (sum <= TOLERANCE) {
                        throw invalid(factors, "must be positive definite", a);
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

    private static IllegalArgumentException invalid(List<String> factors, String problem, double[][] matrix) {
        return new IllegalArgumentException(String.format(Locale.ROOT,
                "Invalid correlation matrix %s: %s; got %s", factors, problem, Arrays.deepToString(matrix)));
    }

    @Override
    public String toString() {
        return factors + " " + Arrays.deepToString(correlations);
    }
}
