package com.fixedincomerisk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixedincomerisk.curve.BundledCurveSource;
import com.fixedincomerisk.curve.CurveSource;
import com.fixedincomerisk.curve.CurveSourceChoice;
import com.fixedincomerisk.session.RiskSession;
import com.fixedincomerisk.session.RiskUpdate;
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
            assertThat(context.getBean(CurveSource.class)).isInstanceOf(BundledCurveSource.class);
            RiskSession session = context.getBean(RiskSession.class);
            assertThat(session.snapshot().session().curveSource()).isEqualTo("BUNDLED");
            assertThat(session.snapshot().session().curveDate()).isEqualTo("2026-09-11");
            assertThat(session.snapshot().session().seed()).isEqualTo(42);
            assertThat(session.snapshot().session().ticksPerDay()).isEqualTo(24);
            assertThat(session.snapshot().session().simulatedSecondsPerTick()).isEqualTo(3600);
            assertThat(context.getEnvironment().getProperty("risk.credit.events.scheduled")).isEqualTo("ACME@120:80:1");
            assertThat(context.getEnvironment().getProperty("risk.simulation.tick-interval")).isEqualTo("1s");
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
