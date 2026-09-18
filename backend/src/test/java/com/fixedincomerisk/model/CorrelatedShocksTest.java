package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class CorrelatedShocksTest {

    @Test
    void choleskyFactorReproducesTheMatrix() {
        CorrelationMatrix matrix = CorrelationMatrix.of(-0.3, 0.1, 0.2);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double[] li = matrix.choleskyRow(i);
                double[] lj = matrix.choleskyRow(j);
                double product = li[0] * lj[0] + li[1] * lj[1] + li[2] * lj[2];
                assertThat(product).isCloseTo(matrix.correlation(i, j), within(1e-15));
            }
        }
    }

    @Test
    void parsesRowsSeparatedBySemicolons() {
        CorrelationMatrix matrix = CorrelationMatrix.parse("1,-0.3,0.1; -0.3,1,0; 0.1,0,1");

        assertThat(matrix.correlation(CorrelationMatrix.SHORT_RATE, CorrelationMatrix.SYSTEMIC)).isEqualTo(-0.3);
        assertThat(matrix.correlation(CorrelationMatrix.BASIS, CorrelationMatrix.SHORT_RATE)).isEqualTo(0.1);
    }

    @Test
    void invalidMatricesFailFastWithAClearMessage() {
        assertThatThrownBy(() -> CorrelationMatrix.parse("1,-0.3,0.1; -0.2,1,0; 0.1,0,1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be symmetric");
        assertThatThrownBy(() -> CorrelationMatrix.parse("0.9,0,0; 0,1,0; 0,0,1"))
                .hasMessageContaining("1 on the diagonal");
        assertThatThrownBy(() -> CorrelationMatrix.parse("1,1.2,0; 1.2,1,0; 0,0,1"))
                .hasMessageContaining("between −1 and 1");
        // Each pair is a valid correlation, but together they are impossible.
        assertThatThrownBy(() -> CorrelationMatrix.of(0.9, 0.9, -0.9))
                .hasMessageContaining("positive definite");
        assertThatThrownBy(() -> CorrelationMatrix.parse("1,0; 0,1"))
                .hasMessageContaining("3×3");
        assertThatThrownBy(() -> CorrelationMatrix.parse("1,x,0; 0,1,0; 0,0,1"))
                .hasMessageContaining("not numeric");
    }

    @Test
    void shocksHaveUnitVarianceAndTheConfiguredCorrelations() {
        CorrelatedShockGenerator generator = new CorrelatedShockGenerator(CorrelationMatrix.of(-0.3, 0.1, 0.2));
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(1);
        int n = 200_000;
        double[] r = new double[n];
        double[] s = new double[n];
        double[] b1 = new double[n];
        double[] b2 = new double[n];
        for (int i = 0; i < n; i++) {
            CorrelatedShockGenerator.Shocks shocks = generator.next(random, 2);
            r[i] = shocks.shortRate();
            s[i] = shocks.systemic();
            b1[i] = shocks.basis().get(0);
            b2[i] = shocks.basis().get(1);
        }

        assertThat(correlation(r, s)).isCloseTo(-0.3, within(0.01));
        assertThat(correlation(r, b1)).isCloseTo(0.1, within(0.01));
        assertThat(correlation(s, b1)).isCloseTo(0.2, within(0.01));
        assertThat(correlation(r, b2)).isCloseTo(0.1, within(0.01));
        assertThat(variance(r)).isCloseTo(1, within(0.01));
        assertThat(variance(b2)).isCloseTo(1, within(0.01));
        // Two contracts' Bases share only the common drivers: correlation L20² + L21².
        double[] basisRow = CorrelationMatrix.of(-0.3, 0.1, 0.2).choleskyRow(CorrelationMatrix.BASIS);
        assertThat(correlation(b1, b2)).isCloseTo(basisRow[0] * basisRow[0] + basisRow[1] * basisRow[1], within(0.01));
    }

    static double correlation(double[] x, double[] y) {
        double mx = mean(x);
        double my = mean(y);
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < x.length; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    private static double variance(double[] x) {
        double m = mean(x);
        double sum = 0;
        for (double v : x) {
            sum += (v - m) * (v - m);
        }
        return sum / x.length;
    }

    private static double mean(double[] x) {
        double sum = 0;
        for (double v : x) {
            sum += v;
        }
        return sum / x.length;
    }
}
