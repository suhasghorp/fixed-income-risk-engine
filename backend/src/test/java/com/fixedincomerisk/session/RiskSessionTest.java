package com.fixedincomerisk.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.credit.CreditEventParameters;
import com.fixedincomerisk.credit.CreditParameters;
import com.fixedincomerisk.curve.CurveSnapshot;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.curve.CurveSourceKind;
import com.fixedincomerisk.curve.ParCurve;
import com.fixedincomerisk.curve.ParPoint;
import com.fixedincomerisk.instrument.Instrument;
import com.fixedincomerisk.market.FactorType;
import com.fixedincomerisk.market.FixingHistory;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.market.FxPairs;
import com.fixedincomerisk.model.CorrelationMatrix;
import com.fixedincomerisk.model.FuturesBasisParameters;
import com.fixedincomerisk.model.HullWhiteParameters;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.repricing.MaterialityThresholds;
import com.fixedincomerisk.repricing.RepricingSettings;
import com.fixedincomerisk.session.RiskSnapshot.PositionResult;
import com.fixedincomerisk.simulation.SimulationSettings;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RiskSessionTest {

    private static final LocalDate CURVE_DATE = LocalDate.of(2026, 9, 11);

    private static final String TREASURIES = """
            cusip,term,coupon,datedDate,maturityDate
            91282CRH6,2Y,4.125,2026-08-31,2028-08-31
            91282CRF0,10Y,4.625,2026-08-15,2036-08-15
            COUPON_13TH,2Y,4.000,2025-03-13,2027-03-13
            MATURES_13TH,2Y,3.500,2024-09-13,2026-09-13
            912810UW6,30Y,4.750,2026-08-15,2056-08-15
            """;

    /** A short-dated and a long-dated Treasury. */
    private static final String SHORT_AND_LONG_BOOK = """
            positionId,instrumentId,quantity
            TWO_YEAR,91282CRH6,10000000
            THIRTY_YEAR,912810UW6,2000000
            """;

    private static final RepricingSettings TWO_BP = new RepricingSettings(new MaterialityThresholds(2, 1, 1, 0.02, 0, 0), 0.05);

    private static final CreditParameters CREDIT = new CreditParameters(0.5, 60, 40, 2, 25, 20, 15);

    private static final FuturesBasisParameters BASIS = new FuturesBasisParameters(12, -0.2, 0.5, 24, 0.15);

    private static final String FUTURES = """
            contract,description,proxyId,coupon,datedDate,maturityDate,conversionFactor
            ZNZ6,ZN Dec26 10Y note future,ZNZ6-CTD1,4.125,2023-08-15,2033-08-15,0.9003
            ZNZ6,ZN Dec26 10Y note future,ZNZ6-CTD2,4.250,2023-11-15,2033-11-15,0.9040
            ZNZ6,ZN Dec26 10Y note future,ZNZ6-CTD3,4.000,2024-02-15,2034-02-15,0.8870
            """;

    /** A bond hedged with a short future. */
    private static final String FUTURES_BOOK = """
            positionId,instrumentId,quantity
            LONG_10Y,91282CRF0,8000000
            SHORT_ZN,ZNZ6,-6000000
            """;

    private static final String RATING_BUCKETS = """
            rating,sector,sectorLongRunMeanBp
            A,Industrials,25
            BBB,Industrials,70
            A,Financials,35
            """;

    /** A liquid issuer, an illiquid one, and one that is never observed. */
    private static final String ISSUERS = """
            issuerId,name,rating,sector,idiosyncraticMeanBp,printsPerYear,printNoiseBp,quotesPerYear,quoteNoiseBp
            LIQUID,Liquid Corp,BBB,Industrials,5,2000,1,3000,4
            ILLIQUID,Illiquid Corp,A,Industrials,10,60,1.5,250,6
            SILENT,Silent Corp,A,Industrials,0,0,1,0,6
            """;

    private static final String CORPORATES = """
            bondId,issuerId,coupon,datedDate,maturityDate
            LIQ-5.10-2029,LIQUID,5.100,2024-10-01,2029-10-01
            ILL-4.85-2031,ILLIQUID,4.850,2024-06-15,2031-06-15
            SIL-5.00-2033,SILENT,5.000,2023-03-01,2033-03-01
            """;

    /** A payer part-way through its first floating period on the curve date, and a receiver resetting on 09-14. */
    private static final String SWAPS = """
            swapId,direction,fixedRate,effectiveDate,maturityDate
            IRS-5Y-PAY,PAY_FIXED,3.950,2026-07-15,2031-07-15
            IRS-10Y-REC,RECEIVE_FIXED,4.100,2025-12-14,2035-12-14
            """;

    private static final String SWAP_BOOK = """
            positionId,instrumentId,quantity
            PAYER,IRS-5Y-PAY,20000000
            RECEIVER,IRS-10Y-REC,15000000
            """;

    private static final String CREDIT_BOOK = """
            positionId,instrumentId,quantity
            LONG_10Y,91282CRF0,8000000
            LONG_LIQUID,LIQ-5.10-2029,4000000
            LONG_ILLIQUID,ILL-4.85-2031,5000000
            SHORT_SILENT,SIL-5.00-2033,-2000000
            """;

    /** No diffusion and no switches: the Basis stays at its long-run mean. */
    private static FuturesBasisParameters frozenBasis(double level) {
        return new FuturesBasisParameters(0, level, 0, 0, 0.15);
    }

    /** Positions in bonds that pay on 2026-09-13, two days after the curve date. */
    private static final String LIFECYCLE_BOOK = """
            positionId,instrumentId,quantity
            LONG_COUPON,COUPON_13TH,1000000
            SHORT_COUPON,COUPON_13TH,-400000
            LONG_MATURING,MATURES_13TH,2000000
            """;

    private static final String BOOK = """
            positionId,instrumentId,quantity
            LONG_2Y,91282CRH6,10000000
            LONG_10Y,91282CRF0,8000000
            SHORT_10Y,91282CRF0,-3000000
            """;

    /** A fake USD Curve Source: a flat 4.5% par curve reported as LIVE. */
    private static final CurveSource FAKE_CURVE_SOURCE = new CurveSource() {

        @Override
        public String currency() {
            return "USD";
        }

        @Override
        public CurveSnapshot load() {
            return CurveSnapshot.of(
                    new ParCurve(CURVE_DATE, List.of(0.5, 1.0, 2.0, 5.0, 10.0, 30.0).stream()
                            .map(t -> new ParPoint(t + "Y", t, 0.045))
                            .toList()),
                    CurveSourceKind.LIVE);
        }
    };

    private final RiskSession session = session(42);

    private static RiskSession session(long seed) {
        return session(seed, BOOK);
    }

    private static RiskSession session(long seed, String book) {
        return session(seed, book, 24);
    }

    private static RiskSession session(long seed, String book, int ticksPerDay) {
        return session(seed, book, ticksPerDay, RepricingSettings.REPRICE_EVERYTHING);
    }

    private static RiskSession session(long seed, String book, int ticksPerDay, RepricingSettings repricing) {
        return session(seed, book, ticksPerDay, repricing, BASIS);
    }

    private static RiskSession session(long seed, String book, int ticksPerDay, RepricingSettings repricing,
                                       FuturesBasisParameters basis) {
        return session(seed, book, ticksPerDay, repricing, basis, CREDIT);
    }

    private static RiskSession session(long seed, String book, int ticksPerDay, RepricingSettings repricing,
                                       FuturesBasisParameters basis, CreditParameters credit) {
        return session(seed, book, ticksPerDay, repricing, basis, credit, CreditEventParameters.NONE);
    }

    /** One currency, no FX: the factor set the engine ran on before the euro curve. */
    private static final List<String> SINGLE_CURRENCY_FACTORS = List.of("shortRate.USD", "systemic", "basis");

    private static RiskSession session(long seed, String book, int ticksPerDay, RepricingSettings repricing,
                                       FuturesBasisParameters basis, CreditParameters credit,
                                       CreditEventParameters creditEvents) {
        return session(seed, book, ticksPerDay, repricing, basis, credit, creditEvents, CorrelationMatrix.independent(SINGLE_CURRENCY_FACTORS));
    }

    private static RiskSession session(long seed, String book, int ticksPerDay, RepricingSettings repricing,
                                       FuturesBasisParameters basis, CreditParameters credit,
                                       CreditEventParameters creditEvents, CorrelationMatrix correlations) {
        return RiskSession.create(new SessionConfig(
                List.of(FAKE_CURVE_SOURCE),
                "USD",
                ReferenceData.parse(new ReferenceData.Csv(TREASURIES, FUTURES, RATING_BUCKETS, ISSUERS, CORPORATES, SWAPS, book)),
                Map.of("USD", new HullWhiteParameters(0.05, 0.01)),
                basis,
                FxPairs.NONE,
                Map.of(),
                Map.of(),
                credit,
                creditEvents,
                correlations,
                Pillar.DEFAULTS,
                new SimulationSettings(seed, Duration.ofHours(1), ticksPerDay),
                repricing));
    }

    @Test
    void snapshotReportsSessionMetadataFromTheCurveSource() {
        RiskSnapshot snapshot = session.snapshot();

        assertThat(snapshot.sequence()).isZero();
        assertThat(snapshot.tick()).isZero();
        assertThat(snapshot.session().curveSource()).isEqualTo("LIVE");
        assertThat(snapshot.session().curveDate()).isEqualTo("2026-09-11");
        assertThat(snapshot.session().valuationDate()).isEqualTo("2026-09-11");
        assertThat(snapshot.session().seed()).isEqualTo(42);
        assertThat(snapshot.session().simulatedSecondsPerTick()).isEqualTo(3600);
        assertThat(snapshot.session().ticksPerDay()).isEqualTo(24);
        assertThat(snapshot.ticksUntilDayRollover()).isEqualTo(24);
        assertThat(snapshot.recentLifecycleEvents()).isEmpty();
    }

    @Test
    void stepAdvancesOneTickWithStrictlyIncreasingSequenceNumbers() {
        RiskUpdate first = session.step();
        RiskUpdate second = session.step();

        assertThat(first.tick()).isEqualTo(1);
        assertThat(second.tick()).isEqualTo(2);
        assertThat(second.sequence()).isEqualTo(first.sequence() + 1);
        assertThat(session.snapshot().tick()).isEqualTo(2);
        assertThat(session.snapshot().sequence()).isEqualTo(second.sequence());
    }

    @Test
    void ticksMoveTheMarket() {
        double before = session.snapshot().positions().getFirst().dirtyPrice();

        session.step();

        assertThat(session.snapshot().positions().getFirst().dirtyPrice()).isNotEqualTo(before);
    }

    @Test
    void sameSeedAndConfigurationReplayIdenticalRiskUpdates() {
        assertThat(updates(session(7), 50)).isEqualTo(updates(session(7), 50));
    }

    @Test
    void differentSeedsDiverge() {
        assertThat(updates(session(7), 5)).isNotEqualTo(updates(session(8), 5));
    }

    @Test
    void applyingEveryRiskUpdateToTheInitialSnapshotReproducesTheCurrentSnapshot() {
        RiskSnapshot client = session.snapshot();

        for (int i = 0; i < 30; i++) {
            client = client.withUpdate(session.step());
        }

        assertThat(client).isEqualTo(session.snapshot());
    }

    private static List<RiskUpdate> updates(RiskSession session, int count) {
        List<RiskUpdate> updates = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            updates.add(session.step());
        }
        return updates;
    }

    @Test
    void positionValueIsSignedQuantityTimesInstrumentDirtyValue() {
        Map<String, PositionResult> positions = byId(session.snapshot());

        assertThat(positions).containsOnlyKeys("LONG_2Y", "LONG_10Y", "SHORT_10Y");
        for (PositionResult position : positions.values()) {
            assertThat(position.value())
                    .isCloseTo(position.quantity() * position.dirtyPrice() / 100, within(1e-6));
            assertThat(position.dirtyPrice())
                    .isCloseTo(position.cleanPrice() + position.accruedInterest(), within(1e-12));
        }
    }

    @Test
    void positionsInTheSameInstrumentShareItsPriceAndNetBySign() {
        Map<String, PositionResult> positions = byId(session.snapshot());
        PositionResult longTen = positions.get("LONG_10Y");
        PositionResult shortTen = positions.get("SHORT_10Y");

        assertThat(shortTen.dirtyPrice()).isEqualTo(longTen.dirtyPrice());
        assertThat(shortTen.value()).isNegative();
        assertThat(longTen.value() / shortTen.value()).isCloseTo(8.0 / -3.0, within(1e-12));
    }

    @Test
    void treasuriesArePricedConsistentlyWithTheInputCurve() {
        // A 4.125% coupon below the flat 4.5% par curve prices below par; the 4.625% ten-year above
        // 4.5% prices slightly above par.
        Map<String, PositionResult> positions = byId(session.snapshot());

        assertThat(positions.get("LONG_2Y").cleanPrice()).isBetween(98.5, 100.0);
        assertThat(positions.get("LONG_10Y").cleanPrice()).isBetween(100.0, 101.5);
    }

    @Test
    void snapshotCarriesTheCurveAtPillarsChartPointsAndParInputs() {
        RiskSnapshot.CurveView curve = session.snapshot().curve();

        assertThat(curve.pillars()).extracting(RiskSnapshot.CurvePoint::label)
                .containsExactly("3M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y");
        assertThat(curve.points()).hasSize(120);
        assertThat(curve.parInputs()).hasSize(6);
        assertThat(curve.pillars().get(6).zeroRate()).isCloseTo(2 * Math.log(1.0225), within(1e-9));
    }

    @Test
    void everyPositionCarriesDv01AndBucketedDv01AtEachPillar() {
        for (PositionResult position : session.snapshot().positions()) {
            assertThat(position.bucketedDv01()).extracting(RiskSnapshot.BucketDv01::pillar)
                    .containsExactly("3M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y");
            double bucketSum = position.bucketedDv01().stream().mapToDouble(RiskSnapshot.BucketDv01::dv01).sum();
            assertThat(bucketSum).isCloseTo(position.dv01(), within(1e-5 * Math.abs(position.dv01())));
        }
        Map<String, PositionResult> positions = byId(session.snapshot());
        assertThat(positions.get("LONG_2Y").dv01()).isPositive();
        assertThat(positions.get("SHORT_10Y").dv01()).isNegative();
    }

    @Test
    void bookTotalsEqualTheSumOfPositionContributions() {
        session.step();
        RiskSnapshot snapshot = session.snapshot();
        RiskSnapshot.BookRisk book = snapshot.bookRisk();

        assertThat(book.value()).isCloseTo(snapshot.positions().stream().mapToDouble(PositionResult::value).sum(),
                within(1e-6));
        assertThat(book.dv01()).isCloseTo(snapshot.positions().stream().mapToDouble(PositionResult::dv01).sum(),
                within(1e-9));
        for (int i = 0; i < book.bucketedDv01().size(); i++) {
            int pillar = i;
            double sum = snapshot.positions().stream().mapToDouble(p -> p.bucketedDv01().get(pillar).dv01()).sum();
            assertThat(book.bucketedDv01().get(i).dv01()).isCloseTo(sum, within(1e-9));
        }
        assertThat(book.byInstrumentType()).singleElement().satisfies(type -> {
            assertThat(type.instrumentType()).isEqualTo("TREASURY_BOND");
            assertThat(type.positionCount()).isEqualTo(3);
            assertThat(type.dv01()).isCloseTo(book.dv01(), within(1e-9));
            assertThat(type.value()).isCloseTo(book.value(), within(1e-6));
        });
    }

    @Test
    void equalAndOppositePositionsNetToZero() {
        RiskSession hedged = session(42, """
                positionId,instrumentId,quantity
                LONG,91282CRF0,5000000
                SHORT,91282CRF0,-5000000
                """);
        hedged.step();
        RiskSnapshot.BookRisk book = hedged.snapshot().bookRisk();

        assertThat(byId(hedged.snapshot()).get("LONG").dv01()).isPositive();
        assertThat(book.dv01()).isZero();
        assertThat(book.value()).isZero();
        assertThat(book.bucketedDv01()).allSatisfy(bucket -> assertThat(bucket.dv01()).isZero());
    }

    @Test
    void twoPositionsInOneInstrumentScaleLinearly() {
        Map<String, PositionResult> positions = byId(session.snapshot());
        PositionResult longTen = positions.get("LONG_10Y");
        PositionResult shortTen = positions.get("SHORT_10Y");

        assertThat(longTen.dv01() / shortTen.dv01()).isCloseTo(8.0 / -3.0, within(1e-12));
        for (int i = 0; i < longTen.bucketedDv01().size(); i++) {
            assertThat(shortTen.bucketedDv01().get(i).dv01())
                    .isCloseTo(longTen.bucketedDv01().get(i).dv01() * -3.0 / 8.0, within(1e-9));
        }
    }

    @Test
    void riskUpdatesCarryTheBookRollups() {
        RiskUpdate update = session.step();

        assertThat(update.bookRisk()).isEqualTo(session.snapshot().bookRisk());
        assertThat(update.positions()).allSatisfy(p -> assertThat(p.dv01()).isNotZero());
    }

    @Test
    void valuationDateAndAccruedInterestStayFixedWithinASimulatedDay() {
        RiskSession fourTicksADay = session(42, BOOK, 4);
        PositionResult before = byId(fourTicksADay.snapshot()).get("LONG_2Y");

        for (int tick = 1; tick <= 3; tick++) {
            RiskUpdate update = fourTicksADay.step();
            PositionResult now = byId(fourTicksADay.snapshot()).get("LONG_2Y");

            assertThat(update.valuationDate()).isNull();
            assertThat(update.ticksUntilDayRollover()).isEqualTo(4 - tick);
            assertThat(update.lifecycleEvents()).isEmpty();
            assertThat(fourTicksADay.snapshot().session().valuationDate()).isEqualTo("2026-09-11");
            assertThat(now.accruedInterest()).isEqualTo(before.accruedInterest());
            assertThat(now.dirtyPrice()).as("the market still moves intraday").isNotEqualTo(before.dirtyPrice());
        }
    }

    @Test
    void dayRolloverEveryNTicksAdvancesTheValuationDateAndAgesAccrual() {
        RiskSession fourTicksADay = session(42, BOOK, 4);
        for (int tick = 1; tick <= 3; tick++) {
            fourTicksADay.step();
        }

        RiskUpdate rollover = fourTicksADay.step();

        assertThat(rollover.tick()).isEqualTo(4);
        assertThat(rollover.valuationDate()).isEqualTo("2026-09-12");
        assertThat(rollover.ticksUntilDayRollover()).isEqualTo(4);
        assertThat(rollover.positions()).hasSize(3);
        RiskSnapshot snapshot = fourTicksADay.snapshot();
        assertThat(snapshot.session().valuationDate()).isEqualTo("2026-09-12");
        assertThat(fourTicksADay.marketState().valuationDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        // 2Y note: 12 days accrued (2026-08-31 → 2026-09-12) of a 181-day period, per 100 face.
        assertThat(byId(snapshot).get("LONG_2Y").accruedInterest())
                .isCloseTo(4.125 / 2 * 12.0 / 181.0, within(1e-12));

        for (int tick = 5; tick <= 8; tick++) {
            fourTicksADay.step();
        }
        assertThat(fourTicksADay.snapshot().session().valuationDate()).isEqualTo("2026-09-13");
    }

    @Test
    void aCouponDateIsProcessedAtTheDayRolloverOntoIt() {
        RiskSession fourTicksADay = session(42, LIFECYCLE_BOOK, 4);
        for (int tick = 1; tick <= 7; tick++) {
            assertThat(fourTicksADay.step().lifecycleEvents()).as("tick %s", tick).isEmpty();
        }
        PositionResult dayBefore = byId(fourTicksADay.snapshot()).get("LONG_COUPON");
        // 4% semi-annual: 183 of 184 days accrued (2026-03-13 → 2026-09-12).
        assertThat(dayBefore.accruedInterest()).isCloseTo(2.0 * 183 / 184, within(1e-12));

        RiskUpdate rollover = fourTicksADay.step();

        assertThat(rollover.valuationDate()).isEqualTo("2026-09-13");
        PositionResult couponDay = byId(fourTicksADay.snapshot()).get("LONG_COUPON");
        assertThat(couponDay.accruedInterest()).isZero();
        assertThat(dayBefore.dirtyPrice() - couponDay.dirtyPrice()).as("the paid coupon leaves the dirty price")
                .isCloseTo(2.0, within(0.05));
        assertThat(couponDay.cleanPrice()).isCloseTo(dayBefore.cleanPrice(), within(0.05));

        assertThat(rollover.lifecycleEvents())
                .extracting(RiskSnapshot.LifecycleEvent::positionId, RiskSnapshot.LifecycleEvent::kind,
                        RiskSnapshot.LifecycleEvent::date, RiskSnapshot.LifecycleEvent::amount)
                .containsExactly(
                        tuple("LONG_COUPON", "COUPON", "2026-09-13", 20_000.0),
                        tuple("SHORT_COUPON", "COUPON", "2026-09-13", -8_000.0),
                        tuple("LONG_MATURING", "COUPON", "2026-09-13", 35_000.0),
                        tuple("LONG_MATURING", "REDEMPTION", "2026-09-13", 2_000_000.0));
        assertThat(rollover.lifecycleEvents()).allSatisfy(event -> assertThat(event.tick()).isEqualTo(8));
        assertThat(fourTicksADay.snapshot().recentLifecycleEvents()).isEqualTo(rollover.lifecycleEvents());

        for (int tick = 9; tick <= 12; tick++) {
            assertThat(fourTicksADay.step().lifecycleEvents()).isEmpty();
        }
        assertThat(byId(fourTicksADay.snapshot()).get("LONG_COUPON").accruedInterest())
                .as("accrual restarts from the coupon date")
                .isCloseTo(2.0 * 1 / 181, within(1e-12));
    }

    @Test
    void aMaturedBondIsRedeemedAndCarriesNoFurtherValueOrRisk() {
        RiskSession oneTickADay = session(42, LIFECYCLE_BOOK, 1);
        oneTickADay.step();
        assertThat(byId(oneTickADay.snapshot()).get("LONG_MATURING").value()).isPositive();

        oneTickADay.step();

        PositionResult matured = byId(oneTickADay.snapshot()).get("LONG_MATURING");
        assertThat(matured.value()).isZero();
        assertThat(matured.accruedInterest()).isZero();
        assertThat(matured.dv01()).isZero();
    }

    @Test
    void replayingRiskUpdatesAcrossDayRolloversReproducesTheSnapshotIncludingLifecycleEvents() {
        RiskSession oneTickADay = session(42, LIFECYCLE_BOOK, 1);
        RiskSnapshot client = oneTickADay.snapshot();

        for (int i = 0; i < 30; i++) {
            client = client.withUpdate(oneTickADay.step());
        }

        assertThat(client.recentLifecycleEvents()).hasSize(4);
        assertThat(client).isEqualTo(oneTickADay.snapshot());
    }

    @Test
    void zeroThresholdsRepriceEveryDependentInstrumentEveryTick() {
        for (int tick = 1; tick <= 30; tick++) {
            RiskUpdate update = session.step();

            assertThat(update.positions()).hasSize(3);
            assertThat(update.telemetry().instrumentsRepriced()).isEqualTo(2);
            assertThat(update.telemetry().instrumentsTotal()).isEqualTo(2);
            assertThat(update.positions()).allSatisfy(p -> assertThat(p.lastPricedTick()).isEqualTo(update.tick()));
        }
    }

    @Test
    void veryLargeThresholdsRepriceNothingExceptAtDayRollover() {
        RiskSession session = session(42, BOOK, 4, new RepricingSettings(new MaterialityThresholds(1e6, 1e6, 1e6, 1e6, 0, 0), 0.05));
        RiskSnapshot start = session.snapshot();

        for (int tick = 1; tick <= 3; tick++) {
            RiskUpdate update = session.step();
            assertThat(update.positions()).isEmpty();
            assertThat(update.bookRisk()).as("no rollup changed").isNull();
            assertThat(update.telemetry().instrumentsRepriced()).isZero();
        }
        assertThat(session.snapshot().positions()).isEqualTo(start.positions());

        RiskUpdate rollover = session.step();

        assertThat(rollover.valuationDate()).isEqualTo("2026-09-12");
        assertThat(rollover.positions()).hasSize(3)
                .allSatisfy(p -> assertThat(p.lastPricedTick()).isEqualTo(4));
        assertThat(rollover.telemetry().instrumentsRepriced()).isEqualTo(2);
        assertThat(rollover.bookRisk()).isNotNull().isNotEqualTo(start.bookRisk());
        PositionResult before = byId(start).get("LONG_10Y");
        PositionResult after = byId(session.snapshot()).get("LONG_10Y");
        assertThat(after.dirtyPrice()).isNotEqualTo(before.dirtyPrice());
        assertThat(after.dv01()).as("sensitivities are refreshed too").isNotEqualTo(before.dv01());
    }

    @Test
    void subThresholdDriftAccumulatesUntilItTriggersAReprice() {
        // A 5bp threshold is several times the per-tick move, so any reprice is triggered by drift that
        // built up over many ticks. With no Day Rollover in the run, drift is the only trigger.
        RiskSession session = session(42, BOOK, 10_000, new RepricingSettings(new MaterialityThresholds(5, 1e6, 1e6, 1e6, 0, 0), 0.05));
        List<RiskSnapshot.CurvePoint> previous = session.snapshot().curve().pillars();
        double largestTickMoveBp = 0;
        int reprices = 0;

        for (int tick = 1; tick <= 500; tick++) {
            RiskUpdate update = session.step();
            List<RiskSnapshot.CurvePoint> pillars = update.curve().pillars();
            for (int i = 0; i < pillars.size(); i++) {
                largestTickMoveBp = Math.max(largestTickMoveBp,
                        Math.abs(pillars.get(i).zeroRate() - previous.get(i).zeroRate()) * 1e4);
            }
            previous = pillars;
            reprices += update.telemetry().instrumentsRepriced();
        }

        assertThat(largestTickMoveBp).isLessThan(5);
        assertThat(reprices).isPositive();
    }

    @Test
    void shortDatedInstrumentsRepriceMoreOftenThanLongDatedOnes() {
        RiskSession session = session(42, SHORT_AND_LONG_BOOK, 24, TWO_BP);
        Map<String, Integer> reprices = new HashMap<>();

        for (int tick = 1; tick <= 2400; tick++) {
            session.step().positions().forEach(p -> reprices.merge(p.positionId(), 1, Integer::sum));
        }

        // Every Instrument reprices at each of the 100 Day Rollovers; the difference is intraday.
        assertThat(reprices.get("TWO_YEAR")).isGreaterThan((int) (1.2 * reprices.get("THIRTY_YEAR")));
        assertThat(reprices.get("THIRTY_YEAR")).isGreaterThan(100);
    }

    @Test
    void instrumentsDependOnTheirMaterialPillarsAndTheValuationDateAllInUsd() {
        RiskSession session = session(42, SHORT_AND_LONG_BOOK, 24, TWO_BP);

        Set<RiskFactorId> twoYear = session.dependencies("91282CRH6");
        Set<RiskFactorId> thirtyYear = session.dependencies("912810UW6");

        assertThat(twoYear).extracting(RiskFactorId::currency).containsOnly("USD");
        assertThat(twoYear).contains(RiskFactorId.valuationDate("USD"));
        assertThat(pillarNames(twoYear)).contains("2Y").doesNotContain("3M", "10Y", "30Y");
        assertThat(pillarNames(thirtyYear)).contains("30Y", "20Y").doesNotContain("3M", "1Y", "2Y");
        assertThat(pillarNames(session(42, SHORT_AND_LONG_BOOK, 24, RepricingSettings.REPRICE_EVERYTHING)
                .dependencies("912810UW6")))
                .as("without the exposure filter a long bond depends on the short end too")
                .contains("3M", "1Y", "30Y");
    }

    @Test
    void maxStalenessNeverExceedsTheThresholdAndMatchesTheLagOfUnrepricedInstruments() {
        RiskSession session = session(42, SHORT_AND_LONG_BOOK, 24, TWO_BP);
        Map<Long, Map<String, Double>> pillarsByTick = new HashMap<>();
        pillarsByTick.put(0L, pillarRates(session.snapshot().curve().pillars()));
        double largestSeen = 0;

        for (int tick = 1; tick <= 500; tick++) {
            RiskUpdate update = session.step();
            Map<String, Double> now = pillarRates(update.curve().pillars());
            pillarsByTick.put(update.tick(), now);

            double lag = 0;
            for (PositionResult position : session.snapshot().positions()) {
                Map<String, Double> pricedAt = pillarsByTick.get(position.lastPricedTick());
                for (String pillar : pillarNames(session.dependencies(position.instrumentId()))) {
                    lag = Math.max(lag, Math.abs(now.get(pillar) - pricedAt.get(pillar)) * 1e4);
                }
            }
            RiskSnapshot.FactorStaleness staleness = update.telemetry().maxStaleness().getFirst();
            assertThat(staleness.factorType()).isEqualTo("PILLAR_ZERO_RATE");
            assertThat(staleness.threshold()).isEqualTo(2);
            assertThat(staleness.maxStaleness()).isLessThanOrEqualTo(2).isCloseTo(lag, within(1e-9));
            largestSeen = Math.max(largestSeen, staleness.maxStaleness());
        }
        assertThat(largestSeen).as("prices really do run stale").isGreaterThan(1);
    }

    @Test
    void riskUpdatesCarryOnlyRepricedPositionsAndStillReplayToTheSnapshot() {
        RiskSession session = session(42, SHORT_AND_LONG_BOOK + "SHORT_TWO,91282CRH6,-4000000\n", 24, TWO_BP);
        RiskSnapshot client = session.snapshot();
        int partialUpdates = 0;

        for (int tick = 1; tick <= 200; tick++) {
            RiskUpdate update = session.step();
            assertThat(update.positions()).allSatisfy(p -> assertThat(p.lastPricedTick()).isEqualTo(update.tick()));
            Set<String> inUpdate = update.positions().stream().map(PositionResult::positionId).collect(Collectors.toSet());
            assertThat(session.snapshot().positions()).filteredOn(p -> !inUpdate.contains(p.positionId()))
                    .allSatisfy(p -> assertThat(p.lastPricedTick()).isLessThan(update.tick()));
            assertThat(update.telemetry().instrumentsRepriced())
                    .isEqualTo((int) update.positions().stream().map(PositionResult::instrumentId).distinct().count());
            if (!update.positions().isEmpty() && update.positions().size() < 3) {
                partialUpdates++;
            }
            client = client.withUpdate(update);
        }

        assertThat(partialUpdates).as("some cycles reprice part of the Book").isPositive();
        assertThat(client).isEqualTo(session.snapshot());
    }

    @Test
    void aCtdSwitchChangesTheProxyBondJumpsTheBasisAndRepricesTheFutureOnThatTick() {
        // A switch on every tick and no diffusion; thresholds so large that nothing else reprices.
        RiskSession session = session(42, FUTURES_BOOK, 10_000,
                new RepricingSettings(new MaterialityThresholds(1e6, 1e6, 1e6, 1e6, 0, 0), 0.05),
                new FuturesBasisParameters(0, -0.2, 0, 1e12, 0.15));
        RiskSnapshot.FuturesView before = session.snapshot().futures().getFirst();
        assertThat(before.proxyBondId()).isEqualTo("ZNZ6-CTD1");
        assertThat(before.ctdSwitchCount()).isZero();
        assertThat(before.lastCtdSwitchTick()).isNull();

        RiskUpdate update = session.step();

        RiskSnapshot.FuturesView after = update.futures().getFirst();
        assertThat(update.ctdSwitches()).singleElement().satisfies(ctdSwitch -> {
            assertThat(ctdSwitch.tick()).isEqualTo(1);
            assertThat(ctdSwitch.contract()).isEqualTo("ZNZ6");
            assertThat(ctdSwitch.fromProxyBondId()).isEqualTo("ZNZ6-CTD1");
            assertThat(ctdSwitch.toProxyBondId()).isEqualTo(after.proxyBondId()).isNotEqualTo("ZNZ6-CTD1");
            assertThat(after.basis() - before.basis()).isCloseTo(ctdSwitch.basisJump(), within(1e-12));
            assertThat(Math.abs(ctdSwitch.basisJump())).isEqualTo(0.15);
        });
        assertThat(after.conversionFactor()).isNotEqualTo(before.conversionFactor());
        assertThat(after.ctdSwitchCount()).isEqualTo(1);
        assertThat(after.lastCtdSwitchTick()).isEqualTo(1);
        assertThat(update.positions()).as("only the future reprices").singleElement().satisfies(future -> {
            assertThat(future.positionId()).isEqualTo("SHORT_ZN");
            assertThat(future.lastPricedTick()).isEqualTo(1);
            Instrument zn = ReferenceData.parse(new StringReader(TREASURIES), new StringReader(FUTURES),
                    new StringReader(FUTURES_BOOK)).instruments().get("ZNZ6");
            assertThat(future.dirtyPrice()).isCloseTo(zn.dirtyValue(session.marketState()) * 100, within(1e-12));
        });
        assertThat(session.snapshot().recentCtdSwitches()).isEqualTo(update.ctdSwitches());
    }

    @Test
    void theFuturesDv01DoesNotDependOnTheBasisLevel() {
        PositionResult lowBasis = byId(session(42, FUTURES_BOOK, 24, TWO_BP, frozenBasis(-0.5)).snapshot()).get("SHORT_ZN");
        PositionResult highBasis = byId(session(42, FUTURES_BOOK, 24, TWO_BP, frozenBasis(1.5)).snapshot()).get("SHORT_ZN");

        assertThat(highBasis.dirtyPrice() - lowBasis.dirtyPrice()).isCloseTo(2.0, within(1e-9));
        assertThat(lowBasis.dv01()).isNegative().isCloseTo(highBasis.dv01(), within(1e-9));
        for (int i = 0; i < lowBasis.bucketedDv01().size(); i++) {
            assertThat(lowBasis.bucketedDv01().get(i).dv01())
                    .isCloseTo(highBasis.bucketedDv01().get(i).dv01(), within(1e-9));
        }
    }

    @Test
    void aBasisMovePastItsThresholdRepricesTheFutureAlone() {
        // Curve thresholds too large to trigger, and no Day Rollover: only the Basis can dirty anything.
        RiskSession session = session(42, FUTURES_BOOK, 10_000,
                new RepricingSettings(new MaterialityThresholds(1e6, 1e6, 1e6, 0.02, 0, 0), 0.05),
                new FuturesBasisParameters(12, -0.2, 0.5, 0, 0.15));
        int futureReprices = 0;

        for (int tick = 1; tick <= 200; tick++) {
            RiskUpdate update = session.step();
            assertThat(update.positions()).allSatisfy(p -> assertThat(p.positionId()).isEqualTo("SHORT_ZN"));
            futureReprices += update.positions().size();
            RiskSnapshot.FactorStaleness basis = update.telemetry().maxStaleness().stream()
                    .filter(s -> s.factorType().equals("BASIS")).findFirst().orElseThrow();
            assertThat(basis.maxStaleness()).isLessThanOrEqualTo(0.02);
            assertThat(basis.unit()).isEqualTo("pts");
        }
        assertThat(futureReprices).isBetween(1, 199);
    }

    @Test
    void futuresPositionsAreMarginedDailySoCarryRiskButNoValue() {
        RiskSnapshot snapshot = session(42, FUTURES_BOOK, 24, TWO_BP).snapshot();
        PositionResult future = byId(snapshot).get("SHORT_ZN");

        assertThat(future.instrumentType()).isEqualTo("TREASURY_FUTURE");
        assertThat(future.value()).isZero();
        assertThat(future.accruedInterest()).isZero();
        assertThat(future.dirtyPrice()).as("a ZN price per 100").isBetween(100.0, 125.0);
        assertThat(future.dv01()).isNegative();
        assertThat(snapshot.bookRisk().value()).isEqualTo(byId(snapshot).get("LONG_10Y").value());
        assertThat(snapshot.bookRisk().byInstrumentType())
                .extracting(RiskSnapshot.InstrumentTypeRisk::instrumentType)
                .containsExactly("TREASURY_BOND", "TREASURY_FUTURE");
        assertThat(snapshot.bookRisk().byInstrumentType().get(1).dv01()).isEqualTo(future.dv01());
        assertThat(snapshot.bookRisk().dv01()).as("the short future hedges part of the bond's DV01")
                .isLessThan(byId(snapshot).get("LONG_10Y").dv01()).isPositive();
    }

    @Test
    void replayingRiskUpdatesWithCtdSwitchesReproducesTheSnapshot() {
        RiskSession session = session(42, FUTURES_BOOK, 24, TWO_BP, new FuturesBasisParameters(12, -0.2, 0.5, 2_000, 0.15));
        RiskSnapshot client = session.snapshot();

        for (int i = 0; i < 100; i++) {
            client = client.withUpdate(session.step());
        }

        assertThat(client.recentCtdSwitches()).isNotEmpty();
        assertThat(client).isEqualTo(session.snapshot());
    }

    @Test
    void onAPrintTheMarkEqualsTheObservedSpreadAndOnAQuoteAloneTheQuote() {
        RiskSession session = session(42, CREDIT_BOOK, 24, TWO_BP);
        int prints = 0;
        int quotesAlone = 0;

        for (int tick = 1; tick <= 500; tick++) {
            RiskUpdate update = session.step();
            RiskSnapshot.IssuerView liquid = issuer(update.credit(), "LIQUID");
            if (Long.valueOf(update.tick()).equals(liquid.lastPrintTick())) {
                assertThat(liquid.markBp()).isEqualTo(liquid.lastPrintBp());
                prints++;
            } else if (Long.valueOf(update.tick()).equals(liquid.lastQuoteTick())) {
                assertThat(liquid.markBp()).isEqualTo(liquid.lastQuoteBp());
                quotesAlone++;
            }
        }

        assertThat(prints).isGreaterThan(50);
        assertThat(quotesAlone).isGreaterThan(50);
    }

    @Test
    void betweenObservationsTheMarkMovesOnlyWithTheSystemicAndSectorFactors() {
        RiskSession session = session(42, CREDIT_BOOK, 24, TWO_BP);
        RiskSnapshot.CreditView previous = session.snapshot().credit();
        int matrixPricedTicks = 0;

        for (int tick = 1; tick <= 500; tick++) {
            RiskUpdate update = session.step();
            RiskSnapshot.CreditView credit = update.credit();
            RiskSnapshot.IssuerView before = issuer(previous, "ILLIQUID");
            RiskSnapshot.IssuerView after = issuer(credit, "ILLIQUID");
            boolean observed = Long.valueOf(update.tick()).equals(after.lastPrintTick())
                    || Long.valueOf(update.tick()).equals(after.lastQuoteTick());
            if (!observed) {
                double matrixMove = (credit.systemicBp() - previous.systemicBp())
                        + (sector(credit, "A Industrials") - sector(previous, "A Industrials"));
                assertThat(after.markBp() - before.markBp()).isCloseTo(matrixMove, within(1e-9));
                matrixPricedTicks++;
            }
            previous = credit;
        }

        assertThat(matrixPricedTicks).isGreaterThan(400);
    }

    @Test
    void idiosyncraticMovesNeverReachAMarkWithoutAnObservation() {
        // The same seed with and without Idiosyncratic volatility: every other factor follows the same path,
        // so an issuer that is never observed must have exactly the same Marks.
        RiskSession quiet = session(42, CREDIT_BOOK, 24, TWO_BP, BASIS, new CreditParameters(0.5, 60, 40, 2, 25, 20, 0));
        RiskSession noisy = session(42, CREDIT_BOOK, 24, TWO_BP, BASIS, new CreditParameters(0.5, 60, 40, 2, 25, 20, 200));

        for (int tick = 1; tick <= 300; tick++) {
            RiskSnapshot.IssuerView quietSilent = issuer(quiet.step().credit(), "SILENT");
            RiskSnapshot.IssuerView noisySilent = issuer(noisy.step().credit(), "SILENT");
            assertThat(noisySilent.markBp()).isEqualTo(quietSilent.markBp());
            assertThat(noisySilent.lastPrintBp()).isNull();
        }
        assertThat(issuer(noisy.snapshot().credit(), "LIQUID").markBp())
                .as("an observed issuer's Mark does pick up its idiosyncratic moves")
                .isNotEqualTo(issuer(quiet.snapshot().credit(), "LIQUID").markBp());
    }

    @Test
    void corporateBondsArePricedOffTheCurvePlusTheirIssuersMark() {
        RiskSession session = session(42, CREDIT_BOOK, 24, RepricingSettings.REPRICE_EVERYTHING);
        session.step();
        ReferenceData referenceData = ReferenceData.parse(
                new ReferenceData.Csv(TREASURIES, FUTURES, RATING_BUCKETS, ISSUERS, CORPORATES, SWAPS, CREDIT_BOOK));

        PositionResult liquid = byId(session.snapshot()).get("LONG_LIQUID");
        double markBp = issuer(session.snapshot().credit(), "LIQUID").markBp();

        assertThat(session.marketState().mark("LIQUID") * 1e4).isCloseTo(markBp, within(1e-9));
        assertThat(liquid.dirtyPrice())
                .isCloseTo(referenceData.instruments().get("LIQ-5.10-2029").dirtyValue(session.marketState()) * 100,
                        within(1e-12));
        assertThat(liquid.cs01()).isPositive();
        assertThat(liquid.ratingBucket()).isEqualTo("BBB Industrials");
        assertThat(session.dependencies("LIQ-5.10-2029")).contains(RiskFactorId.mark("USD", "LIQUID"));
    }

    @Test
    void cs01RollsUpByRatingBucketAndToTheBookFromPositionContributions() {
        RiskSession session = session(42, CREDIT_BOOK, 24, TWO_BP);
        for (int tick = 1; tick <= 50; tick++) {
            session.step();
        }
        RiskSnapshot snapshot = session.snapshot();
        Map<String, PositionResult> positions = byId(snapshot);
        RiskSnapshot.BookRisk book = snapshot.bookRisk();

        assertThat(positions.get("LONG_10Y").cs01()).isZero();
        assertThat(positions.get("LONG_10Y").ratingBucket()).isNull();
        assertThat(positions.get("SHORT_SILENT").cs01()).isNegative();
        assertThat(book.cs01()).isCloseTo(snapshot.positions().stream().mapToDouble(PositionResult::cs01).sum(),
                within(1e-9));
        assertThat(book.byRatingBucket()).extracting(RiskSnapshot.RatingBucketRisk::ratingBucket)
                .containsExactly("A Industrials", "BBB Industrials", "A Financials");
        Map<String, RiskSnapshot.RatingBucketRisk> buckets = book.byRatingBucket().stream()
                .collect(Collectors.toMap(RiskSnapshot.RatingBucketRisk::ratingBucket, Function.identity()));
        assertThat(buckets.get("A Industrials").cs01()).isCloseTo(
                positions.get("LONG_ILLIQUID").cs01() + positions.get("SHORT_SILENT").cs01(), within(1e-9));
        assertThat(buckets.get("A Industrials").positionCount()).isEqualTo(2);
        assertThat(buckets.get("BBB Industrials").cs01()).isCloseTo(positions.get("LONG_LIQUID").cs01(), within(1e-9));
        assertThat(buckets.get("A Financials").positionCount()).isZero();
        assertThat(buckets.get("A Financials").cs01()).isZero();
        assertThat(book.byInstrumentType()).filteredOn(t -> t.instrumentType().equals("CORPORATE_BOND"))
                .singleElement().satisfies(t -> assertThat(t.cs01()).isCloseTo(book.cs01(), within(1e-9)));
    }

    @Test
    void replayingRiskUpdatesWithCreditReproducesTheSnapshot() {
        RiskSession session = session(42, CREDIT_BOOK, 24, TWO_BP);
        RiskSnapshot client = session.snapshot();

        for (int i = 0; i < 100; i++) {
            client = client.withUpdate(session.step());
        }

        assertThat(client).isEqualTo(session.snapshot());
    }

    /** Illiquid Corp (A Industrials) jumps 80bp and is downgraded to BBB Industrials on tick 10. */
    private static final CreditEventParameters ILLIQUID_DOWNGRADE = new CreditEventParameters(0, 0, 0, 0, 1, 30, 100,
            List.of(CreditEventParameters.ScheduledCreditEvent.parse("ILLIQUID@10:80:1")));

    /** Thresholds so large that only discrete moves (Day Rollover, Rating Migration) reprice anything. */
    private static final RepricingSettings NOTHING_BUT_DISCRETE_MOVES =
            new RepricingSettings(new MaterialityThresholds(1e6, 1e6, 1e6, 1e6, 0, 0), 0.05);

    @Test
    void aRatingMigrationRewiresDependenciesAndMovesCs01BetweenBucketsOnTheSameTick() {
        RiskSession session = session(42, CREDIT_BOOK, 10_000, NOTHING_BUT_DISCRETE_MOVES, BASIS, CREDIT,
                ILLIQUID_DOWNGRADE);
        for (int tick = 1; tick <= 9; tick++) {
            assertThat(session.step().positions()).isEmpty();
        }
        assertThat(session.dependencies("ILL-4.85-2031")).contains(RiskFactorId.sector("USD", "A Industrials"));
        RiskSnapshot before = session.snapshot();
        assertThat(byId(before).get("LONG_ILLIQUID").ratingBucket()).isEqualTo("A Industrials");

        RiskUpdate migration = session.step();

        // The migration alone dirties the issuer's bonds, whatever the thresholds; nothing else reprices.
        assertThat(migration.positions()).singleElement().satisfies(p -> {
            assertThat(p.positionId()).isEqualTo("LONG_ILLIQUID");
            assertThat(p.ratingBucket()).isEqualTo("BBB Industrials");
            assertThat(p.lastPricedTick()).isEqualTo(10);
        });
        assertThat(session.dependencies("ILL-4.85-2031"))
                .contains(RiskFactorId.sector("USD", "BBB Industrials"), RiskFactorId.rating("USD", "ILLIQUID"))
                .doesNotContain(RiskFactorId.sector("USD", "A Industrials"));

        Map<String, RiskSnapshot.RatingBucketRisk> was = byBucket(before.bookRisk());
        Map<String, RiskSnapshot.RatingBucketRisk> now = byBucket(migration.bookRisk());
        PositionResult illiquid = migration.positions().getFirst();
        assertThat(now.get("A Industrials").positionCount()).isEqualTo(was.get("A Industrials").positionCount() - 1);
        assertThat(now.get("BBB Industrials").positionCount()).isEqualTo(was.get("BBB Industrials").positionCount() + 1);
        assertThat(now.get("A Industrials").cs01()).isCloseTo(byId(before).get("SHORT_SILENT").cs01(), within(1e-9));
        assertThat(now.get("BBB Industrials").cs01())
                .isCloseTo(byId(before).get("LONG_LIQUID").cs01() + illiquid.cs01(), within(1e-9));
    }

    @Test
    void theMarkStaysWithoutTheJumpUntilALaterObservationRevealsIt() {
        RiskSession session = session(7, CREDIT_BOOK, 10_000, TWO_BP, BASIS, CREDIT, ILLIQUID_DOWNGRADE);
        for (int tick = 1; tick <= 9; tick++) {
            session.step();
        }
        RiskSnapshot.CreditView before = session.snapshot().credit();

        RiskSnapshot.CreditView migrated = session.step().credit();

        RiskSnapshot.IssuerView illiquid = issuer(migrated, "ILLIQUID");
        assertThat(Objects.equals(illiquid.lastPrintTick(), 10L) || Objects.equals(illiquid.lastQuoteTick(), 10L))
                .as("precondition: no observation on the migration tick").isFalse();
        assertThat(illiquid.ratingBucket()).isEqualTo("BBB Industrials");
        assertThat(illiquid.migratedFrom()).isEqualTo("A Industrials");
        assertThat(illiquid.migrationTick()).isEqualTo(10L);
        assertThat(illiquid.markStale()).isTrue();
        // Public information only: the Systemic move, and re-basing from the A to the BBB Sector level.
        double rebase = (migrated.systemicBp() - before.systemicBp())
                + (sector(migrated, "BBB Industrials") - sector(before, "A Industrials"));
        assertThat(illiquid.markBp() - issuer(before, "ILLIQUID").markBp()).isCloseTo(rebase, within(1e-9));

        RiskSnapshot.CreditView previous = migrated;
        for (int tick = 11; tick <= 500; tick++) {
            RiskSnapshot.CreditView credit = session.step().credit();
            RiskSnapshot.IssuerView now = issuer(credit, "ILLIQUID");
            boolean observed = Long.valueOf(tick).equals(now.lastPrintTick()) || Long.valueOf(tick).equals(now.lastQuoteTick());
            if (observed) {
                double matrixPriced = issuer(previous, "ILLIQUID").markBp() + (credit.systemicBp() - previous.systemicBp())
                        + (sector(credit, "BBB Industrials") - sector(previous, "BBB Industrials"));
                assertThat(now.markStale()).isFalse();
                assertThat(now.markBp() - matrixPriced).as("the observation reveals the 80bp jump")
                        .isCloseTo(80, within(25.0));
                assertThat(tick).as("the Print burst lets the Mark catch up within a few ticks").isLessThan(40);
                return;
            }
            assertThat(now.markStale()).isTrue();
            previous = credit;
        }
        throw new AssertionError("No observation for Illiquid Corp after its Credit Event");
    }

    @Test
    void printIntensityRisesAfterACreditEventAndDecaysBack() {
        RiskSession session = session(42, CREDIT_BOOK, 10_000, TWO_BP, BASIS, CREDIT, ILLIQUID_DOWNGRADE);
        for (int tick = 1; tick <= 9; tick++) {
            assertThat(issuer(session.step().credit(), "ILLIQUID").printsPerYear()).isEqualTo(60);
        }

        double burst = issuer(session.step().credit(), "ILLIQUID").printsPerYear();
        double previous = burst;
        for (int tick = 11; tick <= 1000; tick++) {
            double intensity = issuer(session.step().credit(), "ILLIQUID").printsPerYear();
            assertThat(intensity).isLessThan(previous);
            previous = intensity;
        }

        assertThat(burst).isCloseTo(60 * 30, within(1e-9));
        assertThat(previous).isCloseTo(60, within(0.05));
        assertThat(issuer(session.snapshot().credit(), "LIQUID").printsPerYear()).as("other issuers are unaffected")
                .isEqualTo(2000);
    }

    @Test
    void replayingRiskUpdatesThroughARatingMigrationReproducesTheSnapshot() {
        RiskSession session = session(42, CREDIT_BOOK, 24, TWO_BP, BASIS, CREDIT, ILLIQUID_DOWNGRADE);
        RiskSnapshot client = session.snapshot();

        for (int i = 0; i < 60; i++) {
            client = client.withUpdate(session.step());
        }

        assertThat(issuer(client.credit(), "ILLIQUID").migratedFrom()).isEqualTo("A Industrials");
        assertThat(client).isEqualTo(session.snapshot());
    }

    private static Map<String, RiskSnapshot.RatingBucketRisk> byBucket(RiskSnapshot.BookRisk book) {
        return book.byRatingBucket().stream()
                .collect(Collectors.toMap(RiskSnapshot.RatingBucketRisk::ratingBucket, Function.identity()));
    }

    @Test
    void aMidPeriodSwapIsValuedFromTheFixingSeededFromTheOpeningCurve() {
        RiskSession session = session(42, SWAP_BOOK, 24, RepricingSettings.REPRICE_EVERYTHING);
        RiskSnapshot snapshot = session.snapshot();

        RiskSnapshot.SwapView payer = swap(snapshot, "IRS-5Y-PAY");
        assertThat(payer.currentPeriodStart()).isEqualTo("2026-07-15");
        assertThat(payer.currentPeriodEnd()).isEqualTo("2026-10-15");
        assertThat(payer.nextResetDate()).isEqualTo("2026-10-15");
        // Seeded from the t=0 curve (flat 4.5% par): a 3-month rate a little under 4.5%.
        assertThat(payer.currentFixing()).isBetween(0.042, 0.046)
                .isEqualTo(session.marketState().fixings().rate(LocalDate.of(2026, 7, 15)));
        PositionResult position = byId(snapshot).get("PAYER");
        Instrument instrument = swapInstruments().get("IRS-5Y-PAY");
        assertThat(position.dirtyPrice()).isCloseTo(instrument.dirtyValue(session.marketState()) * 100, within(1e-12));
        assertThat(position.value()).isCloseTo(position.dirtyPrice() / 100 * 20_000_000, within(1e-6));
        assertThat(position.dv01()).as("a fixed payer gains as rates rise").isNegative();
        assertThat(byId(snapshot).get("RECEIVER").dv01()).isPositive();
    }

    @Test
    void aFixingRecordedOnARolloverOntoAResetDateNeverChanges() {
        RiskSession session = session(42, SWAP_BOOK, 1, TWO_BP);
        double seeded = session.marketState().fixings().rate(LocalDate.of(2026, 7, 15));
        for (int tick = 1; tick <= 33; tick++) {
            session.step();
        }
        assertThat(session.marketState().fixings().on(LocalDate.of(2026, 10, 15))).isEmpty();

        RiskUpdate reset = session.step();

        assertThat(reset.valuationDate()).isEqualTo("2026-10-15");
        RiskSnapshot.SwapView payer = reset.swaps().stream().filter(s -> s.instrumentId().equals("IRS-5Y-PAY"))
                .findFirst().orElseThrow();
        assertThat(payer.currentPeriodStart()).isEqualTo("2026-10-15");
        double recorded = payer.currentFixing();
        assertThat(recorded).as("recorded from the simulated curve at the reset")
                .isEqualTo(FixingHistory.indexRate(session.marketState(), "USD", LocalDate.of(2026, 10, 15)));
        assertThat(reset.positions()).extracting(PositionResult::positionId).contains("PAYER");
        assertThat(reset.lifecycleEvents()).filteredOn(e -> e.positionId().equals("PAYER")).singleElement()
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo("FLOATING_LEG");
                    assertThat(e.amount()).isCloseTo(20_000_000 * seeded * 92 / 360, within(1e-6));
                });

        double curveAtReset = session.snapshot().curve().pillars().get(1).zeroRate();
        for (int tick = 35; tick <= 60; tick++) {
            session.step();
            assertThat(session.marketState().fixings().rate(LocalDate.of(2026, 10, 15))).isEqualTo(recorded);
            assertThat(session.marketState().fixings().rate(LocalDate.of(2026, 7, 15))).isEqualTo(seeded);
            assertThat(swap(session.snapshot(), "IRS-5Y-PAY").currentFixing()).isEqualTo(recorded);
        }
        assertThat(session.snapshot().curve().pillars().get(1).zeroRate()).as("while the curve moved on")
                .isNotEqualTo(curveAtReset);
    }

    @Test
    void swapsRollUpByInstrumentTypeAndPillarWithTheRestOfTheBook() {
        RiskSession session = session(42, SWAP_BOOK + "LONG_10Y,91282CRF0,8000000\n", 24, TWO_BP);
        RiskSnapshot snapshot = session.snapshot();
        Map<String, PositionResult> positions = byId(snapshot);

        assertThat(snapshot.bookRisk().byInstrumentType()).filteredOn(t -> t.instrumentType().equals("INTEREST_RATE_SWAP"))
                .singleElement().satisfies(t -> {
                    assertThat(t.positionCount()).isEqualTo(2);
                    assertThat(t.dv01()).isCloseTo(positions.get("PAYER").dv01() + positions.get("RECEIVER").dv01(),
                            within(1e-9));
                });
        double payerBuckets = positions.get("PAYER").bucketedDv01().stream().mapToDouble(RiskSnapshot.BucketDv01::dv01).sum();
        assertThat(payerBuckets).isCloseTo(positions.get("PAYER").dv01(), within(1e-4 * Math.abs(positions.get("PAYER").dv01())));
        assertThat(snapshot.bookRisk().dv01()).isCloseTo(
                snapshot.positions().stream().mapToDouble(PositionResult::dv01).sum(), within(1e-9));
        assertThat(session.dependencies("IRS-5Y-PAY")).extracting(RiskFactorId::type)
                .containsOnly(FactorType.PILLAR_ZERO_RATE, FactorType.VALUATION_DATE);
    }

    @Test
    void replayingRiskUpdatesThroughSwapResetsReproducesTheSnapshot() {
        RiskSession session = session(42, SWAP_BOOK, 1, TWO_BP);
        RiskSnapshot client = session.snapshot();

        for (int i = 0; i < 40; i++) {
            client = client.withUpdate(session.step());
        }

        assertThat(client).isEqualTo(session.snapshot());
    }

    /** The default flight-to-quality matrix: short rate vs Systemic −0.3, short rate vs Basis 0.1. */
    private static final CorrelationMatrix FLIGHT_TO_QUALITY = CorrelationMatrix.parse(
            "shortRate.USD, systemic, basis", "1,-0.3,0.1; -0.3,1,0; 0.1,0,1");

    private static final String EVERYTHING_BOOK = """
            positionId,instrumentId,quantity
            LONG_10Y,91282CRF0,8000000
            SHORT_ZN,ZNZ6,-6000000
            LONG_LIQUID,LIQ-5.10-2029,4000000
            """;

    @Test
    void theShockSeriesHaveTheConfiguredCorrelations() {
        RiskSession session = session(42, EVERYTHING_BOOK, 24, TWO_BP, BASIS, CREDIT, CreditEventParameters.NONE,
                FLIGHT_TO_QUALITY);
        int n = 20_000;
        double[] rate = new double[n];
        double[] systemic = new double[n];
        double[] basis = new double[n];
        for (int i = 0; i < n; i++) {
            session.step();
            rate[i] = session.lastShocks().of("shortRate.USD");
            systemic[i] = session.lastShocks().of("systemic");
            basis[i] = session.lastShocks().basis().getFirst();
        }

        assertThat(correlation(rate, systemic)).isCloseTo(-0.3, within(0.03));
        assertThat(correlation(rate, basis)).isCloseTo(0.1, within(0.03));
        assertThat(correlation(systemic, basis)).isCloseTo(0, within(0.03));
    }

    @Test
    void zeroCorrelationGivesNearZeroSampleCorrelations() {
        RiskSession session = session(42, EVERYTHING_BOOK, 24, TWO_BP, BASIS, CREDIT, CreditEventParameters.NONE,
                CorrelationMatrix.independent(SINGLE_CURRENCY_FACTORS));
        int n = 20_000;
        double[] rate = new double[n];
        double[] systemic = new double[n];
        double[] basis = new double[n];
        for (int i = 0; i < n; i++) {
            session.step();
            rate[i] = session.lastShocks().of("shortRate.USD");
            systemic[i] = session.lastShocks().of("systemic");
            basis[i] = session.lastShocks().basis().getFirst();
        }

        assertThat(correlation(rate, systemic)).isCloseTo(0, within(0.03));
        assertThat(correlation(rate, basis)).isCloseTo(0, within(0.03));
        assertThat(correlation(systemic, basis)).isCloseTo(0, within(0.03));
    }

    @Test
    void ratesTendToFallAsSpreadsWidenInTheSimulatedMarket() {
        // The shocks drive the simulators: observed moves of the Systemic Factor and of the short end of
        // the curve inherit the flight-to-quality correlation.
        RiskSession session = session(42, EVERYTHING_BOOK, 10_000, TWO_BP, BASIS, CREDIT, CreditEventParameters.NONE,
                FLIGHT_TO_QUALITY);
        int n = 20_000;
        double[] systemicMoves = new double[n];
        double[] shortEndMoves = new double[n];
        double systemic = session.snapshot().credit().systemicBp();
        double shortEnd = session.snapshot().curve().pillars().getFirst().zeroRate();
        for (int i = 0; i < n; i++) {
            RiskUpdate update = session.step();
            systemicMoves[i] = update.credit().systemicBp() - systemic;
            shortEndMoves[i] = update.curve().pillars().getFirst().zeroRate() - shortEnd;
            systemic = update.credit().systemicBp();
            shortEnd = update.curve().pillars().getFirst().zeroRate();
        }

        assertThat(correlation(systemicMoves, shortEndMoves)).isCloseTo(-0.3, within(0.04));
    }

    @Test
    void theSameSeedStillReplaysIdenticallyWithCorrelatedShocks() {
        List<RiskUpdate> first = updates(session(9, EVERYTHING_BOOK, 24, TWO_BP, BASIS, CREDIT,
                CreditEventParameters.NONE, FLIGHT_TO_QUALITY), 100);
        List<RiskUpdate> second = updates(session(9, EVERYTHING_BOOK, 24, TWO_BP, BASIS, CREDIT,
                CreditEventParameters.NONE, FLIGHT_TO_QUALITY), 100);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void aCoalescedCyclePricesOnlyTheNewestMarketAndReportsHowManyTicksItMerged() {
        RiskSession coalesced = session(42, EVERYTHING_BOOK, 24, RepricingSettings.REPRICE_EVERYTHING);
        RiskSession stepped = session(42, EVERYTHING_BOOK, 24, RepricingSettings.REPRICE_EVERYTHING);

        RiskUpdate update = coalesced.step(5);
        for (int tick = 1; tick <= 5; tick++) {
            stepped.step();
        }

        assertThat(update.tick()).isEqualTo(5);
        assertThat(update.sequence()).as("one Risk Update for the whole cycle").isEqualTo(1);
        assertThat(update.telemetry().ticksCoalesced()).isEqualTo(4);
        assertThat(update.telemetry().totalTicksCoalesced()).isEqualTo(4);
        assertThat(update.positions()).allSatisfy(p -> assertThat(p.lastPricedTick()).isEqualTo(5));
        // The same seed, the same market at tick 5, so the same prices: the cycle priced the newest state.
        assertThat(coalesced.snapshot().positions()).isEqualTo(stepped.snapshot().positions());
        assertThat(coalesced.snapshot().curve()).isEqualTo(stepped.snapshot().curve());
        assertThat(coalesced.snapshot().credit()).isEqualTo(stepped.snapshot().credit());
        assertThat(stepped.snapshot().telemetry().totalTicksCoalesced()).isZero();
    }

    @Test
    void aCoalescedCycleCarriesTheEventsAndDayRolloversOfEveryMergedTick() {
        RiskSession session = session(42, LIFECYCLE_BOOK, 1, TWO_BP);

        RiskUpdate update = session.step(5);

        // Ticks 1..5 roll the Valuation Date from 09-11 to 09-16, crossing the 09-13 coupon and redemption.
        assertThat(update.valuationDate()).isEqualTo("2026-09-16");
        assertThat(update.lifecycleEvents()).extracting(RiskSnapshot.LifecycleEvent::tick).containsOnly(2L);
        assertThat(update.lifecycleEvents()).hasSize(4);
        assertThat(session.snapshot().recentLifecycleEvents()).isEqualTo(update.lifecycleEvents());
    }

    @Test
    void replayingMixedSingleAndCoalescedCyclesReproducesTheSnapshot() {
        RiskSession session = session(42, EVERYTHING_BOOK, 24, TWO_BP);
        RiskSnapshot client = session.snapshot();
        int[] cycles = {1, 3, 1, 7, 2, 1, 1, 12, 4, 30};

        for (int ticks : cycles) {
            client = client.withUpdate(session.step(ticks));
        }

        assertThat(client.tick()).isEqualTo(62);
        assertThat(client.telemetry().totalTicksCoalesced()).isEqualTo(62 - cycles.length);
        assertThat(client).isEqualTo(session.snapshot());
    }

    @Test
    void parallelAndSerialRepricingGiveIdenticalResults() {
        String book = """
                positionId,instrumentId,quantity
                LONG_2Y,91282CRH6,10000000
                LONG_10Y,91282CRF0,8000000
                SHORT_ZN,ZNZ6,-6000000
                LONG_LIQUID,LIQ-5.10-2029,4000000
                LONG_ILLIQUID,ILL-4.85-2031,5000000
                PAYER,IRS-5Y-PAY,20000000
                RECEIVER,IRS-10Y-REC,15000000
                """;
        try (RiskSession serial = session(42, book, 24, TWO_BP, BASIS, CREDIT, CreditEventParameters.NONE, FLIGHT_TO_QUALITY);
             RiskSession parallel = session(42, book, 24, TWO_BP.withWorkerThreads(4), BASIS, CREDIT,
                     CreditEventParameters.NONE, FLIGHT_TO_QUALITY)) {
            assertThat(parallel.snapshot()).isEqualTo(serial.snapshot());
            for (int cycle = 0; cycle < 150; cycle++) {
                int ticks = 1 + cycle % 3;
                assertThat(parallel.step(ticks)).isEqualTo(serial.step(ticks));
            }
            assertThat(parallel.snapshot().positions()).hasSizeGreaterThan(5);
        }
    }

    private static double correlation(double[] x, double[] y) {
        double mx = Arrays.stream(x).average().orElseThrow();
        double my = Arrays.stream(y).average().orElseThrow();
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

    private static Map<String, Instrument> swapInstruments() {
        return ReferenceData.parse(new ReferenceData.Csv(TREASURIES, FUTURES, RATING_BUCKETS, ISSUERS, CORPORATES, SWAPS,
                SWAP_BOOK)).instruments();
    }

    private static RiskSnapshot.SwapView swap(RiskSnapshot snapshot, String id) {
        return snapshot.swaps().stream().filter(s -> s.instrumentId().equals(id)).findFirst().orElseThrow();
    }

    private static RiskSnapshot.IssuerView issuer(RiskSnapshot.CreditView credit, String issuerId) {
        return credit.issuers().stream().filter(i -> i.issuerId().equals(issuerId)).findFirst().orElseThrow();
    }

    private static double sector(RiskSnapshot.CreditView credit, String ratingBucket) {
        return credit.sectors().stream().filter(s -> s.ratingBucket().equals(ratingBucket)).findFirst().orElseThrow()
                .levelBp();
    }

    private static Set<String> pillarNames(Set<RiskFactorId> factors) {
        return factors.stream()
                .filter(f -> f.type() == FactorType.PILLAR_ZERO_RATE)
                .map(RiskFactorId::name)
                .collect(Collectors.toSet());
    }

    private static Map<String, Double> pillarRates(List<RiskSnapshot.CurvePoint> pillars) {
        return pillars.stream().collect(Collectors.toMap(RiskSnapshot.CurvePoint::label, RiskSnapshot.CurvePoint::zeroRate));
    }

    private static Map<String, PositionResult> byId(RiskSnapshot snapshot) {
        return snapshot.positions().stream().collect(Collectors.toMap(PositionResult::positionId, Function.identity()));
    }
}
