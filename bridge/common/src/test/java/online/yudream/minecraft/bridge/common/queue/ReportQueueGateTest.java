package online.yudream.minecraft.bridge.common.queue;

import online.yudream.minecraft.bridge.common.config.BridgeSettings;
import online.yudream.minecraft.bridge.common.http.YudreamApiClient;
import online.yudream.minecraft.bridge.common.log.LogSink;
import online.yudream.minecraft.bridge.common.model.PlayerEventPayload;
import online.yudream.minecraft.bridge.common.model.PlayerEventType;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The queue refuses work when the caller's own mode decision says it should.
 *
 * <p>Without this gate a background producer such as the AFK tracker or the snapshot task would keep
 * uploading to YuDream Admin even after the plugin switched into a mode where the proxy is supposed
 * to be the only uploader.
 */
public class ReportQueueGateTest {

    private static final LogSink QUIET = new LogSink() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void warn(String message, Throwable cause) {
        }
    };

    private static BridgeSettings configured() {
        return BridgeSettings.builder()
                .baseUrl("http://127.0.0.1:1")
                .serverId("test")
                .apiKey("test-key")
                .retryAttempts(1)
                .build();
    }

    private static PlayerEventPayload payload() {
        return new PlayerEventPayload("player-uuid", "Steve", 1783512000000L, "survival");
    }

    @Test
    public void aClosedGateDropsSubmissions() {
        BridgeSettings settings = configured();
        ReportQueue queue = new ReportQueue(QUIET, settings, new YudreamApiClient(settings, "test"), null,
                new ReportQueue.ReportGate() {
                    @Override
                    public boolean canReport() {
                        return false;
                    }
                });
        try {
            queue.submit(PlayerEventType.JOIN, payload());
            queue.submitSnapshot(Collections.singletonList(payload()), 1783512000000L, "survival");
            assertEquals(0, queue.size());
            assertEquals(0, queue.outstandingCount());
        } finally {
            queue.shutdown(200L);
        }
    }

    @Test
    public void anOpenGateLetsWorkThrough() {
        BridgeSettings settings = configured();
        final AtomicInteger consultations = new AtomicInteger();
        ReportQueue queue = new ReportQueue(QUIET, settings, new YudreamApiClient(settings, "test"), null,
                new ReportQueue.ReportGate() {
                    @Override
                    public boolean canReport() {
                        consultations.incrementAndGet();
                        return true;
                    }
                });
        try {
            queue.submitSnapshot(Collections.singletonList(payload()), 1783512000000L, "survival");
            // Consulted synchronously by submit(), so this half is deterministic. The worker may
            // already have failed the POST to the unreachable test endpoint, which is why only an
            // upper bound is asserted on the queue itself.
            assertEquals(1, consultations.get());
            assertTrue(queue.outstandingCount() <= 1);
        } finally {
            queue.shutdown(200L);
        }
    }

    @Test
    public void aDisabledBridgeDropsSubmissionsWithoutAGate() {
        BridgeSettings settings = configured().toBuilder().enabled(false).build();
        ReportQueue queue = new ReportQueue(QUIET, settings, new YudreamApiClient(settings, "test"), null);
        try {
            queue.submit(PlayerEventType.JOIN, payload());
            assertEquals(0, queue.size());
        } finally {
            queue.shutdown(200L);
        }
    }

    @Test
    public void anUnconfiguredBridgeDropsSubmissionsWithoutAGate() {
        BridgeSettings settings = BridgeSettings.builder().baseUrl("").serverId("").apiKey("").build();
        ReportQueue queue = new ReportQueue(QUIET, settings, new YudreamApiClient(settings, "test"), null);
        try {
            queue.submit(PlayerEventType.JOIN, payload());
            assertEquals(0, queue.size());
        } finally {
            queue.shutdown(200L);
        }
    }
}
