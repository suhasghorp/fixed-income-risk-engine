package com.fixedincomerisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.curve.BundledCurveSource;
import com.fixedincomerisk.curve.BundledEcbCurveSource;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.curve.CurveSourceChoice;
import com.fixedincomerisk.instrument.Swaption;
import com.fixedincomerisk.market.ExerciseDecisions;
import com.fixedincomerisk.market.FxFixings;
import com.fixedincomerisk.market.MarketState;
import com.fixedincomerisk.market.Pillar;
import com.fixedincomerisk.market.RiskFactorId;
import com.fixedincomerisk.model.CorrelationMatrix;
import com.fixedincomerisk.refdata.ReferenceData;
import com.fixedincomerisk.risk.RatesSensitivities;
import com.fixedincomerisk.session.RiskSession;
import com.fixedincomerisk.session.RiskSnapshot;
import com.fixedincomerisk.session.RiskSnapshot.BookRisk;
import com.fixedincomerisk.session.RiskSnapshot.CurrencyRates;
import com.fixedincomerisk.session.RiskSnapshot.FxView;
import com.fixedincomerisk.session.RiskSnapshot.LifecycleEvent;
import com.fixedincomerisk.session.RiskSnapshot.InstrumentTypeRisk;
import com.fixedincomerisk.session.RiskSnapshot.PositionResult;
import com.fixedincomerisk.session.RiskUpdate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The demo profile the article series relies on, loaded through the real configuration (no web server or
 * threads): it must pin the bundled curve and produce the identical run every time.
 */
class DemoProfileTest {

