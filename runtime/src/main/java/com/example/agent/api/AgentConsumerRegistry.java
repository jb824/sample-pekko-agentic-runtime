package com.example.agent.api;

import com.example.agent.config.AppConfig;
import com.example.agent.runtime.consumer.AgentConsumer;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

public final class AgentConsumerRegistry {
    private final Map<String, List<AgentConsumer>> consumersByWorkflow;

    public AgentConsumerRegistry(Map<String, List<AgentConsumer>> consumersByWorkflow) {
        Map<String, List<AgentConsumer>> indexed = new LinkedHashMap<>();
        if (consumersByWorkflow != null) {
            consumersByWorkflow.forEach((workflowId, consumers) -> {
                if (workflowId == null || workflowId.isBlank()) {
                    throw new IllegalArgumentException("workflow id must not be blank for scoped consumers");
                }
                indexed.put(workflowId, consumers == null ? List.of() : List.copyOf(consumers));
            });
        }
        this.consumersByWorkflow = Map.copyOf(indexed);
    }

    public static AgentConsumerRegistry discover(AppConfig config) {
        Map<String, List<AgentConsumer>> indexed = new LinkedHashMap<>();
        ServiceLoader.load(AgentConsumer.class)
                .stream()
                .map(ServiceLoader.Provider::type)
                .forEach(type -> register(indexed, instantiate(type, config), type));
        return new AgentConsumerRegistry(indexed);
    }

    public List<AgentConsumer> consumersFor(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return List.of();
        }
        return consumersByWorkflow.getOrDefault(workflowId, List.of());
    }

    private static void register(Map<String, List<AgentConsumer>> indexed, AgentConsumer consumer, Class<? extends AgentConsumer> type) {
        AgentComponent component = type.getAnnotation(AgentComponent.class);
        if (component == null || component.workflow().isBlank()) {
            throw new IllegalStateException("AgentConsumer component must declare @AgentComponent(workflow=...): " + type.getName());
        }
        indexed.computeIfAbsent(component.workflow(), ignored -> new ArrayList<>()).add(Objects.requireNonNull(consumer));
    }

    private static AgentConsumer instantiate(Class<? extends AgentConsumer> type, AppConfig config) {
        try {
            Constructor<? extends AgentConsumer> configConstructor = type.getDeclaredConstructor(AppConfig.class);
            configConstructor.setAccessible(true);
            return configConstructor.newInstance(config);
        } catch (NoSuchMethodException ignored) {
            // Fall through to no-arg constructor.
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create AgentConsumer component with AppConfig constructor: " + type.getName(), exception);
        }
        try {
            Constructor<? extends AgentConsumer> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception exception) {
            throw new IllegalStateException("AgentConsumer component requires a no-arg or AppConfig constructor: " + type.getName(), exception);
        }
    }
}
