package com.example.agent.runtime.checkpoint;

public record ToolResultRef(
        String toolName,
        String requestId,
        String objectRef,
        boolean success
) {
    public ToolResultRef {
        toolName = toolName == null ? "" : toolName;
        requestId = requestId == null ? "" : requestId;
        objectRef = objectRef == null ? "" : objectRef;
    }
}
