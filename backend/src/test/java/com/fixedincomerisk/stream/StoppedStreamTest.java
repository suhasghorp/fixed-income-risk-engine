package com.fixedincomerisk.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Every message must reach the client in full as soon as it is sent, not when the next message pushes it
 * out. A run frozen by stop-at-tick sends nothing after its last cycle, so a client connecting afterwards
 * must still receive the whole Risk Snapshot, which is far larger than the servlet container's output buffer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "risk.simulation.tick-interval=20ms",
        "risk.simulation.stop-at-tick=30"})
@ActiveProfiles("demo")
class StoppedStreamTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void aClientConnectingAfterTheStopReceivesTheWholeSnapshotAtTheStopTick() throws Exception {
        Thread.sleep(2_000); // 30 ticks at 20ms, with room to spare

        String data = assertTimeoutPreemptively(Duration.ofSeconds(10), this::firstEventData);

        assertThat(data).startsWith("{\"sequence\":").contains("\"tick\":30,").endsWith("}");
        assertThat(data.length()).as("larger than an 8KB container buffer").isGreaterThan(20_000);
    }

    /** Reads the first event's data line, which is only complete once its terminating blank line arrives. */
    private String firstEventData() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/risk/stream")).build();
        HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String data = null;
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.startsWith("data:")) {
                    data = line.substring(5);
                } else if (line.isEmpty() && data != null) {
                    return data;
                }
            }
        }
        throw new AssertionError("Stream ended before the first event completed");
    }
}
