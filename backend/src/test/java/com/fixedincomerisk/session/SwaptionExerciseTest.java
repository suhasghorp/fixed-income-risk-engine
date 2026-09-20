package com.fixedincomerisk.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixedincomerisk.credit.CreditEventParameters;
import com.fixedincomerisk.credit.CreditParameters;
import com.fixedincomerisk.curve.CurveSnapshot;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.curve.CurveSourceKind;
import com.fixedincomerisk.curve.ParCurve;
import com.fixedincomerisk.curve.ParPoint;
import com.fixedincomerisk.instrument.InterestRateSwap;
import com.fixedincomerisk.instrument.Swaption;
import com.fixedincomerisk.market.ExerciseDecisions;
import com.fixedincomerisk.market.FactorType;
import com.fixedincomerisk.market.FxPairs;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.SurfacePoints;
import com.fixedincomerisk.model.CorrelationMatrix;
import com.fixedincomerisk.model.FuturesBasisParameters;
import com.fixedincomerisk.model.HullWhiteParameters;
import com.fixedincomerisk.model.NormalVolParameters;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.repricing.RepricingSettings;
import com.fixedincomerisk.session.RiskSnapshot.PositionResult;
import com.fixedincomerisk.simulation.SimulationSettings;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What happens at Expiry, end to end. The strikes here are far enough from any rate the simulation can
 * reach in two days that the outcome is certain whichever way the random path goes — so these test the
 * rule and the recording, not the seed.
 */
class SwaptionExerciseTest {

