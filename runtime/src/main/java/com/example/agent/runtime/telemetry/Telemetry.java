package com.example.agent.runtime.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;

public final class Telemetry {
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pekko-agent-runtime");

    private Telemetry() {
    }

    public static Span startServerSpan(String name) {
        return TRACER.spanBuilder(name).setSpanKind(SpanKind.SERVER).startSpan();
    }

    public static Span startInternalSpan(String name) {
        return TRACER.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).startSpan();
    }
}
