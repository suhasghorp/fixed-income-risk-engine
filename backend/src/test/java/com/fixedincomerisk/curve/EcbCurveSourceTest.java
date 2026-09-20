package com.fixedincomerisk.curve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The EUR Curve Source against a local fake of the ECB Data Portal — never the real network. */
class EcbCurveSourceTest {

    private static final LocalDate SAVED_DATE = LocalDate.of(2026, 9, 17);

    @TempDir
    Path tempDir;

    private final EcbTenors tenors = EcbTenors.fromClasspath();
    private HttpServer ecb;
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
    private volatile Response response = new Response(200, savedResponse());

    @BeforeEach
    void startFakeEcb() throws IOException {
        ecb = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ecb.createContext("/", exchange -> {
            requestedPaths.add(exchange.getRequestURI().toString());
            Response current = response;
            byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(current.status(), body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        ecb.start();
    }

    @AfterEach
    void stopFakeEcb() {
        ecb.stop(0);
    }

    @Test
    void liveFetchSucceedsReportsLiveAndCachesTheCurve() {
        CurveCache<ZeroCurve> cache = cacheInTempDir();

        CurveSnapshot snapshot = source(cache).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.LIVE);
        assertThat(snapshot.curveDate()).isEqualTo(SAVED_DATE);
        assertThat(cache.read()).hasValueSatisfying(cached -> {
            assertThat(cached.curveDate()).isEqualTo(SAVED_DATE);
            assertThat(cached.points()).hasSize(10);
        });
    }

    @Test
    void asksForEveryConfiguredTenorInOneRequest() {
        source(cacheInTempDir()).load();

        assertThat(requestedPaths).hasSize(1);
        // The Data Portal's REST shape: /service/data/{flow}/{key}, so the flow is its own path segment.
        assertThat(requestedPaths.get(0)).startsWith("/service/data/YC/B.U2.EUR.4F.G_N_A.SV_C_YM.");
        assertThat(requestedPaths.get(0)).contains("lastNObservations=1").contains("format=csvdata");
        for (EcbTenors.Tenor tenor : tenors.all()) {
            assertThat(requestedPaths.get(0)).contains(tenor.code());
        }
    }

    @Test
    void liveFailureFallsBackToTheCachedCurve() {
        CurveCache<ZeroCurve> cache = cacheInTempDir();
        source(cache).load();
        response = new Response(503, "Service Unavailable");

        CurveSnapshot snapshot = source(cache).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.CACHED);
        assertThat(snapshot.curveDate()).isEqualTo(SAVED_DATE);
    }

    @Test
    void liveFailureWithoutCacheFallsBackToTheBundledSnapshot() {
        response = new Response(500, "boom");

        CurveSnapshot snapshot = source(cacheInTempDir()).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.BUNDLED);
        assertThat(snapshot.curveDate()).isEqualTo(new BundledEcbCurveSource(tenors).load().curveDate());
    }

    @Test
    void unreachableEcbFallsBack() {
        EcbDataPortalCurveFetcher unreachable =
                new EcbDataPortalCurveFetcher(URI.create("http://127.0.0.1:1"), tenors);

        CurveSnapshot snapshot = new EcbCurveSource(
                unreachable, cacheInTempDir(), new BundledEcbCurveSource(tenors)).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.BUNDLED);
    }

    @Test
    void unusableResponseIsNotCachedAndFallsBack() {
        CurveCache<ZeroCurve> cache = cacheInTempDir();
        response = new Response(200, "<html><body>Maintenance</body></html>");

        CurveSnapshot snapshot = source(cache).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.BUNDLED);
        assertThat(cache.read()).isEmpty();
    }

    @Test
    void tooFewTenorsIsTreatedAsUnusable() {
        CurveCache<ZeroCurve> cache = cacheInTempDir();
        response = new Response(200, """
                KEY,DATA_TYPE_FM,TIME_PERIOD,OBS_VALUE
                YC,SR_10Y,2026-09-17,3.50
                YC,SR_2Y,2026-09-17,3.10
                """);

        assertThat(source(cache).load().source()).isEqualTo(CurveSourceKind.BUNDLED);
        assertThat(cache.read()).isEmpty();
    }

    @Test
    void corruptCacheIsIgnored() throws IOException {
        CurveCache<ZeroCurve> cache = cacheInTempDir();
        Files.createDirectories(cache.file().getParent());
        Files.writeString(cache.file(), "not a curve");
        response = new Response(500, "boom");

        assertThat(source(cache).load().source()).isEqualTo(CurveSourceKind.BUNDLED);
    }

    /** The bundled snapshot is real, dated ECB data and must stay parseable on its own. */
    @Test
    void bundledSnapshotIsDatedAndSpansEveryPillarTenor() {
        CurveSnapshot snapshot = new BundledEcbCurveSource(tenors).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.BUNDLED);
        assertThat(snapshot.curveDate()).isEqualTo(SAVED_DATE);
        assertThat(snapshot.curve().zeroRate(10)).isCloseTo(0.034875063501, within(1e-12));
        assertThat(snapshot.curve().zeroRate(30)).isCloseTo(0.03750190154, within(1e-12));
    }

    /**
     * Pillars are global: one {@code risk.pillars} list for every currency. The ECB tenor set has to span
     * them, or the EUR curve would be reported at Pillars it has no knot for.
     */
    @Test
    void theEcbTenorSetSpansEveryConfiguredPillar() {
        List<String> pillars = List.of("3M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y");

        assertThat(tenors.all()).extracting(EcbTenors.Tenor::label).containsAll(pillars);

        DiscountCurve curve = new BundledEcbCurveSource(tenors).load().curve();
        for (EcbTenors.Tenor tenor : tenors.all()) {
            assertThat(curve.zeroRate(tenor.years())).as(tenor.label()).isBetween(0.0, 0.20);
        }
    }

    private EcbCurveSource source(CurveCache<ZeroCurve> cache) {
        URI baseUrl = URI.create("http://127.0.0.1:" + ecb.getAddress().getPort());
        return new EcbCurveSource(new EcbDataPortalCurveFetcher(baseUrl, tenors), cache,
                new BundledEcbCurveSource(tenors));
    }

    private CurveCache<ZeroCurve> cacheInTempDir() {
        return CurveCache.forZeroCurve(tempDir.resolve("cache").resolve("ecb-zero-curve.csv"), tenors);
    }

    private static String savedResponse() {
        try (InputStream in = EcbCurveSourceTest.class.getResourceAsStream("/ecb/spot-rates-2026-09-17.csv")) {
            if (in == null) {
                throw new IllegalStateException("Saved ECB response missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Response(int status, String body) {
    }
}
