package com.example.agent.protocol;

public record AgentResponse(String requestId, String output, Throwable error) {
    public boolean isSuccess() {
        return error == null;
    }
}
