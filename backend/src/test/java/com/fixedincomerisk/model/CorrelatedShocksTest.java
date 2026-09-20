package com.fixedincomerisk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class CorrelatedShocksTest {

    /** The three-factor set the engine ran on before a second currency; it must still parse unchanged. */
    private static final String THREE_FACTORS = "shortRate.USD, systemic, basis";
    private static final String SEVEN_FACTORS =
            "shortRate.USD, systemic, shortRate.EUR, fxSpot.EURUSD, fxSpot.USDKRW, ndfPoints.USDKRW, basis";

    private static CorrelationMatrix threeFactor(double rateSystemic, double rateBasis, double systemicBasis) {
        return CorrelationMatrix.parse(THREE_FACTORS, String.format(
                "1,%s,%s; %s,1,%s; %s,%s,1",
                rateSystemic, rateBasis, rateSystemic, systemicBasis, rateBasis, systemicBasis));
    }

    @Test
    void choleskyFactorReproducesTheMatrix() {
        CorrelationMatrix matrix = threeFactor(-0.3, 0.1, 0.2);

        for (String a : matrix.factors()) {
            for (String b : matrix.factors()) {
                double[] la = matrix.choleskyRow(a);
                double[] lb = matrix.choleskyRow(b);
                double product = 0;
                for (int k = 0; k < matrix.size(); k++) {
                    product += la[k] * lb[k];
                }
                assertThat(product).isCloseTo(matrix.correlation(a, b), within(1e-15));
            }
        }
    }

    @Test
    void theExistingThreeFactorMatrixStillParses() {
        CorrelationMatrix matrix = CorrelationMatrix.parse(THREE_FACTORS, "1,-0.3,0.1; -0.3,1,0; 0.1,0,1");

        assertThat(matrix.factors()).containsExactly("shortRate.USD", "systemic", "basis");
        assertThat(matrix.correlation("shortRate.USD", "systemic")).isEqualTo(-0.3);
        assertThat(matrix.correlation("basis", "shortRate.USD")).isEqualTo(0.1);
    }

    @Test
    void theSevenFactorMatrixParsesAndIsIndexedByName() {
        CorrelationMatrix matrix = CorrelationMatrix.parse(SEVEN_FACTORS, """
                1,-0.3,0.6,0,0,0,0.1; \
                -0.3,1,0,0,0,0,0; \
                0.6,0,1,0,0,0,0; \
                0,0,0,1,0,0,0; \
                0,0,0,0,1,0,0; \
                0,0,0,0,0,1,0; \
                0.1,0,0,0,0,0,1""");

        assertThat(matrix.size()).isEqualTo(7);
        assertThat(matrix.correlation("shortRate.USD", "shortRate.EUR")).isEqualTo(0.6);
        assertThat(matrix.correlation("fxSpot.EURUSD", "shortRate.USD")).isZero();
        assertThat(matrix.has("ndfPoints.USDKRW")).isTrue();
        assertThat(matrix.has("ndfPoints.EURUSD")).isFalse();
    }

    @Test
    void anUnknownFactorNamesTheOffenderAndListsWhatIsConfigured() {
        CorrelationMatrix matrix = CorrelationMatrix.parse(THREE_FACTORS, "1,0,0; 0,1,0; 0,0,1");

        assertThatThrownBy(() -> matrix.correlation("shortRate.EUR", "systemic"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shortRate.EUR")
                .hasMessageContaining("shortRate.USD");
    }

    @Test
    void aDuplicateFactorNameFailsNamingTheOffender() {
        assertThatThrownBy(() -> CorrelationMatrix.parse("shortRate.USD, systemic, shortRate.USD",
                "1,0,0; 0,1,0; 0,0,1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate correlated factor 'shortRate.USD'");
    }

    @Test
    void invalidMatricesFailFastWithAClearMessage() {
        assertThatThrownBy(() -> CorrelationMatrix.parse(THREE_FACTORS, "1,-0.3,0.1; -0.2,1,0; 0.1,0,1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be symmetric");
        assertThatThrownBy(() -> CorrelationMatrix.parse(THREE_FACTORS, "0.9,0,0; 0,1,0; 0,0,1"))
                .hasMessageContaining("1 on the diagonal");
        assertThatThrownBy(() -> CorrelationMatrix.parse(THREE_FACTORS, "1,1.2,0; 1.2,1,0; 0,0,1"))
                .hasMessageContaining("between −1 and 1");
        // Each pair is a valid correlation, but together they are impossible.
        assertThatThrownBy(() -> threeFactor(0.9, 0.9, -0.9))
                .hasMessageContaining("positive definite");
        assertThatThrownBy(() -> CorrelationMatrix.parse(THREE_FACTORS, "1,0; 0,1"))
                .hasMessageContaining("3×3");
        assertThatThrownBy(() -> CorrelationMatrix.parse(THREE_FACTORS, "1,x,0; 0,1,0; 0,0,1"))
                .hasMessageContaining("not numeric");
        assertThatThrownBy(() -> CorrelationMatrix.parse("", "1"))
                .hasMessageContaining("No correlated factors");
    }

    /** Every contract substitutes its own draw for the Basis slot, so nothing may correlate against it. */
    @Test
    void basisMustBeTheLastFactor() {
        CorrelationMatrix matrix = CorrelationMatrix.parse("shortRate.USD, basis, systemic", "1,0,0; 0,1,0; 0,0,1");

        assertThatThrownBy(() -> new CorrelatedShockGenerator(matrix))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be the last factor");
    }

    @Test
    void shocksHaveUnitVarianceAndTheConfiguredCorrelations() {
        CorrelationMatrix matrix = threeFactor(-0.3, 0.1, 0.2);
        CorrelatedShockGenerator generator = new CorrelatedShockGenerator(matrix);
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(1);
        int n = 200_000;
        double[] r = new double[n];
        double[] s = new double[n];
        double[] b1 = new double[n];
        double[] b2 = new double[n];
        for (int i = 0; i < n; i++) {
            CorrelatedShockGenerator.Shocks shocks = generator.next(random, 2);
            r[i] = shocks.of("shortRate.USD");
            s[i] = shocks.of("systemic");
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
        double[] basisRow = matrix.choleskyRow("basis");
        assertThat(correlation(b1, b2)).isCloseTo(basisRow[0] * basisRow[0] + basisRow[1] * basisRow[1], within(0.01));
    }

    /** Two currencies' short rates must be correlated, not identical: that is what the guard existed for. */
    @Test
    void eachCurrencysShortRateGetsItsOwnShock() {
        CorrelationMatrix matrix = CorrelationMatrix.parse("shortRate.USD, shortRate.EUR",
                "1,0.6; 0.6,1");
        CorrelatedShockGenerator generator = new CorrelatedShockGenerator(matrix);
        RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(7);
        int n = 200_000;
        double[] usd = new double[n];
        double[] eur = new double[n];
        for (int i = 0; i < n; i++) {
            CorrelatedShockGenerator.Shocks shocks = generator.next(random, 0);
            usd[i] = shocks.of("shortRate.USD");
            eur[i] = shocks.of("shortRate.EUR");
        }

        assertThat(correlation(usd, eur)).isCloseTo(0.6, within(0.01));
        assertThat(usd).isNotEqualTo(eur);
    }

    @Test
    void anUnsimulatedFactorHasNoShockRatherThanASilentZero() {
        CorrelatedShockGenerator generator = new CorrelatedShockGenerator(
                CorrelationMatrix.independent(List.of("shortRate.USD", "systemic", "basis")));
        CorrelatedShockGenerator.Shocks shocks =
                generator.next(RandomGeneratorFactory.of("L64X128MixRandom").create(3), 1);

        assertThatThrownBy(() -> shocks.of("fxSpot.EURUSD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fxSpot.EURUSD");
        assertThat(shocks.orZero("fxSpot.EURUSD")).isZero();
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
