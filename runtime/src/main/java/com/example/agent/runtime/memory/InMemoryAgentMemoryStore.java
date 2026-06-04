package com.example.agent.runtime.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryAgentMemoryStore implements AgentMemoryStore {
    private final Map<AgentMemoryKey, Deque<AgentMemoryEvent>> events = new HashMap<>();

    @Override
    public List<AgentMemoryEvent> recent(AgentMemoryKey key, int maxEvents) {
        Deque<AgentMemoryEvent> existing = events.get(key);
        if (existing == null || existing.isEmpty()) {
            return List.of();
        }
        int skip = Math.max(0, existing.size() - maxEvents);
        return existing.stream().skip(skip).toList();
    }

    @Override
    public void append(AgentMemoryKey key, AgentMemoryEvent event, int maxEvents) {
        Deque<AgentMemoryEvent> existing = events.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        existing.addLast(event);
        while (existing.size() > maxEvents) {
            existing.removeFirst();
        }
    }
}
