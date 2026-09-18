package com.fixedincomerisk.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "risk.simulation.seed=1234",
        "risk.simulation.tick-interval=50ms"})
class RiskStreamContractTest {

    private static final Pattern SEQUENCE = Pattern.compile("\"sequence\":(\\d+)");
    private static final Pattern TICK = Pattern.compile("\"tick\":(\\d+)");

    @TempDir
    static Path cacheDir;

    /** Keep the test off the real network: Treasury is unreachable and there's no cache, so the bundled curve is used. */
    @DynamicPropertySource
    static void offlineCurveSource(DynamicPropertyRegistry registry) {
        registry.add("risk.curve.treasury-base-url", () -> "http://127.0.0.1:1");
        registry.add("risk.curve.cache-file", () -> cacheDir.resolve("treasury-par-curve.csv").toString());
    }

    @Value("${local.server.port}")
    private int port;

    @Test
    void connectingDeliversARiskSnapshotThenConsecutiveRiskUpdates() {
        List<Event> events = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> readEvents(4));

        Event snapshot = events.getFirst();
        assertThat(snapshot.name()).isEqualTo("risk-snapshot");
        assertThat(snapshot.data())
                .contains("\"curveSource\":\"BUNDLED\"")
                .contains("\"seed\":1234")
                .contains("\"positionId\":\"P01\"")
                .contains("\"pillars\":[");

        long previous = sequence(snapshot);
        assertThat(snapshot.id()).isEqualTo(Long.toString(previous));
        for (Event update : events.subList(1, events.size())) {
            assertThat(update.name()).isEqualTo("risk-update");
            assertThat(sequence(update)).isEqualTo(previous + 1);
            assertThat(update.id()).isEqualTo(Long.toString(sequence(update)));
            assertThat(tick(update)).isPositive();
            assertThat(update.data()).contains("\"positions\":[").doesNotContain("delta");
            previous = sequence(update);
        }
    }

    @Test
    void reconnectingRequestsAFreshRiskSnapshotAtTheCurrentSequence() {
        long lastSeen = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> sequence(readEvents(3).getLast()));

        Event fresh = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> readEvents(1).getFirst());

        assertThat(fresh.name()).isEqualTo("risk-snapshot");
        assertThat(sequence(fresh)).isGreaterThanOrEqualTo(lastSeen);
    }

    private List<Event> readEvents(int count) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/risk/stream"))
                .header("Accept", "text/event-stream")
                .build();
        HttpResponse<InputStream> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));

        List<Event> events = new ArrayList<>();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.isEmpty()) {
                    if (fields.containsKey("data")) {
                        events.add(new Event(fields.get("event"), fields.get("id"), fields.get("data")));
                        if (events.size() == count) {
                            return events;
                        }
                    }
                    fields.clear();
                    continue;
                }
                int colon = line.indexOf(':');
                fields.merge(line.substring(0, colon), line.substring(colon + 1), String::concat);
            }
        }
        throw new AssertionError("Stream ended after " + events.size() + " events");
    }

    private static long sequence(Event event) {
        return firstNumber(SEQUENCE, event.data());
    }

    private static long tick(Event event) {
        return firstNumber(TICK, event.data());
    }

    private static long firstNumber(Pattern pattern, String data) {
        Matcher matcher = pattern.matcher(data);
        assertThat(matcher.find()).as("%s in %s", pattern, data).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private record Event(String name, String id, String data) {
    }
}
