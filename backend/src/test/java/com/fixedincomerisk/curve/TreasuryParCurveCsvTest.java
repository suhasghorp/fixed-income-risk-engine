package com.fixedincomerisk.curve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.StringReader;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TreasuryParCurveCsvTest {

    private static final String CSV = """
            Date,"1 Mo","1.5 Month","2 Mo","6 Mo","1 Yr","2 Yr","30 Yr"
            09/11/2026,3.93,,4.05,4.12,4.35,4.63,5.35
            09/10/2026,3.91,3.93,4.01,4.07,4.28,4.56,5.37
            """;

    @Test
    void readsTheMostRecentRowWithTenorsInYearsAndYieldsAsDecimals() {
        ParCurve curve = TreasuryParCurveCsv.parse(new StringReader(CSV));

        assertThat(curve.curveDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(curve.points()).extracting(ParPoint::tenor).containsExactly("1M", "2M", "6M", "1Y", "2Y", "30Y");
        assertThat(curve.points().get(1).years()).isCloseTo(2.0 / 12, within(1e-12));
        assertThat(curve.points().get(5).years()).isEqualTo(30.0);
        assertThat(curve.points().get(4).parYield()).isCloseTo(0.0463, within(1e-12));
    }

    @Test
    void bundledSnapshotLoadsAsBundledSource() {
        CurveSnapshot snapshot = new BundledCurveSource().load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.BUNDLED);
        assertThat(snapshot.quotes()).hasSizeGreaterThan(10);
    }
}
