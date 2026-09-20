package com.fixedincomerisk.curve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The ECB parse, pinned against a response saved from the real Data Portal on 2026-09-17. The saved file
 * is the ECB's own {@code format=csvdata} output, unedited.
 */
class EcbSpotRatesCsvTest {

    private static final String SAVED_RESPONSE = "/ecb/spot-rates-2026-09-17.csv";

    private final EcbTenors tenors = EcbTenors.fromClasspath();

    @Test
    void parsesTheSavedEcbResponseWithItsDateAndKnownRates() {
        ZeroCurve curve = parseSavedResponse();

        assertThat(curve.curveDate()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(curve.points()).hasSize(10);
        // The published 10-year spot rate for that date, as a decimal.
        assertThat(rateAt(curve, "10Y")).isCloseTo(0.034875063501, within(1e-15));
        assertThat(rateAt(curve, "3M")).isCloseTo(0.025385641712, within(1e-15));
        assertThat(rateAt(curve, "30Y")).isCloseTo(0.03750190154, within(1e-15));
    }

    @Test
    void ordersPointsByTenorWhateverOrderTheResponseArrivesIn() {
        ZeroCurve curve = parseSavedResponse();

        assertThat(curve.points()).extracting(ZeroPoint::tenor)
                .containsExactly("3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y");
    }

    /** The ECB quotes continuously compounded rates, which is what the discount curve is built from. */
    @Test
    void buildsADiscountCurveThatReturnsThePublishedSpotRates() {
        DiscountCurve curve = parseSavedResponse().toDiscountCurve();

        assertThat(curve.zeroRate(10)).isCloseTo(0.034875063501, within(1e-12));
        assertThat(curve.discountFactor(10)).isCloseTo(Math.exp(-0.034875063501 * 10), within(1e-12));
    }

    @Test
    void survivesARoundTripThroughTheCacheFormat() {
        ZeroCurve original = parseSavedResponse();

        ZeroCurve reparsed = ZeroCurveCsv.parse(new StringReader(ZeroCurveCsv.format(original)), tenors);

        assertThat(reparsed.curveDate()).isEqualTo(original.curveDate());
        assertThat(reparsed.points()).isEqualTo(original.points());
    }

    @Test
    void splitsRowsWhoseLaterColumnsCarryQuotedCommas() {
        String[] fields = EcbSpotRatesCsv.split("a,b,\"c,d\",e");

        assertThat(fields).containsExactly("a", "b", "c,d", "e");
    }

    @Test
    void rejectsAResponseWithNoObservations() {
        String headerOnly = "KEY,DATA_TYPE_FM,TIME_PERIOD,OBS_VALUE\n";

        assertThatThrownBy(() -> EcbSpotRatesCsv.parse(new StringReader(headerOnly), tenors))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no observations");
    }

    @Test
    void keepsOnlyTheMostRecentObservationDate() {
        String twoDates = """
                KEY,DATA_TYPE_FM,TIME_PERIOD,OBS_VALUE
                YC,SR_10Y,2026-09-16,3.40
                YC,SR_10Y,2026-09-17,3.50
                YC,SR_2Y,2026-09-17,3.10
                """;

        ZeroCurve curve = EcbSpotRatesCsv.parse(new StringReader(twoDates), tenors);

        assertThat(curve.curveDate()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(curve.points()).extracting(ZeroPoint::tenor).containsExactly("2Y", "10Y");
        assertThat(rateAt(curve, "10Y")).isCloseTo(0.035, within(1e-15));
    }

    private ZeroCurve parseSavedResponse() {
        InputStream in = EcbSpotRatesCsvTest.class.getResourceAsStream(SAVED_RESPONSE);
        assertThat(in).as("saved ECB response " + SAVED_RESPONSE).isNotNull();
        return EcbSpotRatesCsv.parse(new InputStreamReader(in, StandardCharsets.UTF_8), tenors);
    }

    private static double rateAt(ZeroCurve curve, String tenor) {
        return curve.points().stream()
                .filter(point -> point.tenor().equals(tenor))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + tenor + " point"))
                .zeroRate();
    }
}
