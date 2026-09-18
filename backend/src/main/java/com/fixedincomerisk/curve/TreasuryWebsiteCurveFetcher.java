package com.fixedincomerisk.curve;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Fetches the latest Daily Treasury Par Yield Curve from Treasury's published CSV download
 * (home.treasury.gov). The download is per calendar year; early in January the current year has no
 * rows yet, so the previous year is tried next.
 */
public final class TreasuryWebsiteCurveFetcher {

    public static final URI DEFAULT_BASE_URL = URI.create("https://home.treasury.gov");

    private static final String PATH =
            "/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/%1$d/all"
                    + "?type=daily_treasury_yield_curve&field_tdr_date_value=%1$d&page&_format=csv";
    private static final ZoneId TREASURY_ZONE = ZoneId.of("America/New_York");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MIN_POINTS = 6;

    private final URI baseUrl;
    private final HttpClient http;
    private final Clock clock;

    public TreasuryWebsiteCurveFetcher(URI baseUrl, Clock clock) {
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ParCurve fetchLatest() {
        int year = LocalDate.now(clock.withZone(TREASURY_ZONE)).getYear();
        try {
            return fetchYear(year);
        } catch (CurveUnavailableException currentYearFailure) {
            try {
                return fetchYear(year - 1);
            } catch (CurveUnavailableException previousYearFailure) {
                previousYearFailure.addSuppressed(currentYearFailure);
                throw previousYearFailure;
            }
        }
    }

    private ParCurve fetchYear(int year) {
        URI uri = baseUrl.resolve(PATH.formatted(year));
        HttpResponse<String> response;
        try {
            response = http.send(
                    HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "text/csv").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CurveUnavailableException("Interrupted fetching " + uri, e);
        } catch (Exception e) {
            throw new CurveUnavailableException("Could not reach " + uri + ": " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new CurveUnavailableException("HTTP " + response.statusCode() + " from " + uri);
        }
        ParCurve curve;
        try {
            curve = TreasuryParCurveCsv.parse(new StringReader(response.body()));
        } catch (RuntimeException e) {
            throw new CurveUnavailableException("Unusable par curve CSV for " + year + ": " + e.getMessage(), e);
        }
        if (curve.points().size() < MIN_POINTS) {
            throw new CurveUnavailableException("Par curve for " + curve.curveDate() + " has only "
                    + curve.points().size() + " tenors");
        }
        return curve;
    }
}
