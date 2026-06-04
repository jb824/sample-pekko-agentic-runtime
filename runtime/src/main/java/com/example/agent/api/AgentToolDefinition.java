package com.example.agent.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public record AgentToolDefinition(
        String name,
        String description,
        boolean sourceCapable,
        Duration timeout,
        AgentToolHandler handler
) {
    public AgentToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        description = description == null ? "" : description;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        handler = Objects.requireNonNull(handler, "handler");
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private String description = "";
        private boolean sourceCapable;
        private Duration timeout = Duration.ofSeconds(30);
        private AgentToolHandler handler = request -> CompletableFuture.completedFuture(AgentToolResult.success(""));

        private Builder(String name) {
            this.name = name;
        }

        public Builder describedAs(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder sourceCapable(boolean sourceCapable) {
            this.sourceCapable = sourceCapable;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
            return this;
        }

        public Builder handledBy(AgentToolHandler handler) {
            this.handler = Objects.requireNonNull(handler);
            return this;
        }

        public AgentToolDefinition build() {
            return new AgentToolDefinition(name, description, sourceCapable, timeout, handler);
        }
    }
}
