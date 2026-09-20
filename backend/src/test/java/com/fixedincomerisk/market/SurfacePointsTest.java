package com.fixedincomerisk.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Surface Points are named in configuration, not hardcoded. Only the points the Book needs exist, so
 * asking for one that is not configured is a failure naming the offender rather than an interpolation
 * between whatever neighbours happen to be there.
 */
class SurfacePointsTest {

    private static final SurfacePoints TWO_POINTS = SurfacePoints.parse("USD 1Mx5Y, USD 1Yx10Y");

    @Test
    void pointsAreReadFromConfigurationInOrder() {
        assertThat(TWO_POINTS.labels()).containsExactly("USD 1Mx5Y", "USD 1Yx10Y");
        assertThat(TWO_POINTS.get("USD", "1Mx5Y"))
                .isEqualTo(new SurfacePoint("USD", "1Mx5Y"));
        assertThat(SurfacePoints.parse("").isEmpty()).isTrue();
    }

    @Test
    void anUnknownPointNameFailsNamingTheOffenderAndWhatIsConfigured() {
        assertThatThrownBy(() -> TWO_POINTS.get("USD 3Mx2Y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Surface Point 'USD 3Mx2Y'")
                .hasMessageContaining("[USD 1Mx5Y, USD 1Yx10Y]");
        assertThatThrownBy(() -> TWO_POINTS.get("EUR", "1Mx5Y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EUR 1Mx5Y");
    }

    @Test
    void aMalformedPointFailsRatherThanBecomingASurfacePointNothingQuotes() {
        assertThatThrownBy(() -> SurfacePoints.parse("USD 1Mx5Y, USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency then a point");
        // A tenor with no expiry is a tenor, not a Surface Point.
        assertThatThrownBy(() -> SurfacePoints.parse("USD 1Mx5Y, USD 5Y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<expiry>x<tenor>").hasMessageContaining("'5Y'");
        assertThatThrownBy(() -> SurfacePoints.parse("USD 1Mx5Y, USD 1M-5Y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'1M-5Y'");
        assertThatThrownBy(() -> SurfacePoints.parse("US 1Mx5Y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three-letter code");
        assertThatThrownBy(() -> SurfacePoints.parse("USD 1Mx5Y, USD 1Mx5Y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate Surface Point 'USD 1Mx5Y'");
    }

    /**
     * The Risk Factor identifier already carries a currency, so a Surface Point needs no scheme of its
     * own; the correlated-driver name follows the same shape as an FX pair's.
     */
    @Test
    void eachPointNamesItsRiskFactorAndItsCorrelatedDriver() {
        SurfacePoint point = TWO_POINTS.get("USD 1Yx10Y");

        assertThat(point.volFactor()).isEqualTo(RiskFactorId.normalVol("USD", "1Yx10Y"));
        assertThat(point.volFactor().type()).isEqualTo(FactorType.NORMAL_VOL);
        assertThat(point.volShockFactor()).isEqualTo("normalVol.USD.1Yx10Y");
        assertThat(point.propertyPrefix()).isEqualTo("risk.vol.USD.1Yx10Y.");
        assertThat(point.label()).isEqualTo("USD 1Yx10Y");
    }

    /**
     * A Normal Volatility is quoted, not derived, and the threshold on it is in basis points of vol — so
     * it reports in bp like a zero rate, and unlike a Rating it is not an any-change-is-move factor.
     */
    @Test
    void normalVolReportsInBasisPointsAndIsNotAnAnyChangeIsMoveFactor() {
        assertThat(FactorType.NORMAL_VOL.unit()).isEqualTo("bp");
        assertThat(FactorType.NORMAL_VOL.anyChangeIsMove()).isFalse();
        // 0.0095 to 0.0101 is a six basis point move in vol.
        assertThat(FactorType.NORMAL_VOL.inUnits(0.0101 - 0.0095)).isEqualTo(6, org.assertj.core.api.Assertions.within(1e-9));
    }
}
