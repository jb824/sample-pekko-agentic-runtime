package com.example.adapter.inbound;

import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class CapturingEmitter implements Emitter<String> {
    private String payload;

    @Override
    public CompletionStage<Void> send(String msg) {
        this.payload = msg;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public <M extends Message<? extends String>> void send(M msg) {
        this.payload = msg.getPayload();
    }

    @Override
    public void complete() {
    }

    @Override
    public void error(Exception e) {
        throw new IllegalStateException(e);
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean hasRequests() {
        return true;
    }

    String payload() {
        return payload;
    }
}
