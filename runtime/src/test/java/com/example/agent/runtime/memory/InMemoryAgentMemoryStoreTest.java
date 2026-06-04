package com.example.agent.runtime.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class InMemoryAgentMemoryStoreTest {
    @Test
    void retainsOnlyMostRecentEventsPerKey() {
        InMemoryAgentMemoryStore store = new InMemoryAgentMemoryStore();
        AgentMemoryKey key = new AgentMemoryKey("tenant", "system", "agent");

        store.append(key, event("first"), 2);
        store.append(key, event("second"), 2);
        store.append(key, event("third"), 2);

        assertEquals(
                java.util.List.of("second", "third"),
                store.recent(key, 10).stream().map(AgentMemoryEvent::content).toList()
        );
    }

    private static AgentMemoryEvent event(String content) {
        return new AgentMemoryEvent(Instant.EPOCH, AgentMemoryEventType.AGENT_OUTPUT, "request", content);
    }
}
