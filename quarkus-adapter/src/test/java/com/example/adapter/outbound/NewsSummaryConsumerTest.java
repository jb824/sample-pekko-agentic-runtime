package com.example.adapter.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NewsSummaryConsumerTest {

    @Test
    void throwsWhenPayloadInvalid() {
        NewsSummaryConsumer consumer = new NewsSummaryConsumer();
        consumer.mapper = new ObjectMapper();
        consumer.writer = new CassandraSummaryWriter() {
            @Override
            public void write(NewsSummaryGeneratedEvent event) {
            }
        };

        assertThrows(IllegalStateException.class, () -> consumer.onMessage("not-json"));
    }

    @Test
    void processesValidPayload() {
        NewsSummaryConsumer consumer = new NewsSummaryConsumer();
        consumer.mapper = new ObjectMapper();
        consumer.writer = new CassandraSummaryWriter() {
            @Override
            public void write(NewsSummaryGeneratedEvent event) {
            }
        };

        String payload = """
                {
                  "eventId":"b95dd71a-97a2-4da8-b780-75f5c34df2bc",
                  "eventType":"NewsSummaryGenerated",
                  "eventVersion":1,
                  "tenantId":"tenant-a",
                  "workflowId":"wf-1",
                  "sourceEventId":"src-1",
                  "source":"pekko",
                  "payload":{
                    "articleId":"a1",
                    "summary":"s",
                    "topics":"t",
                    "confidence":"high"
                  }
                }
                """;

        assertDoesNotThrow(() -> consumer.onMessage(payload));
    }
}
