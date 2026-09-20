package com.fixedincomerisk.curve;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches the euro area AAA-rated central government bond spot rates from the ECB Data Portal, series
 * {@code YC.B.U2.EUR.4F.G_N_A.SV_C_YM.SR_<tenor>}. One request carries every configured tenor, and only
 * the latest observation of each is asked for.
 *
 * <p>These are published spot rates, already bootstrapped by the ECB and quoted on a continuously
 * compounded basis, so nothing here goes near {@link CurveBootstrapper}: re-deriving them would mean
 * modelling euro coupon conventions for no gain.
 */
public final class EcbDataPortalCurveFetcher {

    public static final URI DEFAULT_BASE_URL = URI.create("https://data-api.ecb.europa.eu");

    private static final String PATH = "/service/data/YC/B.U2.EUR.4F.G_N_A.SV_C_YM.%s"
            + "?lastNObservations=1&format=csvdata";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MIN_POINTS = 6;

    private final URI baseUrl;
    private final EcbTenors tenors;
    private final HttpClient http;

    public EcbDataPortalCurveFetcher(URI baseUrl, EcbTenors tenors) {
        this.baseUrl = baseUrl;
        this.tenors = tenors;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ZeroCurve fetchLatest() {
        URI uri = baseUrl.resolve(PATH.formatted(tenors.seriesKeySelection()));
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
        ZeroCurve curve;
        try {
            curve = EcbSpotRatesCsv.parse(new StringReader(response.body()), tenors);
        } catch (RuntimeException e) {
            throw new CurveUnavailableException("Unusable ECB spot rate CSV: " + e.getMessage(), e);
        }
        if (curve.points().size() < MIN_POINTS) {
            throw new CurveUnavailableException("ECB spot curve for " + curve.curveDate() + " has only "
                    + curve.points().size() + " tenors");
        }
        return curve;
    }
}