    private static ApplicationContextRunner runner(String profiles) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                // As in the running app, so values such as "1h" convert to Duration.
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withPropertyValues("spring.profiles.active=" + profiles)
                .withUserConfiguration(RiskSessionConfiguration.class);
    }

    @Test
    void theDemoPinsTheBundledCurveTheSeedAndTheScheduledDowngrade() {
        runner("demo").run(context -> {
            // Two Curve Sources now, one per currency; both must be the bundled snapshot in the demo.
            assertThat(context.getBean("curveSource", CurveSource.class)).isInstanceOf(BundledCurveSource.class);
            assertThat(context.getBean("eurCurveSource", CurveSource.class))
                    .isInstanceOf(BundledEcbCurveSource.class);
            RiskSession session = context.getBean(RiskSession.class);
            assertThat(session.snapshot().session().curveSource()).isEqualTo("BUNDLED");
            assertThat(session.snapshot().session().curveDate()).isEqualTo("2026-09-11");
            assertThat(session.snapshot().session().seed()).isEqualTo(42);
            assertThat(session.snapshot().session().ticksPerDay()).isEqualTo(24);
            assertThat(session.snapshot().session().simulatedSecondsPerTick()).isEqualTo(3600);
            // Both currencies' Curve Sources are reported, each with its own date and quote basis: the
            // Treasury curve is par yields, the ECB's is spot rates, and they can fall back independently.
            assertThat(session.snapshot().session().curves()).satisfiesExactly(
                    usd -> {
                        assertThat(usd.currency()).isEqualTo("USD");
                        assertThat(usd.source()).isEqualTo("BUNDLED");
                        assertThat(usd.date()).isEqualTo("2026-09-11");
                        assertThat(usd.quotes()).isEqualTo("PAR_YIELD");
                    },
                    eur -> {
                        assertThat(eur.currency()).isEqualTo("EUR");
                        assertThat(eur.source()).isEqualTo("BUNDLED");
                        assertThat(eur.date()).isEqualTo("2026-09-17");
                        assertThat(eur.quotes()).isEqualTo("ZERO_RATE");
                    });
            assertThat(context.getEnvironment().getProperty("risk.credit.events.scheduled")).isEqualTo("ACME@120:80:1");
            assertThat(context.getEnvironment().getProperty("risk.simulation.tick-interval")).isEqualTo("1s");
        });
    }

    /**
     * The outright is the Book's clearest argument for reporting rates risk per currency: a headline of
     * +0.11 is the near-cancellation of −280.82 of USD and +280.93 of EUR, which are not the same thing.
     * The headline is small enough that its sign is an accident of where the two curves sat this tick;
     * the two legs behind it are not.
     */
    @Test
    void theTwoFxPositionsCarryTheirNotionalCurrencyAndSplitByCurve() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }

            PositionResult outright = position(session, "P18");
            assertThat(outright.instrumentType()).isEqualTo("FX_FORWARD");
            assertThat(outright.notionalCurrency()).isEqualTo("EUR");
            assertThat(dv01In(outright, "USD")).isCloseTo(-280.8155, within(5e-5));
            assertThat(dv01In(outright, "EUR")).isCloseTo(280.9282, within(5e-5));
            assertThat(outright.dv01()).isCloseTo(0.1128, within(5e-5));

            PositionResult ndf = position(session, "P19");
            assertThat(ndf.instrumentType()).isEqualTo("FX_NDF");
            assertThat(ndf.notionalCurrency()).isEqualTo("USD");
            assertThat(dv01In(ndf, "USD")).isCloseTo(0.2086, within(5e-5));
            // No KRW curve exists, so the NDF's rates risk is in one currency only.
            assertThat(dv01In(ndf, "EUR")).isZero();

            assertThat(session.snapshot().bookRisk().byInstrumentType())
                    .extracting(InstrumentTypeRisk::instrumentType)
                    .contains("FX_FORWARD", "FX_NDF");
        });
    }

    /**
     * FX Delta is quoted per currency for a 1% move, and both Positions are long their risk currency, so
     * both read positive — even though one is long the pair's base and the other is short it. Getting
     * that sign right is the whole reason the bump is defined against the currency, not the pair.
     */
    @Test
    void fxDeltaIsPerCurrencyAndThePointsDeltaBelongsOnlyToTheNdf() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }
            BookRisk book = session.snapshot().bookRisk();

            // Long 10m EUR and, through a short USD/KRW NDF on 5m USD, long the won.
            assertThat(book.fxDeltaByCurrency()).satisfiesExactly(
                    eur -> {
                        assertThat(eur.currency()).isEqualTo("EUR");
                        assertThat(eur.amount()).isCloseTo(113_932.0097, within(5e-4));
                    },
                    krw -> {
                        assertThat(krw.currency()).isEqualTo("KRW");
                        assertThat(krw.amount()).isCloseTo(50_137.8699, within(5e-4));
                    });
            // Only the non-deliverable pair has Forward Points to be sensitive to.
            assertThat(book.pointsDeltaByPair()).singleElement().satisfies(points -> {
                assertThat(points.pair()).isEqualTo("USDKRW");
                assertThat(points.amount()).isCloseTo(-36.3003, within(5e-5));
            });

            PositionResult outright = position(session, "P18");
            assertThat(fxDeltaIn(outright, "EUR")).isPositive();
            assertThat(fxDeltaIn(outright, "KRW")).isZero();
            assertThat(outright.pointsDelta()).singleElement()
                    .satisfies(points -> assertThat(points.amount()).isZero());

            PositionResult ndf = position(session, "P19");
            assertThat(fxDeltaIn(ndf, "KRW")).isPositive();
            assertThat(fxDeltaIn(ndf, "EUR")).isZero();
            assertThat(ndf.pointsDelta()).singleElement()
                    .satisfies(points -> assertThat(points.amount()).isNotZero());
        });
    }

    /** The panel's data: the market both contracts price from, and their terms against today's forward. */
    @Test
    void theFxViewCarriesTheMarketAndBothContractsTerms() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }
            FxView fx = session.snapshot().fx();

            assertThat(fx.pairs()).satisfiesExactly(
                    eurusd -> {
                        assertThat(eurusd.pair()).isEqualTo("EURUSD");
                        assertThat(eurusd.riskCurrency()).isEqualTo("EUR");
                        assertThat(eurusd.spot()).isPositive();
                        // Deliverable: its forward comes from two curves, so it has no points.
                        assertThat(eurusd.points()).isNull();
                    },
                    usdkrw -> {
                        assertThat(usdkrw.pair()).isEqualTo("USDKRW");
                        assertThat(usdkrw.riskCurrency()).isEqualTo("KRW");
                        assertThat(usdkrw.points()).isNotNull();
                    });

            assertThat(fx.contracts()).satisfiesExactly(
                    outright -> {
                        assertThat(outright.kind()).isEqualTo("OUTRIGHT");
                        assertThat(outright.contractRate()).isEqualTo(1.15);
                        assertThat(outright.forwardRate()).isPositive();
                        // An outright has no fixing at all.
                        assertThat(outright.fixingDate()).isNull();
                        assertThat(outright.fxFixing()).isNull();
                        assertThat(outright.settlementDate()).isEqualTo("2026-12-11");
                    },
                    ndf -> {
                        assertThat(ndf.kind()).isEqualTo("NDF");
                        assertThat(ndf.notionalCurrency()).isEqualTo("USD");
                        assertThat(ndf.fixingDate()).isEqualTo("2026-10-09");
                        // Tick 24 is long before the fixing date, so nothing is recorded yet.
                        assertThat(ndf.fxFixing()).isNull();
                        assertThat(ndf.forwardRate())
                                .isCloseTo(fx.pairs().get(1).spot() + fx.pairs().get(1).points() * 0.01, within(1e-9));
                    });
        });
    }

    private static double fxDeltaIn(PositionResult position, String currency) {
        return position.fxDelta().stream()
                .filter(delta -> delta.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + currency + " FX Delta for " + position.positionId()))
                .amount();
    }

    private static PositionResult position(RiskSession session, String positionId) {
        return session.snapshot().positions().stream()
                .filter(p -> p.positionId().equals(positionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Position " + positionId));
    }

    private static double dv01In(PositionResult position, String currency) {
        return position.ratesByCurrency().stream()
                .filter(rates -> rates.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + currency + " rates for " + position.positionId()))
                .dv01();
    }

    /**
     * The NDF is sized so its fixing and settlement happen on screen: 2026-09-11 to 2026-10-13 is 32
     * simulated days, about 770 ticks. The outright, at three months, is still alive at the end — so the
     * demo always has a live two-currency valuation, whichever moment it is frozen at.
     */
    @Test
    void theNdfSettlesOnScreenWhileTheOutrightStaysAlive() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            List<LifecycleEvent> events = new ArrayList<>();
            for (int tick = 1; tick <= 790; tick++) {
                events.addAll(session.step().lifecycleEvents());
            }

            // Past the 2026-10-13 settlement and short of the outright's 2026-12-11 maturity.
            assertThat(LocalDate.parse(session.snapshot().session().valuationDate()))
                    .isAfterOrEqualTo(LocalDate.of(2026, 10, 13))
                    .isBefore(LocalDate.of(2026, 12, 11));
            assertThat(events).filteredOn(e -> e.positionId().equals("P19")).singleElement()
                    .satisfies(settlement -> {
                        assertThat(settlement.kind()).isEqualTo("FX_SETTLEMENT");
                        assertThat(settlement.date()).isEqualTo("2026-10-13");
                        assertThat(settlement.currency()).isEqualTo("USD");
                        assertThat(settlement.amount()).isNotZero();
                    });
            // The FX Fixing was recorded exactly once, on the Day Rollover onto its fixing date, and the
            // settlement was struck on it rather than on whatever spot happened to be at settlement.
            FxFixings fixings = session.marketState().fx().fixings();
            assertThat(fixings.keys()).containsExactly(new FxFixings.Key("USDKRW", LocalDate.of(2026, 10, 9)));
            double fixed = fixings.rate("USDKRW", LocalDate.of(2026, 10, 9));
            assertThat(events).filteredOn(e -> e.positionId().equals("P19")).singleElement()
                    .satisfies(settlement -> assertThat(settlement.amount())
                            .isCloseTo((1 - 1386.50 / fixed) * -5_000_000, within(1e-6)));
            // Spot has moved on since the fixing; the recorded rate has not followed it.
            assertThat(session.marketState().fx().spot("USDKRW")).isNotEqualTo(fixed);

            // Settled: worth nothing, still in the Book, and no longer carrying rates risk.
            assertThat(position(session, "P19").value()).isZero();
            assertThat(dv01In(position(session, "P19"), "USD")).isZero();
            // The outright has not matured, so it still splits across two curves.
            assertThat(events).filteredOn(e -> e.positionId().equals("P18")).isEmpty();
            assertThat(dv01In(position(session, "P18"), "EUR")).isNotZero();
            assertThat(dv01In(position(session, "P18"), "USD")).isNotZero();
        });
    }

    /** A struck settlement is not restruck: the recorded rate stands however far spot travels after it. */
    @Test
    void theFxFixingIsRecordedOnceAndNeverMovesAgain() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            // 2026-09-11 to the 2026-10-09 fixing is 28 simulated days, 672 ticks.
            for (int tick = 1; tick <= 680; tick++) {
                session.step();
            }

            FxFixings.Key key = new FxFixings.Key("USDKRW", LocalDate.of(2026, 10, 9));
            double fixed = session.marketState().fx().fixings().rate("USDKRW", key.date());
            double spotAtFixing = session.marketState().fx().spot("USDKRW");
            assertThat(fixed).isPositive();

            for (int tick = 0; tick < 60; tick++) {
                session.step();
            }

            assertThat(session.marketState().fx().fixings().keys()).containsExactly(key);
            assertThat(session.marketState().fx().fixings().rate("USDKRW", key.date())).isEqualTo(fixed);
            assertThat(session.marketState().fx().spot("USDKRW")).isNotEqualTo(spotAtFixing);
        });
    }


    /**
     * The risk optionality adds, at the frozen Tick. Vega is bump-and-reprice like everything else here,
     * so every Position measures it and only the two Swaptions have any: <b>USD +7,117.88</b>, all of it
     * theirs. There is no total across currencies, for the reason FX Delta has none.
     */
    @Test
    void onlyTheSwaptionsHaveVegaAndItRollsUpToTheBook() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }
            BookRisk book = session.snapshot().bookRisk();

            assertThat(book.vegaByCurrency()).singleElement().satisfies(vega -> {
                assertThat(vega.currency()).isEqualTo("USD");
                assertThat(vega.amount()).isCloseTo(7_117.8830, within(5e-5));
            });
            // Both are bought, so both gain when volatility rises; the 1Y receiver holds far more of it,
            // because Vega scales with the time left and it has a year against the payer's month.
            assertThat(usdVega(position(session, "P20"))).isCloseTo(1_164.1089, within(5e-5));
            assertThat(usdVega(position(session, "P21"))).isCloseTo(5_953.7741, within(5e-5));
            // Every other Position measures a Vega, and every one of them measures exactly zero.
            assertThat(session.snapshot().positions())
                    .filteredOn(p -> !p.instrumentType().equals("SWAPTION"))
                    .hasSize(19)
                    .allSatisfy(p -> assertThat(usdVega(p)).isZero());
            assertThat(book.vegaByCurrency().get(0).amount())
                    .isCloseTo(usdVega(position(session, "P20")) + usdVega(position(session, "P21")),
                            within(5e-9));
        });
    }

    /**
     * Gamma, and the sentence the whole feature exists to make checkable. The payer's DV01 is −4,058 and
     * its Gamma is −4,003: shift the curve 25bp and <em>almost all</em> of its rates risk is a different
     * number. The most convex thing the Book held before the options was the thirty-year bond, and it
     * moves 5.4% of its DV01 over the same shift. Both are curvature; they are not the same size.
     */
    @Test
    void theSwaptionsDv01IsTheOneThatMovesWhenRatesDo() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }
            BookRisk book = session.snapshot().bookRisk();
            PositionResult payer = position(session, "P20");
            PositionResult receiver = position(session, "P21");

            assertThat(book.gamma()).isCloseTo(-6_742.8611, within(5e-5));
            assertThat(payer.gamma()).isCloseTo(-4_003.4059, within(5e-5));
            assertThat(receiver.gamma()).isCloseTo(-2_000.6470, within(5e-5));
            // Nearly a whole DV01 of movement on the payer; at most a twentieth of one on everything else.
            assertThat(Math.abs(payer.gamma() / payer.dv01())).isGreaterThan(0.95);
            assertThat(session.snapshot().positions())
                    .filteredOn(p -> !p.instrumentType().equals("SWAPTION"))
                    .allSatisfy(p -> assertThat(Math.abs(p.gamma())).isLessThan(0.06 * Math.abs(p.dv01())));
            // It is a simple sum, like DV01 — and it splits per currency the same way, which is the only
            // split that nets. Both carry their shift size wherever they are shown.
            assertThat(book.gamma()).isCloseTo(
                    session.snapshot().positions().stream().mapToDouble(PositionResult::gamma).sum(),
                    within(5e-9));
            assertThat(book.ratesByCurrency().stream().mapToDouble(CurrencyRates::gamma).sum())
                    .isCloseTo(book.gamma(), within(5e-9));
            assertThat(RatesSensitivities.GAMMA_LABEL).isEqualTo("DV01 change for +25bp");
            assertThat(RatesSensitivities.GAMMA_TOTAL_LABEL).isEqualTo("all curves, +25bp each");
        });
    }

    /**
     * What the swaptions panel is given: the strike beside the Forward Swap Rate that decides the
     * Exercise, the Surface Point's own Normal Volatility, and no decision at all while the Expiry is
     * still ahead. At Tick 24 the payer is already 9.4bp out of the money, a month before it expires.
     */
    @Test
    void theSwaptionViewCarriesTheStrikeAgainstTheForwardAndNoDecisionYet() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }

            assertThat(session.snapshot().swaptions()).hasSize(2);
            assertThat(session.snapshot().swaptions().get(0)).satisfies(payer -> {
                assertThat(payer.instrumentId()).isEqualTo("SWPN-1Mx5Y-PAY");
                assertThat(payer.direction()).isEqualTo("PAYER");
                assertThat(payer.surfacePoint()).isEqualTo("USD 1Mx5Y");
                assertThat(payer.strike()).isCloseTo(0.048017, within(1e-15));
                assertThat(payer.expiryDate()).isEqualTo("2026-10-11");
                assertThat(payer.underlyingMaturityDate()).isEqualTo("2031-10-11");
                assertThat(payer.normalVolBp()).isCloseTo(95.1397, within(5e-5));
                assertThat(payer.forwardRate()).isCloseTo(0.0470732, within(5e-8));
                assertThat(payer.exercised()).as("undecided until the Expiry").isNull();
            });
            assertThat(session.snapshot().swaptions().get(1)).satisfies(receiver -> {
                assertThat(receiver.direction()).isEqualTo("RECEIVER");
                assertThat(receiver.normalVolBp()).isCloseTo(85.9096, within(5e-5));
                // The two points are quoted apart, so the panel is showing a surface and not one number.
                assertThat(receiver.normalVolBp()).isNotEqualTo(95.1397);
            });
        });
    }

    private static double usdVega(PositionResult position) {
        return position.vega().stream().filter(v -> v.currency().equals("USD"))
                .mapToDouble(RiskSnapshot.CurrencyAmount::amount).sum();
    }

    @Test
    void everyDemoRunIsIdentical() {
        List<List<RiskUpdate>> runs = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            runner("demo").run(context -> {
                RiskSession session = context.getBean(RiskSession.class);
                List<RiskUpdate> updates = new ArrayList<>();
                for (int tick = 1; tick <= 130; tick++) {
                    updates.add(session.step());
                }
                runs.add(updates);
            });
        }

        assertThat(runs.get(0)).isEqualTo(runs.get(1));
        // Acme's scheduled downgrade happens on tick 120 of every run.
        assertThat(runs.get(0).get(119).credit().issuers()).filteredOn(i -> i.issuerId().equals("ACME"))
                .singleElement().satisfies(acme -> {
                    assertThat(acme.migrationTick()).isEqualTo(120L);
                    assertThat(acme.migratedFrom()).isEqualTo("A Industrials");
                });
    }

    /**
     * The regression check: the canonical demo frozen at Tick 24, which is the Book the article series
     * quotes. These numbers move only when the seeded random stream moves. It has now moved twice — once
     * when FX joined the correlated draw, and again when the two Normal Volatility Surface Points did —
     * and they were re-baselined each time. Both shifts land before the series re-measure, so it is paid
     * once. Until the next one, a change here means a refactor has changed a price.
     */
    @Test
    void theDemoBookAtTickTwentyFourIsUnchanged() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }
            BookRisk book = session.snapshot().bookRisk();

            assertThat(session.snapshot().tick()).isEqualTo(24);
            assertThat(book.dv01()).isCloseTo(25_383.8273, within(5e-5));
            assertThat(book.value()).isCloseTo(33_661_279.4369, within(5e-4));
            // The two Swaptions are the whole of the change from the previous anchor. Value and DV01 are
            // simple sums at the Book level, so subtracting the new Positions has to give back exactly
            // what the Book read before they were added — which is the check that adding them repriced
            // nothing else.
            PositionResult payer = position(session, "P20");
            PositionResult receiver = position(session, "P21");
            assertThat(book.value() - payer.value() - receiver.value())
                    .isCloseTo(33_011_173.5634, within(5e-4));
            assertThat(book.dv01() - payer.dv01() - receiver.dv01())
                    .isCloseTo(20_878.5301, within(5e-5));
            // Two curves now. The Book holds no euro Position yet, so every basis point is still a
            // dollar one and the EUR line is present and empty — which is the thing worth asserting.
            assertThat(book.ratesByCurrency()).hasSize(2);
            assertThat(book.ratesByCurrency().get(0)).satisfies(usd -> {
                assertThat(usd.currency()).isEqualTo("USD");
                assertThat(usd.dv01()).isCloseTo(25_102.8991, within(5e-5));
                // The headline is no longer any one currency's DV01: it is a basis point of each, added
                // up. That it now differs from USD alone is the whole reason it carries a label.
                assertThat(usd.dv01()).isNotEqualTo(book.dv01());
            });
            assertThat(book.ratesByCurrency().get(1)).satisfies(eur -> {
                assertThat(eur.currency()).isEqualTo("EUR");
                assertThat(eur.dv01()).isCloseTo(280.9282, within(5e-5));
                // Pillars are global: the EUR curve is reported at the same tenors as the USD one.
                assertThat(eur.bucketedDv01()).hasSameSizeAs(book.bucketedDv01());
            });
            assertThat(book.ratesByCurrency().stream().mapToDouble(CurrencyRates::dv01).sum())
                    .isCloseTo(book.dv01(), within(5e-9));
        });
    }

    /**
     * The Book's first optionality. Two Positions, deliberately different: one that reaches its Expiry
     * inside a demo run and one that never does, so the Book always holds a live option whichever moment
     * the run is frozen at.
     */
    @Test
    void bothSwaptionsLoadWithTheirUnderlyingSwapsTermsAndOneExpiresOnScreen() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }
            LocalDate start = LocalDate.parse(session.snapshot().session().curveDate());
            int ticksPerDay = session.snapshot().session().ticksPerDay();

            PositionResult payer = position(session, "P20");
            assertThat(payer.instrumentType()).isEqualTo("SWAPTION");
            assertThat(payer.notionalCurrency()).isEqualTo("USD");
            // A premium is paid, so a Swaption Position is worth something positive from the first tick.
            assertThat(payer.value()).isPositive();
            // A payer gains as rates rise, the same side of the market as the pay-fixed swap P16.
            assertThat(payer.dv01()).isNegative();
            assertThat(position(session, "P16").dv01()).isNegative();

            PositionResult receiver = position(session, "P21");
            assertThat(receiver.instrumentType()).isEqualTo("SWAPTION");
            assertThat(receiver.value()).isPositive();
            assertThat(receiver.dv01()).isPositive();
            assertThat(position(session, "P17").dv01()).isPositive();

            assertThat(session.snapshot().bookRisk().byInstrumentType())
                    .extracting(InstrumentTypeRisk::instrumentType).contains("SWAPTION");

            // The 1M payer reaches its Expiry inside the demo's tick budget; the 1Y receiver cannot,
            // which is why the Book still holds optionality at the end of a run.
            Swaption payerTerms = swaption(context, "SWPN-1Mx5Y-PAY");
            Swaption receiverTerms = swaption(context, "SWPN-1Yx10Y-REC");
            assertThat(ticksTo(start, payerTerms.expiryDate(), ticksPerDay)).isEqualTo(720).isLessThan(DEMO_TICKS);
            assertThat(ticksTo(start, receiverTerms.expiryDate(), ticksPerDay)).isGreaterThan(DEMO_TICKS);

            // The terms are the underlying swap's, and the strike is the one written into the CSV rather
            // than one computed at startup: 4.8017%, the Forward Swap Rate on the session's start date.
            assertThat(payerTerms.isPayer()).isTrue();
            assertThat(payerTerms.strike()).isCloseTo(0.048017, within(1e-15));
            assertThat(payerTerms.expiryDate()).isEqualTo(payerTerms.underlying().effectiveDate());
            assertThat(payerTerms.underlying().maturityDate()).isEqualTo(LocalDate.of(2031, 10, 11));
            assertThat(payerTerms.surfacePoint().label()).isEqualTo("USD 1Mx5Y");
            assertThat(receiverTerms.isPayer()).isFalse();
            assertThat(receiverTerms.strike()).isCloseTo(0.051025, within(1e-15));
            assertThat(receiverTerms.surfacePoint().label()).isEqualTo("USD 1Yx10Y");
            // Each prices off its own point, and the two points are quoted at different volatilities.
            assertThat(session.marketState().vols().normalVol("USD 1Mx5Y"))
                    .isNotEqualTo(session.marketState().vols().normalVol("USD 1Yx10Y"));
        });
    }

    /**
     * The article's moment. The 1M payer reaches its Expiry on screen at tick 720 and — this is what seed
     * 42 decided, not what anyone chose — expires <em>worthless</em>, by 8.4bp. It is struck at the
     * Forward Swap Rate on the session's start date, 4.8017%, and thirty simulated days later the forward
     * is 4.7178%. A payer that cannot be exercised profitably is not exercised.
     *
     * <p>The Position stays exactly where it is, worth nothing. That is ADR-0012: an Exercise changes
     * what a Position is, not what the Book holds.
     */
    @Test
    void thePayerReachesItsExpiryOnScreenAndTheDecisionIsRecordedOnce() {
        runner("demo").run(context -> {
            RiskSession session = context.getBean(RiskSession.class);
            List<String> positionsBefore = null;
            for (int tick = 1; tick <= 744; tick++) {
                if (tick == 720) {
                    positionsBefore = positionIds(session);
                    assertThat(session.marketState().exercises().isEmpty())
                            .as("undecided the tick before the Day Rollover onto Expiry").isTrue();
                }
                session.step();
            }
            LocalDate expiry = LocalDate.of(2026, 10, 11);
            MarketState market = session.marketState();
            Swaption payer = swaption(context, "SWPN-1Mx5Y-PAY");

            // Recorded exactly once, on the Day Rollover onto the Expiry, and nothing else decided.
            assertThat(market.exercises().keys())
                    .containsExactly(new ExerciseDecisions.Key("SWPN-1Mx5Y-PAY", expiry));
            // Out of the money by 8.4bp: the forward finished below the strike it was struck at.
            assertThat(payer.underlying().forwardRate(market)).isLessThan(payer.strike());
            assertThat(market.exercises().wasExercised("SWPN-1Mx5Y-PAY", expiry)).isFalse();

            // The underlying's first floating period starts at the Expiry, and its Fixing was taken there.
            assertThat(market.fixings().on(expiry)).isPresent();

            // Worth nothing, carrying nothing, and still in the Book in the same place. The option is
            // gone, so the risk that was the option's goes with it: no Vega, no Gamma, no DV01.
            PositionResult lapsed = position(session, "P20");
            assertThat(lapsed.value()).isZero();
            assertThat(lapsed.dv01()).isZero();
            assertThat(lapsed.gamma()).isZero();
            assertThat(usdVega(lapsed)).isZero();
            // And the panel now shows what was decided rather than a date still to come.
            assertThat(session.snapshot().swaptions().get(0).exercised()).isFalse();
            assertThat(session.snapshot().swaptions().get(1).exercised())
                    .as("the 1Y receiver has not expired").isNull();
            assertThat(lapsed.instrumentType()).isEqualTo("SWAPTION");
            assertThat(positionIds(session)).isEqualTo(positionsBefore).contains("P20", "P21");

            // The 1Y receiver has not expired, so the Book still holds live optionality and still
            // depends on the volatility surface.
            assertThat(position(session, "P21").value()).isPositive();
            assertThat(swaption(context, "SWPN-1Yx10Y-REC").riskFactors(market, Pillar.DEFAULTS))
                    .contains(RiskFactorId.normalVol("USD", "1Yx10Y"));
            assertThat(payer.riskFactors(market, Pillar.DEFAULTS))
                    .containsExactly(RiskFactorId.valuationDate("USD"));
        });
    }

    private static List<String> positionIds(RiskSession session) {
        return session.snapshot().positions().stream().map(PositionResult::positionId).toList();
    }

    /** The demo's long-run budget, as PLAN.md sizes it: about 760 ticks, roughly 32 simulated days. */
    private static final int DEMO_TICKS = 760;

    private static int ticksTo(LocalDate from, LocalDate to, int ticksPerDay) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) * ticksPerDay;
    }

    private static Swaption swaption(org.springframework.context.ApplicationContext context, String id) {
        return (Swaption) ReferenceData.fromClasspath().instruments().get(id);
    }

    @Test
    void theSlowDemoTicksFastButRepricesSlowly() {
        runner("demo,demo-slow").run(context -> {
            assertThat(context.getEnvironment().getProperty("risk.simulation.tick-interval")).isEqualTo("200ms");
            assertThat(context.getEnvironment().getProperty("risk.repricing.cycle-delay")).isEqualTo("1s");
            assertThat(context.getEnvironment().getProperty("risk.simulation.seed")).isEqualTo("42");
        });
    }

    /**
     * The first Risk Factor in the engine that no curve can produce. Two named Surface Points, both
     * quoted, both in the named correlation matrix, and both independent of everything else in it — the
     * sign of the rate/vol correlation is regime-dependent, so the matrix says nothing rather than
     * guessing. Turning it on later is an edit to the matrix and nothing else.
     */
    @Test
    void bothSurfacePointsAreQuotedAndJoinTheCorrelationMatrixIndependently() {
        runner("demo").run(context -> {
            CorrelationMatrix matrix = CorrelationMatrix.parse(
                    context.getEnvironment().getProperty("risk.correlation.factors"),
                    context.getEnvironment().getProperty("risk.correlation.matrix"));
            assertThat(matrix.has("normalVol.USD.1Mx5Y")).isTrue();
            assertThat(matrix.has("normalVol.USD.1Yx10Y")).isTrue();
            for (String other : matrix.factors()) {
                if (!other.equals("normalVol.USD.1Mx5Y")) {
                    assertThat(matrix.correlation("normalVol.USD.1Mx5Y", other)).isZero();
                }
                if (!other.equals("normalVol.USD.1Yx10Y")) {
                    assertThat(matrix.correlation("normalVol.USD.1Yx10Y", other)).isZero();
                }
            }
            // `basis` still comes last, which is what lets every contract substitute its own draw.
            assertThat(matrix.factors().getLast()).isEqualTo("basis");

            RiskSession session = context.getBean(RiskSession.class);
            MarketState opening = session.marketState();
            assertThat(opening.vols().surfacePoints()).containsExactly("USD 1Mx5Y", "USD 1Yx10Y");
            // Each starts at its configured long-run mean, in decimal: 95bp and 85bp.
            assertThat(opening.vols().normalVol("USD 1Mx5Y")).isCloseTo(0.0095, within(1e-15));
            assertThat(opening.vols().normalVol("USD 1Yx10Y")).isCloseTo(0.0085, within(1e-15));

            for (int tick = 1; tick <= 24; tick++) {
                session.step();
            }
            MarketState market = session.marketState();
            // Both moved, independently, and the Risk Factor reads the level back in its raw unit.
            assertThat(market.riskFactorValue(RiskFactorId.normalVol("USD", "1Mx5Y")))
                    .isEqualTo(market.vols().normalVol("USD 1Mx5Y"))
                    .isNotEqualTo(opening.vols().normalVol("USD 1Mx5Y"))
                    .isPositive();
            assertThat(market.riskFactorValue(RiskFactorId.normalVol("USD", "1Yx10Y")))
                    .isNotEqualTo(opening.vols().normalVol("USD 1Yx10Y"))
                    .isPositive();
        });
    }

    /** A point named with no level, or with no shock of its own, fails at startup naming the offender. */
    @Test
    void aSurfacePointThatIsNotFullyConfiguredFailsAtStartup() {
        runner("demo").withPropertyValues("risk.vol.surface-points=USD 1Mx5Y, USD 1Yx10Y, USD 3Mx2Y")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("USD 3Mx2Y")
                        .hasMessageContaining("risk.vol.USD.3Mx2Y.long-run-mean-bp"));
        runner("demo").withPropertyValues(
                        "risk.vol.surface-points=USD 1Mx5Y, USD 1Yx10Y, USD 3Mx2Y",
                        "risk.vol.USD.3Mx2Y.long-run-mean-bp=90")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("risk.correlation.factors must name 'normalVol.USD.3Mx2Y'")
                        .hasMessageContaining("Surface Point USD 3Mx2Y is quoted"));
        // A currency with no Curve Source has no market to quote a volatility against. The Book's own
        // two points stay configured here, so it is the KRW point that fails and not the Swaptions.
        runner("demo").withPropertyValues(
                        "risk.vol.surface-points=USD 1Mx5Y, USD 1Yx10Y, KRW 1Mx5Y",
                        "risk.vol.KRW.1Mx5Y.long-run-mean-bp=90",
                        "risk.correlation.factors=shortRate.USD, systemic, shortRate.EUR, fxSpot.EURUSD, "
                                + "fxSpot.USDKRW, ndfPoints.USDKRW, normalVol.USD.1Mx5Y, normalVol.USD.1Yx10Y, "
                                + "normalVol.KRW.1Mx5Y, basis",
                        "risk.correlation.matrix=" + identity(10))
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("Surface Point KRW 1Mx5Y is quoted in KRW, which has no Curve Source"));
    }

    /**
     * The Book's own Positions are checked against the configured surface too: a Swaption priced off a
     * point nobody quotes fails at startup, not mid-tick with an empty volatility surface.
     */
    @Test
    void aSwaptionWhoseSurfacePointIsNotQuotedFailsAtStartup() {
        runner("demo").withPropertyValues(
                        "risk.vol.surface-points=USD 1Mx5Y",
                        "risk.correlation.factors=shortRate.USD, systemic, shortRate.EUR, fxSpot.EURUSD, "
                                + "fxSpot.USDKRW, ndfPoints.USDKRW, normalVol.USD.1Mx5Y, basis",
                        "risk.correlation.matrix=" + identity(8))
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("Swaption SWPN-1Yx10Y-REC prices off Surface Point USD 1Yx10Y")
                        .hasMessageContaining("[USD 1Mx5Y]"));
    }

    /** An n x n identity matrix in the property format: rows by ';', entries by ','. */
    private static String identity(int size) {
        StringBuilder matrix = new StringBuilder();
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                matrix.append(row == column ? 1 : 0).append(column == size - 1 ? "" : ",");
            }
            matrix.append(row == size - 1 ? "" : "; ");
        }
        return matrix.toString();
    }

    @Test
    void anUnknownCurveSourceFailsAtStartupWithAClearMessage() {
        assertThatThrownBy(() -> CurveSourceChoice.parse("yahoo"))
                .hasMessageContaining("Invalid risk.curve.source 'yahoo'")
                .hasMessageContaining("treasury")
                .hasMessageContaining("bundled");
        runner("demo").withPropertyValues("risk.curve.source=yahoo")
                .run(context -> assertThat(context).hasFailed());
    }
}
