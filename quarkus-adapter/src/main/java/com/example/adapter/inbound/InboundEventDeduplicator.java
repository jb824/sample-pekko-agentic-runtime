package com.example.adapter.inbound;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InboundEventDeduplicator {
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @ConfigProperty(name = "ingest.idempotency.ttl", defaultValue = "24h")
    Duration ttl;

    public boolean firstTime(String eventId) {
        Instant now = Instant.now();
        prune(now);
        Instant previous = seen.putIfAbsent(eventId, now);
        return previous == null;
    }

    private void prune(Instant now) {
        Instant threshold = now.minus(ttl);
        seen.entrySet().removeIf(e -> e.getValue().isBefore(threshold));
    }
}
