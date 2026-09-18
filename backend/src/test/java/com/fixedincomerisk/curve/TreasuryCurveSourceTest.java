package com.fixedincomerisk.curve;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The live Curve Source against a local fake of Treasury's CSV download — never the real network. */
class TreasuryCurveSourceTest {

    private static final String LIVE_CSV = """
            Date,"1 Mo","1.5 Month","2 Mo","3 Mo","4 Mo","6 Mo","1 Yr","2 Yr","3 Yr","5 Yr","7 Yr","10 Yr","20 Yr","30 Yr"
            09/11/2026,3.93,3.99,4.05,4.07,4.15,4.12,4.35,4.63,4.69,4.78,4.87,4.96,5.38,5.35
            09/10/2026,3.91,3.93,4.01,4.00,4.11,4.07,4.28,4.56,4.63,4.75,4.84,4.95,5.39,5.37
            """;

    private static final String PREVIOUS_YEAR_CSV = """
            Date,"1 Mo","2 Mo","3 Mo","6 Mo","1 Yr","2 Yr","5 Yr","10 Yr","30 Yr"
            12/31/2026,3.80,3.85,3.90,3.95,4.10,4.40,4.60,4.80,5.10
            """;

    private static final Clock SEPTEMBER_2026 = Clock.fixed(Instant.parse("2026-09-13T15:00:00Z"), ZoneOffset.UTC);
    private static final Clock JANUARY_2027 = Clock.fixed(Instant.parse("2027-01-02T15:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private HttpServer treasury;
    private final List<String> requestedYears = new CopyOnWriteArrayList<>();
    private volatile Responder responder = year -> new Response(200, LIVE_CSV);

    @BeforeEach
    void startFakeTreasury() throws IOException {
        treasury = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        treasury.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String year = query.replaceAll(".*field_tdr_date_value=(\\d{4}).*", "$1");
            requestedYears.add(year);
            Response response = responder.respond(Integer.parseInt(year));
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        treasury.start();
    }

    @AfterEach
    void stopFakeTreasury() {
        treasury.stop(0);
    }

    @Test
    void liveFetchSucceedsReportsLiveAndCachesTheCurve() {
        CurveCache cache = cacheInTempDir();

        CurveSnapshot snapshot = source(SEPTEMBER_2026, cache).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.LIVE);
        assertThat(snapshot.curve().curveDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(snapshot.curve().points()).hasSize(14);
        assertThat(requestedYears).containsExactly("2026");
        assertThat(cache.read()).contains(snapshot.curve());
    }

    @Test
    void liveFailureFallsBackToTheCachedCurve() {
        CurveCache cache = cacheInTempDir();
        source(SEPTEMBER_2026, cache).load();
        responder = year -> new Response(503, "Service Unavailable");

        CurveSnapshot snapshot = source(SEPTEMBER_2026, cache).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.CACHED);
        assertThat(snapshot.curve().curveDate()).isEqualTo(LocalDate.of(2026, 9, 11));
    }

    @Test
    void liveFailureWithoutCacheFallsBackToTheBundledSnapshot() {
        responder = year -> new Response(500, "boom");

        CurveSnapshot snapshot = source(SEPTEMBER_2026, cacheInTempDir()).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.BUNDLED);
        assertThat(snapshot.curve()).isEqualTo(new BundledCurveSource().load().curve());
    }

    @Test
    void unreachableTreasuryFallsBack() {
        TreasuryWebsiteCurveFetcher unreachable =
                new TreasuryWebsiteCurveFetcher(URI.create("http://127.0.0.1:1"), SEPTEMBER_2026);

        CurveSnapshot snapshot = new TreasuryCurveSource(unreachable, cacheInTempDir(), new BundledCurveSource()).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.BUNDLED);
    }

    @Test
    void unusableResponseIsNotCachedAndFallsBack() {
        CurveCache cache = cacheInTempDir();
        responder = year -> new Response(200, "<html><body>Maintenance</body></html>");

        CurveSnapshot snapshot = source(SEPTEMBER_2026, cache).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.BUNDLED);
        assertThat(cache.read()).isEmpty();
    }

    @Test
    void corruptCacheIsIgnored() throws IOException {
        CurveCache cache = cacheInTempDir();
        Files.createDirectories(cache.file().getParent());
        Files.writeString(cache.file(), "not a curve");
        responder = year -> new Response(500, "boom");

        assertThat(source(SEPTEMBER_2026, cache).load().source()).isEqualTo(CurveSourceKind.BUNDLED);
    }

    @Test
    void earlyJanuaryUsesThePreviousYearWhenTheCurrentYearHasNoRows() {
        responder = year -> year == 2027 ? new Response(200, "") : new Response(200, PREVIOUS_YEAR_CSV);

        CurveSnapshot snapshot = source(JANUARY_2027, cacheInTempDir()).load();

        assertThat(snapshot.source()).isEqualTo(CurveSourceKind.LIVE);
        assertThat(snapshot.curve().curveDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(requestedYears).containsExactly("2027", "2026");
    }

    @Test
    void cachedCsvRoundTripsThroughTheTreasuryFormat() {
        ParCurve curve = TreasuryParCurveCsv.parse(new StringReader(LIVE_CSV));

        assertThat(TreasuryParCurveCsv.parse(new StringReader(TreasuryParCurveCsv.format(curve)))).isEqualTo(curve);
    }

    private TreasuryCurveSource source(Clock clock, CurveCache cache) {
        URI baseUrl = URI.create("http://127.0.0.1:" + treasury.getAddress().getPort());
        return new TreasuryCurveSource(new TreasuryWebsiteCurveFetcher(baseUrl, clock), cache, new BundledCurveSource());
    }

    private CurveCache cacheInTempDir() {
        return new CurveCache(tempDir.resolve("cache").resolve("treasury-par-curve.csv"));
    }

    private record Response(int status, String body) {
    }

    @FunctionalInterface
    private interface Responder {
        Response respond(int year);
    }
}
