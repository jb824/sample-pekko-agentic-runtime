package com.example.agent.runtime.checkpoint;

public record ContextArtifactRef(
        String objectRef,
        String contentType,
        String contentHash,
        long sizeBytes
) {
    public ContextArtifactRef {
        objectRef = objectRef == null ? "" : objectRef;
        contentType = contentType == null ? "" : contentType;
        contentHash = contentHash == null ? "" : contentHash;
        sizeBytes = Math.max(0L, sizeBytes);
    }
}
