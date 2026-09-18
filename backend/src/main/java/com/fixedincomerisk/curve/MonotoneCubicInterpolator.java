package com.fixedincomerisk.curve;

import java.util.Arrays;

/**
 * Shape-preserving piecewise cubic Hermite interpolation (Fritsch–Carlson / PCHIP slopes).
 * Monotone data stays monotone, linear data is reproduced exactly, and the first derivative
 * is continuous.
 */
final class MonotoneCubicInterpolator {

    private final double[] x;
    private final double[] y;
    private final double[] slopes;

    MonotoneCubicInterpolator(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) {
            throw new IllegalArgumentException("Need at least two matching knots");
        }
        for (int i = 1; i < x.length; i++) {
            if (!(x[i] > x[i - 1])) {
                throw new IllegalArgumentException("Knots must be strictly increasing");
            }
        }
        this.x = x.clone();
        this.y = y.clone();
        this.slopes = pchipSlopes(this.x, this.y);
    }

    double value(double at) {
        int k = segment(at);
        double h = x[k + 1] - x[k];
        double s = (at - x[k]) / h;
        double s2 = s * s;
        double s3 = s2 * s;
        return (2 * s3 - 3 * s2 + 1) * y[k]
                + (s3 - 2 * s2 + s) * h * slopes[k]
                + (-2 * s3 + 3 * s2) * y[k + 1]
                + (s3 - s2) * h * slopes[k + 1];
    }

    double derivative(double at) {
        int k = segment(at);
        double h = x[k + 1] - x[k];
        double s = (at - x[k]) / h;
        double s2 = s * s;
        return ((6 * s2 - 6 * s) * y[k]
                + (3 * s2 - 4 * s + 1) * h * slopes[k]
                + (-6 * s2 + 6 * s) * y[k + 1]
                + (3 * s2 - 2 * s) * h * slopes[k + 1]) / h;
    }

    double firstKnot() {
        return x[0];
    }

    double lastKnot() {
        return x[x.length - 1];
    }

    private int segment(double at) {
        if (at < x[0] || at > x[x.length - 1]) {
            throw new IllegalArgumentException("Outside interpolation range: " + at);
        }
        int i = Arrays.binarySearch(x, at);
        int k = i >= 0 ? i : -i - 2;
        return Math.min(Math.max(k, 0), x.length - 2);
    }

    private static double[] pchipSlopes(double[] x, double[] y) {
        int n = x.length;
        double[] h = new double[n - 1];
        double[] secant = new double[n - 1];
        for (int k = 0; k < n - 1; k++) {
            h[k] = x[k + 1] - x[k];
            secant[k] = (y[k + 1] - y[k]) / h[k];
        }
        double[] m = new double[n];
        m[0] = secant[0];
        m[n - 1] = secant[n - 2];
        for (int k = 1; k < n - 1; k++) {
            if (secant[k - 1] * secant[k] <= 0) {
                m[k] = 0;
            } else {
                double w1 = 2 * h[k] + h[k - 1];
                double w2 = h[k] + 2 * h[k - 1];
                m[k] = (w1 + w2) / (w1 / secant[k - 1] + w2 / secant[k]);
            }
        }
        return m;
    }
}