    private static final LocalDate CURVE_DATE = LocalDate.of(2026, 9, 11);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 9, 13);
    private static final int TICKS_PER_DAY = 24;
    /** The Day Rollover onto the Expiry: two simulated days in. */
    private static final int EXPIRY_TICK = 2 * TICKS_PER_DAY;

    private static final CurveSource FLAT_CURVE = new CurveSource() {
        @Override
        public String currency() {
            return "USD";
        }

        @Override
        public CurveSnapshot load() {
            return CurveSnapshot.of(new ParCurve(CURVE_DATE,
                    List.of(0.5, 1.0, 2.0, 5.0, 10.0, 30.0).stream()
                            .map(t -> new ParPoint(t + "Y", t, 0.045))
                            .toList()),
                    CurveSourceKind.LIVE);
        }
    };

    private static final double NOTIONAL = 10_000_000;

    /** A session and the Swaption in it, so a test can ask the Instrument what it thinks. */
    private record Fixture(RiskSession session, Swaption swaption) implements AutoCloseable {

        @Override
        public void close() {
            session.close();
        }

        MarketState market() {
            return session.marketState();
        }
    }

    /** One Swaption, struck where the answer cannot be in doubt. Its underlying is a 5Y swap. */
    private static Fixture session(String direction, String strikePercent) {
        String swaptions = """
                swaptionId,direction,strike,expiryDate,underlyingMaturityDate,surfacePoint
                SWPN-TEST,%s,%s,%s,2031-09-13,1Mx5Y
                """.formatted(direction, strikePercent, EXPIRY);
        String book = """
                positionId,instrumentId,quantity
                P01,SWPN-TEST,%s
                """.formatted((long) NOTIONAL);
        ReferenceData referenceData =
                ReferenceData.parse(new ReferenceData.Csv("", "", "", "", "", "", "", swaptions, book));
        RiskSession session = RiskSession.create(new SessionConfig(
                List.of(FLAT_CURVE),
                "USD",
                referenceData,
                Map.of("USD", new HullWhiteParameters(0.05, 0.01)),
                new FuturesBasisParameters(12, -0.2, 0.5, 24, 0.15),
                FxPairs.NONE,
                Map.of(),
                Map.of(),
                SurfacePoints.parse("USD 1Mx5Y"),
                Map.of("USD 1Mx5Y", new NormalVolParameters(2, 0.6, 0.0095)),
                new CreditParameters(0.5, 60, 40, 2, 25, 20, 15),
                CreditEventParameters.NONE,
                CorrelationMatrix.independent(List.of("shortRate.USD", "systemic", "normalVol.USD.1Mx5Y", "basis")),
                Pillar.DEFAULTS,
                new SimulationSettings(42, Duration.ofHours(1), TICKS_PER_DAY),
                RepricingSettings.REPRICE_EVERYTHING));
        return new Fixture(session, (Swaption) referenceData.instruments().get("SWPN-TEST"));
    }

    /**
     * Recorded on the Day Rollover onto the Expiry and not before, from that day's curve. A payer struck
     * at nothing is in the money whatever rates did, so this is the exercise branch.
     */
    @Test
    void aDeepInTheMoneyPayerExercisesOnTheDayRolloverOntoItsExpiry() {
        try (Fixture fixture = session("PAY_FIXED", "0.001")) {
            for (int tick = 1; tick < EXPIRY_TICK; tick++) {
                fixture.session().step();
            }
            assertThat(fixture.market().exercises().isEmpty())
                    .as("undecided the tick before Expiry").isTrue();
            assertThat(fixture.market().valuationDate()).isEqualTo(EXPIRY.minusDays(1));

            fixture.session().step();

            MarketState atExpiry = fixture.market();
            assertThat(atExpiry.valuationDate()).isEqualTo(EXPIRY);
            assertThat(atExpiry.exercises().keys())
                    .containsExactly(new ExerciseDecisions.Key("SWPN-TEST", EXPIRY));
            assertThat(atExpiry.exercises().wasExercised("SWPN-TEST", EXPIRY)).isTrue();
            // The underlying starts at the Expiry, so its first floating period fixes that day. Without
            // this the swap prices off a Fixing that is not there.
            assertThat(atExpiry.fixings().on(EXPIRY)).isPresent();
        }
    }

    /** The other branch: a payer struck out of reach lapses, and lapsing is a decision too. */
    @Test
    void aDeepOutOfTheMoneyPayerLapsesAndIsWorthExactlyZero() {
        try (Fixture fixture = session("PAY_FIXED", "50.0")) {
            stepTo(fixture, EXPIRY_TICK + 24);

            assertThat(fixture.market().exercises().wasExercised("SWPN-TEST", EXPIRY)).isFalse();
            PositionResult position = only(fixture.session());
            assertThat(position.value()).isZero();
            assertThat(position.dv01()).isZero();
        }
    }

    /** A receiver's rule is the mirror image, and getting it backwards is the easy mistake. */
    @Test
    void aReceiverExercisesWhenTheForwardIsBelowItsStrikeAndLapsesWhenItIsAbove() {
        try (Fixture inTheMoney = session("RECEIVE_FIXED", "50.0")) {
            stepTo(inTheMoney, EXPIRY_TICK);
            assertThat(inTheMoney.market().exercises().wasExercised("SWPN-TEST", EXPIRY)).isTrue();
        }
        try (Fixture outOfTheMoney = session("RECEIVE_FIXED", "0.001")) {
            stepTo(outOfTheMoney, EXPIRY_TICK);
            assertThat(outOfTheMoney.market().exercises().wasExercised("SWPN-TEST", EXPIRY)).isFalse();
        }
    }

    /**
     * The decision is recorded, not recomputed. A payer struck just above the forward lapses at Expiry;
     * rates then run on for another simulated week, and the recorded lapse stands however far they move.
     */
    @Test
    void aRecordedDecisionNeverFlipsWhenRatesMoveBackThroughTheStrike() {
        try (Fixture fixture = session("PAY_FIXED", "0.001")) {
            stepTo(fixture, EXPIRY_TICK);
            boolean decidedAtExpiry = fixture.market().exercises().wasExercised("SWPN-TEST", EXPIRY);
            double forwardAtExpiry = fixture.swaption().underlying().forwardRate(fixture.market());

            for (int tick = 0; tick < 7 * TICKS_PER_DAY; tick++) {
                fixture.session().step();
                assertThat(fixture.market().exercises().wasExercised("SWPN-TEST", EXPIRY))
                        .as("decision at tick %s", EXPIRY_TICK + tick + 1)
                        .isEqualTo(decidedAtExpiry);
                assertThat(fixture.market().exercises().keys()).hasSize(1);
            }
            // Rates did move, so the decision stood rather than simply never being re-examined.
            assertThat(fixture.swaption().underlying().forwardRate(fixture.market()))
                    .isNotEqualTo(forwardAtExpiry);
        }
    }

    /**
     * An exercised Swaption is worth its underlying swap, to the same number the swap alone would give,
     * and carries the swap's rates risk rather than an option's.
     */
    @Test
    void anExercisedSwaptionValuesAsTheUnderlyingSwap() {
        try (Fixture fixture = session("PAY_FIXED", "0.001")) {
            stepTo(fixture, EXPIRY_TICK + 48);
            MarketState market = fixture.market();
            InterestRateSwap underlying = fixture.swaption().underlying();

            assertThat(fixture.swaption().dirtyValue(market)).isEqualTo(underlying.dirtyValue(market));
            PositionResult position = only(fixture.session());
            assertThat(position.value())
                    .isCloseTo(underlying.dirtyValue(market) * NOTIONAL, org.assertj.core.api.Assertions.within(1e-6));
            // A payer struck at nothing is deep in the money, so it is worth a great deal and has DV01.
            assertThat(position.value()).isPositive();
            assertThat(position.dv01()).isNotZero();
        }
    }

    /** Whichever way it went, volatility is no longer a dependency: intrinsic has no vega. */
    @Test
    void afterExpiryNoSwaptionDependsOnNormalVol() {
        for (String strike : new String[] {"0.001", "50.0"}) {
            try (Fixture fixture = session("PAY_FIXED", strike)) {
                stepTo(fixture, EXPIRY_TICK - 1);
                assertThat(fixture.swaption().riskFactors(fixture.market(), Pillar.DEFAULTS))
                        .as("before Expiry, strike %s", strike)
                        .anyMatch(factor -> factor.type() == FactorType.NORMAL_VOL);

                stepTo(fixture, EXPIRY_TICK + 24);

                assertThat(fixture.swaption().riskFactors(fixture.market(), Pillar.DEFAULTS))
                        .as("after Expiry, strike %s", strike)
                        .noneMatch(factor -> factor.type() == FactorType.NORMAL_VOL);
            }
        }
    }

    /**
     * The Book never changes shape. Risk Updates are keyed by {@code positionId} and the front end has
     * never seen the Position set change; an Exercise must not be where that starts. ADR-0012.
     */
    @Test
    void theBookHoldsTheSamePositionSetBeforeAndAfterExpiry() {
        try (Fixture fixture = session("PAY_FIXED", "0.001")) {
            stepTo(fixture, EXPIRY_TICK - 1);
            List<String> before = positionIds(fixture.session());

            stepTo(fixture, EXPIRY_TICK + 48);

            assertThat(positionIds(fixture.session())).isEqualTo(before).containsExactly("P01");
            // And the Position is the same Instrument it always was, now worth something different.
            assertThat(only(fixture.session()).instrumentId()).isEqualTo("SWPN-TEST");
            assertThat(only(fixture.session()).instrumentType()).isEqualTo("SWAPTION");
        }
    }

    private static void stepTo(Fixture fixture, int tick) {
        while (fixture.session().snapshot().tick() < tick) {
            fixture.session().step();
        }
    }

    private static List<String> positionIds(RiskSession session) {
        return session.snapshot().positions().stream().map(PositionResult::positionId).toList();
    }

    private static PositionResult only(RiskSession session) {
        return session.snapshot().positions().getFirst();
    }

}
