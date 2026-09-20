package com.fixedincomerisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fixedincomerisk.curve.BundledCurveSource;
import com.fixedincomerisk.curve.BundledEcbCurveSource;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.curve.CurveSourceChoice;
import com.fixedincomerisk.market.FxFixings;
import com.fixedincomerisk.session.RiskSession;
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
     * −0.72 is the near-cancellation of −280.80 of USD and +280.08 of EUR, which are not the same thing.
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
            assertThat(dv01In(outright, "USD")).isCloseTo(-280.8031, within(5e-5));
            assertThat(dv01In(outright, "EUR")).isCloseTo(280.0822, within(5e-5));
            assertThat(outright.dv01()).isCloseTo(-0.7208, within(5e-5));

            PositionResult ndf = position(session, "P19");
            assertThat(ndf.instrumentType()).isEqualTo("FX_NDF");
            assertThat(ndf.notionalCurrency()).isEqualTo("USD");
            assertThat(dv01In(ndf, "USD")).isCloseTo(0.3754, within(5e-5));
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
                        assertThat(eur.amount()).isCloseTo(113_588.9064, within(5e-4));
                    },
                    krw -> {
                        assertThat(krw.currency()).isEqualTo("KRW");
                        assertThat(krw.amount()).isCloseTo(50_333.6276, within(5e-4));
                    });
            // Only the non-deliverable pair has Forward Points to be sensitive to.
            assertThat(book.pointsDeltaByPair()).singleElement().satisfies(points -> {
                assertThat(points.pair()).isEqualTo("USDKRW");
                assertThat(points.amount()).isCloseTo(-36.5850, within(5e-5));
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
     * The single-currency regression check: the canonical demo frozen at Tick 24, which is the Book the
     * article series quotes. These numbers move only when the seeded random stream moves — which adding FX
     * to the correlated draw will do, and they are re-baselined then. Until that happens, a change here
     * means a refactor has changed a price.
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
            assertThat(book.dv01()).isCloseTo(20_549.3241, within(5e-5));
            assertThat(book.value()).isCloseTo(32_927_395.5157, within(5e-4));
            // Two curves now. The Book holds no euro Position yet, so every basis point is still a
            // dollar one and the EUR line is present and empty — which is the thing worth asserting.
            assertThat(book.ratesByCurrency()).hasSize(2);
            assertThat(book.ratesByCurrency().get(0)).satisfies(usd -> {
                assertThat(usd.currency()).isEqualTo("USD");
                assertThat(usd.dv01()).isCloseTo(20_269.2418, within(5e-5));
                // The headline is no longer any one currency's DV01: it is a basis point of each, added
                // up. That it now differs from USD alone is the whole reason it carries a label.
                assertThat(usd.dv01()).isNotEqualTo(book.dv01());
            });
            assertThat(book.ratesByCurrency().get(1)).satisfies(eur -> {
                assertThat(eur.currency()).isEqualTo("EUR");
                assertThat(eur.dv01()).isCloseTo(280.0822, within(5e-5));
                // Pillars are global: the EUR curve is reported at the same tenors as the USD one.
                assertThat(eur.bucketedDv01()).hasSameSizeAs(book.bucketedDv01());
            });
            assertThat(book.ratesByCurrency().stream().mapToDouble(CurrencyRates::dv01).sum())
                    .isCloseTo(book.dv01(), within(5e-9));
        });
    }

    @Test
    void theSlowDemoTicksFastButRepricesSlowly() {
        runner("demo,demo-slow").run(context -> {
            assertThat(context.getEnvironment().getProperty("risk.simulation.tick-interval")).isEqualTo("200ms");
            assertThat(context.getEnvironment().getProperty("risk.repricing.cycle-delay")).isEqualTo("1s");
            assertThat(context.getEnvironment().getProperty("risk.simulation.seed")).isEqualTo("42");
        });
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
