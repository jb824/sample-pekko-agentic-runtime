package com.example.agent;

import com.example.agent.client.AgentClient;
import com.example.agent.client.AgentWorkflow;
import com.example.agent.api.AgentRunContext;
import com.example.agent.runtime.AgentError;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        String prompt = args.length == 0
                ? System.getenv().getOrDefault("AGENT_PROMPT", "What time is it?")
                : String.join(" ", args);
        AgentRunContext context = AgentRunContext.tenant(System.getenv().getOrDefault("AGENT_TENANT_ID", "default"));

        AgentWorkflow workflow = AgentWorkflow.singleAgent(
                "assistant",
                "Answer the user directly and use available tools when useful.",
                "agent.request",
                "time.now"
        );

        try (AgentClient client = AgentClient.create()) {
            var result = client.run(context, workflow, prompt).toCompletableFuture().join();
            if (result.isSuccess()) {
                System.out.println(result.output());
                return;
            }
            String errorText = result.errors().isEmpty()
                    ? result.status().name()
                    : result.errors().stream().map(AgentError::message).reduce((left, right) -> left + "; " + right).orElse(result.status().name());
            System.err.println("Agent run failed: " + errorText);
            System.exit(1);
        }
    }
}
