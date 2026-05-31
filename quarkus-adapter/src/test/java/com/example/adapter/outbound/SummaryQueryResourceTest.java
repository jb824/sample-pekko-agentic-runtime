package com.example.adapter.outbound;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SummaryQueryResourceTest {

    @Test
    void boundsLimitToRange() {
        SummaryQueryResource resource = new SummaryQueryResource();
        CapturingWriter writer = new CapturingWriter();
        resource.writer = writer;

        resource.latest("tenant-a", 0);
        assertEquals(1, writer.lastLimit);

        resource.latest("tenant-a", 999);
        assertEquals(200, writer.lastLimit);
    }

    private static final class CapturingWriter extends CassandraSummaryWriter {
        int lastLimit;

        @Override
        public List<SummaryRecord> latestByTenant(String tenantId, int limit) {
            this.lastLimit = limit;
            return List.of(new SummaryRecord(
                    UUID.randomUUID(),
                    tenantId,
                    "a1",
                    "wf",
                    "src",
                    "guardian",
                    "stored",
                    "summary",
                    "topics",
                    "high",
                    Instant.now()
            ));
        }
    }
}
