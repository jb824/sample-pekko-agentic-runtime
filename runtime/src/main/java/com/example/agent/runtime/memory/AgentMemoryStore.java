package com.example.agent.runtime.memory;

import java.util.List;

public interface AgentMemoryStore {
    List<AgentMemoryEvent> recent(AgentMemoryKey key, int maxEvents);

    void append(AgentMemoryKey key, AgentMemoryEvent event, int maxEvents);
}
