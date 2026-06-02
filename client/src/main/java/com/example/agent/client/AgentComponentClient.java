package com.example.agent.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

public final class AgentComponentClient {
    private final AgentClient transport;

    private AgentComponentClient(AgentClient transport) {
        this.transport = transport;
    }

    public static Builder builder() {
        return new Builder();
    }

    public GatewayAgentClient forGatewayAgent(AgentSystem system, String instanceId) {
        return new GatewayAgentClient(transport, system, instanceId);
    }

    public AgentTaskClient forTask(String taskId) {
        return new AgentTaskClient(transport, taskId);
    }

    public static final class Builder {
        private HttpClient httpClient = HttpClient.newHttpClient();
        private URI baseUri = URI.create("http://localhost:8080");
        private Duration defaultTimeout = Duration.ofSeconds(60);

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient);
            return this;
        }

        public Builder baseUri(String baseUri) {
            this.baseUri = URI.create(Objects.requireNonNull(baseUri));
            return this;
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri);
            return this;
        }

        public Builder defaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
            return this;
        }

        public AgentComponentClient build() {
            AgentClient transport = AgentClient.builder()
                    .httpClient(httpClient)
                    .baseUri(baseUri)
                    .defaultTimeout(defaultTimeout)
                    .build();
            return new AgentComponentClient(transport);
        }
    }
}
