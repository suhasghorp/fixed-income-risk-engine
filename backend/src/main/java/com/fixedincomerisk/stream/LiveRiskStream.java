package com.fixedincomerisk.stream;

import com.fixedincomerisk.session.MarketTicks;
import com.fixedincomerisk.session.RiskSession;
import com.fixedincomerisk.session.RiskSnapshot;
import com.fixedincomerisk.session.RiskUpdate;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the running {@link RiskSession}'s pricing side and its subscribers. Each subscriber gets a Risk
 * Snapshot and then every later Risk Update, in order: messages are queued under the same lock that prices
 * the session, and written by the subscriber's own async request thread, so a slow client never holds up
 * the simulation and nothing touches the response before the container has started it. A client that
 * falls too far behind is disconnected; it reconnects and resyncs from a new snapshot.
 */
@Component
class LiveRiskStream {

    static final String RISK_SNAPSHOT_EVENT = "risk-snapshot";
    static final String RISK_UPDATE_EVENT = "risk-update";

    private static final Logger log = LoggerFactory.getLogger(LiveRiskStream.class);
    private static final int MAX_QUEUED_MESSAGES = 256;
    private static final byte[] END_OF_STREAM = new byte[0];

    private final RiskSession session;
    private final JsonMapper json;
    private final Object lock = new Object();
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    LiveRiskStream(RiskSession session, JsonMapper json) {
        this.session = session;
        this.json = json;
    }

    /** Registers a subscriber; the returned body streams to it until it disconnects or the app stops. */
    StreamingResponseBody subscribe() {
        Subscriber subscriber = new Subscriber();
        synchronized (lock) {
            RiskSnapshot snapshot = session.snapshot();
            subscriber.offer(message(RISK_SNAPSHOT_EVENT, snapshot.sequence(), snapshot));
            subscribers.add(subscriber);
        }
        return subscriber::writeTo;
    }

    /**
     * Prices the newest of {@code ticks} (a repricing cycle) and queues its one Risk Update to every
     * subscriber. Runs on the repricing thread, under the same lock as {@link #subscribe()}, so a new
     * subscriber's Risk Snapshot and the Risk Updates that follow it never interleave.
     */
    void priceAndPublish(MarketTicks ticks) {
        synchronized (lock) {
            RiskUpdate update = session.price(ticks);
            byte[] message = message(RISK_UPDATE_EVENT, update.sequence(), update);
            for (Subscriber subscriber : subscribers) {
                if (!subscriber.offer(message)) {
                    log.warn("Risk stream subscriber fell {} messages behind; disconnecting it", MAX_QUEUED_MESSAGES);
                    subscriber.close();
                }
            }
        }
    }

    /**
     * End open streams as soon as shutdown starts. The context-closed event fires before the web
     * server's graceful shutdown, which would otherwise wait on these long-lived requests.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        subscribers.forEach(Subscriber::close);
    }

    /** One Server-Sent Event. The JSON payload is compact, so it fits on a single data line. */
    private byte[] message(String event, long sequence, Object payload) {
        String frame = "event:" + event + "\nid:" + sequence + "\ndata:" + json.writeValueAsString(payload) + "\n\n";
        return frame.getBytes(StandardCharsets.UTF_8);
    }

    private final class Subscriber {

        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private volatile boolean closed;

        boolean offer(byte[] message) {
            return !closed && queue.size() < MAX_QUEUED_MESSAGES && queue.offer(message);
        }

        void close() {
            closed = true;
            subscribers.remove(this);
            queue.clear();
            queue.offer(END_OF_STREAM);
        }

        void writeTo(OutputStream out) throws IOException {
            try {
                while (true) {
                    byte[] message = queue.take();
                    if (message == END_OF_STREAM) {
                        return;
                    }
                    out.write(message);
                    // Sends the whole event now; this only works with spring.http.response.flush.enabled=true
                    // (see src/main/resources/spring.properties).
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.debug("Risk stream subscriber disconnected: {}", e.getMessage());
            } finally {
                closed = true;
                subscribers.remove(this);
            }
        }
    }
}
